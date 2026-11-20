package cn.tedu.droneagent.context;

/**
 * 一条消息在上下文里的「身份」——账单按它分类汇总。
 *
 * <h2>为什么要分类，而不是只数总数</h2>
 * 总数只能回答「贵不贵」，回答不了「贵在哪」：
 * 同样是 1 万 token，花在工具定义上和花在长期记忆上，优化手段完全不同。
 * 分类之后，才能看出「工具定义占了 88%」这种结论。
 *
 * <h2>为什么 {@code RESUME} 是单独一类</h2>
 * 人工确认后的续跑，消息是暂停那一刻原样存下来的；这里没有保留当时的来源信息。
 * 与其猜「这条算会话还是算记忆」，不如如实标成「续跑上下文」——账单要的是准确，不是好看。
 */
public enum MessageKind {

    /** 系统提示词（含按权限裁剪的 Skill L1 清单） */
    SYSTEM("系统提示词"),

    /** 其他会话归档后，按预算注入的长期记忆 */
    LONG_TERM("长期记忆"),

    /** 当前会话里的用户 / 助手消息 */
    SESSION("当前会话"),

    /** 助手请求调用工具的帧（带 tool_calls） */
    TOOL_CALL("工具调用请求"),

    /** 工具执行结果回填（带工具名） */
    TOOL_RESULT("工具结果"),

    /** 人工确认后续跑时，从待确认票据里恢复出来的上下文 */
    RESUME("确认续跑上下文"),

    /** 会话内压缩生成的历史摘要（`REQ-AGENT-0020`） */
    COMPACTION("历史摘要");

    private final String label;

    MessageKind(String label) {
        this.label = label;
    }

    /** 日志里显示的中文名 */
    public String label() {
        return label;
    }
}
