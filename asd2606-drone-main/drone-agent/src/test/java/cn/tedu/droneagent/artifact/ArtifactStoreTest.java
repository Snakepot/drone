package cn.tedu.droneagent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物存储：归属隔离、id 校验、TTL 清理。
 */
class ArtifactStoreTest {

    @TempDir
    Path root;

    private ArtifactStore store(Duration ttl) {
        return new ArtifactStore(root, ttl, new ObjectMapper());
    }

    private static ArtifactExtractor.Extracted doc() {
        return new ArtifactExtractor.Extracted("<html><title>T</title></html>", "摘要", "T.html");
    }

    @Test
    @DisplayName("本人能读回；别人拿 id 也读不到")
    void isolatesByUser() {
        ArtifactStore store = store(Duration.ofDays(7));

        ArtifactRef ref = store.save(1L, "conv", doc());

        assertTrue(store.find(1L, ref.id()).isPresent());
        assertEquals("T.html", store.find(1L, ref.id()).orElseThrow().fileName());
        assertFalse(store.find(2L, ref.id()).isPresent(), "他人目录里不存在");
    }

    @Test
    @DisplayName("id 不合法直接拒绝（防路径穿越）")
    void rejectsInvalidId() {
        ArtifactStore store = store(Duration.ofDays(7));

        assertFalse(store.find(1L, "../../etc/passwd").isPresent());
        assertFalse(store.find(1L, "short").isPresent());
        assertFalse(store.find(1L, null).isPresent());
    }

    @Test
    @DisplayName("超过 TTL 的产物会被清理")
    void purgesExpired() throws Exception {
        ArtifactStore store = store(Duration.ofMillis(1));
        ArtifactRef ref = store.save(1L, "conv", doc());

        Thread.sleep(20);
        store.purgeExpired();

        assertFalse(store.find(1L, ref.id()).isPresent());
    }

    @Test
    @DisplayName("落盘内容就是 HTML 原文")
    void storesExactContent() {
        ArtifactStore store = store(Duration.ofDays(7));
        ArtifactRef ref = store.save(1L, "conv", doc());

        byte[] bytes = store.find(1L, ref.id()).orElseThrow().content();

        assertEquals("<html><title>T</title></html>", new String(bytes, StandardCharsets.UTF_8));
    }
}
