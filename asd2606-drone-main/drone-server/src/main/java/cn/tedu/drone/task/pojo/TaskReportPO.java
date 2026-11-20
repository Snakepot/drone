package cn.tedu.drone.task.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务报告 —— 对应 {@code task_report} 表。
 *
 * <h2>它是交付物，不是证据</h2>
 * 证据（{@code task_evidence}）回答「这件事发生过吗」，是旁人可核对的旁证、
 * <b>不可改</b>；报告回答「我们做完了什么」，是人对结果的总结与承诺、<b>允许改</b>。
 * 两者不可变性不同，所以是两张表 —— 把声明混进证据里，会同时毁掉两边的价值。
 *
 * <h2>为什么要 version / is_latest</h2>
 * 报告能被修订（飞手笔误、管理员要求补充、客户提异议），但**改过必须看得出来**。
 * 原表上有 {@code uk_task_id}，等于「一条任务一份报告、不能改」；
 * 去掉唯一键后同一条任务可以有多份，靠 {@code is_latest} 标出当前版本 ——
 * 所以<b>所有按任务查报告的地方都必须带上 {@code is_latest = 1}</b>
 * （DAO 的方法名里就带 {@code Latest}，免得漏）。
 */
@Data
@Accessors(chain = true)
public class TaskReportPO implements Serializable {

    private Long id;

    private Long taskId;

    /** 版本号，从 1 开始；每次修订 +1 */
    private Integer version;

    /** 1 当前版本 / 0 历史版本 */
    private Integer isLatest;

    /** 结论：success 成功 / partial 部分完成 / failed 失败 */
    private String result;

    private String summary;

    /** 遇到的问题与处置方式 */
    private String problemLog;

    private BigDecimal flightHours;

    private Integer flightCount;

    private Long reporterId;

    private String reporterName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
