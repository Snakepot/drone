package cn.tedu.drone.device.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 设备新增 / 编辑参数。
 *
 * <p>与员工一致，靠是否存在 {@code id} 区分新增与编辑：
 * <ul>
 *   <li>不传 id → 新增</li>
 *   <li>传 id → 编辑</li>
 * </ul>
 *
 * <p><b>编辑时设备编号不可修改</b>：编号是设备档案的业务主键（机队里靠它认设备），
 * 而且历史任务、飞行记录都可能引用它，改了会造成关联错乱。
 * 参数里保留 code 字段只是为了让新增能复用同一个对象。
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备新增/编辑参数")
public class DeviceSaveParam implements Serializable {

    @Schema(description = "主键，新增时不传")
    private Long id;

    @Schema(description = "设备编号，新增时必填；编辑时会被忽略")
    private String code;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "类型，如 multi-rotor / fixed-wing")
    private String type;

    @Schema(description = "采购日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate purchaseDate;

    @Schema(description = "当前位置")
    private String location;

    @Schema(description = "工作状态：idle / flying / maintenance")
    private String status;

    @Schema(description = "累计飞行次数")
    private String flightCount;

    @Schema(description = "累计飞行时长（小时）")
    private String flightHours;
}
