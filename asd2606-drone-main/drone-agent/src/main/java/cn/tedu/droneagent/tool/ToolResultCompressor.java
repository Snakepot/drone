package cn.tedu.droneagent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具结果的**分级压缩**：尽量在「够短」的前提下少丢信息。
 *
 * <h2>三级，从便宜到贵</h2>
 * <ol>
 *   <li>够短 → **原样返回**，不做任何事；</li>
 *   <li>**结构化裁剪**：删链接、并掉连续重复行、去掉多余空行 —— 不调模型，快且免费；</li>
 *   <li>仍超长 → **模型摘要**（一次额外模型调用）：保留关键信息与数值；摘要失败就退回硬截断。</li>
 * </ol>
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不负责取数</b>：它拿到的是工具已经算出来的结果，只做「变短」。</li>
 *   <li><b>不保证语义完备</b>：压缩必然丢信息，这是它与「原样回填」之间的取舍。</li>
 * </ul>
 */
@Slf4j
public class ToolResultCompressor {

    /**
     * 「模型摘要」这一步怎么调模型。为 {@code null} 表示**不启用**模型摘要（只做结构化裁剪 + 硬截断）——
     * 这样本类可单测，不必真的连模型。
     */
    @FunctionalInterface
    public interface Summarizer {

        /** 把 content 压到 maxChars 以内 */
        String summarize(String content, int maxChars);
    }

    /** 链接：压缩时去掉 —— 标题与正文已经含了信息，链接最占地方又最没用 */
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final Summarizer summarizer;

    public ToolResultCompressor(Summarizer summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 把内容压到 {@code maxChars} 以内。**保证返回长度 ≤ maxChars**。
     *
     * @param content  工具产出的原文
     * @param maxChars 目标上限
     */
    public String compress(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }

        // 第二级：结构化裁剪
        String trimmed = structuredTrim(content);
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }

        // 第三级：模型摘要（没配 summarizer 或摘要失败，就退回硬截断）
        if (summarizer != null) {
            try {
                String summary = summarizer.summarize(trimmed, maxChars);
                if (summary != null && !summary.isBlank()) {
                    return hardTruncate(summary, maxChars);
                }
            } catch (RuntimeException e) {
                log.warn("【工具结果压缩】模型摘要失败，退回硬截断：{}", e.getMessage());
            }
        }
        return hardTruncate(trimmed, maxChars);
    }

    /**
     * 结构化裁剪：**逐行**去链接、**并掉连续重复行**、去掉多余空行。
     *
     * <p>只并「连续」重复：相隔的行可能各有上下文，贸然去重会改变含义。
     * 包可见，便于测试直接断言。
     */
    String structuredTrim(String content) {
        List<String> kept = new ArrayList<>();
        String previous = null;
        for (String raw : content.split("\n", -1)) {
            String line = URL.matcher(raw).replaceAll("").replaceAll("[ \t]+$", "");
            if (line.isBlank()) {
                // 空行最多留一个（且不在开头）
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).isEmpty()) {
                    kept.add("");
                }
                continue;
            }
            if (line.equals(previous)) {
                continue;
            }
            kept.add(line);
            previous = line;
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        return String.join("\n", kept);
    }

    /** 硬截断：连省略号一起算进上限，保证结果 ≤ maxChars */
    private static String hardTruncate(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        if (maxChars <= 1) {
            return s.substring(0, maxChars);
        }
        return s.substring(0, maxChars - 1) + "…";
    }
}
