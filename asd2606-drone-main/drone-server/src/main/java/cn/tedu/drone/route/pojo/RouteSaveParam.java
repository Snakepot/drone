package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 航线保存参数。
 *
 * <h2>⚠️ 这里传的坐标是 GCJ-02</h2>
 * 与点位那边完全一致（见 {@link SurveyPointSaveParam} 的说明）：
 * 读出来什么坐标系，写回去就什么坐标系。
 *
 * <h2>航点的顺序 = 数组顺序</h2>
 * 参数里<b>没有 seq</b>。前端拿数组顺序表达先后（拖拽排序本来就产出数组），
 * 服务端按顺序编号 1、2、3…… 让前端传 seq 就得处理「重号、跳号、乱序」，
 * 而数组自己已经说清楚了。这也是「参数里不放不该有的字段」的又一次应用。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航线保存参数（坐标须为 GCJ-02）")
public class RouteSaveParam implements Serializable {

    /** 编辑时必填；为空表示新增 */
    @Schema(description = "主键，编辑时必填")
    private Long id;

    @Schema(description = "航线名称，必填")
    private String name;

    @Schema(description = "说明")
    private String description;

    /** 至少 2 个点（一个点连不成线），顺序即飞行顺序 */
    @Schema(description = "航点，按飞行顺序排列，至少 2 个")
    private List<RoutePointSaveParam> points;
}
