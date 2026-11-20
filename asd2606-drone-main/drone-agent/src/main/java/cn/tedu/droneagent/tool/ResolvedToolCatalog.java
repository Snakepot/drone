package cn.tedu.droneagent.tool;

import java.util.List;

/**
 * 「行为 + 元数据」的合并快照，由 {@code ToolConfig} 组装一次，供注册表与治理层共用。
 *
 * <h2>为什么要有这个类型</h2>
 * 工具现在有两个来源：本地 Bean（元数据在 {@code agent.tool.catalog}）与
 * 启动时发现的 MCP 远程工具（元数据由服务端下发）。
 * 如果注册表和治理层各自拼一遍，两边很可能**看到不同的世界**——
 * 注册表认识某个工具、治理层却认为它不存在（或反过来），问题只在运行时才暴露。
 *
 * <p>两个列表同序、等长：第 N 个行为对应第 N 条元数据。
 *
 * @param tools       行为列表
 * @param descriptors 与 {@code tools} 一一对应的元数据
 */
public record ResolvedToolCatalog(List<Tool> tools, List<ToolDescriptor> descriptors) {
}
