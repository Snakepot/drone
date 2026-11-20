package cn.tedu.drone.device.dao;

import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import org.apache.ibatis.annotations.Param;

import java.util.Map;
import java.util.List;

/**
 * 设备数据访问层
 */
public interface DeviceDao {

    /**
     * 按条件分页查询设备。
     *
     * @param param 查询条件（含分页参数）
     * @return 当前页数据
     */
    List<DevicePO> selectByCondition(DeviceQueryParam param);

    /**
     * 按条件统计总数，分页组件靠它算总页数。
     * <p>条件必须与 {@link #selectByCondition} 完全一致，否则会出现
     * 「总条数说有 10 条、翻到第 2 页却空了」这种对不上的情况。
     */
    long countByCondition(DeviceQueryParam param);

    /** 按工作状态分组计数，返回 {@code [{status, cnt}, ...]} —— 大屏的设备分布图 */
    List<Map<String, Object>> countGroupByStatus();

    /**
     * 累计架次与时长（所有设备之和）。
     *
     * <p>返回 {@code {deviceCount, flights, hours}} 三个数。
     * 这两列在表里是 varchar（历史原因），所以 SQL 里要显式 CAST ——
     * 不转的话 SUM 出来是字符串相加，结果会静默地离谱。
     */
    Map<String, Object> sumFlightStats();

    /**
     * 按主键查询设备
     */
    DevicePO selectById(@Param("id") Long id);

    /**
     * 按编号查询设备。
     * <p>新增时校验编号是否已被占用；编辑时也用它确认编号没被改成别人的。
     */
    DevicePO selectByCode(@Param("code") String code);

    /**
     * 查询全部设备编号，导入时用于唯一性校验。
     * <p>只取一列字符串，比把整张表读进内存小得多。
     */
    List<String> selectCodes();

    /**
     * 新增设备
     */
    int insert(DevicePO device);

    /**
     * 批量新增设备，导入时使用
     *
     * @param devices 待插入的设备，调用方需保证非空
     */
    int insertBatch(List<DevicePO> devices);

    /**
     * 按传入的非空字段更新设备。
     * <p>刻意不更新 code —— 编号是设备档案的业务主键，历史记录可能引用它，改了会关联错乱。
     *
     * @param device 至少包含 id
     */
    int update(DevicePO device);

    /**
     * 更新工作状态，但只在它<b>当前确实是</b> {@code expectStatus} 时才改（比较并交换）。
     *
     * <p>把判断和写入合成一条 SQL，中间不会被别的请求插进来。
     * 分成「先查再改」两步就会出现缝隙：查出是 idle，改之前别人把它设成了 maintenance，
     * 然后你的写就把人工设备状态覆盖掉了。
     *
     * <p>返回影响行数：0 表示当前状态与预期不符，没改。
     */
    int updateStatusIfMatch(@Param("id") Long id,
                            @Param("expectStatus") String expectStatus,
                            @Param("newStatus") String newStatus);
}
