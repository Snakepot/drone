package cn.tedu.drone.user.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.config.JwtProperties;
import cn.tedu.drone.common.JwtUtil;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.dao.PermissionDao;
import cn.tedu.drone.user.dao.RoleDao;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.dao.UserRoleDao;
import cn.tedu.drone.user.pojo.RolePO;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserLoginParam;
import cn.tedu.drone.user.pojo.UserLoginVO;
import cn.tedu.drone.user.pojo.UserPO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import cn.tedu.drone.user.pojo.UserSaveParam;
import cn.tedu.drone.user.pojo.UserVO;
import cn.tedu.drone.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户业务实现，包含登录、查询当前用户与权限查询。
 *
 * <h2>一、登录依次做了什么</h2>
 * <ol>
 *   <li>检查用户名、密码是否填写</li>
 *   <li>拿用户名去数据库查人</li>
 *   <li>比对密码</li>
 *   <li>检查账号是否被禁用</li>
 *   <li>查出该用户的角色与权限，组装成不含密码的 {@link UserVO}</li>
 *   <li>签发 Token 并返回</li>
 * </ol>
 * 上面任何一步不通过，都会抛 {@link BusinessException}，
 * 由 {@link cn.tedu.drone.common.GlobalExceptionHandler} 统一转成友好的 JSON 返回给前端。
 *
 * <h2>二、两个刻意的设计（面试常问）</h2>
 * <ul>
 *   <li><b>「用户不存在」和「密码错误」返回同一句提示</b>。
 *       如果分开提示，攻击者就能拿一份手机号字典批量试探，
 *       从提示语反推出哪些号码在这个平台注册过（这叫「用户名枚举」），
 *       然后再针对性地撞库或发骚扰短信。</li>
 *   <li><b>日志里绝不打印密码</b>。密码一旦写进日志文件，就等于明文落在了磁盘上，
 *       比存在数据库里还危险。所以下面的日志只记录用户名和 userId。</li>
 * </ul>
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    /** user 表中 status 为 "0" 表示该账号已被禁用 */
    private static final String DISABLED_STATUS = "0";

    /** user 表中 status 为 "1" 表示账号可用 */
    private static final String ENABLED_STATUS = "1";

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private PermissionDao permissionDao;

    @Autowired
    private UserRoleDao userRoleDao;

    /** 新增员工复用它做「插用户 + 建角色关联」的事务写入（与导入共用一套逻辑） */
    @Autowired
    private UserBatchWriter batchWriter;

    @Autowired
    private JwtUtil jwtUtil;

    /** 大屏那条只读角色的编码：它签发的 Token 用长效期（见 docs/数字大屏方案.md 4.5） */
    private static final String SCREEN_ROLE = "screen";

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 用户登录。
     *
     * @param param 登录参数（用户名、密码）
     * @return 登录令牌与用户信息（不含密码，但含角色与权限）
     * @throws BusinessException 参数为空、用户名或密码错误、账号被禁用
     */
    @Override
    public UserLoginVO login(UserLoginParam param) {
        log.info("收到登录请求，用户名={}", param == null ? null : param.getUsername());

        // 第一步：基础校验，避免空值一路传到数据库查询
        if (param == null || !StringUtils.hasText(param.getUsername())
                || !StringUtils.hasText(param.getPassword())) {
            log.warn("登录失败：用户名或密码为空");
            throw new BusinessException("用户名和密码不能为空");
        }

        String username = param.getUsername().trim();

        // 第二步：按用户名查库
        UserPO user = userDao.selectByUsername(username);
        if (user == null) {
            // 刻意不提示「用户不存在」，防止攻击者枚举出哪些账号真实存在
            log.warn("登录失败：用户不存在，用户名={}", username);
            throw new BusinessException("用户名或密码错误");
        }

        // 第三步：比对密码
        // 注意：当前数据库里存的是明文，仅供开发阶段使用。
        // 生产环境必须换成 BCrypt 等哈希算法，比对方式也要跟着改。
        if (!param.getPassword().equals(user.getPassword())) {
            log.warn("登录失败：密码错误，用户名={}，userId={}", username, user.getId());
            throw new BusinessException("用户名或密码错误");
        }

        // 第四步：账号状态检查
        if (DISABLED_STATUS.equals(user.getStatus())) {
            log.warn("登录失败：账号已被禁用，用户名={}，userId={}", username, user.getId());
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 第五步：转成带角色权限的 UserVO，确保密码不会跟着响应返回给前端
        UserVO userVO = toUserVO(user);

        // 第六步：签发 Token（内部会记录签发详情）
        // 大屏账号（screen 角色）签长效的，其余走默认时长 —— 判断在 tokenExpireHours 里
        String token = jwtUtil.generateToken(user, tokenExpireHours(userVO));

        log.info("登录成功，userId={}，用户名={}，角色={}，权限数={}",
                user.getId(), user.getUsername(), userVO.getRoles(),
                userVO.getPermissions() == null ? 0 : userVO.getPermissions().size());

        return new UserLoginVO()
                .setToken(token)
                .setUser(userVO);
    }

    /**
     * 查询当前登录用户的信息。
     *
     * <p>入参只有 userId，它是拦截器从 Token 里解析出来的；
     * 这里再按 ID 查一次库，是为了拿到完整且最新的资料 ——
     * 如果直接拿 Token 里的快照，用户改了姓名之后前端还得等 Token 过期才能看到新名字。
     *
     * @param userId 用户 ID
     * @return 用户信息（不含密码，但含角色与权限）
     * @throws BusinessException 用户不存在（例如账号已被删除，但 Token 还没过期）
     */
    @Override
    public UserVO getCurrentUser(Long userId) {
        // 演示用：Service 和 Controller 跑在同一个线程上，所以这里还能从 ThreadLocal 取到登录人。
        // 把这条日志和拦截器、Controller 的日志放在一起看，会发现三者的线程标识完全一致。
        LoginUser current = UserContext.get();
        log.info("Service [{}] 准备按 id={} 查库，此时 ThreadLocal 里仍是 {}",
                UserContext.threadLabel(), userId, current == null ? "null" : current.getUsername());

        UserPO user = userDao.selectById(userId);
        if (user == null) {
            // Token 合法但人已经被删了，此时应当让前端重新登录
            log.warn("查询当前登录用户失败：用户不存在，userId={}", userId);
            throw new BusinessException(401, "登录用户不存在，请重新登录");
        }

        UserVO userVO = toUserVO(user);
        log.debug("查询当前登录用户成功，userId={}，用户名={}", userId, user.getUsername());
        return userVO;
    }

    @Override
    public Set<String> listRoles(Long userId) {
        return new HashSet<>(roleDao.selectCodesByUserId(userId));
    }

    @Override
    public Set<String> listPermissions(Long userId) {
        return new HashSet<>(permissionDao.selectCodesByUserId(userId));
    }

    @Override
    public List<UserVO> listUsers() {
        List<UserPO> users = userDao.selectAll();
        log.debug("查询员工列表（不分页），共 {} 条", users.size());
        return users.stream().map(this::toSimpleUserVO).toList();
    }

    @Override
    public PageResult<UserVO> pageUsers(UserQueryParam param) {
        UserQueryParam query = (param == null) ? new UserQueryParam() : param;

        // 先查总数：为 0 就不用再查数据了，省一次数据库往返
        long total = userDao.countByCondition(query);
        List<UserVO> list = (total == 0)
                ? List.of()
                : userDao.selectByCondition(query).stream().map(this::toSimpleUserVO).toList();

        log.debug("分页查询员工，命中 {} 条，取第 {} 页", total, query.getPage());
        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public List<RoleVO> listRoleOptions() {
        // 员工管理页只用到 id/code/name，但统一走 RoleVO 这个出口，
        // 免得同一个「角色」在前端有两种形状
        return roleDao.selectAll().stream().map(this::toRoleVO).toList();
    }

    @Override
    public boolean changeStateIfMatch(Long userId, String expectState, String newState) {
        if (userId == null) {
            return false;
        }
        int rows = userDao.updateStateIfMatch(userId, expectState, newState);
        if (rows == 0) {
            // 不是错误：只说明这个人的状态已经不是我们以为的样子（别人改过，或本来就是「已停飞」）
            log.debug("在线状态未变更（当前值与预期 {} 不符），userId={}", expectState, userId);
        }
        return rows > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUser(UserSaveParam param) {
        if (param == null || !StringUtils.hasText(param.getUsername())) {
            throw new BusinessException("登录账号不能为空");
        }
        if (!StringUtils.hasText(param.getPassword())) {
            throw new BusinessException("密码不能为空");
        }

        String username = param.getUsername().trim();
        if (userDao.selectByUsername(username) != null) {
            throw new BusinessException("登录账号已存在：" + username);
        }

        RolePO role = requireRole(param.getRoleCode());

        UserPO user = new UserPO()
                .setUsername(username)
                .setName(param.getName())
                .setPassword(param.getPassword())
                .setPhone(param.getPhone())
                .setEmail(param.getEmail())
                .setAge(param.getAge())
                .setGender(param.getGender())
                .setStatus(ENABLED_STATUS)
                .setState("idle");

        // 复用导入用的写入器：它内部就是「插用户 + 建角色关联」的同一个事务
        batchWriter.write(List.of(user), List.of(role.getId()));

        log.info("新增员工，id={}，账号={}，角色={}", user.getId(), user.getUsername(), role.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(UserSaveParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("缺少员工 ID");
        }
        if (userDao.selectById(param.getId()) == null) {
            throw new BusinessException("员工不存在");
        }

        // 基础信息按非空字段更新；账号不在其中 —— 它是登录凭证，压根不允许改
        UserPO update = new UserPO()
                .setId(param.getId())
                .setName(param.getName())
                .setPhone(param.getPhone())
                .setEmail(param.getEmail())
                .setAge(param.getAge())
                .setGender(param.getGender());

        // 密码留空表示不改 —— 否则管理员改个手机号还得先知道原密码
        if (StringUtils.hasText(param.getPassword())) {
            update.setPassword(param.getPassword());
        }
        userDao.update(update);

        // 角色留空表示不调整
        if (StringUtils.hasText(param.getRoleCode())) {
            LoginUser current = UserContext.get();
            if (current != null && current.getId().equals(param.getId())) {
                // 防呆：与权限管理页保持一致，不允许改自己的角色
                throw new BusinessException("不能修改自己的角色，请联系其他管理员");
            }

            RolePO role = requireRole(param.getRoleCode());
            userRoleDao.deleteByUserId(param.getId());
            userRoleDao.insertBatch(param.getId(), List.of(role.getId()));
        }

        log.info("编辑员工，id={}", param.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long userId, String status) {
        if (userId == null) {
            throw new BusinessException("缺少员工 ID");
        }
        if (!ENABLED_STATUS.equals(status) && !DISABLED_STATUS.equals(status)) {
            throw new BusinessException("状态值只能是 1（启用）或 0（禁用）");
        }

        // 防呆：禁用自己会立刻把自己踢下线；如果这是唯一的管理员账号，就再也进不来了
        LoginUser current = UserContext.get();
        if (DISABLED_STATUS.equals(status) && current != null && current.getId().equals(userId)) {
            throw new BusinessException("不能禁用当前登录账号");
        }

        if (userDao.selectById(userId) == null) {
            throw new BusinessException("员工不存在");
        }

        userDao.updateStatus(userId, status);
        log.info("{}员工，id={}", DISABLED_STATUS.equals(status) ? "禁用" : "启用", userId);
    }

    @Override
    public UserVO getUserDetail(Long userId) {
        UserPO user = userDao.selectById(userId);
        if (user == null) {
            throw new BusinessException("员工不存在");
        }
        // 复用 toUserVO，会一并带上角色；编辑弹窗只需要 roles，多查的 permissions 忽略即可
        return toUserVO(user);
    }

    /**
     * 校验角色编码并返回角色。
     *
     * @throws BusinessException 编码为空或角色不存在
     */
    private RolePO requireRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            throw new BusinessException("请选择角色");
        }

        RolePO role = roleDao.selectByCode(roleCode.trim());
        if (role == null) {
            throw new BusinessException("角色不存在：" + roleCode);
        }
        return role;
    }

    /**
     * 转成不含角色与权限的展示对象，用于列表场景。
     *
     * <p>列表每一行都去查角色和权限会造成 N+1 查询（6 行就是 12 次额外查询），
     * 而列表页并不展示这些信息，所以这里刻意不填。
     * 需要角色权限的场景（登录、/me）走 {@link #toUserVO}。
     */
    private UserVO toSimpleUserVO(UserPO user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }

    /**
     * 把数据库实体转成前端展示对象，并补上角色与权限。
     *
     * <p>用 BeanUtils 复制同名字段（不含密码字段，因为 UserVO 里压根没有），
     * 角色和权限不是 user 表的字段，需要单独查两次。
     */
    private UserVO toUserVO(UserPO user) {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        userVO.setRoles(listRoles(user.getId()));
        userVO.setPermissions(listPermissions(user.getId()));
        return userVO;
    }

    /** 角色 PO → VO，供筛选下拉这类「只需要角色元信息」的场景使用 */
    private RoleVO toRoleVO(RolePO role) {
        RoleVO vo = new RoleVO();
        BeanUtils.copyProperties(role, vo);
        return vo;
    }

    /**
     * 这次登录该签多久。
     *
     * <h2>为什么要按角色区分</h2>
     * 大屏挂在值班室/会议室，**没人会去重新登录** —— 用交互式的 2 小时，
     * 第二天早上来看到的是一块停在登录页的屏。所以 {@code screen} 角色签长效期。
     *
     * <p>⚠️ 有效期**只由服务端按角色决定，不接受前端传参**：
     * 前端能要求的有效期，等于谁都能给自己签一年。
     */
    private long tokenExpireHours(UserVO userVO) {
        boolean isScreen = userVO != null && userVO.getRoles() != null
                && userVO.getRoles().contains(SCREEN_ROLE);
        return isScreen ? jwtProperties.getScreenExpireHours() : jwtProperties.getExpireHours();
    }
}
