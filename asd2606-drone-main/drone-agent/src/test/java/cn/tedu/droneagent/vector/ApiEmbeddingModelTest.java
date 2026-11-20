package cn.tedu.droneagent.vector;

import cn.tedu.droneagent.config.KnowledgeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiEmbeddingModel} 的单元测试：只测**请求体构造**与**响应解析**（含维度校验），
 * **不发真实网络请求**（宪法 §5）。
 *
 * <p>真正调 API 的那一步（HTTP 往返）归集成测试；这里把「报文对不对」这层最容易出错、
 * 又完全可离线验证的逻辑覆盖住。
 */
class ApiEmbeddingModelTest {

    private static KnowledgeProperties.Embedding props(int dimension) {
        return new KnowledgeProperties.Embedding("http://localhost:1", "test-key", "test-model", dimension);
    }

    @Test
    @DisplayName("请求体带 model 与 input（OpenAI 兼容的 /embeddings 协议）")
    void requestBodyContainsModelAndInput() {
        ApiEmbeddingModel model = new ApiEmbeddingModel(props(3));

        String body = model.requestBody(List.of("电池怎么保养", "三者责任险"));

        assertTrue(body.contains("\"model\":\"test-model\""), body);
        assertTrue(body.contains("\"input\":[\"电池怎么保养\",\"三者责任险\"]"), body);
    }

    @Test
    @DisplayName("响应解析：按 data[].embedding 取向量，顺序与入参一致")
    void parseResponseMapsEmbeddings() {
        ApiEmbeddingModel model = new ApiEmbeddingModel(props(3));

        List<float[]> vectors = model.parseResponse("""
                {"data":[{"embedding":[0.1,0.2,0.3]},{"embedding":[0.4,0.5,0.6]}]}
                """);

        assertEquals(2, vectors.size());
        assertEquals(0.1f, vectors.get(0)[0], 1e-6);
        assertEquals(0.6f, vectors.get(1)[2], 1e-6);
        assertEquals(3, model.dimension());
    }

    @Test
    @DisplayName("维度与配置不符要**当场报错**，别拖到写向量库时才炸")
    void parseResponseRejectsDimensionMismatch() {
        ApiEmbeddingModel model = new ApiEmbeddingModel(props(2));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> model.parseResponse("""
                        {"data":[{"embedding":[0.1,0.2,0.3]}]}
                        """));

        assertTrue(e.getMessage().contains("维度与配置不符"), e.getMessage());
    }

    @Test
    @DisplayName("未配置嵌入服务时给出明确报错（提示要设哪些键）")
    void throwsWhenNotConfigured() {
        ApiEmbeddingModel model = new ApiEmbeddingModel(
                new KnowledgeProperties.Embedding("", "", "", 3));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> model.embedAll(List.of("随便一段")));

        assertTrue(e.getMessage().contains("未配置嵌入服务"), e.getMessage());
    }

    @Test
    @DisplayName("空入参不发请求、直接返回空")
    void emptyInputReturnsEmpty() {
        ApiEmbeddingModel model = new ApiEmbeddingModel(
                new KnowledgeProperties.Embedding("", "", "", 3));

        assertTrue(model.embedAll(List.of()).isEmpty());
    }

    @Test
    @DisplayName("接口默认的 embedAll 逐条调用且保持顺序（实现类未覆写时的行为）")
    void defaultEmbedAllKeepsOrder() {
        List<String> seen = new ArrayList<>();
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public int dimension() {
                return 1;
            }

            @Override
            public float[] embed(String text) {
                seen.add(text);
                return new float[]{text.length()};
            }
        };

        List<float[]> vectors = model.embedAll(List.of("a", "bb", "ccc"));

        assertEquals(List.of("a", "bb", "ccc"), seen);
        assertEquals(3, vectors.size());
        assertEquals(3f, vectors.get(2)[0], 1e-6);
    }
}
