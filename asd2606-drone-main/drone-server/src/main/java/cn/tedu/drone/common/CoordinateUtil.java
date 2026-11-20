package cn.tedu.drone.common;

/**
 * 坐标转换。
 *
 * <h2>为什么必须有这个类</h2>
 * 我们的设备报的是 <b>WGS84</b>（GPS 原始值），而国内地图服务商显示的是
 * <b>GCJ-02</b>（俗称火星坐标）。把 WGS84 的数字直接丢给地图去画，
 * 位置会<b>偏几百米</b> —— 在北京量级约 500 米。
 *
 * <p>而这个问题**极难被发现**：它看起来「差不多对」，只是房子画到了马路对面。
 * 所以这里的一行一列都不是自由发挥。
 *
 * <h2>算法来源与可靠性</h2>
 * 这是公开的 GCJ-02 偏移算法，与开源实现
 * <a href="https://github.com/wandergis/coordtransform">wandergis/coordtransform</a>
 * 逐行对齐（含它 {@code out_of_china} 的边界取值）。
 * 单元测试直接拿那个项目 README 里公布的转换结果当**参考值**来断言 ——
 * 自己写的算法拿自己测等于没测，必须有外部基准。
 *
 * <h2>存储一律是 WGS84，两个方向各转一次</h2>
 * 库里（设备报的遥测、点位库、航线、起降点）<b>只有 WGS84 一种坐标</b>，
 * 绝不存在「这一列是加密坐标、那一列是原始坐标」的情况 —— 那样迟早会不同步，
 * 且没有任何办法看出某一行到底属于哪一种（见 {@code docs/地图接入方案.md} 2.3 节）。
 *
 * <p>于是地图这条边界上必然有两个方向：
 * <ul>
 *   <li><b>出图</b>：WGS84 → GCJ-02（{@link #wgs84ToGcj02}），
 *       给高德/腾讯直接画。方向是确定的、可测的</li>
 *   <li><b>入图</b>：人在 GCJ-02 的地图上点了一下，拿到的坐标是 GCJ-02，
 *       而库要的是 WGS84 → 必须转回去（{@link #gcj02ToWgs84}）</li>
 * </ul>
 *
 * <h3>关于「反算」这一条，实施第二步时补正过</h3>
 * 一开始只写了正向转换，理由是「GCJ-02 是加密坐标，反算属于灰色地带」。
 * 那个理由对**显示**成立，但漏了一件很实际的事：<b>点位库和航线是人在地图上点的</b>。
 * 只有正向转换的话，「打开一个点 → 拖到新位置 → 保存」会让坐标每次都漂几百米，
 * 而且漂得看不出来（还是「差不多对」）。
 *
 * <p>所以这里用的是公开实现里同样的近似反算：<b>在加密坐标上算出修正量再减回去</b>。
 * 它与正向转换共用同一段椭球修正的计算，因此往返误差是厘米级，
 * 完全不影响「这个点在哪」的判断。注意反算的结果**只用于入库前的换算，不落库**——
 * 库里存的仍然是换算出来的 WGS84，不是 GCJ-02。
 */
public final class CoordinateUtil {

    private CoordinateUtil() {
    }

    /** 圆周率（与参考实现取同样的精度，保证结果逐位一致） */
    private static final double PI = 3.1415926535897932384626;

    /** 克拉索夫斯基椭球长半轴（米） */
    private static final double A = 6378245.0;

    /** 偏心率平方 */
    private static final double EE = 0.00669342162296594323;

    /**
     * WGS84 → GCJ-02。
     *
     * @param lng 经度（WGS84）
     * @param lat 纬度（WGS84）
     * @return 长度为 2 的数组：{@code [经度, 纬度]}（GCJ-02）。
     *         坐标不在中国境内时**原样返回** —— 境外不加密，
     *         硬转会凭空多出几百米误差
     */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double[] delta = deltaDegrees(lng, lat);
        return new double[]{lng + delta[0], lat + delta[1]};
    }

    /**
     * GCJ-02 → WGS84。
     *
     * <p><b>只在一种地方用它：把「人在地图上点的坐标」存回库里之前。</b>
     * 别拿它去把库里已有数据的坐标系「还原」成 GCJ-02 —— 库里的本来就都是 WGS84。
     *
     * <p>算法与参考实现的 {@code gcj02towgs84} 一致：在<b>输入坐标</b>处算出修正量，
     * 再从输入坐标里减掉。它与正向转换共用 {@link #deltaDegrees}，
     * 所以是同一个模型的近似反解，往返（WGS84 → GCJ-02 → WGS84）误差在厘米级。
     * <b>不要「优化」成迭代求解</b>：迭代出来的值和参考实现不再逐位一致，
     * 一旦对不上就没人能判断是谁错了。
     *
     * @param lng 经度（GCJ-02）
     * @param lat 纬度（GCJ-02）
     * @return 长度为 2 的数组：{@code [经度, 纬度]}（WGS84）；
     *         坐标不在中国境内时原样返回（境外没有加密，也就不需要解回来）
     */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double[] delta = deltaDegrees(lng, lat);
        // 参考实现写成 lng * 2 - (lng + dLng)，等价于 lng - dLng；
        // 这里保留前者的形状，是为了和参考实现逐行对得上，改起来一眼能比对
        double magicLng = lng + delta[0];
        double magicLat = lat + delta[1];
        return new double[]{lng * 2 - magicLng, lat * 2 - magicLat};
    }

    /**
     * 该点处 GCJ-02 相对 WGS84 的偏移量（度）。
     *
     * <p>正向与反向转换都用它 —— 反向用的是<b>近似反解</b>（在加密坐标上算偏移再减掉），
     * 所以两个方向共用一段计算是成立的，也是参考实现的做法。
     *
     * @return {@code [经度偏移, 纬度偏移]}（度）
     */
    private static double[] deltaDegrees(double lng, double lat) {
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);

        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);

        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);

        return new double[]{dLng, dLat};
    }

    /**
     * 判断坐标是否在中国境外（含港澳台之外的区域）。
     *
     * <p>边界取自参考实现：经度 73.66 ~ 135.05、纬度 3.86 ~ 53.55。
     * <b>不要自己「修正」这个范围</b> —— 它虽然粗略（把境外的一小部分圈了进来），
     * 但换一组边界就会让结果与参考实现不一致，而对不上的那一刻你没法判断
     * 到底是谁错了。
     */
    private static boolean outOfChina(double lng, double lat) {
        return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
    }

    private static double transformLat(double lng, double lat) {
        double ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat
                + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double lng, double lat) {
        double ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat
                + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
