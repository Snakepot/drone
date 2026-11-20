package cn.tedu.droneagent.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条提示词：**元信息 + 正文**。
 *
 * <h2>正文与元信息分家</h2>
 * {@link #body()} 是**真正发给模型的文本**；{@code name/version/purpose/vars/changelog} 只是给人和启动校验看的，
 * **不会**进 prompt。所以元信息怎么加都不影响模型看到的内容。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不负责读文件</b>：md 的读取与解析在 {@link PromptCatalog}。</li>
 *   <li><b>不负责校验</b>：名字与路径是否一致、{@code vars} 与占位符是否相符，都在 {@link PromptCatalog} 启动时判。</li>
 * </ul>
 *
 * @param name      提示词名，等于文件相对路径（`assistant/system.md` → `assistant.system`）
 * @param version   版本号；改了正文就该 +1（配合 {@link #changelog()} 用）
 * @param purpose   一句话用途，给人看
 * @param vars      正文里会用到的变量名（必须与正文占位符一一对应）
 * @param changelog 变更记录：一行一版，写清改了什么
 * @param body      正文，发给模型的文本
 */
public record PromptDefinition(String name, int version, String purpose,
                               List<String> vars, List<String> changelog, String body) {

    /** 占位符写法：{@code {{变量名}}}；双花括号是为了不和正文里 JSON 的 {@code {}} 打架 */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    public PromptDefinition {
        vars = List.copyOf(vars);
        changelog = List.copyOf(changelog);
    }

    /** 正文里出现的占位符名（按出现顺序、去重） */
    public static List<String> placeholdersOf(String body) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!found.contains(key)) {
                found.add(key);
            }
        }
        return found;
    }

    /**
     * 把正文里的 {@code {{变量}}} 换成实际值。
     *
     * <p><b>缺变量直接抛错</b>：宁可报错，也不要静默把 {@code {{maxChars}}} 原样发给模型
     * —— 那会让模型看到一句莫名其妙的指令。
     */
    public String render(Map<String, Object> values) {
        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (values == null || !values.containsKey(key)) {
                throw new IllegalStateException("提示词「" + name + "」缺少变量：" + key);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(values.get(key))));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
