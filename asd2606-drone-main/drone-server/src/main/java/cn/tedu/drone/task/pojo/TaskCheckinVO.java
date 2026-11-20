package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 到点打卡的视图对象。
 *
 * <p>坐标是 <b>GCJ-02</b>（给地图画的），与点位/航线/禁飞区同一条约定。
 *
 * <h2>它同时带着「目标点」和「打卡点」两组坐标</h2>
 * 因为这条记录最有用的信息是**两者的差**：离目标多远。
 * 地图上把两个点都画出来、连一条虚线，「他到底到没到」一眼就能判断 ——
 * 只给打卡点的话，看的人还得自己去翻航线。
 */
@Data
@Accessors(chain = true)
@Schema(description = "到点打卡（坐标已是 GCJ-02）")
public class TaskCheckinVO implements Serializable {

    /** 固定值 {@code gcj02}，告诉调用方这批坐标是哪种 */
    public static final String GCJ02 = "gcj02";

    @Schema(description = "坐标系，固定 gcj02")
    private String coordinateSystem = GCJ02;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "目标航点序号（从 1 开始）")
    private Integer seq;

    @Schema(description = "目标点位经度（GCJ-02）")
    private Double targetLng;

    @Schema(description = "目标点位纬度（GCJ-02）")
    private Double targetLat;

    @Schema(description = "打卡位置经度（GCJ-02）")
    private Double lng;

    @Schema(description = "打卡位置纬度（GCJ-02）")
    private Double lat;

    @Schema(description = "手机定位精度（米）")
    private BigDecimal accuracyM;

    @Schema(description = "离目标点位的距离（米，服务端算）")
    private Integer deviationM;

    /**
     * 偏差是不是大到「值得怀疑」。
     *
     * <p>阈值（200 米）由后端给，前端不再自己判一遍 —— 两边各写一个阈值，
     * 迟早出现「页面提示了、服务端不提示」这种对不上的情况。
     */
    @Schema(description = "偏差是否超过阈值（200 米）")
    private Boolean farAway;

    @Schema(description = "打卡那一刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;

    /**
     * 我们收到它的时刻。
     *
     * <p>它和 {@link #observedAt} 差得很多，说明这条是**离线补传**上来的 ——
     * 界面据此显示「补传」，否则会被误认为「他刚刚才到」。
     */
    @Schema(description = "我们收到的时刻")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportedAt;

    /** 是否离线补传（收到时刻明显晚于打卡时刻） */
    @Schema(description = "是否离线补传")
    private Boolean submittedLate;

    @Schema(description = "打卡人姓名")
    private String reporterName;

    @Schema(description = "飞手填的说明")
    private String remark;

    /**
     * 本次提交是不是「重复的」：库里已经有同样一条（任务+航点+时刻）。
     *
     * <p>离线补传时会出现这种情况 —— 客户端重发我们不报错，但要让它知道
     * 「这条早就在了」，否则队列会一直以为没传成功、反复重试。
     */
    @Schema(description = "本次提交是否为重复（库里已有同样的一条）")
    private Boolean duplicated;
}
