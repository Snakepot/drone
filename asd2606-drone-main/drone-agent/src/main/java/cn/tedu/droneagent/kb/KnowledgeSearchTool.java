package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import cn.tedu.droneagent.vector.Retriever;
import cn.tedu.droneagent.vector.ScoredRecord;
import cn.tedu.droneagent.vector.VectorFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「检索内部资料」工具：助手用它按**自然语言**找资料，再基于原文作答。
 *
 * <h2>它只做一件事</h2>
 * 「一句问题 → 一批带出处的资料片段」。要不要用它、怎么用结果，都由模型决定
 * （工具**只提供行为**，描述与参数在 {@code agent.tool.catalog}）。
 *
 * <h2>权限是**前置过滤**，不是事后隐藏</h2>
 * 用调用方的**权限码**构造过滤条件（{@link KnowledgeScope}），**无权的内容在检索阶段就命中不到**——
 * 而不是「查出来再不让说」。而且**没有权限码时 fail-closed**（谁都看不到），
 * 所以「忘了传身份」的后果是「查不到」，不是「全都能看」。
 *
 * <h2>为什么返回「查不到」而不是抛异常</h2>
 * 工具失败不该炸整条对话。把「没找到」当成**结果**回填，模型才能如实告诉用户，
 * 而不是让用户看到一个报错页。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不做最终答复</b>：只给素材，话由模型说。</li>
 *   <li><b>不算向量、不查库</b>：那是 {@link Retriever} 与向量库的活。</li>
 * </ul>
 */
@Slf4j
public class KnowledgeSearchTool implements Tool {

    /** 工具名。既是「配置里元数据的键」，也是「模型回来时指代本工具」的字符串 */
    public static final String NAME = "search_knowledge";

    /** 兼容模型可能给的不同键名 */
    private static final List<String> QUERY_KEYS = List.of("query", "question", "q", "keyword");

    private final Retriever retriever;

    private final ObjectMapper objectMapper;

    /** 总开关（{@code agent.kb.enabled}）。关掉时工具**仍然存在**，但一次检索都不做 */
    private final boolean enabled;

    public KnowledgeSearchTool(Retriever retriever, boolean enabled) {
        this(retriever, enabled, new ObjectMapper());
    }

    KnowledgeSearchTool(Retriever retriever, boolean enabled, ObjectMapper objectMapper) {
        this.retriever = retriever;
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 无身份调用（测试/离线）等同于「匿名」——按 fail-closed 处理，查不到任何资料 */
    @Override
    public ToolResult invoke(String argumentsJson) {
        return invoke(argumentsJson, ToolContext.anonymous());
    }

    @Override
    public ToolResult invoke(String argumentsJson, ToolContext context) {
        if (!enabled) {
            // 关掉开关就该**一次检索都不发生**（不是「查了但不给」）
            log.info("【知识检索】总开关已关闭，直接拒绝");
            return ToolResult.failed("内部资料检索当前未启用");
        }
        String query = extractQuery(argumentsJson);
        if (query == null || query.isBlank()) {
            log.warn("【知识检索】参数缺少查询内容：arguments={}", argumentsJson);
            return ToolResult.failed("工具参数缺少查询内容（query）");
        }
        // 一个权限码都没有 = 这个人根本不在资料体系里。直说「没权限」既不泄露资料存在性，也便于排查配置
        if (context.permissions().isEmpty()) {
            log.warn("【知识检索】调用方没有任何权限码，拒绝检索");
            return ToolResult.failed("当前账号没有检索内部资料的权限");
        }

        VectorFilter filter = KnowledgeScope.visibleTo(context.permissions());
        List<ScoredRecord> hits = retriever.retrieve(query, filter);
        if (hits.isEmpty()) {
            log.info("【知识检索】未命中：query 长度 {}，可见范围 {}", query.length(), context.permissions());
            // 【重要】不能编造：明说没找到，并给出可查方向
            return ToolResult.ok("没有检索到相关资料。可以换一种说法再问，或确认该内容是否收录在内部资料里。");
        }

        log.info("【知识检索】命中 {} 条：query 长度 {}，可见范围 {}", hits.size(), query.length(),
                context.permissions());
        return ToolResult.ok(format(hits));
    }

    /**
     * 把命中拼成给模型看的文本，**每段都带出处**（`文件名 › 章节`）——
     * 模型据此才能在答复里注明「出自哪份资料的哪一节」。
     *
     * <p>同一份资料、同一章节、块号连续的命中会**合成一段**：它们本来就是把同一段正文切开的两块，
     * 分开列会让模型以为这是两处不同的内容。
     */
    private String format(List<ScoredRecord> hits) {
        Map<String, List<ScoredRecord>> bySection = new LinkedHashMap<>();
        for (ScoredRecord hit : hits) {
            bySection.computeIfAbsent(citation(hit), key -> new ArrayList<>()).add(hit);
        }

        StringBuilder text = new StringBuilder("检索到 ").append(bySection.size()).append(" 段相关资料：\n");
        for (Map.Entry<String, List<ScoredRecord>> entry : bySection.entrySet()) {
            List<ScoredRecord> group = new ArrayList<>(entry.getValue());
            group.sort((a, b) -> Integer.compare(chunkIndexOf(a), chunkIndexOf(b)));
            text.append("\n【资料】").append(entry.getKey()).append('\n');
            for (ScoredRecord record : group) {
                text.append(metadataText(record, "content")).append('\n');
            }
        }
        return text.toString();
    }

    private static String citation(ScoredRecord record) {
        String fileName = metadataText(record, "file_name");
        String section = metadataText(record, "section");
        return section.isBlank() ? fileName : fileName + " › " + section;
    }

    private static int chunkIndexOf(ScoredRecord record) {
        Object value = record.metadata().get("chunk_index");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String metadataText(ScoredRecord record, String key) {
        Object value = record.metadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 从参数 JSON 里取查询文本；兼容几个常见键名，取不到返回 null */
    private String extractQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            for (String key : QUERY_KEYS) {
                JsonNode node = root.path(key);
                if (node.isTextual() && !node.asText().isBlank()) {
                    return node.asText();
                }
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
