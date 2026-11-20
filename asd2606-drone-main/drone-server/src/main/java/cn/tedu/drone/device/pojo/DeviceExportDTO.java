package cn.tedu.drone.device.pojo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 设备导出的 Excel 列映射。
 *
 * <p>与导入不同，导出这个类是可以加 {@code @Accessors(chain = true)} 的 ——
 * 写 Excel 时 EasyExcel 读的是 getter，链式 setter 不影响读。
 * 之所以统一不加，是为了避免以后有人 copy 到导入 DTO 上又踩一次坑。
 */
@Data
@Accessors(chain = true)
public class DeviceExportDTO {

    @ExcelProperty("设备编号")
    private String code;

    @ExcelProperty("设备名称")
    private String name;

    @ExcelProperty("型号")
    private String model;

    @ExcelProperty("类型")
    private String typeText;

    @ExcelProperty("采购日期")
    private String purchaseDate;

    @ExcelProperty("当前位置")
    private String location;

    @ExcelProperty("状态")
    private String statusText;

    @ExcelProperty("累计飞行次数")
    private String flightCount;

    @ExcelProperty("累计飞行时长")
    private String flightHours;

    @ExcelProperty("创建时间")
    private String createTime;
}
