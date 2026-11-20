package cn.tedu.drone.zone.pojo;

/**
 * 待校验的一个点。
 *
 * <h2>为什么要带 {@code label} 和 {@code alt}</h2>
 * 校验结果要能说清「<b>是哪个点</b>撞上了哪个区域」，否则管理员看到的只有一句
 * 「命中禁飞区」，还得自己去猜是起降点还是第几个航点。
 * {@code label} 就是给这句话用的：{@code "起降点"}、{@code "第 3 个航点"}。
 *
 * <p>{@code alt} 是<b>作业高度</b>（米，相对起飞点），可空：
 * <ul>
 *   <li>限高区（区域有 max_alt）只拦「高度已知且超过它」的点</li>
 *   <li>高度未知（比如起降点、或航线中间插值出来的点）时**不判超高** ——
 *       拿不到高度就说人家超高，是编事实</li>
 * </ul>
 *
 * @param label 出错提示里怎么称呼这个点
 * @param lng   经度（<b>WGS84</b> —— 校验全程在库里那一套坐标上做）
 * @param lat   纬度（WGS84）
 * @param alt   作业高度（米），可空
 */
public record ZoneCheckPoint(String label, double lng, double lat, Double alt) {

    /** 只要位置、不管高度的点（起降点、航线中间插值出来的点） */
    public static ZoneCheckPoint of(String label, double lng, double lat) {
        return new ZoneCheckPoint(label, lng, lat, null);
    }
}
