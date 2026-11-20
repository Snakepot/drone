package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 点位新增 / 编辑参数。
 *
 * <h2>⚠️ 这里传的 lng / lat 是 GCJ-02</h2>
 * 人是站在高德的地图上点的，拿到什么坐标系就传什么坐标系 ——
 * 与读接口（{@link SurveyPointVO}）声明的一致，两边对齐才不会错。
 * 「GCJ-02 → WGS84」的换算在 Service 里做，<b>入库的永远是 WGS84</b>。
 *
 * <p>不额外加一个 {@code coordinateSystem} 字段：目前唯一的来源就是地图点击，
 * 加一个永远只会填同一个值的字段，只会让人以为「还能传别的」。
 * 将来真要做「Excel 批量导入点位」（客户给的通常是 GPS 原始坐标 WGS84），
 * 那个入口自己声明坐标系，不必让这个接口跟着模糊。
 */
@Data
@Accessors(chain = true)
@Schema(description = "点位保存参数（坐标须为 GCJ-02）")
public class SurveyPointSaveParam implements Serializable {

    /** 编辑时必填；为空表示新增 */
    @Schema(description = "主键，编辑时必填")
    private Long id;

    @Schema(description = "点位编号（客户给的，可空；填了就不能和别人重复）")
    private String code;

    @Schema(description = "点位名称，必填")
    private String name;

    @Schema(description = "经度（GCJ-02），必填")
    private Double lng;

    @Schema(description = "纬度（GCJ-02），必填")
    private Double lat;

    @Schema(description = "建议作业高度（米，相对起飞点），可空")
    private Double alt;

    @Schema(description = "类型 survey / takeoff / dock / other，不传按 survey")
    private String category;

    @Schema(description = "备注")
    private String remark;
}
