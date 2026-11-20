package cn.tedu.droneagent.kb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Markdown 解析器：按**标题层级**把文档切成若干 {@link DocumentSection}。
 *
 * <h2>它怎么认标题</h2>
 * <ol>
 *   <li>**ATX 标题**：行首 1～6 个 {@code #}，后面必须跟空白（所以 {@code #标签} 不算标题）；</li>
 *   <li>允许行首最多 3 个空格（CommonMark 规则）；</li>
 *   <li>**代码块里的 {@code #} 不算标题**——靠 {@code ```} / {@code ~~~} 围栏开关来判，
 *       否则一段示例代码里的注释会把文档切乱。</li>
 * </ol>
 *
 * <h2>标题路径怎么攒</h2>
 * 用一个「标题栈」：遇到 N 级标题，先把深度 ≥ N 的弹掉，再压入自己。
 * 于是 H1→H2→H3 得到 {@code [H1, H2, H3]}，再来一个 H2 就变成 {@code [H1, H2]}——与文档的层级一致，
 * 这个路径就是检索时用的**引用出处**。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不切长度</b>：一章可能很长，切多长是 {@link Chunker} 的事。</li>
 *   <li><b>不剥 Markdown 标记</b>：链接、加粗等原样保留——回填给模型时它们是有用信号，且去掉要额外写解析。</li>
 * </ul>
 */
public class MarkdownDocumentParser implements DocumentParser {

    /** 认哪些扩展名（小写比较） */
    private static final Set<String> EXTENSIONS = Set.of(".md", ".markdown");

    /** 围栏标记：代码块用 ``` 或 ~~~ 包起来，里面的 `#` 不是标题 */
    private static final String FENCE_BACKTICK = "```";

    private static final String FENCE_TILDE = "~~~";

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public List<DocumentSection> parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        List<DocumentSection> sections = new ArrayList<>();
        Deque<String> headingPath = new ArrayDeque<>();
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : lines) {
            if (isFence(line)) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            if (!inFence) {
                int level = headingLevel(line);
                if (level > 0) {
                    // 换标题 = 上一段收尾。只有真有正文才产出一个章节，避免留下一堆空章节
                    flush(sections, headingPath, body);
                    applyHeading(headingPath, level, headingText(line));
                    continue;
                }
            }
            body.append(line).append('\n');
        }
        flush(sections, headingPath, body);
        return sections;
    }

    private static boolean isFence(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith(FENCE_BACKTICK) || trimmed.startsWith(FENCE_TILDE);
    }

    /** 行首标题级别（1～6）；不是标题返回 0 */
    static int headingLevel(String line) {
        int i = 0;
        // 允许最多 3 个前导空格（CommonMark）
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        int level = 0;
        while (i < line.length() && line.charAt(i) == '#' && level < 6) {
            i++;
            level++;
        }
        if (level == 0) {
            return 0;
        }
        // `#` 之后必须是空白或行尾，否则是 `#标签` 这类普通文本
        if (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
            return 0;
        }
        return level;
    }

    /** 取标题文字：去掉前导 `#`，并按 CommonMark 去掉**前后带空白的**闭合 `#`（如 {@code # 标题 #}） */
    static String headingText(String line) {
        String text = line.strip();
        int start = 0;
        while (start < text.length() && text.charAt(start) == '#') {
            start++;
        }
        text = text.substring(start).strip();

        int end = text.length();
        int hashStart = end;
        while (hashStart > 0 && text.charAt(hashStart - 1) == '#') {
            hashStart--;
        }
        // 只有当闭合井号前面是空白时才算闭合标记；否则当作标题正文的一部分（如 "C#"）
        if (hashStart < end && hashStart > 0 && Character.isWhitespace(text.charAt(hashStart - 1))) {
            text = text.substring(0, hashStart).strip();
        }
        return text;
    }

    private static void applyHeading(Deque<String> headingPath, int level, String title) {
        while (headingPath.size() >= level) {
            headingPath.pollLast();
        }
        headingPath.addLast(title);
    }

    private static void flush(List<DocumentSection> sections, Deque<String> headingPath, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new DocumentSection(new ArrayList<>(headingPath), text));
        }
    }
}
