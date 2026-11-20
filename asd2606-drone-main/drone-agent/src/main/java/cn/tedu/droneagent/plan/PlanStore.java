package cn.tedu.droneagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成计划的落盘存储（`REQ-AGENT-0031`）。
 *
 * <p>布局：`<root>/<userId>/<planId>.json`。写入先写 `.tmp` 再原子改名；
 * 审批时**先改名为 `.consumed` 再执行**，保证同一计划最多执行一次。
 * 重启后加载未过期计划，用户仍可批准——与待确认票据同思路。
 */
@Slf4j
public class PlanStore {

    private final Map<String, Stored> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Path root;
    private final ObjectMapper mapper;

    public static PlanStore noop() {
        return new PlanStore(Duration.ofMinutes(30), null, null);
    }

    public PlanStore(Duration ttl, Path root, ObjectMapper mapper) {
        this.ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
        this.root = root;
        this.mapper = mapper;
        if (root != null && mapper != null) {
            loadExisting();
        }
    }

    public void put(long userId, Plan plan) {
        Stored stored = new Stored(plan, Instant.now());
        store.put(key(userId, plan.planId()), stored);
        persist(userId, stored);
    }

    /** 取出并消费；不存在 / 不属于该用户 / 已过期返回 empty。 */
    public Optional<Plan> take(long userId, String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        Stored stored = store.get(key(userId, planId));
        if (stored == null) {
            return Optional.empty();
        }
        if (isExpired(stored)) {
            store.remove(key(userId, planId));
            delete(userId, planId, ".expired");
            log.warn("【计划】已过期：planId={}", planId);
            return Optional.empty();
        }
        if (!store.remove(key(userId, planId), stored)) {
            return Optional.empty();
        }
        if (!consumeFile(userId, planId)) {
            store.put(key(userId, planId), stored);
            log.error("【计划】文件消费失败，拒绝本次审批：planId={}", planId);
            return Optional.empty();
        }
        return Optional.of(stored.plan());
    }

    public int size() {
        return store.size();
    }

    private boolean isExpired(Stored stored) {
        return stored.createdAt().plus(ttl).isBefore(Instant.now());
    }

    private void loadExisting() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var users = Files.list(root)) {
            for (Path userDir : users.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(userDir)) {
                    for (Path file : files.filter(PlanStore::isPlanFile).toList()) {
                        loadOne(userDir, file);
                    }
                } catch (IOException e) {
                    log.warn("【计划】扫描目录失败：{}，原因={}", userDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("【计划】扫描根目录失败：{}，原因={}", root, e.getMessage());
        }
    }

    private void loadOne(Path userDir, Path file) {
        try {
            Stored stored = mapper.readValue(file.toFile(), Stored.class);
            if (isExpired(stored)) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".expired"),
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            long userId;
            try {
                userId = Long.parseLong(userDir.getFileName().toString());
            } catch (NumberFormatException e) {
                return;
            }
            store.put(key(userId, stored.plan().planId()), stored);
        } catch (IOException | RuntimeException e) {
            log.warn("【计划】跳过无法解析的文件：{}，原因={}", file, e.getMessage());
        }
    }

    private void persist(long userId, Stored stored) {
        if (root == null || mapper == null) {
            return;
        }
        Path dir = root.resolve(String.valueOf(userId));
        Path file = dir.resolve(stored.plan().planId() + ".json");
        Path tmp = dir.resolve(stored.plan().planId() + ".json.tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, mapper.writeValueAsString(stored), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            setOwnerOnly(tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("【计划】落盘失败（仍保留内存，重启会丢）：planId={}，原因={}",
                    stored.plan().planId(), e.getMessage());
        }
    }

    private boolean consumeFile(long userId, String planId) {
        return move(userId, planId, ".consumed");
    }

    private void delete(long userId, String planId, String suffix) {
        move(userId, planId, suffix);
    }

    private boolean move(long userId, String planId, String suffix) {
        if (root == null) {
            return true;
        }
        Path file = root.resolve(String.valueOf(userId)).resolve(planId + ".json");
        if (!Files.exists(file)) {
            return true;
        }
        try {
            Files.move(file, file.resolveSibling(planId + ".json" + suffix),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.warn("【计划】文件处理失败：{}，原因={}", file, e.getMessage());
            return false;
        }
    }

    private static boolean isPlanFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && !name.endsWith(".tmp");
    }

    private static void setOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统：不是致命问题
        }
    }

    private static String key(long userId, String planId) {
        return userId + ":" + planId;
    }

    /** 落盘信封：计划本身 + 生成时间（TTL 用） */
    public record Stored(Plan plan, Instant createdAt) {
    }
}
