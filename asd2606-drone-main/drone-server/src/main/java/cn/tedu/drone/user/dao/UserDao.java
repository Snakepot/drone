package cn.tedu.drone.user.dao;

import cn.tedu.drone.user.pojo.UserExportRow;
import cn.tedu.drone.user.pojo.UserPO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用户数据访问层
 */
public interface UserDao {

    /**
     * 按用户名查询用户
     *
     * @param username 用户名
     * @return 用户记录，不存在返回 null
     */
    UserPO selectByUsername(@Param("username") String username);

    /**
     * 按主键查询用户
     *
     * @param id 用户 ID
     * @return 用户记录，不存在返回 null
     */
    UserPO selectById(@Param("id") Long id);

    /**
     * 查询全部用户，按 ID 升序。
     * <p>列表展示用不到密码，所以这条 SQL 不查 password 字段。
     *
     * @return 用户列表
     */
    List<UserPO> selectAll();

    /**
     * 按条件分页查询员工。
     * <p>列表展示不需要密码，所以不查 password 字段。
     *
     * @param param 查询条件（含分页参数）
     * @return 当前页数据
     */
    List<UserPO> selectByCondition(UserQueryParam param);

    /**
     * 按条件统计总数，分页组件靠它算总页数。
     * <p>条件必须与 {@link #selectByCondition} 完全一致，否则会出现
     * 「总条数说有 100 条、翻到第 3 页却空了」这种对不上的情况。
     */
    long countByCondition(UserQueryParam param);

    /**
     * 启用中的账号按在线状态分组计数，返回 {@code [{state, cnt}, ...]} —— 大屏的人员一块。
     *
     * <p>只算 {@code status='1'}：被禁用的账号不该出现在「值班的还有几个人」里。
     */
    List<Map<String, Object>> countEnabledGroupByState();

    /**
     * 查询全部登录账号，导入时用于唯一性校验。
     * <p>只取一列字符串，比把整张表读进内存小得多；
     * 真要处理百万级导入时，应改为「按批查询已存在账号」。
     */
    List<String> selectUsernames();

    /**
     * 批量新增用户。
     * <p>调用后自增主键会回填到每个 {@code UserPO} 的 id 上，导入时靠它建立角色关联。
     *
     * @param users 待插入的用户，调用方需保证非空
     * @return 影响行数
     */
    int insertBatch(List<UserPO> users);

    /**
     * 导出专用查询：按条件分批取数，并带出角色名称。
     * <p>复用与列表相同的查询条件，所以「先筛选、再导出」的结果与页面所见一致。
     *
     * @param param 查询条件（导出时逐批设置分页参数）
     * @return 导出数据行
     */
    List<UserExportRow> selectForExport(UserQueryParam param);

    /**
     * 按传入的非空字段更新用户基础信息。
     * <p>刻意不更新 username，也不在 SQL 里写死 password ——
     * 密码为空表示「保持原样」，只有传了才改。
     *
     * @param user 至少包含 id
     * @return 影响行数
     */
    int update(UserPO user);

    /**
     * 更新账号状态（启用 / 禁用）
     *
     * @param id     用户 ID
     * @param status 目标状态："1" 启用，"0" 禁用
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 更新在线状态，但只在它<b>当前确实是</b> {@code expectState} 时才改。
     *
     * <p>这就是一次「比较并交换」（CAS），把判断和写入合成一条 SQL，
     * 中间不会被别的请求插进来。写成「先查再改」就会出现这样的缝隙：
     * 查出是 idle，改之前别人把它设成了 grounded，然后你的写覆盖掉了人工标记。
     *
     * <p>返回影响行数：0 表示当前状态与预期不符，没改。
     */
    int updateStateIfMatch(@Param("id") Long id,
                           @Param("expectState") String expectState,
                           @Param("newState") String newState);
}
