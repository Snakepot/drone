package cn.tedu.droneagent.service;

import cn.tedu.droneagent.config.DeepSeekProperties;
import cn.tedu.droneagent.context.ContextMessage;
import cn.tedu.droneagent.prompt.TestPrompts;
import cn.tedu.droneagent.skill.SkillCatalog;
import cn.tedu.droneagent.tool.ModelMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词里的 Skill 清单必须按当前用户权限裁剪。
 *
 * <p>这条直接决定 token 成本与「模型会不会去调无权流程」，所以单独钉住。
 */
class ChatServiceSkillTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SkillCatalog catalog() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("daily/SKILL.md", content("daily", "task:list, alert:list", "日报"));
        files.put("handover/SKILL.md", content("handover", "task:execute", "交接"));
        return SkillCatalog.fromContent(files, Map.of(), 8000);
    }

    @Test
    @DisplayName("权限不同 → 前缀指纹不同（Skill L1 变化，缓存失效属预期）")
    void prefixFingerprintChangesWithPermissions() {
        ChatService service = new ChatService(
                new DeepSeekProperties("http://unused", "k", "m", null, false),
                TestPrompts.withSkills("你是助手\n{{skills}}"), MAPPER, catalog());

        ChatService.BuiltRequest pilot = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                List.of(), Set.of("task:execute"), false);
        ChatService.BuiltRequest admin = service.buildRequest(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                List.of(), Set.of("task:list", "alert:list", "task:execute"), false);

        assertNotEquals(pilot.prefixFingerprint(), admin.prefixFingerprint(),
                "可见 Skill 不同 → 系统提示词不同 → 指纹必须不同");
    }

    @Test
    @DisplayName("admin 看到两条；pilot 只看到交接；无权限看到「暂无」")
    void filtersL1ByPermissions() {
        ChatService service = new ChatService(
                new DeepSeekProperties("http://unused", "k", "m", null, false),
                TestPrompts.withSkills("你是助手\n{{skills}}"), MAPPER, catalog());

        String admin = body(service, Set.of("task:list", "alert:list", "task:execute"));
        assertTrue(admin.contains("daily"));
        assertTrue(admin.contains("handover"));

        String pilot = body(service, Set.of("task:execute"));
        assertTrue(pilot.contains("handover"));
        assertFalse(pilot.contains("daily"));

        String nobody = body(service, Set.of());
        assertTrue(nobody.contains("没有可用的标准化流程"));
        assertFalse(nobody.contains("daily"));
    }

    @Test
    @DisplayName("普通对话路径也按权限渲染（不只工具路径）")
    void plainChatPathAlsoFilters() {
        ChatService service = new ChatService(
                new DeepSeekProperties("http://unused", "k", "m", null, false),
                TestPrompts.withSkills("你是助手\n{{skills}}"), MAPPER, catalog());

        List<ContextMessage> messages = service.withSystemPrompt(
                List.of(ContextMessage.session(ModelMessage.user("你好"))),
                Set.of("task:execute"));

        String system = messages.get(0).message().content();
        assertTrue(system.contains("handover"));
        assertFalse(system.contains("daily"));
    }

    private static String body(ChatService service, Set<String> permissions) {
        return service.buildToolBody(
                List.of(ContextMessage.session(ModelMessage.user("出个日报"))),
                List.of(), permissions);
    }

    private static String content(String name, String permissions, String text) {
        return "---\nname: " + name + "\nversion: 1\ndescription: " + text + "流程\n"
                + "permissions: [" + permissions + "]\ntools: []\n---\n正文";
    }
}
