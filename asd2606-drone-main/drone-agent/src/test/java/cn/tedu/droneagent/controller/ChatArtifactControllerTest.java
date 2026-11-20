package cn.tedu.droneagent.controller;

import cn.tedu.droneagent.artifact.ArtifactExtractor;
import cn.tedu.droneagent.artifact.ArtifactRef;
import cn.tedu.droneagent.artifact.ArtifactStore;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物接口：预览/下载响应头、归属隔离、404 语义。
 *
 * <p>直接调用方法（不启容器），鉴权拦截器由其他用例覆盖；这里把登录人放进
 * {@code UserContext} 模拟已登录。
 */
class ChatArtifactControllerTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private ChatArtifactController controller;
    private ArtifactRef ref;

    @BeforeEach
    void setUp() {
        store = new ArtifactStore(root, Duration.ofDays(7), new ObjectMapper());
        controller = new ChatArtifactController(store);
        ref = store.save(1L, "conv",
                new ArtifactExtractor.Extracted("<html><title>运营日报</title></html>", "摘要",
                        "运营日报 · 2026-10-22.html"));
        UserContext.set(new LoginUser().setId(1L).setUsername("tester"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("预览：text/html + CSP sandbox + inline")
    void servesInline() {
        ResponseEntity<byte[]> response = controller.get(ref.id(), false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("text/html;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("inline;"), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), disposition);
    }

    @Test
    @DisplayName("下载：attachment + 文件名")
    void servesAttachment() {
        ResponseEntity<byte[]> response = controller.get(ref.id(), true);

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.startsWith("attachment;"), disposition);
        assertTrue(disposition.contains("%E8%BF%90%E8%90%A5%E6%97%A5%E6%8A%A5"), disposition);
        assertTrue(disposition.contains("%20"), "空格必须编码为 %20，不能是 +");
        assertFalse(disposition.contains("+"), "filename* 里的 + 是字面量，会出现在文件名里");
    }

    @Test
    @DisplayName("别人的产物与不存在都返回 404")
    void hidesExistence() {
        UserContext.set(new LoginUser().setId(2L).setUsername("other"));

        assertEquals(HttpStatus.NOT_FOUND, controller.get(ref.id(), false).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.get("does-not-exist", false).getStatusCode());
    }
}
