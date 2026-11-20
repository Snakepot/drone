package cn.tedu.droneagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeProperties} 的绑定测试。
 *
 * <p>理由同 {@link ToolPropertiesTest}：按构造方法绑定的 record，**yml 里漏键或拼错键名时
 * 绑定会悄悄失效**（尤其嵌套的 {@code chunk / embedding / milvus} 三组），
 * 服务照常启动、运行时才露出诡异表现。这里逐个断言取值，顺带当「yml 键名写错」的探测器。
 *
 * <p>只断言**不依赖环境变量**的键；被 {@code ${ENV:默认}} 覆盖的键
 * （如 {@code directory}、{@code milvus.host}）只断言非空，免得开发机设了环境变量就误报。
 */
@SpringBootTest
class KnowledgePropertiesTest {

    @Autowired
    private KnowledgeProperties kb;

    @Test
    @DisplayName("agent.kb.* 全部能绑定（含 chunk / embedding / milvus 三组嵌套）")
    void bindsAllKeysFromYml() {
        assertTrue(kb.enabled());
        // 【回归】测试环境必须关掉「启动时扫描」：否则 @SpringBootTest 会真去调嵌入、写向量库
        assertFalse(kb.scanOnStartup(), "测试里 scan-on-startup 必须为 false，见 test/resources/application.properties");
        assertNotNull(kb.directory());
        assertFalse(kb.directory().isBlank());
        assertEquals(5, kb.topK());
        // 实测定的：相关 ≈0.53 / 不相关 ≈0.46，取 0.5 把两者分开（见 docs/tests/REQ-AGENT-0010-端到端回归.md）
        assertEquals(0.5, kb.minScore());

        assertNotNull(kb.chunk());
        assertEquals(800, kb.chunk().maxChars());
        assertEquals(100, kb.chunk().overlapChars());

        assertNotNull(kb.embedding());
        // 实测：智谱 GLM embedding-3 输出 2048 维；维度必须与模型一致
        assertEquals(2048, kb.embedding().dimension());

        // 可见范围：规则与默认都允许为空（为空 = fail-closed，谁都看不到）
        assertNotNull(kb.permRules());
        assertNotNull(kb.defaultPerms());

        assertNotNull(kb.milvus());
        assertFalse(kb.milvus().host().isBlank());
        assertEquals(19530, kb.milvus().port(), "实测：Milvus 的 REST(/v2/vectordb) 与 gRPC 共用 19530");
        // 集合名沿用环境里原有的 drone_knowledge（plans §4），不是草稿名 kb_chunks
        assertEquals("drone_knowledge", kb.milvus().collection());
    }

    @Test
    @DisplayName("嵌入未配置 Key 时 configured()=false（调用方据此降级为「查不到」，不炸对话）")
    void embeddingConfiguredRequiresKeyAndModel() {
        assertFalse(new KnowledgeProperties.Embedding("http://x", "", "m", 1024).configured());
        assertFalse(new KnowledgeProperties.Embedding("http://x", "k", "", 1024).configured());
        assertFalse(new KnowledgeProperties.Embedding("http://x", null, null, 1024).configured());
        assertTrue(new KnowledgeProperties.Embedding("http://x", "k", "m", 1024).configured());
    }
}
