package cn.tedu.drone.zone.pojo;

import java.util.List;

/**
 * 一次校验要拿进去的东西：<b>一串点 + 一条折线</b>。
 *
 * <h2>为什么点和线要分开给</h2>
 * 它们问的是两个不同的问题（见设计文档 6.3.2）：
 * <ul>
 *   <li>{@code points}：「<b>这些位置在不在区内</b>」—— 起降点、航点，
 *       每个都带着自己的标签与作业高度，命中时能说出是哪一个</li>
 *   <li>{@code polyline}：「<b>这条线有没有从区内穿过去</b>」——
 *       起点终点都在区外、中间横穿的情况，只看点会漏掉。
 *       线是由点连成的，但判定时要先按间隔插值成密集点
 *       （{@link cn.tedu.drone.zone.geo.ZoneGeometry#samplePolyline}），
 *       插出来的点没有名字也没有高度</li>
 * </ul>
 * 合成一个列表也能跑，但「哪些点该报出名字」这件事就丢了 ——
 * 那正是命中提示里最有用的一半信息。
 *
 * @param points   必须逐个判定的点（可空）
 * @param polyline 要判「有没有穿过」的折线，格式 {@code [[lng,lat], ...]}（可空）
 */
public record ZoneCheckInput(List<ZoneCheckPoint> points, List<double[]> polyline) {

    /** 只判点、不判线（比如「派发前拿这条任务的计划点再看一眼」） */
    public static ZoneCheckInput ofPoints(List<ZoneCheckPoint> points) {
        return new ZoneCheckInput(points, List.of());
    }
}
