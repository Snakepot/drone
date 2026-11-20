package cn.tedu.droneagent.kb;

/**
 * 一个**块**：检索的最小单位，也是回填给模型的单位。
 *
 * <h2>为什么「引用出处」与「正文」分开存</h2>
 * <ul>
 *   <li>{@link #sectionPath()} 用于**引用**（告诉用户「出自哪一节」），也用于落库时的标量字段与权限过滤；</li>
 *   <li>{@link #text()} 是**真正的内容片段**，长度受 {@code agent.kb.chunk.max-chars} 约束。</li>
 * </ul>
 * 两者分开，引用就不会因为切块而丢失，长度统计也才准。
 *
 * <h2>为什么嵌入另给一个 {@link #embeddingText()}</h2>
 * 「**展示**的内容」与「**拿去算向量**的内容」可以不同：把标题路径拼进向量输入，
 * 语义会更准（同一段文字在「电池保养」下和在「应急处置」下含义不同）；
 * 但拼进去会让长度超限，所以**只在算向量时拼**，落库与引用仍用 {@link #text()}。
 *
 * @param sectionPath 所属章节路径，如「无人机操作手册 › 8. 电池与日常保养」；可为空（文档开头无标题的内容）
 * @param text        块的正文
 */
public record Chunk(String sectionPath, String text) {

    /** 算向量时用的文本：把章节路径拼在正文前，给嵌入模型更多上下文 */
    public String embeddingText() {
        return sectionPath == null || sectionPath.isBlank() ? text : sectionPath + "\n" + text;
    }
}
