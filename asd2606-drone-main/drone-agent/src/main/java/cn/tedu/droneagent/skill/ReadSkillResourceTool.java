package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.tool.Tool;
import cn.tedu.droneagent.tool.ToolContext;
import cn.tedu.droneagent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 工具 {@code read_skill_resource}：按需读取 Skill 的参考资料（L3）。
 *
 * <h2>安全边界</h2>
 * <ol>
 *   <li>只能读**当前用户可见**的 skill；无权与不存在返回同一句文案；</li>
 *   <li>路径必须落在 {@code references/} 或 {@code assets/} 下，拒绝 {@code ..}、绝对路径与反斜杠；</li>
 *   <li>扩展名白名单（md/txt/json/csv），不提供任意文件读取；</li>
 *   <li>内容在启动时已载入内存，运行期**不做磁盘 IO**。</li>
 * </ol>
 */
@Slf4j
public class ReadSkillResourceTool implements Tool {

    public static final String NAME = "read_skill_resource";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** L3 只允许这两个目录：references/ 放口径资料，assets/ 放模板与素材 */
    private static final java.util.List<String> ALLOWED_DIRS =
            java.util.List.of("references/", "assets/");

    private final SkillCatalog catalog;
    private final int maxResourceChars;

    public ReadSkillResourceTool(SkillCatalog catalog, int maxResourceChars) {
        this.catalog = catalog;
        this.maxResourceChars = maxResourceChars <= 0 ? 32768 : maxResourceChars;
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
        JsonNode args;
        try {
            args = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            return ToolResult.failed("参数不是合法 JSON");
        }
        String skillName = args.path("skill").asText("").trim();
        String path = args.path("path").asText("").trim();
        if (skillName.isBlank() || path.isBlank()) {
            return ToolResult.failed("缺少参数 skill 或 path");
        }

        Optional<SkillDefinition> visible = catalog.findVisible(skillName, context.permissions());
        if (visible.isEmpty()) {
            log.warn("【Skill】读取资料被拒：skill={}，path={}，原因=不存在或无权限", skillName, path);
            return ToolResult.failed(UseSkillTool.MISS_MESSAGE);
        }

        String normalized = path.replace('\\', '/');
        boolean allowedDir = ALLOWED_DIRS.stream().anyMatch(normalized::startsWith);
        if (!allowedDir || normalized.contains("..") || normalized.startsWith("/")) {
            log.warn("【Skill】读取资料被拒：skill={}，path={}，原因=路径不在 references/ 或 assets/ 下",
                    skillName, path);
            return ToolResult.failed("只能读取该流程 references/ 或 assets/ 目录下的资料");
        }
        if (!SkillCatalog.isAllowedResource(normalized)) {
            return ToolResult.failed("不支持的资料类型（只允许 md/txt/json/csv/html/css）");
        }

        String text = visible.get().resources().get(normalized);
        if (text == null) {
            log.info("【Skill】资料不存在：skill={}，path={}", skillName, path);
            return ToolResult.failed("没有这份资料：" + normalized);
        }

        String content = text.length() <= maxResourceChars
                ? text
                : text.substring(0, maxResourceChars) + "\n…（资料超过 " + maxResourceChars + " 字符，已截断）";
        log.info("【Skill】读取资料：skill={}，path={}，{} 字符", skillName, path, content.length());
        return ToolResult.ok(content);
    }
}
