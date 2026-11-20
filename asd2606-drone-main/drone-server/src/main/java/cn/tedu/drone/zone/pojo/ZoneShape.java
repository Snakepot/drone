package cn.tedu.drone.zone.pojo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 禁飞区的形状词汇表 —— 对应 {@code no_fly_zone.shape} 列。
 *
 * <h2>它同时管着「几何怎么存」</h2>
 * 两种形状存在同一个 json 列里，格式不同：
 * <pre>
 *   polygon: [[lng,lat], [lng,lat], ...]        顶点数组（首尾点不重复，闭合由读的一方补）
 *   circle : {"lng":.., "lat":.., "radius":米}   圆心 + 半径
 * </pre>
 * 所以「形状」不只是个标签，它决定了这一列的解析方式 ——
 * 校验、序列化、反序列化都得以它为准（都收在 {@code NoFlyZoneServiceImpl} 里，
 * 别在外面各写一份 {@code if ("polygon")}）。
 *
 * <h2>为什么现在是两种，而不是一开始就上任意多边形 + 圆环 + 带洞</h2>
 * 现实里最常见的两种就是「绕着一块场地画一圈」和「以某个点为中心多少米」
 * （后者常常是「机场净空区半径 X 公里」那种说法）。
 * 带洞、多环这些等真遇到再说 —— 加的时候是加一个形状取值 + 一段解析，
 * 判定逻辑（射线法/距离）本身不用动。
 */
public final class ZoneShape {

    private ZoneShape() {
    }

    /** 多边形 */
    public static final String POLYGON = "polygon";

    /** 圆 */
    public static final String CIRCLE = "circle";

    /** 全部取值，顺序固定 */
    public static final List<String> ALL = List.of(POLYGON, CIRCLE);

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(POLYGON, "多边形");
        LABELS.put(CIRCLE, "圆");
    }

    /** 新建时不传形状就按多边形 —— 画区最常用的就是沿场地描一圈 */
    public static final String DEFAULT = POLYGON;

    public static boolean isValid(String shape) {
        return shape != null && LABELS.containsKey(shape);
    }

    public static String label(String shape) {
        return LABELS.getOrDefault(shape, shape);
    }

    /** 取值 → 中文名，供前端渲染下拉 */
    public static Map<String, String> labels() {
        return new LinkedHashMap<>(LABELS);
    }
}
