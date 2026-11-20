package cn.tedu.droneagent.kb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库**台账**：记住「每份资料上次是什么内容」，用来做**增量**。
 *
 * <h2>为什么不用数据库</h2>
 * 宪法 §2 规定 `drone-agent` **不得访问 `drone-server` 的数据库**，另起一个库又要多一套中间件。
 * 台账很小（一份资料一行），**落一个 JSON 文件**就够——与记忆子系统的「索引文件」同一思路。
 *
 * <h2>增量怎么算</h2>
 * 台账里存每份资料的 {@code contentHash}。入库前比一下：
 * **一样就跳过，不一样才重切重嵌**。所以判断依据是**内容**，不是文件的修改时间——
 * 时间戳会被「touch 一下」这类操作骗到，内容哈希不会。
 *
 * <h2>为什么写文件要「先写临时文件再原子改名」</h2>
 * 台账被写坏的后果是**整库重嵌**（贵）。原子改名保证读的人要么读到完整旧版、要么读到完整新版，
 * 不会读到写了一半的 JSON。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不做并发控制</b>：入库是单线程的（`KnowledgeIngestor` 串行处理）。</li>
 *   <li><b>不存正文</b>：只存「哪份资料、什么哈希、几块」。</li>
 * </ul>
 */
@Slf4j
public class KnowledgeManifest {

    private static final String FILE_NAME = "manifest.json";

    /**
     * 台账里的一行。
     *
     * @param docId       文档标识（由**文件路径**推出，内容改了也不变——这样才删得掉旧块）
     * @param fileName    文件名（引用出处用）
     * @param contentHash 内容哈希，判断是否要重嵌
     * @param chunkCount  切出多少块（观测用）
     * @param updatedAt   最后入库时间（ISO 字符串）
     */
    public record Entry(String docId, String fileName, String contentHash, int chunkCount, String updatedAt) {
    }

    private final Path file;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public KnowledgeManifest(Path directory) {
        this.file = directory.resolve(FILE_NAME);
    }

    /** 从磁盘读台账；文件不存在或读坏都当作**空台账**（读坏不该让服务起不来） */
    public static KnowledgeManifest load(Path directory) {
        KnowledgeManifest manifest = new KnowledgeManifest(directory);
        manifest.readFromDisk();
        return manifest;
    }

    /** 取某份资料的台账行；没有表示「没入过库」或「上次入库失败」 */
    public Entry get(String docId) {
        return entries.get(docId);
    }

    /** 写入/覆盖一行（**只在入库成功后才调**——失败不记账，下次扫描自然会重试） */
    public void put(Entry entry) {
        entries.put(entry.docId(), entry);
        save();
    }

    /** 移除一行（资料被删时用） */
    public void remove(String docId) {
        if (entries.remove(docId) != null) {
            save();
        }
    }

    /** 当前台账（只读视图） */
    public Map<String, Entry> all() {
        return Map.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    private void readFromDisk() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, Entry> loaded = objectMapper.readValue(json, new TypeReference<>() {
            });
            entries.putAll(loaded);
            log.info("【知识库】台账已载入：{} 条（{}）", entries.size(), file);
        } catch (IOException e) {
            // 读坏不该让服务起不来；最坏的结果是「全部重嵌一次」
            log.warn("【知识库】台账读取失败，按空台账处理（下次会全量重嵌）：{}，原因={}", file, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "manifest", ".tmp");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entries), StandardCharsets.UTF_8);
            // 原子改名：读的人要么拿到完整旧版、要么完整新版，不会拿到写了一半的
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写知识库台账失败：" + file, e);
        }
    }
}
