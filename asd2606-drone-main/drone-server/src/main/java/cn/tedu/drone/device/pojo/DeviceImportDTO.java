package cn.tedu.drone.device.pojo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.format.DateTimeFormat;
import lombok.Data;

import java.time.LocalDate;

/**
 * 设备导入的 Excel 行映射。
 *
 * <p>{@code @ExcelProperty} 的值就是表头文字，必须与导入模版严格一致。
 *
 * <p><b>注意：这个类不要加 {@code @Accessors(chain = true)}。</b>
 * 链式 setter 返回 {@code this} 而不是 {@code void}，EasyExcel 反射赋值时
 * 按「set 开头且返回 void」找方法会找不到，结果是每行读进来所有字段都是 null ——
 * 表现为「导入时提示编号不能为空」，却怎么查都查不出问题。
 * 员工导入那边就踩过这个坑。
 */
@Data
public class DeviceImportDTO {

    @ExcelProperty("设备编号")
    private String code;

    @ExcelProperty("设备名称")
    private String name;

    @ExcelProperty("型号")
    private String model;

    @ExcelProperty("类型")
    private String type;

    @ExcelProperty("采购日期")
    @DateTimeFormat("yyyy-MM-dd")
    private LocalDate purchaseDate;

    @ExcelProperty("当前位置")
    private String location;

    @ExcelProperty("状态")
    private String status;

    @ExcelProperty("累计飞行次数")
    private String flightCount;

    @ExcelProperty("累计飞行时长")
    private String flightHours;
}
