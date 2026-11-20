package cn.tedu.droneagent.memory;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落盘前的敏感信息脱敏。
 *
 * <h2>为什么要在落盘时做</h2>
 * 内存里的会话保留原文（模型需要完整语义），只有写进长期记忆的**持久副本**需要脱敏。
 * 两件事分开，才能同时满足「敏感字段不落盘」与「非敏感内容逐字保留」。
 *
 * <h2>两条设计原则</h2>
 * <ol>
 *   <li><b>保留结构性标签，遮掉秘密值。</b>
 *       {@code 密码：Abc12345} 变成 {@code 密码：***} 而不是整个 {@code ***}——
 *       读记忆文件的人需要看懂「这里原本是一条密码」，但不需要也不该看到密码本身。</li>
 *   <li><b>遮掉的位数不足以还原原值。</b>
 *       手机号保留前 3 后 4，是为了让人认出「这是哪一条记录」；
 *       中间 4 位不给，光靠前后缀无法还原。</li>
 * </ol>
 *
 * <h2>规则顺序为什么是这么排的</h2>
 * 两类顺序问题，都会导致「先被局部改掉、导致外层规则失配」：
 * <ul>
 *   <li><b>外层结构优先。</b>邮箱与令牌的值里可能含数字，若先跑数字规则，
 *       邮箱会被切成 {@code user138****8000@example.com} 这种残缺形态，后续邮箱规则再也匹配不上。
 *       所以邮箱、令牌、密码这类「包裹型」规则排在数字类之前。</li>
 *   <li><b>长数字模式优先。</b>身份证（18 位）排在银行卡（16~19 位）与手机号（11 位）之前。
 *       同时每条数字规则都加了 {@code (?<!\d)} / {@code (?!\d)} 边界，
 *       避免在更长的数字串中间咬下一截。</li>
 * </ul>
 *
 * <h2>已知的误报</h2>
 * 16 位以上的**纯数字业务编号**（如某些设备序列号）会被当成银行卡遮掉。
 * 规则层面无法区分「长得像卡号」与「真的是卡号」，这里选择宁可多遮。
 * 若某类编号被误遮影响使用，需要为该模式单独加豁免。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：**所有「要写出去的东西」的最后一道** —— 长期记忆的正文与索引、
 * 以及模型调用的日志，都先过它一遍。
 *
 * <ul>
 *   <li><b>不决定什么时候脱敏</b>：调用方决定（落盘前、打日志前）；它只是个纯函数，
 *       不感知谁在调用。</li>
 *   <li><b>不做加密</b>：只替换字符，不提供任何还原手段 —— 要求就是「遮掉的拿不回来」。</li>
 *   <li><b>不做分类与检索</b>：它不标记「这段是敏感数据」，只是就地改文本。</li>
 * </ul>
 */
public final class SensitiveMasker {

    /** 工具类不需要被实例化 */
    private SensitiveMasker() {
    }

    /** 一条脱敏规则：命中后由 {@code replacer} 决定替换成什么 */
    private record Rule(Pattern pattern, Function<Matcher, String> replacer) {
    }

    private static final List<Rule> RULES = List.of(
            // 1. 令牌的前缀本身就是秘密格式的一部分，但留着它便于看懂上下文，所以只遮值
            new Rule(Pattern.compile("(sk-)[A-Za-z0-9_-]{8,}"),
                    m -> m.group(1) + "***"),
            new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}"),
                    m -> m.group(1) + "***"),

            // 2. 密码类：保留标签与分隔符，值整体遮掉。限定到分隔符之后、常见标点之前，
            //    否则 \S+ 会一路吃到句子结尾
            new Rule(Pattern.compile("(?i)(密码|password)(\\s*[:：=]\\s*)[^\\s,，;；。]{1,64}"),
                    m -> m.group(1) + m.group(2) + "***"),

            // 3. 邮箱：保留首字符与域名，本地部分其余遮掉
            new Rule(Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)"),
                    m -> m.group(1) + "***@" + m.group(2)),

            // 4. 身份证：18 位，末位可能是 X。要求前后都不是数字，
            //    否则 19 位以上的数字串会被这里咬掉 18 位、剩一位裸露在外
            new Rule(Pattern.compile("(?<!\\d)(\\d{6})\\d{8}([\\dXx]{4})(?!\\d)"),
                    m -> m.group(1) + "****" + m.group(2)),

            // 5. 银行卡：16~19 位纯数字，保留后 4 位
            new Rule(Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)"),
                    m -> "****" + m.group(1).substring(m.group(1).length() - 4)),

            // 6. 手机号：最短的数字模式，放最后
            new Rule(Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)"),
                    m -> m.group(1) + "****" + m.group(2))
    );

    /**
     * 脱敏一段文本。
     *
     * @param text 原文；为 {@code null} 或空串时原样返回
     * @return 脱敏后的文本；只改动命中的片段，其余字符逐字保留
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Rule rule : RULES) {
            masked = apply(rule, masked);
        }
        return masked;
    }

    private static String apply(Rule rule, String text) {
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // 用 quoteReplacement：替换串里若出现 $ 或 \ 会被 appendReplacement 当成分组引用
            matcher.appendReplacement(out, Matcher.quoteReplacement(rule.replacer().apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
