package cn.tedu.drone.route.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 航线（模板），对应 {@code route} 表。
 *
 * <p>{@code pointCount} 与 {@code distanceM} 是<b>冗余列</b>：由它的航点算出来存着，
 * 让列表页显示「6 个点 / 3.2 公里」时不用把每条航线的点都查一遍（那正是 N+1）。
 * 冗余的代价是「改了点就得记得更新它」—— 所以更新只在一处发生：
 * {@code RouteServiceImpl#savePoints}，那里改点与改统计是同一件事。
 */
@Data
@Accessors(chain = true)
@Schema(description = "航线")
public class RoutePO implements Serializable {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "航线名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    /** 冗余：航点数（由航点算出来） */
    @Schema(description = "航点数")
    private Integer pointCount;

    /** 冗余：总航程（米，由相邻航点累加） */
    @Schema(description = "总航程（米）")
    private Integer distanceM;

    @Schema(description = "创建人 ID")
    private Long createBy;

    @Schema(description = "创建人姓名")
    private String createByName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
