package cn.tedu.droneagent.artifact;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型回复里抽取**完整 HTML 文档**。
 *
 * <h2>为什么要求「完整文档」而不是任意 HTML 片段</h2>
 * 模型在解释代码时也可能输出 `<div>` 片段；只有同时包含 `<html` 与 `</html>` 的才当产物，
 * 避免把普通回答误存成文件。
 *
 * <h2>两种来源</h2>
 * <ol>
 *   <li>代码围栏：`` ```html … ``` ``（Skill 里要求的格式）；</li>
 *   <li>裸文档：回复里直接出现 `<!DOCTYPE html>` 或 `<html>`（模型没按要求加围栏时的兜底）。</li>
 * </ol>
 *
 * <h2>清洗后的回复</h2>
 * 把 HTML 块从回复里摘掉，只留摘要与一行「报告已生成」。这一步很关键：
 * 会话里存的是清洗后的文本，后续每轮不会重复携带十几 KB 的 HTML。
 */
@Slf4j
public class ArtifactExtractor {

    /** ```html … ```；用 \\R 兼容不同换行 */
    private static final Pattern FENCE =
            Pattern.compile("(?is)```[ \\t]*html[ \\t]*\\R(.*?)\\R[ \\t]*```");

    private static final Pattern TITLE =
            Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    /** 文件名里不允许出现的字符（Windows/Unix 通用） */
    private static final Pattern ILLEGAL_NAME = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final int MAX_NAME_CHARS = 80;

    private final long maxBytes;

    public ArtifactExtractor(long maxBytes) {
        this.maxBytes = maxBytes <= 0 ? 524288 : maxBytes;
    }

    /**
     * @param reply 模型回复
     * @return 抽到完整文档时返回；否则 empty
     */
    public Optional<Extracted> extract(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }

        String html = null;
        String cleaned = reply;

        Matcher fenced = FENCE.matcher(reply);
        if (fenced.find()) {
            html = fenced.group(1).strip();
            cleaned = (reply.substring(0, fenced.start()) + "\n" + reply.substring(fenced.end())).strip();
        } else {
            String lower = reply.toLowerCase();
            int start = lower.indexOf("<!doctype html");
            if (start < 0) {
                start = lower.indexOf("<html");
            }
            int end = lower.lastIndexOf("</html>");
            if (start >= 0 && end > start) {
                html = reply.substring(start, end + "</html>".length()).strip();
                cleaned = (reply.substring(0, start) + "\n" + reply.substring(end + "</html>".length())).strip();
            }
        }

        if (html == null) {
            return Optional.empty();
        }
        String lowerHtml = html.toLowerCase();
        if (!lowerHtml.contains("<html") || !lowerHtml.contains("</html>")) {
            log.debug("【产物】忽略：内容不是完整 HTML 文档");
            return Optional.empty();
        }

        long bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            log.warn("【产物】忽略：HTML {} 字节，超过上限 {}；回复按原文返回", bytes, maxBytes);
            return Optional.empty();
        }

        String fileName = fileName(html);
        if (cleaned.isBlank()) {
            cleaned = "HTML 报告已生成：" + fileName + "，可在下方预览或下载。";
        } else {
            cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").strip();
        }
        return Optional.of(new Extracted(html, cleaned, fileName));
    }

    /** 从 {@code <title>} 取文件名；取不到用时间戳兜底 */
    String fileName(String html) {
        Matcher title = TITLE.matcher(html);
        String name = "";
        if (title.find()) {
            name = TAGS.matcher(title.group(1)).replaceAll("").strip();
        }
        name = ILLEGAL_NAME.matcher(name).replaceAll("-").replaceAll("\\s+", " ").strip();
        if (name.isEmpty()) {
            name = "report-" + LocalDateTime.now().format(STAMP);
        }
        if (name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS).strip();
        }
        if (!name.toLowerCase().endsWith(".html")) {
            name = name + ".html";
        }
        return name;
    }

    /**
     * 抽取结果。
     *
     * @param html         完整 HTML 文档
     * @param cleanedReply 去掉 HTML 后的回复（用于返回与持久化）
     * @param fileName     建议的下载文件名
     */
    public record Extracted(String html, String cleanedReply, String fileName) {
    }
}
