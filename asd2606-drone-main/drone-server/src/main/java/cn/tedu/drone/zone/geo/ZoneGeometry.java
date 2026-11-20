package cn.tedu.drone.zone.geo;

import cn.tedu.drone.common.GeoDistance;

import java.util.ArrayList;
import java.util.List;

/**
 * 禁飞区的几何判定 —— <b>纯逻辑，不碰数据库，也不碰任何地图 SDK</b>。
 *
 * <h2>为什么自己写，而不用 MySQL 的空间函数</h2>
 * {@code ST_Contains} 确实更强，但有三个必踩的坑：必须显式指定 SRID 4326、
 * 多边形的环必须闭合、坐标顺序必须是 {@code (lng lat)} 不能反。
 * 而我们的形状很简单（圆 + 多边形），一条航线也就几十个点，
 * 取出来在 Java 里算既够用又**能写单测**（见设计文档 6.3.2 的三种做法对照）。
 *
 * <h2>两段式：先外接矩形，再精确判定</h2>
 * 这是本类唯一的性能设计，也是第四步（每条遥测都要判一次）能跑起来的前提：
 * <ol>
 *   <li>{@link Bounds#contains} —— 一次减法比较，几纳秒。绝大多数点在这里就被否掉</li>
 *   <li>{@link #inRing} / {@link #inCircle} —— 只有进了矩形的点才做</li>
 * </ol>
 * <b>预筛必须是「宁可放过、不可错杀」</b>：矩形要比真实形状大，
 * 否则会把本该命中的点提前丢掉，而且这种错不报错、只是偶尔不拦。
 *
 * <h2>坐标必须是同一种</h2>
 * 这里只看数字，不认坐标系。禁飞区、航线、起降点在库里都是 **WGS84**，
 * 所以判定也全在 WGS84 上做 —— 别把地图上点出来的 GCJ-02 直接混进来比较。
 */
public final class ZoneGeometry {

    /** 多边形至少要有 3 个顶点才围得成一块区域 */
    public static final int MIN_RING_POINTS = 3;

    private ZoneGeometry() {
    }

    /**
     * 外接矩形（经纬度范围）。
     *
     * <p>用 record 是因为它就是个「四个数、只读、用完即弃」的值 ——
     * 不需要 setter，也不需要 Lombok 那一套。{@code @Data} 的类反而
     * 会让人以为它需要被改。
     */
    public record Bounds(double minLng, double minLat, double maxLng, double maxLat) {

        /**
         * 点是否落在矩形内（含边界）。
         *
         * <p>含边界是刻意的：边界上的点要不要算命中是另一回事，
         * 但预筛这一步**绝不能**先把边界排除掉，那是「错杀」。
         */
        public boolean contains(double lng, double lat) {
            return lng >= minLng && lng <= maxLng && lat >= minLat && lat <= maxLat;
        }
    }

    /** 多边形的外接矩形；顶点少于 3 个时返回 null（形状本身就不成立） */
    public static Bounds boundsOfRing(List<double[]> ring) {
        if (ring == null || ring.size() < MIN_RING_POINTS) {
            return null;
        }
        double minLng = ring.get(0)[0];
        double maxLng = minLng;
        double minLat = ring.get(0)[1];
        double maxLat = minLat;
        for (double[] p : ring) {
            minLng = Math.min(minLng, p[0]);
            maxLng = Math.max(maxLng, p[0]);
            minLat = Math.min(minLat, p[1]);
            maxLat = Math.max(maxLat, p[1]);
        }
        return new Bounds(minLng, minLat, maxLng, maxLat);
    }

    /**
     * 圆的外接矩形。
     *
     * <p>把半径（米）换算成经纬度跨度。两个方向不一样，别写成同一个数：
     * <ul>
     *   <li>纬度：一度永远是约 110540 米，直接除</li>
     *   <li>经度：一度是 {@code 111320 × cos(纬度)} 米，所以要<b>除以 cos(纬度)</b> ——
     *       越靠近两极，同样的米数对应更大的度数。
     *       忘了这个 {@code cos}（或者乘反了）会让矩形比圆小，
     *       于是圆边上靠近东西两侧的点会被预筛错杀</li>
     * </ul>
     */
    public static Bounds boundsOfCircle(double lng, double lat, double radiusMeters) {
        double latSpan = radiusMeters / GeoDistance.METERS_PER_DEGREE_LAT;
        double lngSpan = radiusMeters / (GeoDistance.METERS_PER_DEGREE_LNG * Math.cos(Math.toRadians(lat)));
        return new Bounds(lng - lngSpan, lat - latSpan, lng + lngSpan, lat + latSpan);
    }

    /**
     * 点是否在多边形内部 —— <b>射线法</b>。
     *
     * <h2>它怎么工作</h2>
     * 从该点向正东（经度增大的方向）引一条射线，数它与多边形各条边相交几次：
     * <b>奇数次 = 在内部，偶数次 = 在外部</b>。道理很直观 ——
     * 每穿过一条边，就进出一次；从外面出发，进出次数为奇数时必然落在里面。
     *
     * <h2>为什么不能退化成「外接矩形」或「凸包」</h2>
     * 现实里的禁飞区常常是凹的（绕着机场跑道的一段，或者一块 L 形的厂区）。
     * 用矩形判断，缺口那一片会被误判成禁飞；
     * 用凸包判断，凹进去的部分会被误判成可以飞 —— <b>后者才是危险的</b>。
     *
     * <h2>顶点顺序与闭合</h2>
     * 顺时针、逆时针都行（射线法不关心方向）；<b>首尾点不用重复</b>，
     * 最后一条边（末点 → 首点）由 {@code j} 的初值补上。
     *
     * <h2>⚠️ 边界上的点是未定义的</h2>
     * 正好落在边上或顶点上的点，射线法可能给「内」也可能给「外」——
     * 这是算法本身的性质（浮点比较），不是 bug。
     * 对我们的用途（软拦 + 留痕）完全可以接受：差几厘米不影响判断，
     * 而且判定的结果会连区域名一起显示给人看，人还能复核。
     */
    public static boolean inRing(double lng, double lat, List<double[]> ring) {
        if (ring == null || ring.size() < MIN_RING_POINTS) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i)[0];
            double yi = ring.get(i)[1];
            double xj = ring.get(j)[0];
            double yj = ring.get(j)[1];

            // ① 这条边要跨过该点的纬度（一端在上、一端在下，或者反过来）
            boolean crossesLat = (yi > lat) != (yj > lat);
            if (!crossesLat) {
                continue;
            }
            // ② 交点在射线起点（该点）以东吗？
            //    直线的参数式 x = xi + (xj-xi) * (lat-yi)/(yj-yi)，
            //    这里先比大小再除，少一次除法，也避免除零（crossesLat 已经保证了 yj != yi）
            boolean eastOfPoint = lng < (xj - xi) * (lat - yi) / (yj - yi) + xi;
            if (eastOfPoint) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 点是否在圆内（含圆上）—— 圆心到点的距离与半径比大小 */
    public static boolean inCircle(double lng, double lat, double centerLng, double centerLat, double radiusMeters) {
        return GeoDistance.meters(centerLng, centerLat, lng, lat) <= radiusMeters;
    }

    /**
     * 把一条折线按固定间隔插值成「密集点」。
     *
     * <h2>为什么需要它：航线不只是「点在区内」</h2>
     * 起点和终点都在区外，中间那一段照样可能横穿过去。
     * 而「线段与多边形相交」要写一套几何求交（还要处理端点、重合这些边界），
     * 相比之下<b>插值 + 逐点判断</b>粗糙但够用，而且复用的就是上面那两段代码 ——
     * 一套逻辑只有一种行为，不会出现「点在区内」和「线穿区」判断不一致的情况。
     *
     * <p>间隔越小越准、点数越多。默认取 50 米：一条 3 公里的航线约 60 个点，
     * 判定一次是微秒级，而 50 米对「有没有飞进那块区域」这个判断已经足够细
     * （设计文档 6.3.2 说的「固定间隔插值」就是这个做法）。
     *
     * @param path        折线顶点（同一坐标系），首尾都要给
     * @param stepMeters  插值间隔（米），<= 0 时按默认 50 米
     * @return 插值后的点（含原始顶点与末尾点）；原折线不足 2 个点时原样返回
     */
    public static List<double[]> samplePolyline(List<double[]> path, double stepMeters) {
        if (path == null || path.size() < 2) {
            return path == null ? List.of() : path;
        }
        double step = stepMeters > 0 ? stepMeters : 50.0;

        List<double[]> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            double[] from = path.get(i - 1);
            double[] to = path.get(i);
            double distance = GeoDistance.meters(from[0], from[1], to[0], to[1]);
            // 段内插几个点：间距不足一个步长就不插（两端点已经在列表里了）
            int pieces = (int) Math.ceil(distance / step);
            for (int k = 1; k <= pieces; k++) {
                double ratio = (double) k / pieces;
                result.add(new double[]{
                        from[0] + (to[0] - from[0]) * ratio,
                        from[1] + (to[1] - from[1]) * ratio,
                });
            }
        }
        return result;
    }
}
