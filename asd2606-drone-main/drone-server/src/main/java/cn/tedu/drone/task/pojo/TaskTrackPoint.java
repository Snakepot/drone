package cn.tedu.drone.task.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 轨迹上的一个点。
 *
 * <h2>它既是 DAO 的查询结果，也是接口出口</h2>
 * 按本项目的规矩「Controller 出口一律是 VO」，这里本可以拆成两个类。
 * 但它的字段**全都是展示用的**（坐标 + 时刻），没有任何表内部结构可泄漏 ——
 * 拆两个只是把同样三个字段写两遍，而写两遍的地方迟早有一处漏改。
 * <b>判断依据是「有没有内部信息需要挡住」，不是「名字后缀对不对」</b>。
 *
 * <h2>这里的坐标是 WGS84 还是 GCJ-02？</h2>
 * 这个类在链路中<b>会经历两种坐标</b>：
 * <ul>
 *   <li>刚从数据库查出来时：<b>WGS84</b>（设备报的原始值）</li>
 *   <li>放进 {@link TaskTrackVO} 交给前端时：<b>已经转成 GCJ-02</b></li>
 * </ul>
 * 所以**别拿它和库里的值直接比**。转换只在 {@code TaskTrackService} 里做一次，
 * 出口的那个 VO 上写着 {@code coordinateSystem}，一眼能看出是哪种。
 */
@Data
@Accessors(chain = true)
public class TaskTrackPoint implements Serializable {

    /** 经度 */
    private Double lng;

    /** 纬度 */
    private Double lat;

    /**
     * 设备报这个点的时刻（用它给起点/终点标注时间）。
     *
     * <p><b>必须显式写 {@code @JsonFormat}</b>：`application.yml` 里那个
     * `spring.jackson.date-format` 只对 `java.util.Date` 生效，
     * 对 `LocalDateTime` 不管用 —— 不加这个注解，序列化出来是带 `T` 的 ISO 串
     * （`2027-08-26T09:30:00`），而项目里所有别的接口都是空格分隔的那种。
     * 前端按 `slice(0, 16)` 取时间，就会在页面上显示出一个多余的 `T`。
     * 这一条是写轨迹测试时发现的（断言直接对上了原始字符串）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observedAt;
}
