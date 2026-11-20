package cn.tedu.droneagent.bill;

import cn.tedu.droneagent.config.BillProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账单存储：**追加写 JSONL** + 最近请求的内存缓存（`REQ-AGENT-0025`）。
 *
 * <h2>为什么先用文件，不上数据库</h2>
 * 账单是「按天追加、按区间读」的日志型数据，JSONL 足够；`drone-agent` 本来就没有数据库，
 * 引入一个库只为存账单不划算。多实例部署时再迁 DB，接口不用变。
 *
 * <h2>两条读路径</h2>
 * <ul>
 *   <li>{@link #summary(String)}：请求刚结束时按 traceId 从**内存**取，给 SSE {@code done}；</li>
 *   <li>{@link #query} / {@link #aggregate}：管理端按区间读**文件**，重启后历史仍在。</li>
 * </ul>
 *
 * <h2>失败策略</h2>
 * 写账单失败只记 WARN，绝不拖垮对话——账单是观测，不是业务主流程。
 */
@Slf4j
public class BillStore {

    private static final String FILE_PREFIX = "bills-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final boolean enabled;

    private final Path root;

    private final int retentionDays;

    private final ObjectMapper objectMapper;

    /** 最近请求的逐轮记录：traceId → 轮次列表；10 分钟后过期，避免常驻 */
    private final Cache<String, List<BillRecord>> recent;

    private final Object writeLock = new Object();

    /** 上次清理的日期：跨天时再清一次，避免每天启动才清 */
    private volatile LocalDate cleanedDay;

    public static BillStore noop() {
        return new BillStore(false, null, DEFAULT_RETENTION_DAYS, new ObjectMapper());
    }

    public BillStore(BillProperties properties, ObjectMapper objectMapper) {
        this(properties.enabled(),
                properties.root() == null || properties.root().isBlank()
                        ? Path.of("./data/bills") : Path.of(properties.root()),
                properties.retentionDays() <= 0 ? DEFAULT_RETENTION_DAYS : properties.retentionDays(),
                objectMapper);
    }

    private BillStore(boolean enabled, Path root, int retentionDays, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.root = root;
        this.retentionDays = retentionDays;
        this.objectMapper = objectMapper;
        this.recent = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
        if (enabled) {
            cleanupExpired(LocalDate.now());
        }
    }

    /**
     * 记一次模型调用。轮次由本类按 traceId 递增；写失败只 WARN。
     */
    public void record(BillRecord record) {
        if (!enabled || record == null) {
            return;
        }
        List<BillRecord> stored = recent.asMap().compute(record.traceId(), (key, old) -> {
            List<BillRecord> next = old == null ? new ArrayList<>() : new ArrayList<>(old);
            next.add(record.withRound(next.size() + 1));
            return next;
        });
        append(stored.get(stored.size() - 1));
        cleanupIfDayChanged();
    }

    /**
     * 一次请求的合计，供 SSE {@code done} 使用。
     *
     * @return 没有该 trace 的记录时返回 null（例如工具关闭、只走了非流式摘要）
     */
    public RequestBill summary(String traceId) {
        if (!enabled) {
            return null;
        }
        List<BillRecord> records = recent.getIfPresent(traceId);
        if (records == null || records.isEmpty()) {
            return null;
        }
        BillRecord last = records.get(records.size() - 1);
        long prompt = 0;
        long cached = 0;
        Set<String> tools = new LinkedHashSet<>();
        for (BillRecord record : records) {
            if (record.promptTokens() != null) {
                prompt += record.promptTokens();
            }
            if (record.cachedTokens() != null) {
                cached += record.cachedTokens();
            }
            tools.addAll(record.toolNames());
        }
        double hitRate = prompt == 0 ? 0 : round1(cached * 100.0 / prompt);
        return new RequestBill(prompt, last.estimateTotal(), cached, hitRate,
                last.estimateTotal(), last.kindTokens(), List.copyOf(tools), records.size());
    }

    /**
     * 查询明细（管理端表格）。
     *
     * @param userId         过滤用户；{@code allUsers=true} 时忽略
     * @param conversationId 可选
     * @param from           起始日期（含）；null 表示最近 7 天
     * @param to             结束日期（含）；null 表示今天
     */
    public List<BillRecord> query(long userId, boolean allUsers, String conversationId,
                                  LocalDate from, LocalDate to, int limit) {
        List<BillRecord> records = readRange(from, to).stream()
                .filter(record -> allUsers || record.userId() == userId)
                .filter(record -> conversationId == null || conversationId.isBlank()
                        || conversationId.equals(record.conversationId()))
                .sorted(Comparator.comparing(BillRecord::ts).reversed())
                .limit(limit <= 0 ? 50 : limit)
                .toList();
        return records;
    }

    /** 区间汇总（管理端卡片与图表） */
    public BillSummary aggregate(long userId, boolean allUsers, LocalDate from, LocalDate to) {
        List<BillRecord> records = readRange(from, to).stream()
                .filter(record -> allUsers || record.userId() == userId)
                .toList();
        if (records.isEmpty()) {
            return new BillSummary(0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }

        Map<String, List<BillRecord>> byTrace = new LinkedHashMap<>();
        long prompt = 0;
        long cached = 0;
        for (BillRecord record : records) {
            byTrace.computeIfAbsent(record.traceId(), key -> new ArrayList<>()).add(record);
            if (record.promptTokens() != null) {
                prompt += record.promptTokens();
            }
            if (record.cachedTokens() != null) {
                cached += record.cachedTokens();
            }
        }

        long contextSum = 0;
        Set<String> fingerprints = new LinkedHashSet<>();
        Map<String, Long> kindTokens = new LinkedHashMap<>();
        Map<String, long[]> byDay = new LinkedHashMap<>();
        Map<String, Integer> toolCalls = new LinkedHashMap<>();

        for (List<BillRecord> group : byTrace.values()) {
            List<BillRecord> ordered = group.stream()
                    .sorted(Comparator.comparingInt(BillRecord::round)).toList();
            BillRecord last = ordered.get(ordered.size() - 1);
            contextSum += last.estimateTotal();
            Set<String> toolsThisRequest = new LinkedHashSet<>();
            for (BillRecord record : ordered) {
                if (record.fingerprint() != null) {
                    fingerprints.add(record.fingerprint());
                }
                record.kindTokens().forEach((kind, tokens) ->
                        kindTokens.merge(kind, (long) tokens, Long::sum));
                String day = dayOf(record.ts());
                long[] dayTotals = byDay.computeIfAbsent(day, key -> new long[2]);
                dayTotals[0] += record.promptTokens() == null ? 0 : record.promptTokens();
                dayTotals[1] += record.cachedTokens() == null ? 0 : record.cachedTokens();
                toolsThisRequest.addAll(record.toolNames());
            }
            toolsThisRequest.forEach(tool -> toolCalls.merge(tool, 1, Integer::sum));
        }

        int requests = byTrace.size();
        List<BillSummary.KindTokens> kindList = kindTokens.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> new BillSummary.KindTokens(entry.getKey(), entry.getValue()))
                .toList();
        List<BillSummary.DayTokens> dayList = byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new BillSummary.DayTokens(entry.getKey(),
                        entry.getValue()[0], entry.getValue()[1]))
                .toList();
        List<BillSummary.ToolUsage> toolList = toolCalls.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(entry -> new BillSummary.ToolUsage(entry.getKey(), entry.getValue()))
                .toList();

        return new BillSummary(requests, prompt, cached,
                prompt == 0 ? 0 : round1(cached * 100.0 / prompt),
                (int) Math.round((double) contextSum / requests),
                fingerprints.size(), kindList, dayList, toolList);
    }

    private void append(BillRecord record) {
        Path file = root.resolve(FILE_PREFIX + dayOf(record.ts()) + FILE_SUFFIX);
        try {
            Files.createDirectories(root);
            String line = objectMapper.writeValueAsString(record);
            synchronized (writeLock) {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("【账单】写入失败，不影响对话：{}，原因={}", file, e.getMessage());
        }
    }

    private List<BillRecord> readRange(LocalDate from, LocalDate to) {
        if (!enabled || root == null) {
            return List.of();
        }
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            return List.of();
        }

        List<BillRecord> records = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Path file = root.resolve(FILE_PREFIX + day + FILE_SUFFIX);
            if (!Files.exists(file)) {
                continue;
            }
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                lines.filter(line -> !line.isBlank())
                        .forEach(line -> parseLine(line, records));
            } catch (IOException e) {
                log.warn("【账单】读取失败，跳过该文件：{}，原因={}", file, e.getMessage());
            }
        }
        return records;
    }

    private void parseLine(String line, List<BillRecord> into) {
        try {
            into.add(objectMapper.readValue(line, BillRecord.class));
        } catch (Exception e) {
            // 半行/坏行只跳过：一条坏数据不该让整页账单打不开
            log.debug("【账单】忽略无法解析的行：{}", e.getMessage());
        }
    }

    private void cleanupIfDayChanged() {
        LocalDate today = LocalDate.now();
        if (today.equals(cleanedDay)) {
            return;
        }
        cleanupExpired(today);
    }

    private void cleanupExpired(LocalDate today) {
        cleanupExpired(today, today.minusDays(retentionDays));
    }

    private synchronized void cleanupExpired(LocalDate today, LocalDate cutoff) {
        cleanedDay = today;
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX))
                    .forEach(path -> deleteIfExpired(path, cutoff));
        } catch (IOException e) {
            log.warn("【账单】清理过期文件失败：{}", e.getMessage());
        }
    }

    private static void deleteIfExpired(Path path, LocalDate cutoff) {
        String name = path.getFileName().toString();
        String dayText = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        try {
            if (LocalDate.parse(dayText).isBefore(cutoff)) {
                Files.deleteIfExists(path);
                log.info("【账单】已清理过期文件：{}", name);
            }
        } catch (DateTimeParseException | IOException e) {
            log.debug("【账单】跳过无法识别的文件：{}", name);
        }
    }

    private static String dayOf(String ts) {
        try {
            return OffsetDateTime.parse(ts).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            return LocalDate.now().toString();
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
