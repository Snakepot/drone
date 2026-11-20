package cn.tedu.droneagent.prompt;

import java.util.Map;

/**
 * 测试用的提示词目录工厂：免得每个用例都手写 frontmatter。
 *
 * <p>只造测试需要的正文；元信息按最小合法值给。
 */
public final class TestPrompts {

    private TestPrompts() {
    }

    /** 只含系统提示词 */
    public static PromptCatalog of(String systemPrompt) {
        return new PromptCatalog(Map.of(
                "assistant/system.md", doc("assistant.system", "[]", systemPrompt)));
    }

    /** 系统提示词里带 {@code {{skills}}} 占位符（Skill 相关测试用） */
    public static PromptCatalog withSkills(String systemPrompt) {
        return new PromptCatalog(Map.of(
                "assistant/system.md", doc("assistant.system", "[skills]", systemPrompt)));
    }

    /** 含系统提示词 + 摘要提示词（摘要正文里用 {@code {{maxChars}}}） */
    public static PromptCatalog withSummary(String systemPrompt, String summaryTemplate) {
        return new PromptCatalog(Map.of(
                "assistant/system.md", doc("assistant.system", "[]", systemPrompt),
                "tool/summary.md", doc("tool.summary", "[maxChars]", summaryTemplate)));
    }

    private static String doc(String name, String vars, String body) {
        return "---\n"
                + "name: " + name + "\n"
                + "version: 1\n"
                + "purpose: 测试用\n"
                + "vars: " + vars + "\n"
                + "changelog:\n"
                + "  - v1 初版\n"
                + "---\n\n"
                + body + "\n";
    }
}
