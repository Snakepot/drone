package cn.tedu.drone.common;

/**
 * 坐标输入的校验 —— 点位、航点、起降点共用同一套判据。
 *
 * <h2>为什么值得单独一个类</h2>
 * 这三处的判据一模一样（要有值、在地球范围内、不能是 0,0），
 * 但它们的出错提示要能指出<b>是哪个东西错了</b>（「第 3 个航点」而不是「坐标错了」）。
 * 所以把判据收在这里、把称呼从调用方传进来 ——
 * 三份各写一遍的结果，往往是三处的边界值慢慢长得不一样。
 *
 * <h2>它不做的两件事</h2>
 * <ul>
 *   <li><b>不做坐标系转换</b>。校验只关心「数字本身合不合理」，
 *       与它是 WGS84 还是 GCJ-02 无关。转换由 {@link CoordinateUtil} 负责，
 *       调用顺序是先校验、后转换</li>
 *   <li><b>不做合规判断</b>（能不能飞这个高度）。那是禁飞区校验的事，
 *       见 {@code docs/地图接入方案.md} 第三步</li>
 * </ul>
 */
public final class GeoInput {

    /**
     * 作业高度的合理上限（米，相对起飞点）。
     *
     * <p>取 1000：它挡的是「把米填成了千米」这类输入错误，<b>不是</b>合规限制。
     * 现在就写死一个「合法高度」反而会误导 —— 实际能飞多高是空域规定的事。
     */
    public static final double ALT_MAX = 1000.0;

    private GeoInput() {
    }

    /**
     * 校验经纬度必须存在且合理。
     *
     * @param what 出错时怎么称呼这个东西，如 {@code "第 3 个航点"}、{@code "起降点"}
     * @return {@code [经度, 纬度]}，原样返回（<b>没有</b>做坐标系转换）
     * @throws BusinessException 缺值、超出地球范围、或两个都是 0
     */
    public static double[] require(Double lng, Double lat, String what) {
        if (lng == null || lat == null) {
            throw new BusinessException(what + "缺少经纬度，请在地图上选一个位置");
        }
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            throw new BusinessException(what + "的经纬度超出合理范围：" + lng + ", " + lat);
        }
        // 0,0 在几内亚湾。它几乎不可能是真的业务位置 ——
        // 它是「地图还没加载好、坐标当成了 0」时的典型表现。
        // 这一类坏数据不会自己报错，只会安静地留在库里，所以宁可在这里挡掉
        if (lng == 0 && lat == 0) {
            throw new BusinessException(what + "的经纬度都是 0，请确认地图已加载、并真的点了位置");
        }
        return new double[]{lng, lat};
    }

    /**
     * 校验作业高度（可空 —— 不填表示「按默认高度」）。
     *
     * @param what 出错时怎么称呼这个东西
     */
    public static void validateAlt(Double alt, String what) {
        if (alt == null) {
            return;
        }
        if (alt < 0 || alt > ALT_MAX) {
            throw new BusinessException(what + "的作业高度应在 0 ~ " + (int) ALT_MAX + " 米之间");
        }
    }
}
