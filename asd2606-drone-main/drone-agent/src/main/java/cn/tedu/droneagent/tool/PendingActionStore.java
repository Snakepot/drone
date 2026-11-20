package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.tool.PendingActionStore.PendingAction;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认动作的存储：内存 + **可选文件持久化**（`REQ-AGENT-0029`）。
 *
 * <h2>为什么需要落盘</h2>
 * 危险操作暂停后生成的票据原来只在内存，服务重启/升级即丢；用户过一会儿点「确认」
 * 只会得到「票据不存在」。长流程的人工审批（等主管、等现场）根本撑不过一次重启。
 *
 * <h2>文件布局</h2>
 * <pre>
 * &lt;root&gt;/&lt;userId&gt;/&lt;pendingId&gt;.json     有效票据
 * &lt;root&gt;/&lt;userId&gt;/&lt;pendingId&gt;.consumed  已消费/已取消（启动时忽略并清理）
 * </pre>
 *
 * <h2>为什么消费先改名再执行</h2>
 * 改名是原子的：成功后票据立即失效，重启也不会再被加载。
 * 若进程在改名后、执行前崩溃，操作不会执行——这是**最多一次**语义，
 * 对危险操作来说，漏执行比重复执行安全。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li>不校验工具权限：那是 {@code ToolGovernance} 与执行层的事；</li>
 *   <li>不跨实例共享：本期单实例；多实例需 Redis/DB；</li>
 *   <li>不加密：文件在 {@code data/} 下（已忽略提交），尽量设 600 权限，TTL 后删除。</li>
 * </ul>
 */
@Slf4j
public class PendingActionStore {

    /**
     * 一枚待确认动作。
     *
     * @param id            票据 id，回给前端、确认时带回来
     * @param userId        发起人；确认时必须还是他
     * @param conversationId 会话，便于定位
     * @param toolName      要执行的工具
     * @param argumentsJson 模型给的参数
     * @param messages      确认后续跑 ReAct 所需的**对话快照**（到这里为止的全部消息）
     * @param assistantFrame 模型当初那条「请求调用工具」的 assistant 消息：含**原始 tool_call id**
     *                       与 `reasoning_content`。续跑必须原样回放它，否则模型服务会拒绝
     *                       （REQ-AGENT-0006 修复：不能现造 confirm_ 帧）
     * @param dangerousCallId 需要确认的那次调用的**原始 id**，用于在同一帧里定位它
     * @param createdAt     生成时间，用于算有效期
     * @param traceId       生成票据时的链路 id（REQ-AGENT-0026）；旧调用为 null
     */
    public record PendingAction(String id, long userId, String conversationId, String toolName,
                                String argumentsJson, List<ModelMessage> messages,
                                ModelMessage assistantFrame, String dangerousCallId, Instant createdAt,
                                String traceId) {

        public PendingAction {
            messages = List.copyOf(messages);
        }

        /** 兼容旧调用：不带 traceId */
        public PendingAction(String id, long userId, String conversationId, String toolName,
                             String argumentsJson, List<ModelMessage> messages,
                             ModelMessage assistantFrame, String dangerousCallId, Instant createdAt) {
            this(id, userId, conversationId, toolName, argumentsJson, messages, assistantFrame,
                    dangerousCallId, createdAt, null);
        }
    }

    private final Map<String, PendingAction> store = new ConcurrentHashMap<>();

    private final Duration ttl;

    /** 落盘根目录；null 表示不持久化（既有测试与不需要重启恢复的场景） */
    private final Path root;

    private final ObjectMapper objectMapper;

    /** 兼容旧调用：只存内存 */
    public PendingActionStore(Duration ttl) {
        this(ttl, null, null);
    }

    /**
     * @param root         落盘根目录；null = 不持久化
     * @param objectMapper 序列化器（Spring 的 mapper 已注册 JavaTime）
     */
    public PendingActionStore(Duration ttl, Path root, ObjectMapper objectMapper) {
        this.ttl = ttl;
        this.root = root;
        this.objectMapper = objectMapper;
        if (root != null && objectMapper != null) {
            loadExisting();
        }
    }

    public PendingAction put(long userId, String conversationId, String toolName,
                             String argumentsJson, List<ModelMessage> messages,
                             ModelMessage assistantFrame, String dangerousCallId) {
        return put(userId, conversationId, toolName, argumentsJson, messages, assistantFrame,
                dangerousCallId, null);
    }

    /** 存入一枚待确认动作并尽力落盘；落盘失败只 WARN，仍保留在内存 */
    public PendingAction put(long userId, String conversationId, String toolName,
                             String argumentsJson, List<ModelMessage> messages,
                             ModelMessage assistantFrame, String dangerousCallId, String traceId) {
        purgeExpired();
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), userId,
                conversationId, toolName, argumentsJson, messages, assistantFrame,
                dangerousCallId, Instant.now(), traceId);
        store.put(action.id(), action);
        persist(action);
        return action;
    }

    /**
     * 取出并**消费**一枚待确认动作。
     *
     * <p>校验不通过（不存在 / 不是本人 / 已过期）返回 empty；只有通过才移除 —— 保证
     * 「重复确认只执行一次」，且**别人拿 id 也消费不掉**。
     */
    public Optional<PendingAction> take(String id, long userId) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        PendingAction action = store.get(id);
        if (action == null) {
            return Optional.empty();
        }
        if (action.userId() != userId) {
            log.warn("【待确认】归属不符：票据 id={} 属于 userId={}，但被 userId={} 请求", id, action.userId(), userId);
            return Optional.empty();
        }
        if (isExpired(action)) {
            store.remove(id);
            deleteFile(action, ".expired");
            log.warn("【待确认】票据已过期：id={}", id);
            return Optional.empty();
        }
        if (!store.remove(id, action)) {
            return Optional.empty();
        }
        if (!consumeFile(action)) {
            // 文件改名失败：宁可确认失败，也不承担重启后重复执行的风险
            store.put(action.id(), action);
            log.error("【待确认】票据文件消费失败，拒绝本次确认：id={}", id);
            return Optional.empty();
        }
        return Optional.of(action);
    }

    /** 当前存了几枚（供测试与观测） */
    public int size() {
        return store.size();
    }

    /**
     * 取消时作废某会话的全部票据（`REQ-AGENT-0029`）。
     *
     * @return 作废数量
     */
    public int removeByConversation(long userId, String conversationId) {
        List<PendingAction> matched = store.values().stream()
                .filter(action -> action.userId() == userId
                        && action.conversationId().equals(conversationId))
                .toList();
        for (PendingAction action : matched) {
            store.remove(action.id(), action);
            deleteFile(action, ".cancelled");
            log.info("【待确认】已作废：id={}，userId={}，conversationId={}",
                    action.id(), userId, conversationId);
        }
        return matched.size();
    }

    /** 查询某会话当前有效票据（不消费），供重启后面板恢复确认框 */
    public List<PendingAction> listByConversation(long userId, String conversationId) {
        purgeExpired();
        return store.values().stream()
                .filter(action -> action.userId() == userId
                        && action.conversationId().equals(conversationId))
                .filter(action -> !isExpired(action))
                .toList();
    }

    private boolean isExpired(PendingAction action) {
        return action.createdAt().plus(ttl).isBefore(Instant.now());
    }

    private void purgeExpired() {
        List<PendingAction> expired = store.values().stream().filter(this::isExpired).toList();
        for (PendingAction action : expired) {
            store.remove(action.id(), action);
            deleteFile(action, ".expired");
        }
    }

    // ---------------- 持久化 ----------------

    private void loadExisting() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var users = Files.list(root)) {
            for (Path userDir : users.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(userDir)) {
                    for (Path file : files.filter(PendingActionStore::isTicketFile).toList()) {
                        loadOne(file);
                    }
                } catch (IOException e) {
                    log.warn("【待确认】扫描票据目录失败：{}，原因={}", userDir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("【待确认】扫描票据根目录失败：{}，原因={}", root, e.getMessage());
        }
    }

    private void loadOne(Path file) {
        try {
            PendingAction action = objectMapper.readValue(file.toFile(), PendingAction.class);
            if (isExpired(action)) {
                deleteFile(action, ".expired");
                return;
            }
            store.put(action.id(), action);
        } catch (IOException | RuntimeException e) {
            log.warn("【待确认】跳过无法解析的票据：{}，原因={}", file, e.getMessage());
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 坏文件清不掉也不影响运行
            }
        }
    }

    private void persist(PendingAction action) {
        if (root == null || objectMapper == null) {
            return;
        }
        Path dir = root.resolve(String.valueOf(action.userId()));
        Path file = dir.resolve(action.id() + ".json");
        Path tmp = dir.resolve(action.id() + ".json.tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, objectMapper.writeValueAsString(action), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            setOwnerOnly(tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("【待确认】票据落盘失败（仍保留内存，重启会丢）：id={}，原因={}",
                    action.id(), e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理临时文件失败不影响主流程
            }
        }
    }

    /** 消费：原子改名；失败返回 false */
    private boolean consumeFile(PendingAction action) {
        if (root == null) {
            return true;
        }
        return moveToSuffix(action, ".consumed");
    }

    private void deleteFile(PendingAction action, String suffix) {
        if (root == null) {
            return;
        }
        moveToSuffix(action, suffix);
    }

    private boolean moveToSuffix(PendingAction action, String suffix) {
        Path file = root.resolve(String.valueOf(action.userId())).resolve(action.id() + ".json");
        if (!Files.exists(file)) {
            return true;
        }
        try {
            Files.move(file, file.resolveSibling(action.id() + ".json" + suffix),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.warn("【待确认】票据文件处理失败：{}，原因={}", file, e.getMessage());
            return false;
        }
    }

    private static boolean isTicketFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && !name.endsWith(".tmp");
    }

    private static void setOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统或权限不足：不是致命问题
        }
    }
}
