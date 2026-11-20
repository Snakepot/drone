package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务视图对象，返回给前端。
 *
 * <h2>为什么不直接把 {@link TaskPO} 返给前端</h2>
 * PO 是「表长什么样」，VO 是「接口对外承诺什么形状」。两者绑在一起会出问题：
 * <ul>
 *   <li>表里加一个只有内部才用的字段（比如软删除标记、内部备注），
 *       接口会跟着一起吐出去，等于被动泄字段</li>
 *   <li>表字段改名，前端跟着崩 —— 数据库的一点重构会穿透到页面</li>
 *   <li>前端要的展示字段（状态中文名、飞手电话、是否超期）表里根本没有，
 *       硬凑就得往表里加冗余列</li>
 * </ul>
 * 所以 Controller 出口一律是 VO，PO 只在 Service 内部和 DAO 之间流转。
 *
 * <p>当前这个 VO 和 PO 字段恰好一一对应，看起来是重复代码 ——
 * 但这份重复是刻意的：加展示字段时改 VO、动表结构时改 PO，两边互不牵动。
 * 需要转换的地方只有 Service 里一个 {@code toVO} 方法。
 */
@Data
@Accessors(chain = true)
@Schema(description = "任务视图对象")
public class TaskVO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "任务编号，如 TK-20260918-001")
    private String taskNo;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "优先级 P0 紧急 / P1 高 / P2 中 / P3 低")
    private String priority;

    @Schema(description = "状态：pending/dispatched/executing/finished/failed/cancelled")
    private String status;

    @Schema(description = "计划开始")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartTime;

    @Schema(description = "计划结束")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndTime;

    @Schema(description = "实际开始，未开工为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartTime;

    @Schema(description = "实际结束，未完工为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndTime;

    @Schema(description = "指派的设备 ID，未派发为空")
    private Long droneId;

    @Schema(description = "设备编号，未派发为空")
    private String droneCode;

    @Schema(description = "指派的飞手 ID，未派发为空")
    private Long pilotId;

    @Schema(description = "飞手姓名，未派发为空")
    private String pilotName;

    @Schema(description = "任务内容 / 航线描述")
    private String routeInfo;

    /**
     * 引用的航线模板 id，可空。
     *
     * <p>只给 id、不给航线名称：任务列表要显示「用的是哪条线」的话，
     * 用 {@link #routeInfo} 那句话就够了（选航线时后端会把它填成
     * 「东线巡检 · 6 个点 · 3.2 公里」）。真要看航线本体的形状，
     * 走任务详情里的 {@code plannedRoute}。
     */
    @Schema(description = "引用的航线模板 id，可空")
    private Long routeId;

    /**
     * 起降点，⚠️ <b>坐标是 GCJ-02</b>（不是库里那个 WGS84）。
     *
     * <p>为什么这个 VO 不跟库保持一致：<b>它的每一个使用者都是地图</b> ——
     * 管理端要在详情图上标一个「起降点」，飞手端将来要用它做一键导航
     * （导航 SDK 收的正是 GCJ-02）。给 WGS84 的话，两处都得各自再转一次，
     * 而前端没有测试能钉住转换结果，偏了 500 米只能靠肉眼发现。
     *
     * <p>与 {@code TaskTrackVO} / {@code RouteVO} 同一个思路，只是那两个
     * 还会在报文里带一个 {@code coordinateSystem} 声明（它们成批给几何数据）。
     * 这里只有两个数、且整个任务接口只有这一处坐标，就用这段说明代替 ——
     * <b>转换发生的位置也只有一个：{@code TaskServiceImpl#toTaskVO}</b>。
     */
    @Schema(description = "起降点经度（GCJ-02）")
    private Double takeoffLng;

    @Schema(description = "起降点纬度（GCJ-02）")
    private Double takeoffLat;

    @Schema(description = "起降点名称")
    private String takeoffName;

    @Schema(description = "补充说明")
    private String description;

    @Schema(description = "创建人 ID")
    private Long createBy;

    @Schema(description = "创建人姓名")
    private String createByName;

    /**
     * 执行依据：none / declared / confirmed / stale。
     *
     * <p>前端要能一眼看出「执行中」这一步<b>是靠什么支撑的</b>，
     * 所以这个字段必须出现在列表接口里 —— 否则「已派发」和「执行中」
     * 在页面上看起来就差一个字，管理员判断不了该不该信。
     *
     * <p>这里只给原始编码，中文名交给前端字典 ——
     * 和 status 的处理保持一致，前端一处映射共用。
     */
    @Schema(description = "执行依据：none 无 / declared 仅飞手声明 / confirmed 已证实 / stale 声明已过期")
    private String evidenceState;

    @Schema(description = "最新一条支撑证据的类型：telemetry / flight_log / witness / manual")
    private String evidenceType;

    @Schema(description = "最新一条证据的时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime evidenceAt;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 这条任务上还有多少条<b>未处理</b>的告警 —— 飞手端任务卡片的角标。
     *
     * <p><b>只有「我的任务」接口会填这两个字段</b>，管理端的任务总表留 null。
     * 为什么不做成两个 VO：字段是加在既有 VO 上的、可空，不会影响管理端；
     * 而拆一个新 VO 意味着把二十来个字段抄一遍，之后每加一个任务字段都要抄两处。
     *
     * <p>口径是 <b>未处理</b>（{@code pending} + {@code handling}）：角标回答
     * 「有什么要管的」，处理完就该消失。要「这次任务一共发生过几条」的话，
     * 展开后的告警清单里有完整的（含已处理与误报）。
     */
    @Schema(description = "未处理告警数（仅飞手端「我的任务」返回）")
    private Integer alertCount;

    /**
     * 其中 {@code critical} 的条数 —— 界面靠它决定角标是红的还是黄的。
     *
     * <p>为什么不让前端自己按 severity 过滤：前端要过滤就得把整页告警都拉回来，
     * 而角标的意义恰恰是「不点进去也能看出严重程度」。
     */
    @Schema(description = "未处理告警中 critical 的条数（仅飞手端「我的任务」返回）")
    private Integer criticalAlertCount;
}
