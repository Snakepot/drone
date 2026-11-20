package cn.tedu.drone.zone.pojo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 一条命中记录：<b>哪个点（或航线）撞上了哪个区域，为什么</b>。
 *
 * <h2>为什么要给出这么多字段，而不是一句 message</h2>
 * 这句 message 要出现在三个地方，而且三处的用法不同：
 * <ul>
 *   <li>建任务/派发的确认弹窗：要列出<b>区域名</b>，让人知道撞的是哪一块</li>
 *   <li>流转流水的留痕：要写清「<b>当时我们提示了什么、他为什么还是建了</b>」</li>
 *   <li>将来的处理：可能要按区域 id 去统计「哪个区最常被撞」</li>
 * </ul>
 * 所以结构化字段 + 一句拼好的中文说明都给 —— 拼好的那句是给人和流水用的，
 * 结构化字段是给程序用的。<b>让前端自己拼那句话</b>则会出现三处措辞不一致。
 */
@Data
@Accessors(chain = true)
@Schema(description = "禁飞区命中记录")
public class ZoneHitVO implements Serializable {

    @Schema(description = "命中的区域 id")
    private Long zoneId;

    @Schema(description = "命中的区域名称")
    private String zoneName;

    @Schema(description = "区域来源编码 official / custom")
    private String source;

    @Schema(description = "区域形状编码 polygon / circle")
    private String shape;

    @Schema(description = "是不是完全禁飞（没有限高）")
    private Boolean forbidden;

    @Schema(description = "区域内限高（米），null 表示完全禁飞")
    private Integer maxAlt;

    /** 命中点是什么：起降点 / 第 3 个航点 / 航线途经点 */
    @Schema(description = "命中点说明，如「起降点」「第 3 个航点」「航线途经点」")
    private String pointLabel;

    @Schema(description = "命中点经度（GCJ-02，给地图标点用）")
    private Double lng;

    @Schema(description = "命中点纬度（GCJ-02）")
    private Double lat;

    /** 是不是「因为超高」才命中；完全禁飞的区域里恒为 false */
    @Schema(description = "是否因超过区域限高而命中")
    private Boolean overAlt;

    /** 拼好的一句话，直接显示即可 */
    @Schema(description = "命中说明（中文，可直接显示）")
    private String message;
}
