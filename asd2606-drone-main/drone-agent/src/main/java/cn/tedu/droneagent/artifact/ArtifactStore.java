package cn.tedu.droneagent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * HTML 产物的落盘存储。
 *
 * <h2>目录即权限边界</h2>
 * {@code {root}/{userId}/{artifactId}.html}：读取时用**当前登录用户**拼目录，
 * 别人拿到 id 也读不到（与不存在一样返回 empty）。
 *
 * <h2>为什么 id 要校验</h2>
 * id 来自 URL，是外部输入；只允许 UUID 形态的字符，避免路径穿越。
 *
 * <h2>原子写</h2>
 * 先写 `.tmp` 再 `ATOMIC_MOVE`，避免读到写了一半的文件。
 */
@Slf4j
public class ArtifactStore {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final Path root;
    private final Duration ttl;
    private final ObjectMapper objectMapper;

    public ArtifactStore(Path root, Duration ttl, ObjectMapper objectMapper) {
        this.root = root;
        this.ttl = ttl == null ? Duration.ofDays(7) : ttl;
        this.objectMapper = objectMapper;
        purgeExpired();
    }

    /**
     * 保存一份产物。
     *
     * @param userId         归属用户（取自登录态）
     * @param conversationId 会话 id，仅用于元数据排查
     * @param extracted      抽取结果
     */
    public ArtifactRef save(long userId, String conversationId, ArtifactExtractor.Extracted extracted) {
        String id = UUID.randomUUID().toString();
        Path dir = root.resolve(Long.toString(userId));
        byte[] bytes = extracted.html().getBytes(StandardCharsets.UTF_8);
        try {
            Files.createDirectories(dir);
            writeAtomically(dir.resolve(id + ".html"), bytes);

            Meta meta = new Meta(extracted.fileName(), conversationId, Instant.now().toString());
            writeAtomically(dir.resolve(id + ".json"),
                    objectMapper.writeValueAsBytes(meta));

            purgeExpired();
            log.info("【产物】已保存：userId={}，conversationId={}，id={}，{} 字节，文件名={}",
                    userId, conversationId, id, bytes.length, extracted.fileName());
            return new ArtifactRef(id, extracted.fileName(), bytes.length);
        } catch (IOException e) {
            throw new IllegalStateException("产物保存失败：" + e.getMessage(), e);
        }
    }

    /** 读取本人产物；id 不合法 / 不存在 / 不属于本人，一律 empty */
    public Optional<StoredArtifact> find(long userId, String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path dir = root.resolve(Long.toString(userId));
        Path html = dir.resolve(id + ".html");
        if (!Files.isRegularFile(html)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(html);
            String fileName = readFileName(dir.resolve(id + ".json"), id);
            return Optional.of(new StoredArtifact(fileName, bytes));
        } catch (IOException e) {
            log.warn("【产物】读取失败：userId={}，id={}，原因={}", userId, id, e.getMessage());
            return Optional.empty();
        }
    }

    private String readFileName(Path meta, String id) {
        try {
            if (Files.isRegularFile(meta)) {
                Meta parsed = objectMapper.readValue(meta.toFile(), Meta.class);
                if (parsed != null && parsed.fileName() != null && !parsed.fileName().isBlank()) {
                    return parsed.fileName();
                }
            }
        } catch (IOException e) {
            log.debug("【产物】元数据损坏，使用默认文件名：id={}，原因={}", id, e.getMessage());
        }
        return "report-" + id + ".html";
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清理超过 TTL 的产物；启动与每次写入时执行（规模很小，不做定时任务） */
    void purgeExpired() {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant deadline = Instant.now().minus(ttl);
        try (Stream<Path> users = Files.list(root)) {
            users.filter(Files::isDirectory).forEach(dir -> purgeUser(dir, deadline));
        } catch (IOException e) {
            log.debug("【产物】清理失败：{}", e.getMessage());
        }
    }

    private void purgeUser(Path dir, Instant deadline) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
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
    }

    /**
     * 一条可返回给前端的产物。
     *
     * @param fileName 下载文件名
     * @param content  HTML 原文
     */
    public record StoredArtifact(String fileName, byte[] content) {
    }

    /** createdAt 用 ISO 字符串：元数据只是排障用，不依赖 ObjectMapper 的 JavaTime 配置 */
    private record Meta(String fileName, String conversationId, String createdAt) {
    }
}
