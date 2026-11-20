package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆的**按需检索**工具（`REQ-AGENT-0021`）。
 *
 * <h2>为什么改成「工具」而不是每轮注入</h2>
 * 之前每个请求都会把其他会话的最近 10 轮 / 8,000 字符塞进上下文——与当前问题无关也照付。
 * 改成工具之后：模型只在「用户提到上次/之前/我说过」时取一次，固定成本几乎归零。
 *
 * <h2>为什么先做关键词</h2>
 * 业务问题大量是编号、人名、任务号（`TK-2026-1001`、`UAV-001`），关键词比语义检索更准，
 * 也不用维护嵌入与向量索引。语义召回留到后续需求，替换时**工具契约不变**。
 *
 * <h2>隔离与删除</h2>
 * <ul>
 *   <li>只读 {@code <root>/<userId>/} 下的归档正文，天然不跨用户；</li>
 *   <li>删除会话会物理删除正文，检索随之失效，不存在「删了还能查到」；</li>
 *   <li>{@link ToolContext#userId()} 为空时 **fail-closed**，一个文件都不读。</li>
 * </ul>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不自动调用</b>：是否检索由模型按系统提示词决定；</li>
 *   <li><b>不做摘要</b>：返回原文摘录，避免二次失真；</li>
 *   <li><b>不写记忆</b>：写入仍由归档链路负责。</li>
 * </ul>
 */
@Slf4j
public class MemorySearchTool implements Tool {

    public static final String NAME = "search_memory";

    /** 最多返回几段记忆；再多会挤占上下文 */
    private static final int MAX_HITS = 3;

    /** 返回内容总长上限（字符） */
    private static final int MAX_TOTAL_CHARS = 2000;

    /** 摘录窗口：命中位置前后各取多少字符 */
    private static final int EXCERPT_RADIUS = 120;

    /** 同一个词最多计几次，避免高频词把分数拉爆 */
    private static final int MAX_OCCURRENCES_PER_TERM = 5;

    /** 编号类词项：字母/数字开头，允许中间出现 - 与 _（如 TK-2026-1001、UAV-001） */
    private static final Pattern CODE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,}");

    /** 按空白与中英文标点切词（中文不做分词，只按标点断句） */
    private static final Pattern SEPARATOR =
            Pattern.compile("[\\s，。？！、；：,.?!;:()（）\\[\\]【】\"'`]+");

    private final LongTermMemoryStore store;

    private final ObjectMapper objectMapper;

    public MemorySearchTool(LongTermMemoryStore store) {
        this(store, new ObjectMapper());
    }

    MemorySearchTool(LongTermMemoryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 无身份调用（测试/离线）等同于「匿名」——按 fail-closed 处理，不读任何文件 */
    @Override
    public ToolResult invoke(String argumentsJson) {
        return invoke(argumentsJson, ToolContext.anonymous());
    }

    @Override
    public ToolResult invoke(String argumentsJson, ToolContext context) {
        Long userId = context == null ? null : context.userId();
        if (userId == null) {
            log.warn("【记忆检索】调用方没有 userId，拒绝检索");
            return ToolResult.failed("当前请求没有用户身份，无法检索长期记忆");
        }

        String query = extractQuery(argumentsJson);
        if (query == null || query.isBlank()) {
            log.warn("【记忆检索】参数缺少查询内容：arguments={}", argumentsJson);
            return ToolResult.failed("工具参数缺少查询内容（query）");
        }

        long startNanos = System.nanoTime();
        List<LongTermMemoryStore.ArchivedSection> sections;
        try {
            sections = store.readAll(userId);
        } catch (UncheckedIOException e) {
            log.warn("【记忆检索】读取归档失败：userId={}，原因={}", userId, e.getMessage());
            return ToolResult.failed("读取长期记忆失败，请稍后再试");
        }

        Map<String, Integer> terms = termsOf(query);
        List<Scored> hits = new ArrayList<>();
        for (LongTermMemoryStore.ArchivedSection section : sections) {
            int score = score(section, terms);
            if (score > 0) {
                hits.add(new Scored(section, score));
            }
        }

        if (hits.isEmpty()) {
            log.info("【记忆检索】未命中：userId={}，查询 {} 字，归档 {} 段，耗时 {} ms",
                    userId, query.length(), sections.size(), millisSince(startNanos));
            return ToolResult.ok("没有找到相关记忆。可以换一个关键词（如任务号、设备编号）再试。");
        }

        hits.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(scored -> scored.section().startedAtText(),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        log.info("【记忆检索】命中：userId={}，查询 {} 字，归档 {} 段，命中 {} 段，耗时 {} ms",
                userId, query.length(), sections.size(), hits.size(), millisSince(startNanos));
        return ToolResult.ok(format(hits, terms));
    }

    /** 解析 query；JSON 不合法或类型不对时返回 null */
    private String extractQuery(String argumentsJson) {
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            JsonNode query = root.path("query");
            return query.isTextual() ? query.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成检索词项与权重：整句 3、编号 2、普通词 1。
     *
     * <p>用 {@link LinkedHashMap} 去重并保留最高权重——同一个词既是编号又是普通词时只算一次。
     */
    private static Map<String, Integer> termsOf(String query) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        addTerm(terms, query.trim(), 3);
        for (String token : SEPARATOR.split(query)) {
            addTerm(terms, token.trim(), 1);
        }
        Matcher matcher = CODE_TOKEN.matcher(query);
        while (matcher.find()) {
            addTerm(terms, matcher.group(), 2);
        }
        return terms;
    }

    private static void addTerm(Map<String, Integer> terms, String term, int weight) {
        if (term == null || term.length() < 2) {
            return;
        }
        String key = term.toLowerCase(Locale.ROOT);
        terms.merge(key, weight, Math::max);
    }

    private static int score(LongTermMemoryStore.ArchivedSection section, Map<String, Integer> terms) {
        StringBuilder text = new StringBuilder();
        for (var message : section.messages()) {
            text.append(message.content()).append('\n');
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);

        int score = 0;
        for (Map.Entry<String, Integer> term : terms.entrySet()) {
            int occurrences = countOccurrences(lower, term.getKey());
            score += Math.min(occurrences, MAX_OCCURRENCES_PER_TERM) * term.getValue();
        }
        return score;
    }

    private static int countOccurrences(String text, String term) {
        int count = 0;
        int index = 0;
        while (count < MAX_OCCURRENCES_PER_TERM) {
            index = text.indexOf(term, index);
            if (index < 0) {
                break;
            }
            count++;
            index += term.length();
        }
        return count;
    }

    /**
     * 渲染结果：每段带会话与时间，正文只取命中附近的一小段。
     *
     * <p>总长受 {@link #MAX_TOTAL_CHARS} 约束——检索结果同样属于工具回填，不能无限占上下文。
     */
    private static String format(List<Scored> hits, Map<String, Integer> terms) {
        StringBuilder out = new StringBuilder("检索到 ").append(Math.min(hits.size(), MAX_HITS))
                .append(" 段相关记忆：");
        int used = 0;
        int included = 0;

        for (Scored hit : hits) {
            if (included >= MAX_HITS) {
                break;
            }
            LongTermMemoryStore.ArchivedSection section = hit.section();
            String excerpt = excerpt(section, terms);
            String block = "\n\n【记忆】会话 " + section.conversationId()
                    + "（" + section.startedAtText() + "）\n" + excerpt;
            if (used + block.length() > MAX_TOTAL_CHARS) {
                break;
            }
            out.append(block);
            used += block.length();
            included++;
        }
        if (included < hits.size()) {
            out.append("\n\n（更多结果已省略）");
        }
        return out.toString();
    }

    /** 从首个命中词的位置向两侧各取 {@link #EXCERPT_RADIUS} 字 */
    private static String excerpt(LongTermMemoryStore.ArchivedSection section,
                                  Map<String, Integer> terms) {
        String text = String.join("\n", section.messages().stream().map(m -> m.content()).toList());
        if (text.length() <= EXCERPT_RADIUS * 2) {
            return text;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        int hit = -1;
        for (String term : terms.keySet()) {
            int index = lower.indexOf(term);
            if (index >= 0 && (hit < 0 || index < hit)) {
                hit = index;
            }
        }
        if (hit < 0) {
            hit = 0;
        }

        int from = Math.max(0, hit - EXCERPT_RADIUS);
        int to = Math.min(text.length(), hit + EXCERPT_RADIUS);
        String excerpt = text.substring(from, to);
        return (from > 0 ? "…" : "") + excerpt + (to < text.length() ? "…" : "");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 一段命中：原始会话段 + 分数 */
    private record Scored(LongTermMemoryStore.ArchivedSection section, int score) {
    }
}
