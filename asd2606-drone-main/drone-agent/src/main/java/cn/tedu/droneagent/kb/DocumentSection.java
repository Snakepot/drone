package cn.tedu.droneagent.kb;

import java.util.List;

/**
 * 文档里的一段**结构区间**：标题路径 + 正文。
 *
 * <h2>为什么是「章节」而不是「纯文本」</h2>
 * 检索到的内容必须能**说清出处**（「出自哪份资料的哪一节」）。若解析阶段就把结构丢成一大段文本，
 * 后面再想标出处就只能靠猜。所以解析器**保留标题层级**，切块时把它作为每个块的来源。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：{@link DocumentParser} 的产物，随后交给 {@link Chunker} 切块。
 *
 * <ul>
 *   <li><b>不含长度控制</b>：正文可能很长，切多长是 {@link Chunker} 的事。</li>
 *   <li><b>不含来源文件名</b>：文件名由上层（入库编排）持有，本类只管文档**内部**的结构。</li>
 * </ul>
 *
 * @param headingPath 标题路径，从一级标题往下依次排列（如 {@code [无人机操作手册, 8. 电池与日常保养]}）；
 *                    文档开头、首个标题之前的内容为空列表
 * @param body        该标题下的正文（**不含**标题行本身）
 */
public record DocumentSection(List<String> headingPath, String body) {

    public DocumentSection {
        headingPath = List.copyOf(headingPath);
    }

    /** 标题路径的文本形式，用于**引用出处**与落库，如「无人机操作手册 › 8. 电池与日常保养」 */
    public String pathText() {
        return String.join(" › ", headingPath);
    }
}
