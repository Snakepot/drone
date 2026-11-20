package cn.tedu.drone.route.dao;

import cn.tedu.drone.route.pojo.SurveyPointPO;
import cn.tedu.drone.route.pojo.SurveyPointQueryParam;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 点位数据访问层。
 *
 * <p>这里的坐标全是 <b>WGS84</b>（库里存的那种）。
 * GCJ-02 只在接口出入口出现，由 Service 转换 ——
 * <b>DAO 不认坐标系，也不做转换</b>，否则「一张表里有两种坐标」迟早会发生。
 */
public interface SurveyPointDao {

    /** 按条件分页查询 */
    List<SurveyPointPO> selectByCondition(SurveyPointQueryParam param);

    /** 按条件统计总数；条件必须与 {@link #selectByCondition} 完全一致 */
    long countByCondition(SurveyPointQueryParam param);

    /** 按主键查询；不存在返回 null */
    SurveyPointPO selectById(@Param("id") Long id);

    /** 按编号查询（新增/编辑时校验编号没被占用）；不存在返回 null */
    SurveyPointPO selectByCode(@Param("code") String code);

    /**
     * 按主键批量查询，供「航线上的点反查点位名称」用。
     *
     * <p>{@code ids} 为空时调用方应先return —— 空集合会拼出 {@code IN ()}，
     * 那是语法错误，不是「查不到」。
     */
    List<SurveyPointPO> selectByIds(@Param("ids") List<Long> ids);

    /** 新增 */
    int insert(SurveyPointPO point);

    /** 按非空字段更新 */
    int update(SurveyPointPO point);

    /** 删除 */
    int deleteById(@Param("id") Long id);
}
