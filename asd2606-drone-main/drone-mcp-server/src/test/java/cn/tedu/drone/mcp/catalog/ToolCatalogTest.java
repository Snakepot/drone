package cn.tedu.drone.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具目录的生成规则测试。
 *
 * <p>用**仓库里真实提交的 OpenAPI 快照**做断言——这同时是一道防漂移闸门：
 * 如果 `drone-server` 的接口变了、快照没更新，这里会立刻失败。
 */
class ToolCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolCatalog catalog;

    @BeforeAll
    static void loadSnapshot() throws Exception {
        try (InputStream in = new ClassPathResource("openapi.json").getInputStream()) {
            catalog = ToolCatalog.fromOpenApi(MAPPER.readTree(in));
        }
    }

    @Test
    @DisplayName("快照生成 70 个工具，名字唯一")
    void derivesSeventyUniqueTools() {
        assertEquals(70, catalog.size());
        Set<String> names = new HashSet<>();
        for (ToolSpec spec : catalog.tools()) {
            assertTrue(names.add(spec.name()), "工具名重复：" + spec.name());
        }
        assertFalse(catalog.fingerprint().isBlank());
    }

    @Test
    @DisplayName("基础设施接口一个都不暴露")
    void excludesInfrastructureEndpoints() {
        List<String> names = catalog.tools().stream().map(ToolSpec::name).toList();
        for (String forbidden : List.of("user_login", "user_logout", "user_export", "device_import",
                "task_attachment_upload", "internal_user_authorities", "user_health")) {
            assertFalse(names.contains(forbidden), "不应暴露：" + forbidden);
        }
    }

    @Test
    @DisplayName("$ref 已递归内联，模型能看到真实字段")
    void inlinesRefs() {
        for (ToolSpec spec : catalog.tools()) {
            assertFalse(spec.inputSchema().toString().contains("$ref"),
                    "Schema 里仍有 $ref：" + spec.name());
        }
        ToolSpec zoneAdd = catalog.find("zone_add");
        assertNotNull(zoneAdd);
        assertTrue(zoneAdd.inputSchema().toString().contains("points"),
                "禁飞区新增应能展开航点数组：" + zoneAdd.inputSchema());
    }

    @Test
    @DisplayName("查询 Bean 被摊平：列表字段非必填，offset/limit 被剔除")
    void flattensQueryBean() {
        ToolSpec taskList = catalog.find("task_list");
        assertNotNull(taskList);
        assertTrue(taskList.queryParams().contains("keyword"));
        assertTrue(taskList.queryParams().contains("page"));
        assertFalse(taskList.queryParams().contains("offset"), "计算属性 offset 应被剔除");
        assertFalse(taskList.queryParams().contains("limit"), "计算属性 limit 应被剔除");

        JsonNode required = taskList.inputSchema().path("required");
        assertFalse(required.toString().contains("keyword"), "查询条件不应被标成必填");
    }

    @Test
    @DisplayName("路径参数必填；POST 带请求体；GET 只读")
    void marksParamsAndModes() {
        ToolSpec toggle = catalog.find("zone_toggle");
        assertNotNull(toggle);
        assertEquals(List.of("id"), toggle.pathParams());
        assertTrue(toggle.queryParams().contains("enabled"));
        assertTrue(toggle.inputSchema().path("required").toString().contains("id"));
        assertTrue(toggle.inputSchema().path("required").toString().contains("enabled"));
        assertFalse(toggle.readOnly(), "POST 不是只读");

        ToolSpec dispatch = catalog.find("task_dispatch");
        assertNotNull(dispatch);
        assertTrue(dispatch.hasRequestBody());
        assertTrue(dispatch.bodyParams().contains("taskId"));
        assertTrue(dispatch.queryParams().isEmpty());

        ToolSpec detail = catalog.find("task_detail");
        assertNotNull(detail);
        assertTrue(detail.readOnly(), "GET 应标为只读");
    }

    @Test
    @DisplayName("重名消歧：/task/zone-check 与 /task/{taskId}/zone-check 都能用")
    void disambiguatesDuplicateNames() {
        assertNotNull(catalog.find("task_zone_check"), "POST 版本保留原名");
        assertNotNull(catalog.find("task_zone_check_by_task"), "GET 版本用路径参数消歧");
        assertNull(catalog.find("internal_user_authorities"));
    }

    @Test
    @DisplayName("删除类接口的 data 为空时能回退到 message（由 ToolInvoker 保证，这里只钉住工具存在）")
    void mutationToolsExist() {
        for (String name : List.of("zone_delete", "route_delete", "point_delete", "user_toggle",
                "system_role_grant", "system_user_grant", "task_withdraw", "task_refuse")) {
            assertNotNull(catalog.find(name), "缺少工具：" + name);
        }
    }
}
