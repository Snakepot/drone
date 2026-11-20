package cn.tedu.droneagent.context;

import cn.tedu.droneagent.tool.ModelMessage;
import cn.tedu.droneagent.tool.ToolCall;

/**
 * 上下文 token 的**估算器**：不调模型、不依赖第三方 tokenizer，纯字符串扫描。
 *
 * <h2>系数从哪来（2026-10-23 对 deepseek-flash 实测）</h2>
 * <pre>
 *   112 个汉字              → 73 token   ≈ 0.62 / 字
 *   132 个 ASCII 字符       → 32 token   ≈ 0.22 / 字符
 *   每多一条消息（"a"）      → +4 token
 *   空对话的固定模板开销     → 约 22 token
 * </pre>
 * 这四个数是拿真实接口的 {@code usage.prompt_tokens} 反推出来的，不是拍脑袋。
 * 换模型或供应商改分词器后系数会漂，所以账单同时打印**模型实际值**用于校准。
 *
 * <h2>为什么不用精确 tokenizer</h2>
 * DeepSeek 没有公开的分词器；引一个近似 tokenizer（如 cl100k）反而给人「精确」的错觉。
 * 对「看趋势、找大头」这件事，±5% 的估算足够；要精确值就看每次请求的实际 usage。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不截断</b>：只报数字，不改变任何消息；</li>
 *   <li><b>不联网</b>：纯函数，单测可完全离线；</li>
 *   <li><b>不保证计费级精度</b>：它是预算与排障工具，不是账单系统。</li>
 * </ul>
 */
public final class TokenEstimator {

    /** 一次请求的固定模板开销（BOS、角色标记、结束符等），实测约 22 */
    public static final int TEMPLATE_TOKENS = 22;

    /** 每条消息的固定开销（角色标记等），实测约 4 */
    public static final int PER_MESSAGE_TOKENS = 4;

    /** 每个工具调用帧除函数名与参数外的结构开销（估算值，无实测样本） */
    private static final int PER_TOOL_CALL_TOKENS = 8;

    /** 汉字系数：实测 73 / 112 ≈ 0.65，整段消息合计后取 0.62 最贴近 */
    private static final double CJK_PER_CHAR = 0.62;

    /** 其他字符系数：实测约 0.21–0.24，取 0.21 与中文系数搭配后整体误差最小 */
    private static final double OTHER_PER_CHAR = 0.21;

    /**
     * 结构化文本（JSON / HTML / 工具结果信封）里非汉字字符的系数：0.34。
     *
     * <p>实测：日报 HTML 模板 11,300 字符（汉字 456、其余 10,844）≈ 3,969 token，
     * 反推非汉字约 0.34/字符；工具定义 JSON 也是同一档。
     * 这类内容里引号、尖括号、冒号占比高，分词器几乎按字符切。
     */
    private static final double STRUCTURED_PER_CHAR = 0.34;

    /**
     * JSON 字符系数：与自然语言不同，JSON 里大量是引号、冒号、花括号与英文键名，
     * 分词器几乎按字符切。用两个真实请求反推（73 个工具 29,606 字符 ≈ 10,490 token；
     * 21 个工具 8,503 字符 ≈ 3,334 token），得 0.34/字符。
     */
    private static final double JSON_PER_CHAR = 0.34;

    /**
     * 带工具时的固定模板开销：只要请求里出现 {@code tools}，对话模板就会多一段
     * 函数调用说明。实测：1 个极小工具比不带工具多 249 token，其中约 450 由
     * 两个真实请求反推得出（两个样本联立后固定项约 450，与 1 工具探针的量级一致）。
     */
    private static final int JSON_FIXED_TOKENS = 450;

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * <p>汉字（含中文标点、全角字符）按 0.62/字，其余按 0.21/字符，四舍五入。
     * 用四舍五入而不是向上取整：逐条消息向上取整时，误差会随消息条数累加
     * （实测 20 条消息能多算 10 token 以上）。空串或 null 返回 0。
     */
    public static int estimateText(String text) {
        return estimate(text, OTHER_PER_CHAR);
    }

    /**
     * 估算**结构化文本**（JSON / HTML / 工具结果）的 token 数。
     *
     * <p>非汉字按 0.34/字符——这类内容标点密集，不能套自然语言的 0.21。
     */
    public static int estimateStructuredText(String text) {
        return estimate(text, STRUCTURED_PER_CHAR);
    }

    private static int estimate(String text, double otherRatio) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.round(cjk * CJK_PER_CHAR + other * otherRatio);
    }

    /**
     * 估算**工具定义** JSON 的 token 数。
     *
     * <p>不能直接套自然语言系数：JSON 里引号、冒号、括号与英文键名占大头，
     * 分词器对这些几乎按字符计价；套 0.21 会低估四成（第一版实测低估 24%）。
     * 另外「带工具」本身有一段固定模板开销，规则为空时必须返回 0（请求里没有 tools）。
     */
    public static int estimateJson(String json) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        return JSON_FIXED_TOKENS + (int) Math.round(json.length() * JSON_PER_CHAR);
    }

    /**
     * 估算一条线格式消息的 token 数（按「普通文本」口径）。
     *
     * @see #estimateMessage(ModelMessage, MessageKind)
     */
    public static int estimateMessage(ModelMessage message) {
        return estimateMessage(message, MessageKind.SESSION);
    }

    /**
     * 估算一条线格式消息的 token 数：固定开销 + 正文 + 思考 + 工具调用的名字与参数。
     *
     * <p>口径按消息类别选：
     * <ul>
     *   <li><b>工具结果 / 工具调用帧</b>：内容是 JSON、HTML 或数据信封，按结构化系数 0.34；</li>
     *   <li><b>系统 / 记忆 / 会话</b>：自然语言，按 0.21。</li>
     * </ul>
     * 思考内容（{@code reasoningContent}）平时不回放，只有「危险操作确认续跑」时会原样带回，
     * 所以这里把它算进去——续跑那一次确实要付这份钱。
     */
    public static int estimateMessage(ModelMessage message, MessageKind kind) {
        if (message == null) {
            return 0;
        }
        boolean structured = kind == MessageKind.TOOL_RESULT || kind == MessageKind.TOOL_CALL;
        int tokens = PER_MESSAGE_TOKENS
                + (structured ? estimateStructuredText(message.content()) : estimateText(message.content()))
                + estimateText(message.reasoningContent());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                // 函数名与参数都是 JSON 片段，按结构化口径
                tokens += PER_TOOL_CALL_TOKENS
                        + estimateStructuredText(call.name())
                        + estimateStructuredText(call.argumentsJson());
            }
        }
        return tokens;
    }

    /** 汉字与中文/全角标点：按「字」计价 */
    private static boolean isCjk(int codePoint) {
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
            return true;
        }
        return (codePoint >= 0x3000 && codePoint <= 0x303F)   // 中文标点
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF); // 全角字符
    }
}
