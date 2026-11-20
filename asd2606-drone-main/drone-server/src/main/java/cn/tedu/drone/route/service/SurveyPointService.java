package cn.tedu.drone.route.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.route.pojo.SurveyPointQueryParam;
import cn.tedu.drone.route.pojo.SurveyPointSaveParam;
import cn.tedu.drone.route.pojo.SurveyPointVO;

import java.util.List;
import java.util.Map;

/**
 * 点位库。
 *
 * <h2>一个必须记住的约定：进出都是 GCJ-02，库里是 WGS84</h2>
 * 这个接口的<b>每一项</b>都遵守它，因为它最容易在这里出问题：
 * <ul>
 *   <li>返回的 {@link SurveyPointVO} 坐标已转成 GCJ-02，可直接交给地图</li>
 *   <li>{@link SurveyPointSaveParam} 传进来的坐标<b>也按 GCJ-02 解释</b>，
 *       入库前转回 WGS84</li>
 * </ul>
 * 两边一致，编辑表单「读出来 → 改一改 → 存回去」才不会让点位漂移。
 */
public interface SurveyPointService {

    /** 按条件分页查询，坐标已是 GCJ-02 */
    PageResult<SurveyPointVO> pagePoints(SurveyPointQueryParam param);

    /** 点位详情，坐标已是 GCJ-02；不存在时抛业务异常 */
    SurveyPointVO getPoint(Long id);

    /** 新增，返回落库后的详情（带回自增 id 与创建时间） */
    SurveyPointVO addPoint(SurveyPointSaveParam param);

    /** 编辑 */
    void updatePoint(SurveyPointSaveParam param);

    /**
     * 删除。
     *
     * <p>不会去检查「有没有航线在用这个点」—— 航线上存的是复制过去的坐标，
     * 删点位不影响任何已有航线（见 {@code SurveyPointMapper.xml} 的说明）。
     */
    void deletePoint(Long id);

    /**
     * 一批点位 id → 点位名称，给航线详情显示「这个航点来自哪个点位」用。
     *
     * <p>返回 Map 而不是 List：调用方拿 {@code point_id} 直接取名字，
     * 不用再在列表里找一遍。查不到的 id 不会出现在 Map 里（由调用方按「没有来源」处理）。
     *
     * @param ids 可为空；为空时直接返回空 Map，不查库
     */
    Map<Long, String> namesOf(List<Long> ids);
}
