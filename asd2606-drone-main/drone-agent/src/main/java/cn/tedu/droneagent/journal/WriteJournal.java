package cn.tedu.droneagent.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * 写操作台账（`REQ-AGENT-0029`）：每次写工具执行前后各记一条，供取消报告与补偿追溯。
 *
 * <p>只记元数据：operationId、工具、参数哈希、状态、对象 ID。**不记参数原文与用户内容**。
 * 写失败只 WARN，不影响工具执行。
 */
@Slf4j
public class WriteJournal {

    private static final String PREFIX = "operations-";
    private static final String SUFFIX = ".jsonl";

    private final Path root;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public static WriteJournal noop() {
        return new WriteJournal(null, null);
    }

    public WriteJournal(Path root, ObjectMapper objectMapper) {
        this.root = root;
        this.objectMapper = objectMapper;
        this.enabled = root != null && objectMapper != null;
    }

    /** 记一条写操作；status: started / succeeded / failed / unknown / cancelled */
    public void record(String operationId, String traceId, long userId, String conversationId,
                       String tool, String argumentsJson, String status, String objectId) {
        if (!enabled) {
            return;
        }
        Entry entry = new Entry(OffsetDateTime.now().toString(), operationId, traceId, userId,
                conversationId, tool, digest(argumentsJson), status, objectId);
        Path dir = root;
        Path file = dir.resolve(PREFIX + LocalDate.now() + SUFFIX);
        try {
            Files.createDirectories(dir);
            String line = objectMapper.writeValueAsString(entry);
            synchronized (this) {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("【写台账】落盘失败（不影响工具执行）：operationId={}，tool={}，原因={}",
                    operationId, tool, e.getMessage());
        }
    }

    /**
     * 参数摘要（前 8 字节的 hex）。
     *
     * <p>台账与续跑令牌共用同一份摘要算法：续跑时模型重发「同一工具 + 同一参数」，
     * 才能对回上一次的 operationId，命中 MCP 幂等缓存。
     */
    public static String digest(String text) {
        if (text == null || text.isEmpty()) {
            return "empty";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /** 一行台账 */
    public record Entry(String ts, String operationId, String traceId, long userId,
                        String conversationId, String tool, String argsDigest,
                        String status, String objectId) {
    }
}
