package cn.tedu.drone.zone.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 一个坐标点（经纬度）。
 *
 * <p>禁飞区的顶点与圆心用它。刻意不复用点位库那个 {@code SurveyPointVO} ——
 * 那个带着编号、类型、创建人一大堆字段，到这里全是噪音；
 * 而且它的字段是「点位库的业务字段」，将来点位库加字段，
 * 这个纯几何的类型不该跟着变。
 */
@Data
@Accessors(chain = true)
@Schema(description = "坐标点")
public class ZonePointVO implements Serializable {

    @Schema(description = "经度（GCJ-02）")
    private Double lng;

    @Schema(description = "纬度（GCJ-02）")
    private Double lat;
}
