package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.vector.Retriever;
import cn.tedu.droneagent.vector.ScoredRecord;
import cn.tedu.droneagent.vector.VectorFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeSearchTool} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-1</b>（命中并**给出处**）、
 * <b>AC-3</b>（无权限 → 检索不到）、<b>AC-5</b>（查不到 → 如实说，不编造）、
 * <b>AC-7</b>（开关关闭 → **一次检索都不发生**）。
 */
class KnowledgeSearchToolTest {

    /** 桩检索器：返回预置命中，并记下收到的过滤条件 */
    private static final class StubRetriever implements Retriever {
        private final List<ScoredRecord> hits;
        private VectorFilter seenFilter;
        private int calls;

        StubRetriever(List<ScoredRecord> hits) {
            this.hits = hits;
        }

        @Override
        public List<ScoredRecord> retrieve(String query, VectorFilter filter) {
            this.seenFilter = filter;
            this.calls++;
            return hits;
        }
    }

    private static ScoredRecord hit(String file, String section, int index, String content) {
        return new ScoredRecord(file + "#" + index, 0.9, Map.of(
                "file_name", file, "section", section, "chunk_index", index, "content", content));
    }

    private static ToolContext withPerms(String... codes) {
        return new ToolContext(Set.of("pilot"), Set.of(codes));
    }

    @Test
    @DisplayName("命中：把原文与**出处**一起回填（AC-1）")
    void returnsContentWithCitation() {
        StubRetriever retriever = new StubRetriever(List.of(
                hit("无人机操作手册.md", "手册 › 8. 电池与日常保养", 0, "电池长期存放约 50%~60% 电量。")));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, true);

        ToolResult result = tool.invoke("{\"query\":\"电池怎么保养\"}", withPerms("agent:chat"));

        assertTrue(result.ok());
        assertTrue(result.content().contains("无人机操作手册.md › 手册 › 8. 电池与日常保养"), result.content());
        assertTrue(result.content().contains("电池长期存放约 50%~60% 电量。"));
    }

    @Test
    @DisplayName("权限作为**过滤条件**下推给检索器（前置过滤，AC-3）")
    void passesPermissionFilterDown() {
        StubRetriever retriever = new StubRetriever(List.of());
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, true);

        tool.invoke("{\"query\":\"保险\"}", withPerms("agent:chat", "agent:finance"));

        assertNotNull(retriever.seenFilter);
        assertTrue(retriever.seenFilter instanceof VectorFilter.ContainsAny);
        assertEquals("perm_codes", ((VectorFilter.ContainsAny) retriever.seenFilter).field());
    }

    @Test
    @DisplayName("一个权限码都没有：直接拒绝，**连检索都不调**（fail-closed）")
    void noPermissionRefusesWithoutRetrieving() {
        StubRetriever retriever = new StubRetriever(List.of(hit("x.md", "", 0, "内容")));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, true);

        ToolResult result = tool.invoke("{\"query\":\"随便\"}", ToolContext.anonymous());

        assertFalse(result.ok());
        assertEquals(0, retriever.calls, "没权限时不该去检索");
        assertNull(retriever.seenFilter);
    }

    @Test
    @DisplayName("查不到：如实说没找到，**不编造**（AC-5）")
    void emptyHitsSaysNotFound() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(new StubRetriever(List.of()), true);

        ToolResult result = tool.invoke("{\"query\":\"库里没有的东西\"}", withPerms("agent:chat"));

        assertTrue(result.ok(), "「没找到」是正常结果，不是工具故障");
        assertTrue(result.content().contains("没有检索到"), result.content());
    }

    @Test
    @DisplayName("参数缺 query：给出明确失败，不瞎猜")
    void missingQueryFails() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(new StubRetriever(List.of()), true);

        assertFalse(tool.invoke("{}", withPerms("agent:chat")).ok());
        assertFalse(tool.invoke("不是 JSON", withPerms("agent:chat")).ok());
    }

    @Test
    @DisplayName("总开关关闭：一次检索都不发生（AC-7）")
    void disabledSwitchSkipsRetrieval() {
        StubRetriever retriever = new StubRetriever(List.of(hit("x.md", "", 0, "内容")));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, false);

        ToolResult result = tool.invoke("{\"query\":\"电池\"}", withPerms("agent:chat"));

        assertFalse(result.ok());
        assertTrue(result.content().contains("未启用"), result.content());
        assertEquals(0, retriever.calls);
    }

    @Test
    @DisplayName("同一份资料的同一章节、块号连续：合并成一段，不重复列出")
    void mergesAdjacentChunksOfSameSection() {
        StubRetriever retriever = new StubRetriever(List.of(
                hit("手册.md", "手册 › 保养", 1, "后半段内容。"),
                hit("手册.md", "手册 › 保养", 0, "前半段内容。")));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(retriever, true);

        ToolResult result = tool.invoke("{\"query\":\"保养\"}", withPerms("agent:chat"));

        assertEquals(1, result.content().split("【资料】").length - 1, "同一章节应只出现一次");
        assertTrue(result.content().indexOf("前半段内容。") < result.content().indexOf("后半段内容。"),
                "合并时应按块号顺序");
    }
}
