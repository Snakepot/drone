package cn.tedu.drone.route.pojo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点位类型的词汇表 —— 对应 {@code survey_point.category} 列。
 *
 * <h2>为什么不干脆让前端随便传个字符串</h2>
 * 这一列是给人筛选用的（「只看起降点」）。一旦库里混进 {@code Survey}、
 * {@code takeoff_point} 这种拼写，筛选就会出现「明明有 3 个起降点，
 * 筛出来只有 1 个」—— 而且<b>不会有任何报错</b>，只能靠人去数。
 * 所以和附件类型一个道理：<b>类型是白名单，不是自由文本</b>。
 *
 * <h2>为什么中文名放在后端</h2>
 * 和告警编码那边的取舍不同。告警有 29 条、还在长，前端再抄一份必然漂移；
 * 这里只有 4 条、几乎不会变，<b>但筛选下拉、地图图例、列表标签三处都要用</b>，
 * 让后端一处给出，前端就不用维护三份映射。
 */
public final class PointCategory {

    private PointCategory() {
    }

    /** 巡检点：绝大多数点位是这个 */
    public static final String SURVEY = "survey";

    /** 起降点：安全和空域相关，选中它的人通常是在安排「从哪起飞」 */
    public static final String TAKEOFF = "takeoff";

    /** 机巢：无人机自动机场的位置 */
    public static final String DOCK = "dock";

    /** 其他：留一个兜底，避免为了「一个说不上来的点」去改代码 */
    public static final String OTHER = "other";

    /** 全部取值，顺序固定（界面下拉与文档的排列都以它为准） */
    public static final List<String> ALL = List.of(SURVEY, TAKEOFF, DOCK, OTHER);

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(SURVEY, "巡检点");
        LABELS.put(TAKEOFF, "起降点");
        LABELS.put(DOCK, "机巢");
        LABELS.put(OTHER, "其他");
    }

    /** 新增时不传类型就用它 —— 建点位最常见的用途就是巡检 */
    public static final String DEFAULT = SURVEY;

    /** 是不是白名单里的取值 */
    public static boolean isValid(String category) {
        return category != null && LABELS.containsKey(category);
    }

    /** 中文名；取值非法时原样返回（用于报错信息里能看见传进来的是什么） */
    public static String label(String category) {
        return LABELS.getOrDefault(category, category);
    }

    /** 取值 → 中文名，供前端渲染下拉（保持 ALL 的顺序） */
    public static Map<String, String> labels() {
        return new LinkedHashMap<>(LABELS);
    }
}
