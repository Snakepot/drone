package cn.tedu.drone.common;

/**
 * 两点间的距离（米）。
 *
 * <h2>为什么不做成完整的 haversine</h2>
 * 用它的地方只关心「够不够几十米」这个量级的判断：
 * <ul>
 *   <li>轨迹抽稀：相邻点够不够 5 米</li>
 *   <li>航线里程：几个航点加起来大概多少公里</li>
 * </ul>
 * 在几十公里的范围内，「把经纬度按当地纬度折算成平面米」的近似误差在千分之一量级，
 * 对这两个用途完全够用，而且没有三角函数嵌套（只有一个 {@code cos}），读起来一眼能懂。
 * 真要算跨洲距离时才需要换 haversine —— 我们的业务里没有这种事。
 *
 * <h2>唯一必须记住的一点</h2>
 * 纬度那一侧用固定的 {@code 110540} 米/度（经线方向基本恒定）；
 * 经度那一侧要乘 {@code cos(纬度)} —— 越靠近两极，一度经线越短。
 * 忘了这个 {@code cos} 就等于假设地球是圆柱面，在我们的纬度上会差掉两三成。
 *
 * <p><b>输入必须是同一种坐标系的坐标。</b>这里只看数字差，不认坐标系；
 * 拿一个 WGS84 的点和一个 GCJ-02 的点来算，会得到一个「几百米」的假距离。
 * 库里存的全是 WGS84，所以正常用法不会踩到 —— 要比较的双方都先确保在同一个系里。
 */
public final class GeoDistance {

    /**
     * 纬度方向：一度约 110540 米。
     *
     * <p>公开出来是给「把米换算成度」用的（比如禁飞区圆的外接矩形）——
     * 那种地方需要的是「一度有几米」，而不是两点之间的距离。
     */
    public static final double METERS_PER_DEGREE_LAT = 110540.0;

    /**
     * 经度方向：赤道上一度约 111320 米。
     *
     * <p>⚠️ 这个值对应**赤道**，是各纬度上的<b>最大值</b>。
     * 换算时要乘 {@code cos(纬度)}，方向别搞反：纬度越高，一度经线越短，
     * 所以「同样的米数」对应的**度数越大**。
     * 反着用会让外接矩形比真实范围小，从而漏掉本该命中的点（假阴性）——
     * 那种错不报错、只是偶尔不拦，最难查。
     */
    public static final double METERS_PER_DEGREE_LNG = 111320.0;

    private GeoDistance() {
    }

    /**
     * 两点间的近似平面距离。
     *
     * @return 米；两点相同时为 0
     */
    public static double meters(double lng1, double lat1, double lng2, double lat2) {
        // 两点的纬度取平均：这样「往北飞」和「往南飞」算出来的东西向尺度是对称的
        double latAvg = Math.toRadians((lat1 + lat2) / 2.0);
        double dx = (lng2 - lng1) * METERS_PER_DEGREE_LNG * Math.cos(latAvg);
        double dy = (lat2 - lat1) * METERS_PER_DEGREE_LAT;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 把一串有序的点连起来的总长度。
     *
     * <p>少于两个点时为 0（一个点没有「里程」可言）。
     * 点数很多时注意：这是 {@code n-1} 次开方，不是一次 ——
     * 目前只用在航线上（几十个点），够用；将来要算几万个点的轨迹里程时再谈优化。
     *
     * @param lngLat 交替存放的经纬度：{@code [lng1, lat1, lng2, lat2, ...]}，长度须为偶数
     */
    public static double polylineMeters(double[] lngLat) {
        if (lngLat == null || lngLat.length < 4) {
            return 0;
        }
        double total = 0;
        for (int i = 2; i < lngLat.length; i += 2) {
            total += meters(lngLat[i - 2], lngLat[i - 1], lngLat[i], lngLat[i + 1]);
        }
        return total;
    }
}
