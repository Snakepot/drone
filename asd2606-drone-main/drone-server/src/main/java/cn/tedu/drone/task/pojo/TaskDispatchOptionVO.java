package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 派发下拉的一个选项。
 *
 * <p>只有 id 和一句展示文案，因为它的唯一用途就是填进 {@code <select>}。
 *
 * <h2>为什么不直接把 UserVO / DeviceVO 返给前端</h2>
 * 那两个对象带着邮箱、年龄、权限、飞行时长等一堆字段。派发接口的权限码是
 * {@code task:dispatch}，而 {@code UserVO} / {@code DeviceVO} 的背后分别是
 * {@code staff:list} 和 {@code device:list} —— 直接复用等于让一个只有派发权限的账号
 * 顺手看到了完整的花名册。<b>接口返回什么，要按这个接口自己的权限来定，不能蹭别人的。</b>
 *
 * <p>文案（label）在服务端拼好，前端拿到就渲染，不用自己拼字符串 ——
 * 「张伟 · 13800001001」这种格式只在一个地方定义，不会前后端各写一遍然后慢慢跑偏。
 */
@Data
@Accessors(chain = true)
@Schema(description = "派发下拉选项")
public class TaskDispatchOptionVO implements Serializable {

    @Schema(description = "选项对应的 ID（飞手 ID 或设备 ID）")
    private Long id;

    @Schema(description = "展示文案，如「张伟 · 13800001001」「UAV-001 · 苍鹰一号」")
    private String label;
}
