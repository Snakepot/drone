package cn.tedu.drone.user.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.dao.PermissionDao;
import cn.tedu.drone.user.dao.RoleDao;
import cn.tedu.drone.user.dao.RolePermissionDao;
import cn.tedu.drone.user.dao.UserRoleDao;
import cn.tedu.drone.user.pojo.PermissionPO;
import cn.tedu.drone.user.pojo.PermissionVO;
import cn.tedu.drone.user.pojo.RolePO;
import cn.tedu.drone.user.pojo.RoleSaveParam;
import cn.tedu.drone.user.pojo.RoleVO;
import cn.tedu.drone.user.pojo.UserVO;
import cn.tedu.drone.user.service.RbacService;
import cn.tedu.drone.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 权限管理业务实现。
 *
 * <h2>授权为什么用「覆盖式」而不是增删改</h2>
 * 前端提交的是「这个角色最终应该有哪些权限」的完整清单，服务端先清空再写入。
 * 这样前端不用计算「加了哪几个、删了哪几个」，逻辑简单、也不容易漏。
 * 代价是每次都要删+插，所以整个方法包在事务里。
 *
 * <h2>两处防呆（很重要）</h2>
 * <ol>
 *   <li><b>管理员角色不可禁用、权限不可调整</b> —— 否则一不小心就把「权限管理」
 *       这个入口自己的权限关掉了，从此谁也进不去</li>
 *   <li><b>不能修改自己的角色</b> —— 管理员手一抖把自己降权，同样会把自己锁在门外</li>
 * </ol>
 * 这类「把自己关在门外」的问题是权限系统最典型的翻车方式，必须在服务端挡住。
 */
@Slf4j
@Service
public class RbacServiceImpl implements RbacService {

    /** 管理员角色编码，用于防呆判断 */
    private static final String ADMIN_ROLE_CODE = "admin";

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private PermissionDao permissionDao;

    @Autowired
    private UserRoleDao userRoleDao;

    @Autowired
    private RolePermissionDao rolePermissionDao;

    /** 员工列表直接复用 UserService，避免重复写一遍查询 */
    @Autowired
    private UserService userService;

    @Override
    public List<RoleVO> listRoles() {
        return roleDao.selectAll().stream().map(this::toRoleVO).toList();
    }

    @Override
    @Transactional
    public void saveRole(RoleSaveParam param) {
        if (param == null || !StringUtils.hasText(param.getName())) {
            throw new BusinessException("角色名称不能为空");
        }

        if (param.getId() == null) {
            insertRole(param);
        } else {
            updateRole(param);
        }
    }

    private void insertRole(RoleSaveParam param) {
        if (!StringUtils.hasText(param.getCode())) {
            throw new BusinessException("角色编码不能为空");
        }

        String code = param.getCode().trim();
        if (roleDao.selectByCode(code) != null) {
            throw new BusinessException("角色编码已存在：" + code);
        }

        RolePO role = new RolePO()
                .setCode(code)
                .setName(param.getName().trim())
                .setDescription(param.getDescription())
                .setStatus(param.getStatus() == null ? 1 : param.getStatus());

        roleDao.insert(role);
        log.info("新增角色，id={}，code={}，name={}", role.getId(), role.getCode(), role.getName());
    }

    private void updateRole(RoleSaveParam param) {
        RolePO existing = roleDao.selectById(param.getId());
        if (existing == null) {
            throw new BusinessException("角色不存在");
        }

        // 防呆一：禁用管理员角色会让所有管理员立刻失去全部权限，谁也进不来权限管理页
        if (ADMIN_ROLE_CODE.equals(existing.getCode()) && Integer.valueOf(0).equals(param.getStatus())) {
            throw new BusinessException("管理员角色不允许禁用");
        }

        RolePO role = new RolePO()
                .setId(param.getId())
                .setName(param.getName().trim())
                .setDescription(param.getDescription())
                .setStatus(param.getStatus() == null ? existing.getStatus() : param.getStatus());

        roleDao.update(role);
        log.info("更新角色，id={}，name={}，status={}", role.getId(), role.getName(), role.getStatus());
    }

    @Override
    public List<Long> listRolePermissionIds(Long roleId) {
        return rolePermissionDao.selectPermissionIdsByRoleId(roleId);
    }

    @Override
    @Transactional
    public void grantRolePermissions(Long roleId, List<Long> permissionIds) {
        RolePO role = roleDao.selectById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 防呆：允许改管理员权限的话，随时可能把「权限管理」入口自己的权限也取消掉
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new BusinessException("管理员角色的权限不可调整");
        }

        // 覆盖式：先清空再写入。空列表时不能调 insertBatch，否则会拼出语法错误的 SQL
        rolePermissionDao.deleteByRoleId(roleId);
        if (permissionIds != null && !permissionIds.isEmpty()) {
            rolePermissionDao.insertBatch(roleId, permissionIds);
        }

        log.info("角色授权完成，roleId={}，角色={}，权限数={}",
                roleId, role.getCode(), permissionIds == null ? 0 : permissionIds.size());
    }

    @Override
    public List<PermissionVO> listPermissions() {
        return permissionDao.selectAll().stream().map(this::toPermissionVO).toList();
    }

    @Override
    public List<UserVO> listUsers() {
        return userService.listUsers();
    }

    @Override
    public List<Long> listUserRoleIds(Long userId) {
        return userRoleDao.selectRoleIdsByUserId(userId);
    }

    @Override
    @Transactional
    public void grantUserRoles(Long userId, List<Long> roleIds) {
        // 防呆二：不允许改自己的角色，否则管理员一不小心就把自己降权了
        LoginUser current = UserContext.get();
        if (current != null && current.getId().equals(userId)) {
            throw new BusinessException("不能修改自己的角色，请联系其他管理员");
        }

        userRoleDao.deleteByUserId(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            userRoleDao.insertBatch(userId, roleIds);
        }

        log.info("员工授权完成，userId={}，角色数={}", userId, roleIds == null ? 0 : roleIds.size());
    }

    /**
     * PO → VO。字段同名同类型，交给 BeanUtils 按 VO 的字段清单搬。
     */
    private RoleVO toRoleVO(RolePO role) {
        RoleVO vo = new RoleVO();
        BeanUtils.copyProperties(role, vo);
        return vo;
    }

    private PermissionVO toPermissionVO(PermissionPO permission) {
        PermissionVO vo = new PermissionVO();
        BeanUtils.copyProperties(permission, vo);
        return vo;
    }
}
