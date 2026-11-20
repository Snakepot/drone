package cn.tedu.drone.mcp.invoke;

import cn.tedu.drone.mcp.config.McpServerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 访问 `drone-server` 的 HTTP 客户端。
 *
 * <p>只做两件容易出错的事：**超时**与**响应体上限**。
 * 前者防止一次慢请求把工具线程占满；后者防止某个列表接口返回几十 MB 把模型上下文打爆。
 */
@Component
public class UpstreamClient {

    private final HttpClient client;
    private final int maxResponseBytes;

    public UpstreamClient(McpServerProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.maxResponseBytes = properties.maxResponseBytes() <= 0 ? 204800 : properties.maxResponseBytes();
    }

    public HttpResponse<InputStream> execute(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 读取响应体，超过上限就截断。
     *
     * <p>截断而不是报错：模型至少还能看到前半段并据此回答，比整次调用失败有用。
     */
    public String readCapped(InputStream body) throws IOException {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(maxResponseBytes + 1);
            boolean truncated = bytes.length > maxResponseBytes;
            String text = new String(bytes, 0, Math.min(bytes.length, maxResponseBytes), StandardCharsets.UTF_8);
            return truncated ? text + "\n…（结果超过 " + maxResponseBytes + " 字节，已截断）" : text;
        }
    }
}
