package cn.tedu.drone.dashboard.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数字大屏「一屏要的所有数字」。
 *
 * <h2>核心设计：没权限的块**不出现**，而不是给 0</h2>
 * 类上的 {@code @JsonInclude(NON_NULL)} 让 null 字段在序列化时**直接消失**。
 * 于是「这个人看得到什么」在报文里就是「有哪些 key」——
 * 前端据此决定渲染哪几张卡片，不需要知道任何角色规则。
 *
 * <p><b>为什么不能给 0</b>：`0` 与「看不到」在界面上是两回事。前者会让人以为
 * 「今天一架次都没飞」，后者才是「你没这个权限」。把这两种情况混成同一个数字，
 * 是最容易让人误判系统状态的一种做法。
 *
 * <h2>各块的口径写在每个字段上</h2>
 * 「设备在线率」这类指标，不同人算出来可能是不同的数（有没有心跳表、多久算在线），
 * 所以口径必须写在代码里，而不是靠口口相传。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "数字大屏总览（没权限的块不会出现）")
public class DashboardSummaryVO implements Serializable {

    /**
     * 这份数据是什么时候算出来的。
     *
     * <p>大屏上要显示「数据截至…」—— 屏上没有一个「刷新中」的转圈，
     * 看的人只能靠这个时间判断画面新不新。**它比任何一张卡片都重要**：
     * 一块看起来很正常的屏，如果数据是两小时前的，那它就是在骗人。
     */
    @Schema(description = "这份数据的生成时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;

    /** 任务总览 —— 需要 {@code task:list}（管理侧） */
    @Schema(description = "任务总览；没权限时不返回")
    private Tasks tasks;

    /** 我自己的任务 —— 需要 {@code task:execute}（飞手侧）。与 {@link #tasks} **口径不同** */
    @Schema(description = "我的任务；没权限时不返回")
    private MyTasks myTasks;

    /** 告警总览（全量）—— 需要 {@code alert:list} */
    @Schema(description = "告警总览；没权限时不返回")
    private Alerts alerts;

    /** 我任务上的告警 —— 需要 {@code task:execute} */
    @Schema(description = "我的告警；没权限时不返回")
    private MyAlerts myAlerts;

    /** 设备总览 —— 需要 {@code device:list} */
    @Schema(description = "设备总览；没权限时不返回")
    private Devices devices;

    /** 人员总览 —— 需要 {@code staff:list} */
    @Schema(description = "人员总览；没权限时不返回")
    private Staff staff;

    /** 地理资源计数 —— **登录即可看**（见类注释与设计文档 4.3 的理由） */
    @Schema(description = "点位/航线/禁飞区数量")
    private Geo geo;

    /** 设备数据的活跃度 —— 需要 {@code task:list} */
    @Schema(description = "证据活跃度；没权限时不返回")
    private Evidence evidence;

    // ------------------------------------------------------------------
    // 各块
    // ------------------------------------------------------------------

    /** 任务总览 */
    @Data
    @Accessors(chain = true)
    public static class Tasks implements Serializable {
        @Schema(description = "任务总数")
        private long total;
        /** key 是状态编码（pending/dispatched/…），只含**有数据**的状态 */
        @Schema(description = "按状态分布")
        private Map<String, Long> byStatus;
        @Schema(description = "今天新建的任务数")
        private long todayNew;
        /** 「执行中且已过计划结束时间」—— 与监控看板的 overdue 同一口径 */
        @Schema(description = "超期任务数（执行中 + 已过计划结束时间）")
        private long overdue;
    }

    /**
     * 我的任务。
     *
     * <p>刻意**不叫** `Tasks` 也不复用它的结构：两者是完全不同的 SQL
     * （一个全表、一个 `pilot_id = 自己`）。名字和字段都分开，才不会有人拿错。
     */
    @Data
    @Accessors(chain = true)
    public static class MyTasks implements Serializable {
        @Schema(description = "我的任务总数")
        private long total;
        @Schema(description = "待派发")
        private long pending;
        /** 已派发 + 执行中：合起来才是「手头正在办的」 */
        @Schema(description = "进行中（已派发 + 执行中）")
        private long doing;
        /** 已完成 + 已失败：终态都算「办完了」 */
        @Schema(description = "已结束（已完成 + 已失败）")
        private long done;
    }

    /** 告警总览 */
    @Data
    @Accessors(chain = true)
    public static class Alerts implements Serializable {
        /** 口径是「未处理」：pending + handling（与列表页、飞手端角标一致） */
        @Schema(description = "未处理告警数（pending + handling）")
        private long open;
        @Schema(description = "未处理告警按级别分布")
        private Map<String, Long> bySeverity;
        @Schema(description = "今天新产生的告警数（含已处理的）")
        private long todayNew;
        /** 最近几条，给大屏上的滚动流直接用（省一次请求） */
        @Schema(description = "最近若干条告警")
        private List<RecentAlert> recent;
    }

    /** 我任务上的告警 */
    @Data
    @Accessors(chain = true)
    public static class MyAlerts implements Serializable {
        @Schema(description = "未处理告警数（只算派给我的任务上的）")
        private long open;
    }

    /** 设备总览 */
    @Data
    @Accessors(chain = true)
    public static class Devices implements Serializable {
        @Schema(description = "设备总数")
        private long total;
        @Schema(description = "按工作状态分布")
        private Map<String, Long> byStatus;
        @Schema(description = "累计飞行架次（所有设备之和）")
        private long totalFlights;
        @Schema(description = "累计飞行时长（小时，所有设备之和）")
        private BigDecimal totalHours;
        /**
         * 在线率。
         *
         * <p>口径：**最近 {@code onlineWindowMinutes} 分钟内有证据的设备数 / 设备总数**。
         * 我们没有实时心跳表，「在线」只能这样定义 —— 口径写在这里，
         * 免得两个人算出两个数。
         */
        @Schema(description = "设备在线率（0~1）")
        private BigDecimal onlineRate;
        @Schema(description = "在线率的口径（分钟）：最近这么久内有数据就算在线")
        private int onlineWindowMinutes;
    }

    /** 人员总览 */
    @Data
    @Accessors(chain = true)
    public static class Staff implements Serializable {
        @Schema(description = "启用中的账号数")
        private long total;
        @Schema(description = "空闲")
        private long idle;
        @Schema(description = "作业中")
        private long busy;
        /** 已停飞/其他状态 */
        @Schema(description = "其他（已停飞等）")
        private long other;
    }

    /** 地理资源计数（登录即可看） */
    @Data
    @Accessors(chain = true)
    public static class Geo implements Serializable {
        @Schema(description = "点位数")
        private long points;
        @Schema(description = "航线数")
        private long routes;
        @Schema(description = "禁飞区数")
        private long zones;
    }

    /** 证据活跃度：设备到底有没有在发数据 */
    @Data
    @Accessors(chain = true)
    public static class Evidence implements Serializable {
        @Schema(description = "今天的证据条数")
        private long today;
        @Schema(description = "最近一小时的证据条数")
        private long lastHour;
    }

    /**
     * 大屏滚动流里的一条告警。
     *
     * <p>只挑大屏上要显示的那几样，**不复用 AlertVO** ——
     * 那个带着处理人、处置说明、附件等等，一屏放不下也用不着；
     * 而且它字段多，20 条乘下来报文会明显变大，而这块每 10 秒刷一次。
     */
    @Data
    @Accessors(chain = true)
    public static class RecentAlert implements Serializable {
        @Schema(description = "告警编码的中文名")
        private String label;
        @Schema(description = "级别编码 critical / warning / info")
        private String severity;
        @Schema(description = "设备编号")
        private String deviceCode;
        @Schema(description = "说明")
        private String message;
        @Schema(description = "发生的时刻")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime observedAt;
    }
}
