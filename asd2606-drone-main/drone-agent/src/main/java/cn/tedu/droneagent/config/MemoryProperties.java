package cn.tedu.droneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 记忆子系统的配置，对应 yml 节点 {@code agent.memory.*}。
 *
 * <h2>为什么用 Duration / DataSize 而不是裸数字</h2>
 * 裸数字必须靠注释说明单位，而注释会过期；{@link Duration} 与 {@link DataSize}
 * 让 yml 里直接写 {@code 30m}、{@code 2MB}，单位写在值里，改的人一眼看得懂。
 *
 * <h2>为什么是新加而不是复用已有配置</h2>
 * 记忆与模型调用是两件事：换模型不该影响会话保留多久，
 * 换存储目录也不该去动 DeepSeek 的配置。
 *
 * @param root                   长期记忆落盘根目录。<b>部署必须挂卷</b>，且只允许单实例
 * @param sessionMaxCount        内存中同时保留的会话数上限，超出时最久未访问的被驱逐
 * @param idleTimeout            会话空闲多久算结束；到期先落盘、再移除
 * @param maxMessagesPerSession  单个会话在内存里保留的消息条数上限
 * @param fileMaxBytes           单个正文文件的大小上限，超过则滚动到下一个文件
 * @param injectMaxTurns         注入模型的历史会话轮数上限
 * @param injectMaxChars         注入模型的历史会话字符数上限
 * @param injectEnabled          是否**每轮无条件注入**其他会话的长期记忆（`REQ-AGENT-0021` 起默认关闭；
 *                               关闭后由 {@code search_memory} 工具按需检索。开启即回滚旧行为）
 * @param compactTriggerMessages 会话内未摘要消息超过多少条时触发压缩（`REQ-AGENT-0020`）
 * @param compactKeepMessages    压缩后保留最近多少条原文
 * @param compactSummaryChars    摘要长度上限
 * @param compactInputChars      送去摘要的原文长度上限
 */
@ConfigurationProperties(prefix = "agent.memory")
public record MemoryProperties(
        String root,
        int sessionMaxCount,
        Duration idleTimeout,
        int maxMessagesPerSession,
        DataSize fileMaxBytes,
        int injectMaxTurns,
        int injectMaxChars,
        boolean injectEnabled,
        int compactTriggerMessages,
        int compactKeepMessages,
        int compactSummaryChars,
        int compactInputChars
) {
}
