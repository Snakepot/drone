package cn.tedu.drone.analytics.pojo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 覆盖率导出里「点位明细」那一张表的行。
 *
 * <p>为什么单独一个 DTO 而不是直接导出 {@code CoverageVO.PointCoverage}：
 * 表头的中文、列的取舍是**给客户看的**（比如「经纬度保留几位」「要不要编号」），
 * 跟接口报文的结构不是一回事。分开之后改表不用动接口（反之亦然）。
 */
@Data
@Accessors(chain = true)
public class CoveragePointExcel {

    @ExcelProperty("点位编号")
    private String code;

    @ExcelProperty("点位名称")
    private String name;

    @ExcelProperty("经度")
    private Double lng;

    @ExcelProperty("纬度")
    private Double lat;

    @ExcelProperty("被计划次数")
    private Integer plannedTimes;

    @ExcelProperty("到过次数")
    private Integer visitedTimes;

    @ExcelProperty("本月是否覆盖")
    private String covered;
}
