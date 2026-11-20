package cn.tedu.droneagent.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownDocumentParser} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-6</b>（放入资料即被正确解析、切块、可引用）的一半——
 * 这一半保证「**结构与出处**解析正确」。
 *
 * <p>用 {@link TempDir} 写临时文件，不依赖真实外部环境（宪法 §5）。
 */
class MarkdownDocumentParserTest {

    @TempDir
    Path dir;

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    private List<DocumentSection> parse(String content) throws IOException {
        Path file = dir.resolve("doc.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return parser.parse(file);
    }

    @Test
    @DisplayName("只认 Markdown 扩展名")
    void supportsMarkdownOnly() {
        assertTrue(parser.supports("手册.md"));
        assertTrue(parser.supports("A.MD"));
        assertTrue(parser.supports("a.markdown"));
        assertFalse(parser.supports("保险.pdf"));
        assertFalse(parser.supports("data.txt"));
        assertFalse(parser.supports(null));
    }

    @Test
    @DisplayName("按标题层级攒出引用路径；无正文的标题不产出空章节")
    void buildsHeadingPathAndSkipsEmptySections() throws IOException {
        List<DocumentSection> sections = parse("""
                前言段落。

                # 手册

                ## 保养

                电池要保养。

                ### 低温

                低温要预热。

                ## 应急

                就近降落。
                """);

        assertEquals(4, sections.size(), "「# 手册」没有正文，不应产出空章节");
        assertEquals("", sections.get(0).pathText(), "首个标题之前的内容，路径为空");
        assertEquals("前言段落。", sections.get(0).body());
        assertEquals("手册 › 保养", sections.get(1).pathText());
        assertEquals("电池要保养。", sections.get(1).body());
        assertEquals("手册 › 保养 › 低温", sections.get(2).pathText(), "三级标题应带完整路径");
        assertEquals("手册 › 应急", sections.get(3).pathText(), "回到二级标题，三级应被弹掉");
    }

    @Test
    @DisplayName("代码块里的 # 不算标题（否则示例代码会把文档切乱）")
    void ignoresHashInsideCodeFence() throws IOException {
        List<DocumentSection> sections = parse("""
                # 标题

                正文前。

                ~~~
                # 不是标题

                代码里的内容。
                ~~~

                正文后。
                """);

        assertEquals(1, sections.size(), "围栏里的 # 不应产生新章节");
        assertEquals("标题", sections.get(0).pathText());
        assertTrue(sections.get(0).body().contains("# 不是标题"), "围栏内容应原样留在正文里");
        assertTrue(sections.get(0).body().contains("正文后。"));
    }

    @Test
    @DisplayName("`#标签` 这类不是标题；`# 标题 #` 的闭合井号应被去掉")
    void headingRules() {
        assertEquals(0, MarkdownDocumentParser.headingLevel("#标签"), "`#` 后面没空白，不是标题");
        assertEquals(1, MarkdownDocumentParser.headingLevel("# 标题"));
        assertEquals(3, MarkdownDocumentParser.headingLevel("   ### 三级"), "允许最多 3 个前导空格");
        assertEquals(0, MarkdownDocumentParser.headingLevel("####### 七个井号"), "7 个以上不算标题");
        assertEquals("标题", MarkdownDocumentParser.headingText("# 标题 #"), "闭合井号应去掉");
        assertEquals("C#", MarkdownDocumentParser.headingText("# C#"), "标题正文里的 # 不能被误删");
    }
}
