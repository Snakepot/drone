package cn.tedu.drone.task.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 创建任务前的禁飞区预检参数。
 *
 * <h2>为什么形状和 {@code TaskCreateParam} 的那几个字段一样</h2>
 * 因为前端要拿<b>同一批值</b>问两次：「这样建行不行」和「就这样建」。
 * 让它为了预检换个字段名（比如 takeoffLat 改成 lat），就会出现
 * 「预检用的位置和真正存下去的位置不是同一个」这种极难发现的错。
 * 两边字段同名同义、而且同为 GCJ-02，是这个设计里最重要的部分。
 */
@Data
@Accessors(chain = true)
@Schema(description = "创建任务前的禁飞区预检参数")
public class TaskZoneCheckParam implements Serializable {

    /** 引用的航线模板 id，可空 —— 不引用航线时只有起降点可判 */
    @Schema(description = "引用的航线模板 id，可空")
    private Long routeId;

    /** ⚠️ GCJ-02：和创建接口收的是同一种，别在这里换成 WGS84 */
    @Schema(description = "起降点经度（GCJ-02），可空")
    private Double takeoffLng;

    @Schema(description = "起降点纬度（GCJ-02），可空")
    private Double takeoffLat;
}
