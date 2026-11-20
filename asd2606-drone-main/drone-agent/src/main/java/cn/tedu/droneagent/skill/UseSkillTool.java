package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 工具 {@code use_skill}：把某个业务流程的**完整步骤**交给模型（L2）。
 *
 * <h2>为什么要先加载再执行</h2>
 * L1 只有一行元数据；模型判断「这个请求属于某条固定流程」后，调用本工具取正文，
 * 之后按正文里的编号步骤调用业务工具。这样正文不必常驻上下文。
 *
 * <h2>权限为什么在这里再判一次</h2>
 * L1 已经按权限过滤，正常用户看不到无权流程；但模型可能凭历史记忆/猜测名字直接调用。
 * 这里再判一次，并让「无权」与「不存在」返回**同一句文案**，避免暴露流程存在性。
 */
@Slf4j
public class UseSkillTool implements Tool {

    public static final String NAME = "use_skill";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无权与不存在共用同一句：对外不暴露流程是否真实存在 */
    static final String MISS_MESSAGE = "没有找到该流程（可能不存在，或当前账号无权使用）";

    private final SkillCatalog catalog;

    public UseSkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolResult invoke(String argumentsJson) {
        return invoke(argumentsJson, ToolContext.anonymous());
    }

    @Override
    public ToolResult invoke(String argumentsJson, ToolContext context) {
        String name = parseName(argumentsJson);
        if (name.isBlank()) {
            return ToolResult.failed("缺少参数 name（要加载的流程名）");
        }

        Optional<SkillDefinition> visible = catalog.findVisible(name, context.permissions());
        if (visible.isEmpty()) {
            explainMiss(name, context);
            return ToolResult.failed(MISS_MESSAGE);
        }

        SkillDefinition skill = visible.get();
        StringBuilder out = new StringBuilder();
        out.append("流程「").append(skill.name()).append("」的完整步骤如下。\n")
                .append("**必须按顺序执行，缺少任一步就停下并向用户说明原因；不要凭记忆跳步，也不要自行改参数。**\n\n")
                .append(skill.body());

        if (!skill.resources().isEmpty()) {
            out.append("\n\n## 可用的参考资料与模板\n")
                    .append("需要核对口径/字段或使用模板时，调用 `read_skill_resource` 读取（不要凭空推测）：\n");
            skill.resources().keySet().forEach(path -> out.append("- ").append(path).append('\n'));
        }

        log.info("【Skill】加载流程：name={}，version={}，正文 {} 字符，参考资料 {} 份，userId 权限数={}",
                skill.name(), skill.version(), skill.body().length(), skill.resources().size(),
                context.permissions().size());
        return ToolResult.ok(out.toString());
    }

    /** 日志记录真实原因，但不返回给模型 */
    private void explainMiss(String name, ToolContext context) {
        Optional<SkillDefinition> any = catalog.findAny(name);
        if (any.isPresent()) {
            log.warn("【Skill】拒绝加载：name={}，原因=当前账号权限不足；需要 {}", name,
                    any.get().permissions());
        } else {
            log.info("【Skill】加载失败：name={}，原因=不存在；当前可见 {} 个",
                    name, catalog.visibleTo(context.permissions()).size());
        }
    }

    private static String parseName(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode args = MAPPER.readTree(argumentsJson);
            return args.path("name").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
