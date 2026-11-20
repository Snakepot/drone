package cn.tedu.droneagent.trace;

import cn.tedu.droneagent.common.TraceIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把一次对话的 span 追加写入 {@code {root}/{userId}/{traceId}.jsonl}。
 *
 * <h2>为什么按 userId 分目录</h2>
 * 追踪文件里有业务信息（工具名、耗时、状态）。按用户分目录后，
 * 查询接口用当前登录人拼路径，别人拿到 traceId 也读不到。
 *
 * <h2>为什么写失败不影响对话</h2>
 * 追踪是旁路能力：磁盘满、权限错都不该让用户的问题失败。
 * 所有 IO 异常只记 WARN。
 *
 * <h2>它不负责什么</h2>
 * 不采集 MCP/server 的 span（P2 做），不建复杂 span 树；只记录 agent 侧的
 * root / model / tool / artifact，足够先跑通回放。
 */
@Slf4j
public class TraceRecorder {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private static final String FILE_SUFFIX = ".jsonl";

    private static final long PURGE_INTERVAL_MS = 60 * 60 * 1000L;

    private final Path root;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /** traceId → 根 spanId；tool/model 子 span 用它做 parent */
    private final Map<String, String> roots = new ConcurrentHashMap<>();

    private final Object writeLock = new Object();

    private volatile long lastPurge = 0L;

    public TraceRecorder(Path root, Duration ttl, ObjectMapper objectMapper, boolean enabled) {
        this.root = root;
        this.ttl = ttl == null ? Duration.ofDays(7) : ttl;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        if (enabled) {
            purgeExpired();
        }
    }

    /** 关闭追踪时使用：所有方法都安全地什么都不做 */
    public static TraceRecorder noop() {
        return new TraceRecorder(null, Duration.ofDays(7), new ObjectMapper(), false);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 开始一次对话的根 span，返回 spanId；关闭时返回 null */
    public String beginRoot(long userId, String traceId) {
        if (!enabled || !valid(traceId)) {
            return null;
        }
        String spanId = TraceIds.newId();
        roots.put(traceId, spanId);
        return spanId;
    }

    /** 取根 spanId；没有（如确认续跑）时返回 null，子 span 仍会以 parent=null 落盘 */
    public String rootSpanId(String traceId) {
        return traceId == null ? null : roots.get(traceId);
    }

    /** 结束根 span 并落盘 */
    public void finishRoot(long userId, String traceId, String spanId, String start,
                           long durationMs, String status, Map<String, Object> attrs) {
        record(userId, traceId, null, "chat.request", start, durationMs, status, attrs);
        if (traceId != null) {
            roots.remove(traceId);
        }
    }

    /** 记录一条子 span；parentId 为空时按扁平记录 */
    public void record(Long userId, String traceId, String parentId, String name,
                       String start, long durationMs, String status, Map<String, Object> attrs) {
        if (!enabled || userId == null || userId <= 0 || !valid(traceId)) {
            return;
        }
        maybePurge();
        TraceSpan span = new TraceSpan(traceId, TraceIds.newId(), parentId,
                "drone-agent", name, start, durationMs, status, attrs);
        Path dir = root.resolve(String.valueOf(userId));
        Path file = dir.resolve(traceId + FILE_SUFFIX);
        try {
            synchronized (writeLock) {
                Files.createDirectories(dir);
                String line = objectMapper.writeValueAsString(span);
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("【Trace】写入失败（不影响对话）：traceId={}，userId={}，name={}，原因={}",
                    traceId, userId, name, e.getMessage());
        }
    }

    /** 读取本人某条 trace；不存在、非法 id、别人的 trace 都返回空列表 */
    public List<TraceSpan> find(long userId, String traceId) {
        if (!enabled || userId <= 0 || !valid(traceId)) {
            return List.of();
        }
        Path file = root.resolve(String.valueOf(userId)).resolve(traceId + FILE_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<TraceSpan> spans = new ArrayList<>();
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    spans.add(objectMapper.readValue(line, TraceSpan.class));
                } catch (IOException e) {
                    // 单行坏了不影响其他 span
                    log.debug("【Trace】跳过坏行：traceId={}，原因={}", traceId, e.getMessage());
                }
            });
            spans.sort(Comparator.comparing(TraceSpan::start));
            return spans;
        } catch (IOException e) {
            log.warn("【Trace】读取失败：traceId={}，userId={}，原因={}",
                    traceId, userId, e.getMessage());
            return List.of();
        }
    }

    /** 清理超过 TTL 的 trace 文件；启动与每小时最多一次 */
    void purgeExpired() {
        if (!enabled || root == null || !Files.isDirectory(root)) {
            return;
        }
        Instant deadline = Instant.now().minus(ttl);
        try (Stream<Path> users = Files.list(root)) {
            users.filter(Files::isDirectory).forEach(dir -> {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile)
                            .filter(file -> file.toString().endsWith(FILE_SUFFIX))
                            .forEach(file -> {
                                try {
                                    if (Files.getLastModifiedTime(file).toInstant().isBefore(deadline)) {
                                        Files.deleteIfExists(file);
                                    }
                                } catch (IOException ignored) {
                                    // 单个文件删不掉不影响整体
                                }
                            });
                } catch (IOException ignored) {
                    // 目录读不了就跳过
                }
            });
        } catch (IOException e) {
            log.debug("【Trace】清理失败：{}", e.getMessage());
        }
        lastPurge = System.currentTimeMillis();
    }

    private void maybePurge() {
        long now = System.currentTimeMillis();
        if (now - lastPurge > PURGE_INTERVAL_MS) {
            purgeExpired();
        }
    }

    private static boolean valid(String traceId) {
        return traceId != null && SAFE_TRACE_ID.matcher(traceId).matches();
    }
}
