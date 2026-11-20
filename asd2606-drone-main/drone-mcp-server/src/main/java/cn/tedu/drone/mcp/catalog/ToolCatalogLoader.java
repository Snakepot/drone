package cn.tedu.drone.mcp.catalog;

import cn.tedu.drone.mcp.config.McpServerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具目录的加载与刷新。
 *
 * <h2>启动为什么只读 classpath</h2>
 * 快照 `openapi.json` 随包发布，**不依赖 `drone-server` 是否在线**——这消除了服务启动的
 * 顺序依赖：三个服务任意顺序启动，都不会因为「文档接口还没起来」而失败。
 *
 * <h2>后台刷新做什么</h2>
 * 应用就绪后异步拉一次线上 OpenAPI，比较工具名集合：
 * 有变化就**原子替换**内存目录并打印「新增 / 删除」；没变化只记 debug；失败只记 WARN。
 * 它永远不会回滚已加载的目录，也不会让服务退出。
 */
@Slf4j
@Component
public class ToolCatalogLoader {

    private final McpServerProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<ToolCatalog> current = new AtomicReference<>();

    public ToolCatalogLoader(McpServerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.current.set(loadFromClasspath());
        ToolCatalog catalog = current.get();
        log.info("【MCP-Server】工具目录加载完成：{} 个工具，指纹={}，{}",
                catalog.size(), catalog.fingerprint(),
                catalog.tools().stream().map(ToolSpec::name).toList());
    }

    /** 当前生效的目录（刷新期间可能被替换，调用方拿到的是一个不可变快照） */
    public ToolCatalog current() {
        return current.get();
    }

    /** 应用就绪后再刷新，避免拖慢启动 */
    @EventListener(ApplicationReadyEvent.class)
    public void scheduleRefresh() {
        if (properties.refresh() == null || !properties.refresh().enabled()) {
            log.info("【MCP-Server】后台刷新已关闭，工具目录以快照为准");
            return;
        }
        Thread thread = new Thread(this::refreshQuietly, "mcp-catalog-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private ToolCatalog loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource("openapi.json");
        if (!resource.exists()) {
            // 快照缺失是打包缺陷，不是运行环境问题——直接失败，别让服务带着 0 个工具上线
            throw new IllegalStateException("classpath 下缺少 openapi.json 快照");
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode document = objectMapper.readTree(in);
            return ToolCatalog.fromOpenApi(document);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAPI 快照无法解析：" + e.getMessage(), e);
        }
    }

    private void refreshQuietly() {
        McpServerProperties.Refresh refresh = properties.refresh();
        int attempts = Math.max(1, refresh.retries() + 1);
        Duration delay = refresh.delay() == null ? Duration.ofSeconds(5) : refresh.delay();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ToolCatalog fetched = fetchCatalog(refresh.openapiUrl());
                applyIfChanged(fetched);
                return;
            } catch (Exception e) {
                log.warn("【MCP-Server】快照刷新失败（第 {}/{} 次）：{}；"
                                + "本次不阻断，继续使用已加载的目录", attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    sleep(delay);
                }
            }
        }
    }

    private ToolCatalog fetchCatalog(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OpenAPI 返回 HTTP " + response.statusCode());
        }
        return ToolCatalog.fromOpenApi(objectMapper.readTree(response.body()));
    }

    private void applyIfChanged(ToolCatalog fetched) {
        ToolCatalog existing = current.get();
        Set<String> before = names(existing);
        Set<String> after = names(fetched);
        if (before.equals(after) && existing.fingerprint().equals(fetched.fingerprint())) {
            log.debug("【MCP-Server】快照刷新：工具集合与结构均未变化（{} 个）", fetched.size());
            return;
        }
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        current.set(fetched);
        log.warn("【MCP-Server】工具目录已随线上 OpenAPI 更新：{} → {} 个，新增={}，删除={}；"
                        + "若这是预期变化，请执行 scripts/sync-openapi.sh 更新快照",
                before.size(), after.size(), added, removed);
    }

    private static Set<String> names(ToolCatalog catalog) {
        return new TreeSet<>(catalog.tools().stream().map(ToolSpec::name).toList());
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供 /health 使用 */
    public List<String> toolNames() {
        return current.get().tools().stream().map(ToolSpec::name).toList();
    }
}
