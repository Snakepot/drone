package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 开始执行参数。
 *
 * <p>只有一个任务 ID —— 飞手点「开始执行」时不需要提供别的东西。
 *
 * <p>那为什么还要单独一个参数类，不用路径参数（{@code POST /task/{id}/start}）？
 * 因为本项目所有「改变状态」的接口统一是 {@code POST + JSON body}（create / dispatch /
 * withdraw 都是），保持一致比省一个类更值。前端调用方式也就统一了。
 *
 * <p><b>没有 pilotId</b>：理所当然 —— 谁在执行由登录态决定，
 * 前端传什么都不作数（和「我的任务」接口一个道理）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "开始执行参数")
public class TaskStartParam implements Serializable {

    @Schema(description = "任务 ID")
    private Long taskId;
}
