package cn.tedu.drone.route.pojo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 航点动作的词汇表 —— 对应 {@code route_point.action} 列。
 *
 * <h2>为什么现在就把动作定下来，而不是先只存坐标</h2>
 * 航线上的每个点「要干什么」是航线的一部分，不是任务的一部分：
 * 「东线巡检」这条航线天生就是「第 3 个点拍照、第 7 个点悬停 15 秒」。
 * 要是先在任务里描述，同一条航线被 10 条任务引用时就要写 10 遍，
 * 而且必然有 3 条写得不一样。
 *
 * <p>真正执行这些动作的是飞手或飞机自己 —— 系统这边负责「把要求说清楚」，
 * 所以取值只有三档，不像飞控那样有十几个指令。
 */
public final class RouteAction {

    private RouteAction() {
    }

    /** 不动作：路过 */
    public static final String NONE = "none";

    /** 拍照 */
    public static final String PHOTO = "photo";

    /** 悬停指定秒数（配 {@code hover_seconds}） */
    public static final String HOVER = "hover";

    /** 全部取值，顺序固定（前端下拉与文档都以它为准） */
    public static final List<String> ALL = List.of(NONE, PHOTO, HOVER);

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(NONE, "飞过");
        LABELS.put(PHOTO, "拍照");
        LABELS.put(HOVER, "悬停");
    }

    /** 不传动作就按「飞过」—— 只是途经一个坐标点 */
    public static final String DEFAULT = NONE;

    public static boolean isValid(String action) {
        return action != null && LABELS.containsKey(action);
    }

    public static String label(String action) {
        return LABELS.getOrDefault(action, action);
    }

    /** 取值 → 中文名，供前端渲染下拉 */
    public static Map<String, String> labels() {
        return new LinkedHashMap<>(LABELS);
    }
}
