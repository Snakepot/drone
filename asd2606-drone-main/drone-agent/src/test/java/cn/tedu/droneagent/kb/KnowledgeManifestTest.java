package cn.tedu.droneagent.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeManifest} 的单元测试：台账要**能落盘、能读回、读坏不炸**。
 */
class KnowledgeManifestTest {

    @TempDir
    Path dir;

    private static KnowledgeManifest.Entry entry(String docId, String hash) {
        return new KnowledgeManifest.Entry(docId, "手册.md", hash, 3, "2026-10-16T00:00:00Z");
    }

    @Test
    @DisplayName("写入后能从磁盘读回（跨进程也要认得）")
    void persistsAcrossReload() {
        KnowledgeManifest manifest = KnowledgeManifest.load(dir);
        manifest.put(entry("d1", "hash-1"));

        KnowledgeManifest reloaded = KnowledgeManifest.load(dir);

        assertEquals(1, reloaded.size());
        assertEquals("hash-1", reloaded.get("d1").contentHash());
        assertEquals(3, reloaded.get("d1").chunkCount());
    }

    @Test
    @DisplayName("同 docId 覆盖（内容变了就更新哈希，不新增行）")
    void putOverwritesSameDoc() {
        KnowledgeManifest manifest = KnowledgeManifest.load(dir);
        manifest.put(entry("d1", "hash-1"));
        manifest.put(entry("d1", "hash-2"));

        assertEquals(1, manifest.size());
        assertEquals("hash-2", KnowledgeManifest.load(dir).get("d1").contentHash());
    }

    @Test
    @DisplayName("remove 后不再存在，且落盘生效")
    void removePersists() {
        KnowledgeManifest manifest = KnowledgeManifest.load(dir);
        manifest.put(entry("d1", "hash-1"));
        manifest.remove("d1");

        assertNull(KnowledgeManifest.load(dir).get("d1"));
        assertEquals(0, KnowledgeManifest.load(dir).size());
    }

    @Test
    @DisplayName("台账读坏按空处理（最坏是全量重嵌，不该让服务起不来）")
    void brokenFileTreatedAsEmpty() throws IOException {
        Files.writeString(dir.resolve("manifest.json"), "{ 这不是合法 JSON", StandardCharsets.UTF_8);

        KnowledgeManifest manifest = KnowledgeManifest.load(dir);

        assertEquals(0, manifest.size());
        assertTrue(manifest.all().isEmpty());
    }

    @Test
    @DisplayName("空文件也不炸")
    void emptyFileTreatedAsEmpty() throws IOException {
        Files.writeString(dir.resolve("manifest.json"), "", StandardCharsets.UTF_8);

        assertEquals(0, KnowledgeManifest.load(dir).size());
    }
}
