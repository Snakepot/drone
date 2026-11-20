package cn.tedu.droneagent.skill;

import cn.tedu.droneagent.tool.ToolDefinition;
import cn.tedu.droneagent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 启动时校验：Skill 正文里声明的工具是否真的存在。
 *
 * <h2>为什么本地工具缺失要启动失败、MCP 工具缺失只告警</h2>
 * 本地工具（如 {@code search_knowledge}）随代码一起打包，缺失一定是写错了名字，应当立即失败；
 * 而 {@code drone__*} 依赖 `drone-mcp-server` 的发现结果——服务器临时没起时工具本就不在，
 * 属于可降级情形，只告警（与 MCP 的降级策略一致）。
 */
@Slf4j
public class SkillToolValidator {

    public SkillToolValidator(SkillCatalog catalog, ToolRegistry registry) {
        Set<String> known = registry.definitions().stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());

        for (SkillDefinition skill : catalog.all()) {
            for (String tool : skill.tools()) {
                if (known.contains(tool)) {
                    continue;
                }
                if (tool.contains("__")) {
                    log.warn("【Skill】「{}」引用的工具「{}」本次未发现（MCP 服务器可能未起）；"
                            + "该步骤执行时会失败，修复后重启即可", skill.name(), tool);
                    continue;
                }
                throw new IllegalStateException("Skill「" + skill.name()
                        + "」引用了不存在的本地工具「" + tool + "」");
            }
        }
        log.info("【Skill】工具引用校验完成：{} 个流程", catalog.all().size());
    }
}
