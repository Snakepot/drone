package cn.tedu.drone.user.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserLoginParam;
import cn.tedu.drone.user.pojo.UserLoginVO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import cn.tedu.drone.user.pojo.UserSaveParam;
import cn.tedu.drone.user.pojo.UserVO;

import java.util.List;
import java.util.Set;

/**
 * 用户业务接口
 */
public interface UserService {

    /**
     * 用户登录
     *
     * @param param 登录参数
     * @return 登录令牌与用户信息（含角色与权限）
     */
    UserLoginVO login(UserLoginParam param);

    /**
     * 查询当前登录用户的信息。
     *
     * <p>只拿得到用户 ID —— 它由拦截器从 Token 中解析出来，
     * 完整信息（真实姓名、角色、权限）还是要从数据库查，这样资料被改过也能拿到最新的。
     *
     * @param userId 用户 ID
     * @return 用户信息（不含密码，但含角色与权限）
     */
    UserVO getCurrentUser(Long userId);

    /**
     * 查询用户拥有的角色编码集合。
     *
     * @param userId 用户 ID
     * @return 角色编码集合，如 ["admin"]
     */
    Set<String> listRoles(Long userId);

    /**
     * 查询用户拥有的权限编码集合（用户 → 角色 → 权限 三级关联）。
     *
     * @param userId 用户 ID
     * @return 权限编码集合，如 ["staff:add", "device:list"]
     */
    Set<String> listPermissions(Long userId);

    /**
     * 查询员工列表（不分页），供权限管理的授权界面使用。
     *
     * <p>列表页不展示权限明细，所以这里的 UserVO 不填 roles / permissions，
     * 避免逐行查库造成 N+1 查询。
     *
     * @return 用户列表
     */
    List<UserVO> listUsers();

    /**
     * 按条件分页查询员工，供员工管理页使用。
     *
     * @param param 查询条件（关键字 / 角色 / 状态 + 分页）
     * @return 分页结果（含总条数）
     */
    PageResult<UserVO> pageUsers(UserQueryParam param);

    /**
     * 查询角色选项，供员工管理页的「角色」筛选下拉使用。
     *
     * <p>为什么不直接复用权限管理的 {@code /system/role/list}？
     * 因为那个接口要求 {@code system:rbac:list} 权限 ——
     * 「能看员工列表」和「能进权限管理」是两回事，
     * 一个只有 staff:list 的角色不该因为要看筛选下拉就被迫获得配置权限。
     */
    List<RoleVO> listRoleOptions();

    /**
     * 改在线状态，但只在它当前确实是 {@code expectState} 时才改（比较并交换）。
     *
     * <p>给任务模块联动用的：派发任务时把飞手置为「作业中」，撤回时置回「空闲」。
     * 之所以要「预期值」这个参数，是为了不踩掉人工设置的状态 ——
     * 比如飞手被标了「已停飞」，派发流程就不该把它改成「作业中」。
     *
     * @param userId      用户 ID
     * @param expectState 预期当前状态，如 {@code idle}
     * @param newState    目标状态，如 {@code busy}
     * @return true 表示确实改了；false 表示当前状态与预期不符，没动
     */
    boolean changeStateIfMatch(Long userId, String expectState, String newState);

    /**
     * 新增员工。
     *
     * @param param 员工信息（账号、密码、角色编码必填）
     * @throws cn.tedu.drone.common.BusinessException 账号重复、角色不存在、必填项为空
     */
    void addUser(UserSaveParam param);

    /**
     * 编辑员工。
     *
     * <p>登录账号不可修改；密码与角色编码留空表示保持原样。
     *
     * @param param 员工信息（必须带 id）
     * @throws cn.tedu.drone.common.BusinessException 员工不存在、角色不存在、试图修改自己的角色
     */
    void updateUser(UserSaveParam param);

    /**
     * 启用 / 禁用员工。
     *
     * @param userId 用户 ID
     * @param status 目标状态："1" 启用，"0" 禁用
     * @throws cn.tedu.drone.common.BusinessException 员工不存在、试图禁用当前登录账号
     */
    void changeStatus(Long userId, String status);

    /**
     * 查询员工详情（含角色），供编辑弹窗回显。
     *
     * <p>为什么不直接让列表接口带上角色？因为列表要避免 N+1 查询 ——
     * 每行都查一次角色太重，而列表页也不展示角色。
     * 只有真正打开编辑弹窗时才查这一个用户的角色，按需加载。
     *
     * @param userId 用户 ID
     * @return 用户信息（含角色）
     */
    UserVO getUserDetail(Long userId);
}
