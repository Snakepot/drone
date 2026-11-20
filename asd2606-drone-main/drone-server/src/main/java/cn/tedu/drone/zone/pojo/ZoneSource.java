package cn.tedu.drone.zone.pojo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 禁飞区的来源词汇表 —— 对应 {@code no_fly_zone.source} 列。
 *
 * <h2>为什么这个「来源」不是可有可无的备注</h2>
 * 两者出问题时该找谁、能不能改，完全不一样（见设计文档 6.3.1）：
 * <ul>
 *   <li>{@link #OFFICIAL} 官方空域（机场净空区、临时管制）：<b>不是我们画的，
 *       也不该由我们决定要不要遵守</b>。看到它被停用、被改小，本身就是个信号</li>
 *   <li>{@link #CUSTOM} 自建（厂区、客户场地、涉密区域）：我们自己画的，随时可调</li>
 * </ul>
 * 所以列表页把它显示出来，而不是混在一起只看名字。
 *
 * <p>官方数据**目前靠人工录入**（没有对接数据源）——
 * 这一点在文档里写清楚了，免得有人以为系统会自动更新临时管制。
 */
public final class ZoneSource {

    private ZoneSource() {
    }

    /** 官方空域：人工录入的权威数据 */
    public static final String OFFICIAL = "official";

    /** 自建：管理员自己画的，是主要用法 */
    public static final String CUSTOM = "custom";

    public static final List<String> ALL = List.of(CUSTOM, OFFICIAL);

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(CUSTOM, "自建");
        LABELS.put(OFFICIAL, "官方空域");
    }

    public static final String DEFAULT = CUSTOM;

    public static boolean isValid(String source) {
        return source != null && LABELS.containsKey(source);
    }

    public static String label(String source) {
        return LABELS.getOrDefault(source, source);
    }

    public static Map<String, String> labels() {
        return new LinkedHashMap<>(LABELS);
    }
}
