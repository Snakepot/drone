package cn.tedu.droneagent.vector;

import cn.tedu.droneagent.config.KnowledgeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过 **embedding API** 算向量的实现（{@code ADR-0006} 决策 5）。
 *
 * <h2>按什么协议调</h2>
 * 按 **OpenAI 兼容的 {@code POST {base-url}/embeddings}**：
 * 请求体 {@code {"model": 模型名, "input": [文本…]}}，
 * 响应 {@code {"data": [{"embedding": [数值…]}, …]}}。
 * 多数嵌入服务都提供这个兼容端点；**若所用供应商不兼容，只需换一个 {@link EmbeddingModel} 实现，
 * 上层不动**。
 *
 * <h2>为什么用 JDK 自带的 HttpClient，不引 SDK</h2>
 * 与本项目既有取向一致（见 `ChatService`）：参数就这几个，多一个依赖不划算。
 *
 * <h2>维度必须校验</h2>
 * {@code agent.kb.embedding.dimension} 与模型返回的向量长度**不一致时必须当场报错**——
 * 否则会以「维度不对」的形式在**写入向量库**时才炸，排查要多绕一层（且可能已经写坏一部分数据）。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不重试、不缓存</b>：失败交给调用方（入库有重试队列）。</li>
 *   <li><b>不打文本进日志</b>：入库时输入的是**内部资料原文**，日志只记条数与维度。</li>
 * </ul>
 */
@Slf4j
public class ApiEmbeddingModel implements EmbeddingModel {

    /** 单次嵌入请求的超时。入库可批量提交，给得比对话宽松一些 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final KnowledgeProperties.Embedding props;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public ApiEmbeddingModel(KnowledgeProperties.Embedding props) {
        this(props, new ObjectMapper());
    }

    ApiEmbeddingModel(KnowledgeProperties.Embedding props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public int dimension() {
        return props.dimension();
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        if (!props.configured()) {
            throw new IllegalStateException(
                    "未配置嵌入服务：请设置 agent.kb.embedding.base-url / api-key / model");
        }

        String body = requestBody(texts);
        String url = props.baseUrl() + "/embeddings";
        log.info("【嵌入】请求：模型={}，输入 {} 条文本", props.model(), texts.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("调用嵌入服务失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用嵌入服务被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "嵌入服务返回 HTTP " + response.statusCode() + "：" + response.body());
        }

        List<float[]> vectors = parseResponse(response.body());
        log.info("【嵌入】完成：返回 {} 条，维度 {}", vectors.size(), dimension());
        return vectors;
    }

    /** 构造请求体。包可见，便于单测直接断言结构，不必真发请求 */
    String requestBody(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("input", texts);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造嵌入请求体失败", e);
        }
    }

    /**
     * 解析响应。包可见，便于单测喂固定报文。
     *
     * <p>顺带**校验维度**：与配置不符就报错，别把问题拖到写向量库那一步。
     */
    List<float[]> parseResponse(String responseBody) {
        JsonNode data;
        try {
            data = objectMapper.readTree(responseBody).path("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析嵌入响应失败", e);
        }
        if (!data.isArray()) {
            throw new IllegalStateException("嵌入响应缺少 data 数组：" + responseBody);
        }

        List<float[]> vectors = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("嵌入响应里有一条没有 embedding：" + item);
            }
            int actual = embedding.size();
            if (actual != dimension()) {
                throw new IllegalStateException("嵌入维度与配置不符：配置 " + dimension()
                        + "，模型返回 " + actual + "；请核对 agent.kb.embedding.model 与 dimension");
            }
            float[] vector = new float[actual];
            for (int i = 0; i < actual; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
