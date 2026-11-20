package cn.tedu.drone.task.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 「一条任务上还挂着多少条未处理告警」—— 飞手端任务卡片角标用。
 *
 * <h2>为什么不直接用 AlertVO 数一数</h2>
 * 角标只需要两个数，而拉整页告警回来数，等于为了显示一个数字把消息正文、
 * 处置记录全查一遍。更关键的是**条数不能受分页影响**：
 * 一页只查 15 条，飞手看到「15 条」而实际有 40 条，那这个角标就是在骗人。
 *
 * <h2>口径：只数未处理的</h2>
 * {@code pending} + {@code handling}。角标回答的是「<b>有什么要管的</b>」，
 * 处理完就该消失 —— 和告警页「未处理的排在前面」是同一个思路（设计文档 10.1）。
 * 所以要「这次任务一共发生过几条」的话，那不是这个对象的事。
 */
@Data
@Accessors(chain = true)
public class TaskAlertCount implements Serializable {

    /** 任务 id */
    private Long taskId;

    /** 该任务上未处理的告警总数 */
    private Integer total;

    /** 其中 `critical` 的条数 —— 界面上用它决定角标是红的还是黄的 */
    private Integer criticalCount;
}
