package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.vector.InMemoryVectorStore;
import cn.tedu.droneagent.vector.VectorFilter;
import cn.tedu.droneagent.vector.VectorRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeScope} 的单元测试。
 *
 * <p>对应验收条件 `REQ-AGENT-0010` 的 <b>AC-3</b>：无权限的内容**在检索阶段就命中不到**。
 * 重点验 **fail-closed**：用户没有任何权限码时，**谁都看不到**——而不是「谁都看得到」。
 */
class KnowledgeScopeTest {

    @Test
    @DisplayName("有权限码：按 perm_codes 做包含匹配")
    void buildsContainsAnyFilter() {
        VectorFilter filter = KnowledgeScope.visibleTo(List.of("agent:chat", "agent:finance"));

        assertInstanceOf(VectorFilter.ContainsAny.class, filter);
        assertEquals("perm_codes", ((VectorFilter.ContainsAny) filter).field());
    }

    @Test
    @DisplayName("没有权限码 → 匹配零条（fail-closed），不是「不过滤」")
    void emptyCodesMeansMatchNothing() {
        assertInstanceOf(VectorFilter.None.class, KnowledgeScope.visibleTo(List.of()));
        assertInstanceOf(VectorFilter.None.class, KnowledgeScope.visibleTo(null));
    }

    @Test
    @DisplayName("端到端：无权限码的用户检索不到任何内部资料；有权限的能查到该看到的")
    void scopeGatesRetrieval() {
        InMemoryVectorStore store = new InMemoryVectorStore(2);
        store.upsert(List.of(
                new VectorRecord("m#0", new float[]{1, 0}, Map.of(
                        "content", "电池要保养", "perm_codes", List.of("agent:chat"))),
                new VectorRecord("i#0", new float[]{1, 0}, Map.of(
                        "content", "三者责任险保额", "perm_codes", List.of("agent:finance")))));

        // 无任何权限码：一条都查不到
        assertTrue(store.search(new float[]{1, 0}, KnowledgeScope.visibleTo(List.of()), 10).isEmpty(),
                "没有权限码的人不该看到任何资料");

        // 只有 agent:chat：只看到操作手册那一块
        List<String> chatOnly = store.search(new float[]{1, 0},
                KnowledgeScope.visibleTo(List.of("agent:chat")), 10).stream()
                .map(record -> record.id()).toList();
        assertEquals(List.of("m#0"), chatOnly);
    }
}
