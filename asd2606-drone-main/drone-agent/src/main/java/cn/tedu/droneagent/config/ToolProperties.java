package cn.tedu.droneagent.config;

import cn.tedu.droneagent.tool.ToolDescriptor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工具调用配置，对应 yml 节点 {@code agent.tool.*}。
 *
 * <h2>为什么用 Duration 而不是毫秒数</h2>
 * 与 {@link MemoryProperties} 同理：{@code 5s} 写在值里，改的人一眼看得懂单位。
 *
 * @param enabled         总开关。关掉即回到「只会聊天」的纯对话，行为与本需求之前一致
 * @param maxRounds       一次对话里最多几轮「模型要工具」的循环；超过就不再给工具、逼模型收尾作答
 * @param parallelEnabled 是否允许把本轮的多个**只读**工具并发执行
 * @param concurrency     并发执行时的线程数上限
 * @param invokeTimeout   单个工具的执行超时（编排层兜底，与工具自身的客户端超时是两道闸）
 * @param catalog         **工具元数据**（名字、描述、参数、能否并行）。代码里的工具只提供行为，
 *                        两者由 {@code ToolRegistry} 按名字合并、校验
 * @param optimizeEnabled 编排优化（`REQ-AGENT-0007`）总开关。关掉即回到 `0005` 的行为
 *                       （下面的预算与「重试/压缩」一并失效）
 * @param maxResultChars  单次工具回填的字符上限（`optimizeEnabled=false` 时用旧值 4000）
 * @param maxTotalResultChars 一次对话里**所有**工具结果的字符总量上限；超出的结果不回填、只留省略标记
 * @param maxSkillResultChars 单次 Skill 资源（`use_skill` / `read_skill_resource`）的字符上限
 * @param maxTotalSkillChars  一次请求内 Skill 资源的累计字符上限（`REQ-AGENT-0019`）；
 *                            与通用工具结果预算**互相独立**，超出时只截断、不做模型摘要
 * @param maxRetries      工具**失败**后允许的重试次数
 * @param dangerous       **危险工具清单**（`REQ-AGENT-0006`）：命中则**每次执行前都要人工确认**
 * @param roleTools       角色 → 允许使用的工具。危险工具**必须**在这里授予某角色，否则谁都不可用（fail-closed）
 * @param visibleBy       工具可见性规则（`REQ-AGENT-0018`）：模式 → 需要的权限码，任一满足才下发；
 *                        未匹配任何规则的工具默认可见。它只影响「模型看不看得到」，执行侧仍会再判一次
 * @param pendingRoot     待确认票据落盘目录（`REQ-AGENT-0029`）；空表示只存内存
 * @param pendingTtl      待确认票据有效期
 * @param operationRoot   写操作台账目录（`REQ-AGENT-0029`）；空表示不落盘
 */
@ConfigurationProperties(prefix = "agent.tool")
public record ToolProperties(
        boolean enabled,
        int maxRounds,
        boolean parallelEnabled,
        int concurrency,
        Duration invokeTimeout,
        List<ToolDescriptor> catalog,
        boolean optimizeEnabled,
        int maxResultChars,
        int maxTotalResultChars,
        int maxSkillResultChars,
        int maxTotalSkillChars,
        int maxRetries,
        List<String> dangerous,
        Map<String, List<String>> roleTools,
        List<ToolVisibilityRule> visibleBy,
        String pendingRoot,
        Duration pendingTtl,
        String operationRoot
) {
}
