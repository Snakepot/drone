package cn.tedu.droneagent.mcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 相关的密钥脱敏。
 *
 * <h2>为什么单独写一个，而不用 {@code SensitiveMasker}</h2>
 * {@code SensitiveMasker} 处理的是「正文里的密码/手机号/卡号」，是**内容**层面的规则；
 * 而 MCP 的密钥藏在 **URL 的 query** 里（高德的写法就是
 * {@code https://mcp.amap.com/mcp?key=xxxx}），它的格式是「参数名 + 等号 + 值」，
 * 内容层面的规则一条都匹配不上。两类规则混在一起，反而会让两边都难改。
 *
 * <h2>遮到什么程度</h2>
 * 只保留参数名，值整体替换为 {@code ***}：
 * {@code ?key=fc51...} → {@code ?key=***}。
 * 读日志的人需要知道「这里原本有一个 key」，但不需要、也不应该看到它。
 */
public final class McpSecrets {

    /** 常见的密钥参数名；等号后的值一直遮到下一个 {@code &} 或 {@code #} */
    private static final Pattern SECRET_QUERY = Pattern.compile(
            "(?i)([?&](?:key|api[_-]?key|apikey|token|access_token|secret|signature)=)[^&#]*");

    private McpSecrets() {
    }

    /**
     * 把 URL 里的密钥值遮掉；不是 URL 的普通文本原样返回。
     *
     * @param url 可能含密钥的地址；null 原样返回
     */
    public static String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        Matcher matcher = SECRET_QUERY.matcher(url);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "***"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
