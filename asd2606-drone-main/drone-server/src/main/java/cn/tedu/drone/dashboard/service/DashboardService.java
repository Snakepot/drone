package cn.tedu.drone.dashboard.service;

import cn.tedu.drone.dashboard.config.ScreenProperties;
import cn.tedu.drone.dashboard.pojo.DashboardSummaryVO;

import java.util.List;

/**
 * 数字大屏的数据（见 {@code docs/数字大屏方案.md}）。
 *
 * <h2>这个 Service 最要紧的一条职责：按权限裁剪</h2>
 * 大屏上「这个人能看到哪些块」<b>完全由它决定</b>：
 * 没有权限的块返回 null，序列化时字段直接消失（见 {@link DashboardSummaryVO} 的类注释）。
 * 前端据此决定渲染哪几张卡片，它不需要知道任何角色规则 ——
 * 而绕过前端直接调接口，拿到的也还是同一个裁剪结果。
 *
 * <h2>为什么每个块单独判权限，而不是「先判角色」</h2>
 * 角色会变、权限点也会加，而「这个块需要哪个权限」是稳定的。
 * 按角色写 if 的话，将来加一个「只能看设备」的角色，就要回来改这里的判断；
 * 按权限写则一行都不用动。
 */
public interface DashboardService {

    /** 一屏要的所有数字；没权限的块为 null（不返回该 key） */
    DashboardSummaryVO summary();

    /**
     * 最近的告警，给大屏上的滚动流用。
     *
     * <p>和 {@code summary.alerts.recent} 的区别是**刷新频率**：
     * 告警流要 10 秒刷一次，而统计块 60 秒就够了 —— 分开两个接口，
     * 才能给它们不同的节奏（见设计文档 3.5）。
     *
     * <p>排序按**发生时间倒序**。⚠️ 与告警中心列表的排序（未处理在前、级别高的在前）**不同** ——
     * 那个顺序是给「我要去处理」的人用的，而大屏这条流回答的是「最近发生了什么」。
     *
     * @param limit 最多几条；会被收敛到 1~50
     */
    List<DashboardSummaryVO.RecentAlert> recentAlerts(int limit);

    /** 大屏自己的配置（刷新间隔、轮播），原样给前端 */
    ScreenProperties config();
}
