package cn.tedu.droneagent.controller;

import cn.tedu.droneagent.artifact.ArtifactStore;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.RequirePermission;
import cn.tedu.droneagent.common.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTML 产物的读取接口。
 *
 * <h2>为什么不让前端直接用静态 URL</h2>
 * 报告里有任务、人员、设备等业务数据，必须鉴权；产物按 {@code userId} 分目录，
 * 读的时候用**当前登录人**拼路径，别人拿到 id 也读不到。
 *
 * <h2>为什么同时给预览与下载</h2>
 * 前端用 axios 取回 Blob 后，预览走 iframe（sandbox），下载走 `saveBlob`；
 * `download=1` 主要给「直接打开链接」的场景用。
 *
 * <h2>安全响应头</h2>
 * <ul>
 *   <li>{@code Content-Security-Policy: sandbox}：即使有人直接打开链接，也禁止脚本执行；</li>
 *   <li>{@code X-Content-Type-Options: nosniff}：不让浏览器猜类型；</li>
 *   <li>归属不符与不存在都返回 404，不暴露「这个 id 是否存在」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/chat/artifacts")
public class ChatArtifactController {

    private final ArtifactStore store;

    public ChatArtifactController(ArtifactStore store) {
        this.store = store;
    }

    @RequirePermission("agent:chat")
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable String id,
                                      @RequestParam(name = "download", defaultValue = "false") boolean download) {
        LoginUser user = UserContext.get();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return store.find(user.getId(), id)
                .map(artifact -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
                    headers.set("X-Content-Type-Options", "nosniff");
                    headers.set("Content-Security-Policy", "sandbox");
                    headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
                    // URLEncoder 会把空格编成 +，而 filename*=UTF-8'' 里 + 是字面量，要换成 %20
                    String encodedName = URLEncoder.encode(artifact.fileName(), StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    headers.set(HttpHeaders.CONTENT_DISPOSITION,
                            (download ? "attachment" : "inline") + "; filename*=UTF-8''" + encodedName);
                    return new ResponseEntity<>(artifact.content(), headers, HttpStatus.OK);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
