package cn.tedu.droneagent.prompt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果的「**数据信封**」：把**不可信**的外部内容包起来，明确它是**数据、不是指令**。
 *
 * <h2>为什么需要它（提示词注入的间接路径）</h2>
 * 工具结果是**外部来的文本**（如天气接口返回的内容）。如果直接把原文塞回对话，
 * 模型分不清哪句是「用户/系统说的」、哪句是「外部数据里写的」——恶意指令藏在数据里就能被当成指令执行，
 * 这叫**间接提示词注入**。包一层标记后：一是模型能看出边界，二是**排障时一眼能认出哪段是数据**。
 *
 * <h2>为什么要「中和」</h2>
 * 光加标记还不够：工具结果里若**自带** {@code </tool_result>}，就能**提前闭合**信封、
 * 把后面的内容伪装成「信封之外」的指令。所以包装前先把内容里出现的标记串**中和掉**——
 * 只改边界串本身，**内容的其余部分一字不动**，信息不丢。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不负责判断内容好坏</b>：不做内容过滤（那既脆弱又容易误杀），只做「划清边界」。</li>
 *   <li><b>不负责提示词里怎么声明</b>：告诉模型「信封里是数据」是 `assistant.system` 的事。</li>
 *   <li><b>不替代结构性防线</b>：真正的兜底是服务端权限与人工确认（见 `REQ-AGENT-0006`）。</li>
 * </ul>
 */
public final class ToolResultEnvelope {

    /** 信封起始标记（成对；正文里极少出现，模型也认得出这是「分块」） */
    public static final String OPEN = "<tool_result>";

    /** 信封结束标记 */
    public static final String CLOSE = "</tool_result>";

    /** 内容里出现的标记串（容忍大小写与中间空白），一律中和 */
    private static final Pattern DELIMITER =
            Pattern.compile("<\\s*/?\\s*tool_result\\s*>", Pattern.CASE_INSENSITIVE);

    private static final String NEUTRALIZED = "［tool_result 标记已中和］";

    private ToolResultEnvelope() {
    }

    /** 把工具结果包成「数据」块；内容里的标记串先中和 */
    public static String wrap(String content) {
        return OPEN + "\n" + neutralize(content == null ? "" : content) + "\n" + CLOSE;
    }

    /** 中和内容里出现的标记串（只改边界串，内容其余部分不动） */
    static String neutralize(String content) {
        return DELIMITER.matcher(content).replaceAll(Matcher.quoteReplacement(NEUTRALIZED));
    }
}
