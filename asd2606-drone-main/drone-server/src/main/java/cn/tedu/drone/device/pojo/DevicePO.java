package cn.tedu.drone.device.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备持久化对象，对应 drone 表
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备持久化对象")
public class DevicePO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设备编号")
    private String code;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "采购日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate purchaseDate;

    @Schema(description = "当前位置")
    private String location;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "累计飞行次数")
    private String flightCount;

    @Schema(description = "累计飞行时长")
    private String flightHours;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
