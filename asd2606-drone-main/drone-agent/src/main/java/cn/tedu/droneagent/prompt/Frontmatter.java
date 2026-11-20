package cn.tedu.droneagent.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 frontmatter 解析器：提示词与 Skill 共用。
 *
 * <h2>为什么自己写，不引 YAML 库</h2>
 * 我们只需要三种写法：{@code key: 值}、{@code key:} 后跟 {@code - 列表项}、
 * {@code key: [a, b]}。为此引一个 YAML 依赖，既增加包体也增加「解析行为不可控」的风险。
 *
 * <h2>格式约定</h2>
 * <pre>
 * ---
 * name: tool.summary
 * vars: [maxChars]
 * ---
 * 正文……
 * </pre>
 * 首行必须是 {@code ---}，必须有收尾的 {@code ---}；之后到文件末尾都是正文（会 strip）。
 *
 * <h2>失败方式</h2>
 * 格式错误一律抛 {@link IllegalStateException}——这些文件在启动时加载，
 * 写坏了就应该让服务起不来，而不是带着半份配置上线。
 */
public final class Frontmatter {

    public static final String DELIMITER = "---";

    private Frontmatter() {
    }

    /**
     * 解析结果。
     *
     * @param fields frontmatter 的键值（值可能是 String 或 List&lt;String&gt;）
     * @param body   正文（已 strip）
     */
    public record Parsed(Map<String, Object> fields, String body) {
    }

    /**
     * @param source  用于报错的来源标识（相对路径）
     * @param content 文件全文
     */
    public static Parsed parse(String source, String content) {
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(lines[0])) {
            throw invalid(source, "首行必须是 ---（缺 frontmatter）");
        }
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i])) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw invalid(source, "frontmatter 没有收尾的 ---");
        }

        Map<String, Object> fields = parseFields(source,
                String.join("\n", java.util.Arrays.copyOfRange(lines, 1, close)));
        String body = String.join("\n",
                java.util.Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
        return new Parsed(fields, body);
    }

    /** 取字符串值；不存在返回 null */
    public static String string(Map<String, Object> fields, String key) {
        Object raw = fields.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 取列表值。
     *
     * <p>{@code key:} 后没跟列表项时，解析结果是**空列表**而不是 null——
     * 这与「显式声明空列表」语义一致。
     */
    public static List<String> list(Map<String, Object> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(raw));
    }

    /** 取正整数；缺失或格式不对返回 -1，由调用方决定是否报错 */
    public static int positiveInt(Map<String, Object> fields, String key) {
        Object raw = fields.get(key);
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, Object> parseFields(String source, String block) {
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> currentList = null;
        int lineNo = 0;
        for (String raw : block.split("\n", -1)) {
            lineNo++;
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (line.stripLeading().startsWith("- ")) {
                if (currentList == null) {
                    throw invalid(source, "第 " + lineNo + " 行的列表项没有归属的键");
                }
                currentList.add(unquote(line.stripLeading().substring(2).strip()));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw invalid(source, "第 " + lineNo + " 行不是 `key: value` 或 `key:` 形式");
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            currentList = null;
            if (value.isEmpty()) {
                List<String> items = new ArrayList<>();
                fields.put(key, items);
                currentList = items;
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> items = new ArrayList<>();
                for (String item : value.substring(1, value.length() - 1).split(",")) {
                    if (!item.isBlank()) {
                        items.add(unquote(item.strip()));
                    }
                }
                fields.put(key, items);
            } else {
                fields.put(key, unquote(value));
            }
        }
        return fields;
    }

    /** 去掉值两端成对的引号（`'x'` / `"x"` → `x`） */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static IllegalStateException invalid(String source, String why) {
        return new IllegalStateException("frontmatter「" + source + "」不合格：" + why);
    }
}
