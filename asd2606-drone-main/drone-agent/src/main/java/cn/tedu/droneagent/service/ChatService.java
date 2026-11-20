package cn.tedu.droneagent.service;

import cn.tedu.droneagent.bill.BillRecord;
import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.common.TraceIds;
import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextBill;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.context.MessageKind;
import cn.tedu.droneagent.context.TokenEstimator;
import cn.tedu.droneagent.context.TokenUsage;
import cn.tedu.droneagent.memory.ConversationCompactor;
import cn.tedu.droneagent.memory.SensitiveMasker;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.prompt.PromptCatalog;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ModelTurn;
import cn.tedu.droneagent.tool.ToolCall;
import cn.tedu.droneagent.tool.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型对话服务。
 *
 * <p>直接走 HTTP 调用 DeepSeek 的 /chat/completions，不引第三方 SDK ——
 * 参数就那几个，用 JDK 自带的 HttpClient 足够，少一个依赖。
 *
 * <h2>它不再校验历史</h2>
 * 传进来的消息**已经由服务端组装好**（{@link cn.tedu.droneagent.memory.ContextAssembler}），
 * 不是客户端塞的，所以这里只负责拼上系统提示词再发出去。
 * 早先那套「角色白名单 / 长度上限 / 尾部必须是提问」的校验已经移到组装环节——
 * 放在那里才拦得住源头，放在这里既拦不住也已经多余。
 *
 * <p>系统提示词仍然只由服务端决定（从 {@link PromptCatalog} 按名字 {@code assistant.system} 取），
 * 且永远排在第一条。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不管会话</b>：不读也不写会话存储。上下文是调用方组装好传进来的，
 *       回复由调用方负责写回。</li>
 *   <li><b>不组装上下文</b>：不决定要带多少条历史、要不要注入长期记忆
 *       （那是 {@link cn.tedu.droneagent.memory.ContextAssembler} 的事）。</li>
 *   <li><b>不做权限判断</b>：能调到这里说明鉴权已经过了（见 {@code AuthInterceptor}）。</li>
 * </ul>
 *
 * <h2>工具调用走单独的方法</h2>
 * 带工具的对话不是「一次问答」而是「若干轮」：模型可能先要调用工具，拿到结果再作答。
 * 那条链路走 {@link #chatStep}（返回 {@link ModelTurn}），而不是 {@link #chat} ——
 * 因为它必须把「模型要调工具」这个中间状态**如实交回**给编排层，
 * 而不能像 {@link #chat} 那样只认最终文本。一次性与流式两条老路径**不受影响**。
 *
 * <p>唯一的例外是它需要 {@link SessionKey}：<b>仅用于日志定位</b>，
 * 行为上完全不依赖它。之所以要传，是因为压在这条日志前后的调用都是按会话串行的，
 * 不把会话标识写进日志就没法把「这次模型调用」和「哪个用户哪个会话」对上。
 */
@Slf4j
@Service
public class ChatService implements ConversationCompactor {

    /** 系统提示词在 {@link PromptCatalog} 里的名字（= 文件相对路径 `assistant/system.md`） */
    private static final String SYSTEM_PROMPT = "assistant.system";

    /** 摘要提示词在 {@link PromptCatalog} 里的名字（= 文件相对路径 `tool/summary.md`） */
    private static final String SUMMARY_PROMPT = "tool.summary";

    /** 会话压缩提示词（`REQ-AGENT-0020`） */
    private static final String COMPACTION_PROMPT = "memory.compaction";

    private final DeepSeekProperties props;

    /** 提示词目录：系统提示词从这里按名字取（`REQ-AGENT-0008` 之后就不再写在配置里了） */
    private final PromptCatalog promptCatalog;

    private final ObjectMapper objectMapper;

    private final SkillCatalog skillCatalog;

    /** 账单落盘（`REQ-AGENT-0025`）；测试与不需要落盘的场景用 noop */
    private final BillStore billStore;

    private final HttpClient httpClient;

    /** 兼容旧调用：没有 Skill 目录（测试与不需要裁剪的场景） */
    public ChatService(DeepSeekProperties props, PromptCatalog promptCatalog, ObjectMapper objectMapper) {
        this(props, promptCatalog, objectMapper, SkillCatalog.empty());
    }

    /** 兼容旧调用：不落账单 */
    public ChatService(DeepSeekProperties props, PromptCatalog promptCatalog, ObjectMapper objectMapper,
                       SkillCatalog skillCatalog) {
        this(props, promptCatalog, objectMapper, skillCatalog, BillStore.noop());
    }

    @Autowired
    public ChatService(DeepSeekProperties props, PromptCatalog promptCatalog, ObjectMapper objectMapper,
                       SkillCatalog skillCatalog, BillStore billStore) {
        this.props = props;
        this.promptCatalog = promptCatalog;
        this.objectMapper = objectMapper;
        this.skillCatalog = skillCatalog;
        this.billStore = billStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 一次性拿到完整回复。
     *
     * @param key     本次对话对应的会话，<b>仅用于日志定位</b>
     * @param context 服务端组装好的消息（不含系统提示词）
     * @return 模型回复的正文（choices[0].message.content）
     * @throws IllegalStateException 未配置 API-KEY、模型不可用、返回非 200
     */
    public String chat(SessionKey key, List<ContextMessage> context) {
        return chat(key, context, java.util.Set.of());
    }

    /**
     * 一次性拿到完整回复，并按当前用户权限裁剪系统提示词里的 Skill 清单。
     *
     * @param permissions 当前用户的权限码；决定 L1 里能看到哪些标准化流程
     */
    public String chat(SessionKey key, List<ContextMessage> context, java.util.Set<String> permissions) {
        return chat(key, context, permissions, TraceIds.newId());
    }

    /**
     * 带 traceId 的完整版：账单日志要和请求链路同一条 trace，排障时才串得起来。
     */
    public String chat(SessionKey key, List<ContextMessage> context,
                       java.util.Set<String> permissions, String traceId) {
        if (!props.configured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
        }

        BuiltRequest built = buildRequest(context, List.of(), permissions, false);
        String requestBody = built.body();
        String url = props.baseUrl() + "/chat/completions";
        long start = System.nanoTime();

        /*
         * 入参全量打印，便于回答「模型为什么这么答」。
         * ⚠️ 两个约束：
         *   1. 只打请求体，绝不打请求头 —— 头里是 Authorization: Bearer <API-KEY>；
         *   2. 请求体要**先过脱敏**再打 —— 里面是用户的原始提问，可能带手机号。
         *      落盘要脱敏，日志同样是「写出去的东西」，规则一样。
         */
        log.debug("调用模型入参：userId={}，conversationId={}，url={}，body={}",
                key.userId(), key.conversationId(), url, SensitiveMasker.mask(requestBody));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("调用模型失败：userId={}，conversationId={}，原因={}",
                    key.userId(), key.conversationId(), e.getMessage());
            throw new IllegalStateException("调用大模型失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用大模型被中断", e);
        }

        // 出参也全量打印（同样脱敏）：开启深度思考时这里还能看到 reasoning_content
        log.debug("调用模型出参：userId={}，conversationId={}，HTTP {}，body={}",
                key.userId(), key.conversationId(), response.statusCode(), SensitiveMasker.mask(response.body()));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode() + "：" + response.body());
        }
        logBill(key, traceId, built.bill(), usageOf(response.body()), built.prefixFingerprint(),
                millisSince(start));
        return extractContent(response.body());
    }

    /**
     * 流式对话：每读到一段增量就回调一次 {@code onDelta}，方法返回时流已结束。
     *
     * @param key     本次对话对应的会话，<b>仅用于日志定位</b>
     * @param context 服务端组装好的消息（不含系统提示词）
     * @param onDelta 增量回调；返回 false 表示调用方不再需要后续内容，此时立即停止读取
     * @throws IllegalStateException 未配置 API-KEY、模型不可用、返回非 200，或读取中途失败
     */
    public void streamChat(SessionKey key, List<ContextMessage> context, DeltaConsumer onDelta) {
        streamChat(key, context, java.util.Set.of(), onDelta);
    }

    /** 流式回复，并按权限裁剪 L1（SSE 在请求线程上取好权限后传入） */
    public void streamChat(SessionKey key, List<ContextMessage> context,
                           java.util.Set<String> permissions, DeltaConsumer onDelta) {
        streamChat(key, context, permissions, TraceIds.newId(), onDelta);
    }

    /**
     * 带 traceId 的流式版。流式请求额外带 {@code stream_options.include_usage}，
     * 这样最后一帧会返回真实 {@code prompt_tokens}，账单才能对账。
     */
    public void streamChat(SessionKey key, List<ContextMessage> context,
                           java.util.Set<String> permissions, String traceId, DeltaConsumer onDelta) {
        if (!props.configured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
        }

        BuiltRequest built = buildRequest(context, List.of(), permissions, true);
        String requestBody = built.body();
        String url = props.baseUrl() + "/chat/completions";

        // 与一次性调用一样：只打请求体（且先脱敏），不打请求头（头里是 API-KEY）
        log.debug("调用模型（流式）入参：userId={}，conversationId={}，url={}，body={}",
                key.userId(), key.conversationId(), url, SensitiveMasker.mask(requestBody));

        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("调用模型（流式）失败：userId={}，conversationId={}，原因={}",
                    key.userId(), key.conversationId(), e.getMessage());
            throw new IllegalStateException("调用大模型失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用大模型被中断", e);
        }

        if (response.statusCode() != 200) {
            String errorBody = readFully(response.body());
            log.warn("调用模型（流式）失败：userId={}，conversationId={}，HTTP {}，body={}",
                    key.userId(), key.conversationId(), response.statusCode(), SensitiveMasker.mask(errorBody));
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode() + "：" + errorBody);
        }

        TokenUsage usage = null;
        StringBuilder full = new StringBuilder();
        // try-with-resources：无论正常结束还是客户端断开，都要关掉与模型的连接，
        // 否则上游会一直推、我们这边没人读，连接和额度都被白占
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // SSE 是一行一个字段。只认 data:，空行与 ":xxx" 注释行（心跳）跳过
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(payload);
                // 用量帧只带 usage、没有 choices，先接住它
                TokenUsage frameUsage = usageOf(root);
                if (frameUsage != null) {
                    usage = frameUsage;
                }
                // 只取正文增量。开深度思考时模型还会先推 reasoning_content，这里刻意不转发
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isTextual() || delta.asText().isEmpty()) {
                    continue;
                }
                full.append(delta.asText());
                if (!onDelta.accept(delta.asText())) {
                    log.debug("调用方已不再接收，停止读取模型流：userId={}，conversationId={}",
                            key.userId(), key.conversationId());
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取模型流失败：" + e.getMessage(), e);
        }

        logBill(key, traceId, built.bill(), usage, built.prefixFingerprint(), millisSince(start));
        log.debug("调用模型（流式）出参：userId={}，conversationId={}，完整回复={}",
                key.userId(), key.conversationId(), SensitiveMasker.mask(full.toString()));
    }

    /**
     * 带工具的**流式**模型调用：边收边把思考/正文增量交给监听器，结束时返回完整回合。
     *
     * <h2>为什么流式也要带 tools</h2>
     * 工具轮次同样要能「边想边显示」；tool_calls 在流里按 index 分片到达，
     * 由 {@link StreamAssembler} 拼接。若模型本轮只调工具、没有正文，
     * 返回的 {@code content} 为 null，调用方照常执行工具再进下一轮。
     *
     * @param listener 增量回调；传 {@link ChatStreamListener#NOOP} 表示不展示
     */
    public ModelTurn streamStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                                String traceId, java.util.Set<String> permissions,
                                ChatStreamListener listener) {
        if (!props.configured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
        }
        ChatStreamListener sink = listener == null ? ChatStreamListener.NOOP : listener;

        BuiltRequest built = buildRequest(messages, tools, permissions, true);
        String requestBody = built.body();
        String url = props.baseUrl() + "/chat/completions";
        log.info("【模型】调用（流式·带工具）：traceId={}，userId={}，conversationId={}，消息 {} 条、工具 {} 个",
                traceId, key.userId(), key.conversationId(), messages.size(), tools.size());
        log.debug("调用模型（流式·工具）入参：traceId={}，userId={}，url={}，body={}",
                traceId, key.userId(), url, SensitiveMasker.mask(requestBody));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        long startNanos = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("调用大模型失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用大模型被中断", e);
        }
        if (response.statusCode() != 200) {
            String errorBody = readFully(response.body());
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode() + "：" + errorBody);
        }

        StreamAssembler assembler = new StreamAssembler();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode root;
                try {
                    root = objectMapper.readTree(payload);
                } catch (JsonProcessingException e) {
                    // 单条坏帧不该让整次流失败；跳过并留痕
                    log.debug("忽略无法解析的流式帧：traceId={}，payload={}", traceId,
                            SensitiveMasker.mask(payload));
                    continue;
                }
                long elapsed = System.nanoTime() - startNanos;
                StreamAssembler.Increment increment = assembler.accept(root, elapsed);
                if (!increment.reasoningDelta().isEmpty()) {
                    sink.onReasoning(increment.reasoningDelta());
                }
                if (!increment.contentDelta().isEmpty()) {
                    sink.onContent(increment.contentDelta());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取模型流失败：" + e.getMessage(), e);
        }

        ModelTurn turn = new ModelTurn(assembler.content(), assembler.toolCalls(),
                assembler.reasoning(), assembler.reasoningMs());
        logBill(key, traceId, built.bill(), assembler.usage(), built.prefixFingerprint(),
                millisSince(startNanos));
        log.info("【模型】流式完成：traceId={}，正文 {} 字，工具调用 {} 个，思考 {} 字，用时 {} ms",
                traceId,
                turn.content() == null ? 0 : turn.content().length(),
                turn.toolCalls().size(),
                turn.reasoningContent() == null ? 0 : turn.reasoningContent().length(),
                turn.reasoningMs());
        return turn;
    }

    /**
     * 带工具的一次模型调用（**非流式**）：可能返回正文，也可能返回「想调用哪些工具」。
     *
     * <p>与 {@link #chat} 的区别：{@code chat} 只认最终文本，本方法把「模型要调工具」
     * 这个中间状态**如实交回**给编排层。工具结果怎么回填，由编排层拼进 {@code messages}
     * 再调一次本方法完成（见 {@link ModelMessage#tool}）。
     *
     * <p>请求体固定 {@code stream=false}：本期工具调用只走非流式（见 ADR-0004）。
     *
     * @param key      本次对话对应的会话，<b>仅用于日志定位</b>
     * @param messages 线格式消息（不含系统提示词，系统提示词由本方法补齐在最前）
     * @param tools    可用的工具定义；空列表表示不带工具
     * @return 本次响应：正文或工具调用请求
     * @throws IllegalStateException 未配置 API-KEY、模型不可用、返回非 200
     */
    /** 便捷重载：自己生成一个 traceId（供直接调用/测试；正常链路由 {@code ConversationService} 传入） */
    public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools) {
        return chatStep(key, messages, tools, TraceIds.newId());
    }

    public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                              String traceId) {
        return chatStep(key, messages, tools, traceId, java.util.Set.of());
    }

    /** 工具循环里的一步；权限决定系统提示词中的 L1 清单 */
    public ModelTurn chatStep(SessionKey key, List<ContextMessage> messages, List<ToolDefinition> tools,
                              String traceId, java.util.Set<String> permissions) {
        if (!props.configured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
        }

        BuiltRequest built = buildRequest(messages, tools, permissions, false);
        String requestBody = built.body();
        String url = props.baseUrl() + "/chat/completions";

        // 一进：INFO 上说清「发了多少消息、带了几个工具」；具体请求体放到 DEBUG（又长又贵）
        log.info("【模型】调用（带工具）：traceId={}，userId={}，conversationId={}，发送 {} 条消息、{} 个工具",
                traceId, key.userId(), key.conversationId(), messages.size(), tools.size());

        // 与 chat 一样：只打请求体（先脱敏），绝不打请求头（头里是 API-KEY）
        log.debug("调用模型（工具）入参：traceId={}，userId={}，conversationId={}，url={}，body={}",
                traceId, key.userId(), key.conversationId(), url, SensitiveMasker.mask(requestBody));

        long start = System.nanoTime();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("调用模型（工具）失败：userId={}，conversationId={}，原因={}",
                    key.userId(), key.conversationId(), e.getMessage());
            throw new IllegalStateException("调用大模型失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用大模型被中断", e);
        }

        log.debug("调用模型（工具）出参：traceId={}，userId={}，conversationId={}，HTTP {}，body={}",
                traceId, key.userId(), key.conversationId(), response.statusCode(),
                SensitiveMasker.mask(response.body()));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode() + "：" + response.body());
        }

        // 一出：讲清这次模型是「要工具」还是「给了正文」，读日志的人才知道下一步往哪走
        ModelTurn turn = parseTurn(response.body());
        logBill(key, traceId, built.bill(), usageOf(response.body()), built.prefixFingerprint(),
                millisSince(start));
        log.info("【模型】返回（带工具）：traceId={}，userId={}，conversationId={}，耗时 {} ms，模型请求工具 {} 个，正文 {} 字",
                traceId, key.userId(), key.conversationId(), millisSince(start), turn.toolCalls().size(),
                turn.content() == null ? 0 : turn.content().length());
        return turn;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 一次性摘要：把 {@code content} 压到 {@code maxChars} 以内。
     *
     * <p>用于工具结果的「模型摘要」压缩（`REQ-AGENT-0007`）：结构化裁剪之后仍超长时才调它。
     * 与 {@link #chat} 不同——它**不套**对话用的系统提示词，而是用摘要专用的提示词
     * （从 {@link PromptCatalog} 按名字 {@code tool.summary} 取，{@code maxChars} 由占位符填），
     * 且**不启用**深度思考（要快）。
     *
     * @throws IllegalStateException 未配置 API-KEY、模型不可用、返回非 200
     */
    public String summarize(String content, int maxChars) {
        return summarizeWithPrompt(SUMMARY_PROMPT, content, maxChars);
    }

    /**
     * 会话内压缩的摘要（`REQ-AGENT-0020`）：把早期对话转录压成背景摘要。
     *
     * <p>与工具结果摘要共用同一套「一次性调用」逻辑，只是提示词不同；
     * 失败时抛 {@link RuntimeException}，由组装层回退到「只保留最近若干条」。
     */
    @Override
    public String compact(String transcript, int maxChars) {
        return summarizeWithPrompt(COMPACTION_PROMPT, transcript, maxChars);
    }

    private String summarizeWithPrompt(String promptName, String content, int maxChars) {
        if (!props.configured()) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY，无法调用大模型");
        }
        String systemPrompt = promptCatalog.render(promptName, Map.of("maxChars", maxChars));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", content)));
        body.put("stream", false);

        String url = props.baseUrl() + "/chat/completions";
        log.info("【模型】调用（摘要）：提示词={}，上限 {} 字，输入 {} 字",
                promptName, maxChars, content.length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("调用大模型失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用大模型被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("大模型返回 HTTP " + response.statusCode() + "：" + response.body());
        }
        return extractContent(response.body());
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    /**
     * 构造请求体并顺手算好**上下文账单**。**包可见**，让测试能直接断言结构，不必真发请求。
     *
     * <p>顺序：模型 → **系统提示词（永远第一条）** → 传入的消息 →（可选）工具定义。
     * 工具定义的 {@code parameters} 要作为 JSON **对象**而不是字符串嵌入，所以这里把
     * {@link ToolDefinition#parametersJson()} 解析成树再放进去。
     *
     * <p>账单与请求体在同一次遍历里产出：消息条数、各类别 token、工具定义 token 直接来自
     * 这次真正要发出去的内容，不会出现「日志里算的和发出去的不是一回事」。
     */
    BuiltRequest buildRequest(List<ContextMessage> context, List<ToolDefinition> tools,
                              java.util.Set<String> permissions, boolean stream) {
        // 系统提示词也要进账单——它是每条消息里最稳定的固定成本之一
        List<ContextMessage> billed = withSystemPrompt(context, permissions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", wireMessages(billed));
        body.put("stream", stream);
        // 流式默认不返回 usage；显式要它，账单才能和真实计费对账（供应商已实测支持）
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }

        if (props.reasoningEffort() != null && !props.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", props.reasoningEffort());
        }
        if (props.thinkingEnabled()) {
            body.put("thinking", Map.of("type", "enabled"));
        }

        int toolsTokens = 0;
        String toolsJson = "";
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> wireTools = tools.stream().map(this::wireTool).toList();
            body.put("tools", wireTools);
            toolsJson = writeJson(wireTools);
            // 工具定义不是消息，但确实是每次请求都重发的固定成本；按实际发送的 JSON 估算。
            // 这里必须用 JSON 系数：它占请求体近九成，套自然语言系数会低估四成
            toolsTokens = TokenEstimator.estimateJson(toolsJson);
        }

        String json = writeJson(body);
        String prefixFingerprint = prefixFingerprint(billed.get(0).message().content(), toolsJson);
        return new BuiltRequest(json, new ContextBill(billed, toolsTokens,
                tools == null ? 0 : tools.size()), prefixFingerprint);
    }

    /**
     * 一次请求的产物：真正发出去的 JSON + 对应的账单。
     *
     * <p>两者一起返回，是为了让调用方在打完请求后能直接按同一次内容出账，
     * 不必再拼一遍消息列表（拼错了账单就失真）。
     */
    record BuiltRequest(String body, ContextBill bill, String prefixFingerprint) {
    }

    /**
     * 把账单与模型返回的真实用量打一行 INFO。
     *
     * <p>只打数量与 token，<b>不打任何消息正文</b>：常规日志里不该出现用户的原话。
     * 拿不到真实用量（客户端断开、供应商没回 usage）时如实说「实际值不可用」，不编数。
     */
    private void logBill(SessionKey key, String traceId, ContextBill bill, TokenUsage usage,
                         String prefixFingerprint, long latencyMs) {
        recordBill(key, traceId, bill, usage, prefixFingerprint, latencyMs);

        // 工具结果按工具名归因：只给总数看不出「贵在哪个工具」
        String toolDetail = bill.toolResultCounts().isEmpty()
                ? "" : "；工具结果分布 " + bill.toolResultCounts();
        if (usage == null) {
            log.info("【上下文账单】traceId={}，userId={}，conversationId={}，估算 {} token（{}）{}；"
                            + "前缀指纹={}；实际值不可用",
                    traceId, key.userId(), key.conversationId(), bill.totalTokens(), bill.describe(),
                    toolDetail, prefixFingerprint);
            return;
        }
        double deviation = usage.promptTokens() == 0 ? 0
                : (bill.totalTokens() - usage.promptTokens()) * 100.0 / usage.promptTokens();
        double hitRate = usage.promptTokens() == 0 ? 0
                : usage.cachedTokens() * 100.0 / usage.promptTokens();
        log.info("【上下文账单】traceId={}，userId={}，conversationId={}，估算 {} token（{}）{}；"
                        + "前缀指纹={}；模型实际 prompt_tokens={}，缓存命中={}（{}%），估算偏差 {}%",
                traceId, key.userId(), key.conversationId(), bill.totalTokens(), bill.describe(), toolDetail,
                prefixFingerprint, usage.promptTokens(), usage.cachedTokens(),
                String.format("%.1f", hitRate), String.format("%+.1f", deviation));
    }

    /**
     * 把本次调用写成一条账单记录（`REQ-AGENT-0025`）。
     *
     * <p>与日志是两条路：日志给人看，记录给页面看。写失败只 WARN，不影响对话。
     */
    private void recordBill(SessionKey key, String traceId, ContextBill bill, TokenUsage usage,
                            String prefixFingerprint, long latencyMs) {
        Integer promptTokens = usage == null ? null : usage.promptTokens();
        Integer cachedTokens = usage == null ? null : usage.cachedTokens();
        Double hitRate = usage == null || usage.promptTokens() == 0 ? null
                : Math.round(usage.cachedTokens() * 1000.0 / usage.promptTokens()) / 10.0;

        billStore.record(new BillRecord(
                OffsetDateTime.now().toString(), traceId, key.userId(), key.conversationId(),
                0, props.model(), prefixFingerprint, bill.totalTokens(), kindTokens(bill),
                bill.toolsTokens(), bill.toolCount(), promptTokens, cachedTokens, hitRate, latencyMs,
                List.copyOf(bill.toolResultCounts().keySet())));
    }

    /** 账单分类：消息类别 + 工具定义（工具定义不是消息，但同属请求上下文） */
    private static Map<String, Integer> kindTokens(ContextBill bill) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        bill.byKind().forEach((kind, bucket) -> {
            if (bucket.count() > 0) {
                kinds.put(kind.name(), bucket.tokens());
            }
        });
        if (bill.toolCount() > 0) {
            kinds.put("TOOL_DEFINITIONS", bill.toolsTokens());
        }
        return kinds;
    }

    /** 从响应 JSON 里取真实用量；没有 usage 节点时返回 null（不能拿 0 冒充） */
    private TokenUsage usageOf(String responseBody) {
        try {
            return usageOf(objectMapper.readTree(responseBody));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 从已解析的节点里取用量；流式的最后一帧也走这里 */
    private static TokenUsage usageOf(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.has("prompt_tokens")) {
            return null;
        }
        int cached = usage.has("prompt_cache_hit_tokens")
                ? usage.path("prompt_cache_hit_tokens").asInt(0)
                : usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        return new TokenUsage(usage.path("prompt_tokens").asInt(0), cached);
    }

    /**
     * 拼上系统提示词并打上账单分类。**包可见**：测试要断言「系统提示词永远排第一」。
     *
     * <p>计量与发送共用这一份列表——账单里的顺序就是请求里的顺序。
     */
    List<ContextMessage> withSystemPrompt(List<ContextMessage> context, java.util.Set<String> permissions) {
        List<ContextMessage> billed = new ArrayList<>(context.size() + 1);
        billed.add(new ContextMessage(
                new ModelMessage("system", systemPrompt(permissions), null, null, null),
                MessageKind.SYSTEM, null));
        billed.addAll(context);
        return List.copyOf(billed);
    }

    /** 便捷重载：只关心请求体（测试与不需要账单的场景） */
    String buildToolBody(List<ContextMessage> messages, List<ToolDefinition> tools) {
        return buildToolBody(messages, tools, java.util.Set.of());
    }

    String buildToolBody(List<ContextMessage> messages, List<ToolDefinition> tools,
                         java.util.Set<String> permissions) {
        return buildRequest(messages, tools, permissions, false).body();
    }

    /**
     * 前缀指纹：系统提示词 + 工具定义的 SHA-256 前 12 位。
     *
     * <p>模型的前缀缓存只认「逐 token 相同的前缀」。工具顺序、Skill 顺序、权限变化
     * 都会让指纹变化——把指纹打进日志，跨请求对比就能立刻判断「这次没命中缓存，
     * 是内容真的变了，还是顺序抖了」。
     */
    private static String prefixFingerprint(String systemPrompt, String toolsJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(toolsJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    /**
     * 解析一次响应。**包可见**，便于测试喂固定报文。
     *
     * <p>{@code content} 缺失时给 null（请求工具时它本就为空）；
     * {@code tool_calls} 逐项取出 id / 函数名 / 参数原文。
     */
    ModelTurn parseTurn(String responseBody) {
        try {
            JsonNode message = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message");
            JsonNode contentNode = message.path("content");
            String content = contentNode.isTextual() ? contentNode.asText() : null;

            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    JsonNode function = call.path("function");
                    calls.add(new ToolCall(
                            call.path("id").asText(""),
                            function.path("name").asText(""),
                            function.path("arguments").asText("{}")));
                }
            }
            String reasoning = message.path("reasoning_content").asText(null);
            return new ModelTurn(content, calls, reasoning);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析大模型响应失败：" + responseBody, e);
        }
    }

    /** 线格式消息 → 请求体里的一条 message */
    private Map<String, Object> wireMessage(ModelMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role());
        // content 始终带上：助手请求调工具时它为空串，其余情况就是正文
        wire.put("content", message.content() == null ? "" : message.content());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(this::wireToolCall).toList());
        }
        if (message.toolCallId() != null) {
            wire.put("tool_call_id", message.toolCallId());
        }
        // 深度思考模式下，带 tool_calls 的 assistant 消息必须把 reasoning_content 原样带回
        if (message.reasoningContent() != null && !message.reasoningContent().isBlank()) {
            wire.put("reasoning_content", message.reasoningContent());
        }
        return wire;
    }

    /** 模型请求的工具调用 → 请求体里的一项 tool_calls（回填时要原样带回 id 与参数） */
    private Map<String, Object> wireToolCall(ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", call.id());
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
    }

    /** 工具定义 → 请求体 tools 里的一项 */
    private Map<String, Object> wireTool(ToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", parseJson(tool.parametersJson()));
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "function");
        wire.put("function", function);
        return wire;
    }

    /** 把工具的 parametersJson 解析成 JSON 树，才能作为**对象**嵌入请求体 */
    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具参数 Schema 不是合法 JSON：" + json, e);
        }
    }

    /**
     * 带计量的消息 → 请求体里的 messages。
     *
     * <p>包可见是为了让测试能直接断言「系统提示词永远排第一条」——这个顺序没法靠行为观察。
     * 这里的顺序与账单里的顺序是同一份列表，两边不会各说各话。
     */
    List<Map<String, Object>> wireMessages(List<ContextMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>(messages.size());
        for (ContextMessage message : messages) {
            wire.add(wireMessage(message.message()));
        }
        return wire;
    }

    /** 系统提示词：L1 清单按当前用户权限实时裁剪 */
    private String systemPrompt(java.util.Set<String> permissions) {
        return promptCatalog.render(SYSTEM_PROMPT, Map.of("skills", skillCatalog.l1Text(permissions)));
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode content = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new IllegalStateException("大模型响应中没有 content：" + responseBody);
            }
            return content.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析大模型响应失败：" + responseBody, e);
        }
    }

    /** 把响应体整个读成字符串，用于打印出错时的原文 */
    private String readFully(InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(读取响应失败：" + e.getMessage() + ")";
        }
    }

    /**
     * 流式增量回调。
     *
     * <p>用自定义接口而不是 {@link java.util.function.Consumer}，是为了让回调能通过
     * 返回值告诉上游「我不需要了」—— 客户端断开时要能立刻停止读取，Consumer 表达不了。
     */
    @FunctionalInterface
    public interface DeltaConsumer {

        /**
         * @return false 表示不需要后续增量，上游应立即停止读取
         */
        boolean accept(String delta);
    }
}
