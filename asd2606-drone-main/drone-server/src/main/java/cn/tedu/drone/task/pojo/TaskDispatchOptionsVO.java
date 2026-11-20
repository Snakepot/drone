package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 派发弹窗要用的两组下拉数据。
 *
 * <p>打包成一个接口返回，而不是让前端分别调员工接口和设备接口：
 * <ul>
 *   <li>少一次往返，弹窗打开就是完整的</li>
 *   <li>更重要的是权限：这个接口只要 {@code task:dispatch}，
 *       不会逼着「能派发」的角色去获得「能看员工列表」「能看设备列表」</li>
 * </ul>
 *
 * <p>两组数据都只包含<b>当前可派</b>的：飞手要启用且拥有飞手角色，
 * 设备要处于待命状态。前端因此不需要自己做过滤，也不会出现
 * 「下拉里选了个人，一提交被告知他账号被禁用」这种白跑一趟的情况。
 */
@Data
@Accessors(chain = true)
@Schema(description = "派发下拉数据")
public class TaskDispatchOptionsVO implements Serializable {

    @Schema(description = "可派发的飞手")
    private List<TaskDispatchOptionVO> pilots;

    @Schema(description = "可派发的设备")
    private List<TaskDispatchOptionVO> drones;
}
