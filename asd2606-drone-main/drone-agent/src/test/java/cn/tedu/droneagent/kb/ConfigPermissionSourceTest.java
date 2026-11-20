package cn.tedu.droneagent.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigPermissionSource} 的单元测试。
 *
 * <p>对应验收条件 `REQ-AGENT-0010` 的 <b>AC-3</b>：可见范围**在配置里按路径声明**，
 * **不改原始资料**；没命中的资料**无人可见**（fail-closed）。
 */
class ConfigPermissionSourceTest {

    @TempDir
    Path dir;

    private Path file(String relative) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "内容", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("按路径规则授予：命中哪个目录就归哪些权限码（资料本身一个字不改）")
    void grantsByPathRule() throws IOException {
        ConfigPermissionSource source = new ConfigPermissionSource(dir, List.of(
                new PermissionRule("财务/**", List.of("agent:finance")),
                new PermissionRule("手册/**", List.of("agent:chat"))), List.of());

        assertEquals(List.of("agent:finance"), source.permissionCodesOf(file("财务/保险方案.md")));
        assertEquals(List.of("agent:chat"), source.permissionCodesOf(file("手册/操作手册.md")));
    }

    @Test
    @DisplayName("未命中任何规则 → 用 default-perms")
    void fallsBackToDefaults() throws IOException {
        ConfigPermissionSource source = new ConfigPermissionSource(dir,
                List.of(new PermissionRule("财务/**", List.of("agent:finance"))),
                List.of("agent:chat"));

        assertEquals(List.of("agent:chat"), source.permissionCodesOf(file("其它/随便.md")));
    }

    @Test
    @DisplayName("规则与默认都没有 → 空（fail-closed：谁都看不到）")
    void noRuleNoDefaultMeansInvisible() throws IOException {
        ConfigPermissionSource source = new ConfigPermissionSource(dir, List.of(), List.of());

        assertTrue(source.permissionCodesOf(file("随便.md")).isEmpty());
    }

    @Test
    @DisplayName("多条规则按顺序，先命中先用（更具体的写前面）")
    void firstMatchWins() throws IOException {
        ConfigPermissionSource source = new ConfigPermissionSource(dir, List.of(
                new PermissionRule("财务/对外/**", List.of("agent:public")),
                new PermissionRule("财务/**", List.of("agent:finance"))), List.of());

        assertEquals(List.of("agent:public"), source.permissionCodesOf(file("财务/对外/公告.md")));
        assertEquals(List.of("agent:finance"), source.permissionCodesOf(file("财务/内部/账.md")));
    }

    @Test
    @DisplayName("支持按扩展名匹配（同一目录下 pdf 与 md 可以不同）")
    void supportsExtensionGlob() throws IOException {
        ConfigPermissionSource source = new ConfigPermissionSource(dir,
                List.of(new PermissionRule("**/*.pdf", List.of("agent:chat"))), List.of());

        assertEquals(List.of("agent:chat"), source.permissionCodesOf(file("手册/机型图册.pdf")));
        assertTrue(source.permissionCodesOf(file("手册/说明.md")).isEmpty());
    }
}
