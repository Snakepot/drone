package cn.tedu.droneagent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptCatalog} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0008-提示词工程化管理.md` 的
 * <b>AC-2</b>（缺字段/名字不符/重名 → 启动失败）、<b>AC-3</b>（vars 与占位符不符 → 启动失败）、
 * <b>AC-4</b>（占位符替换 / 缺变量报错）、<b>AC-5</b>（改内容即生效）、<b>AC-6</b>（能取到名字与版本）。
 *
 * <p>直接把「相对路径 → 内容」喂给 {@link PromptCatalog}，不用真造文件。
 */
class PromptCatalogTest {

    /** 造一个合法的 frontmatter（vars 由调用方给） */
    private static String frontmatter(String name, String vars) {
        return "name: " + name + "\n"
                + "version: 1\n"
                + "purpose: 测试用\n"
                + "vars: " + vars + "\n"
                + "changelog:\n"
                + "  - v1 初版\n";
    }

    /** 造一个提示词文件：frontmatter + 正文 */
    private static String md(String frontmatter, String body) {
        return "---\n" + frontmatter + "---\n\n" + body;
    }

    @Test
    @DisplayName("正常加载：能按名字取正文，能读到版本（AC-6）")
    void loadsAndExposes() {
        PromptCatalog catalog = new PromptCatalog(Map.of(
                "assistant/system.md", md(frontmatter("assistant.system", "[]"), "你是助手。")));

        assertEquals(Set.of("assistant.system"), catalog.names());
        assertEquals("你是助手。", catalog.text("assistant.system"));
        assertEquals(1, catalog.versions().get("assistant.system"));
    }

    @Test
    @DisplayName("AC-4 占位符被替换；缺变量直接报错（不静默留下 {{x}}）")
    void rendersPlaceholders() {
        PromptCatalog catalog = new PromptCatalog(Map.of(
                "tool/summary.md",
                md(frontmatter("tool.summary", "[maxChars]"), "把内容压缩到 {{maxChars}} 字以内。")));

        assertEquals("把内容压缩到 50 字以内。", catalog.render("tool.summary", Map.of("maxChars", 50)));
        assertThrows(IllegalStateException.class, () -> catalog.render("tool.summary", Map.of()));
    }

    @Test
    @DisplayName("AC-2 目录为空 → 启动失败")
    void rejectsEmpty() {
        assertThrows(IllegalStateException.class, () -> new PromptCatalog(Map.of()));
    }

    @Test
    @DisplayName("AC-2 缺 frontmatter / frontmatter 没结束 → 启动失败")
    void rejectsBrokenFrontmatter() {
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", "没有 frontmatter 的正文")));
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", "---\nname: a.b\nversion: 1\n")));
    }

    @Test
    @DisplayName("AC-2 正文为空 / 缺 version / name 与路径不符 → 启动失败")
    void rejectsInvalidFields() {
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[]"), "  "))));
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", md("name: a.b\npurpose: 测试\nvars: []\n", "正文"))));
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", md(frontmatter("wrong.name", "[]"), "正文"))));
    }

    @Test
    @DisplayName("AC-2 两条不同路径解析出同一个名字 → 启动失败")
    void rejectsDuplicateName() {
        // a/b.md 与 a.b.md 都会得到名字 a.b
        assertThrows(IllegalStateException.class, () -> new PromptCatalog(Map.of(
                "a/b.md", md(frontmatter("a.b", "[]"), "正文一"),
                "a.b.md", md(frontmatter("a.b", "[]"), "正文二"))));
    }

    @Test
    @DisplayName("AC-3 vars 与正文占位符不一致（少了/多了/拼错）→ 启动失败")
    void rejectsVarsMismatch() {
        // 声明了却没用
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[maxChars]"), "正文没占位符"))));
        // 用了却没声明
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[]"), "正文用了 {{maxChars}}"))));
        // 拼错
        assertThrows(IllegalStateException.class,
                () -> new PromptCatalog(Map.of("a/b.md",
                        md(frontmatter("a.b", "[maxChars]"), "正文用了 {{maxChart}}"))));
    }

    @Test
    @DisplayName("AC-5 同一条路径换成新内容即生效（不是硬编码/缓存）")
    void reflectsContentChange() {
        PromptCatalog first = new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[]"), "旧正文")));
        PromptCatalog second = new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[]"), "新正文")));

        assertEquals("旧正文", first.text("a.b"));
        assertEquals("新正文", second.text("a.b"));
    }

    @Test
    @DisplayName("取不存在的名字 → 报错")
    void unknownNameFails() {
        PromptCatalog catalog = new PromptCatalog(Map.of("a/b.md", md(frontmatter("a.b", "[]"), "正文")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog.text("不存在"));
        assertTrue(e.getMessage().contains("不存在"));
    }
}
