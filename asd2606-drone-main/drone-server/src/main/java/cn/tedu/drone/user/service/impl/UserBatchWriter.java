package cn.tedu.drone.user.service.impl;

import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.dao.UserRoleDao;
import cn.tedu.drone.user.pojo.UserPO;
import cn.tedu.drone.user.pojo.UserRoleRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入时的批次写入器。
 *
 * <h2>为什么单独一个类，而不是写在 UserExcelServiceImpl 里</h2>
 * 为了让 {@code @Transactional} 生效。
 * <p>Spring 的事务是基于代理的：同一个类里 A 方法调 B 方法，走的是 {@code this}，
 * 不经过代理，B 上的 {@code @Transactional} <b>不会生效</b> ——
 * 这是初学者最常踩的事务坑之一。
 * 把带事务的方法拆到独立 Bean 里，由外部调用，代理才有机会介入。
 *
 * <h2>为什么一批要放在一个事务里</h2>
 * 用户记录和它的角色关联必须同生共死：只插了用户却没插角色，
 * 这个账号登录后什么都干不了，属于脏数据。
 */
@Slf4j
@Service
public class UserBatchWriter {

    @Autowired
    private UserDao userDao;

    @Autowired
    private UserRoleDao userRoleDao;

    /**
     * 批量写入用户及其角色关联。
     *
     * @param users   用户列表（插入后自增主键会被回填到 id 上）
     * @param roleIds 与 users 一一对应的角色 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void write(List<UserPO> users, List<Long> roleIds) {
        // 第一步：批量插入用户，insertBatch 配置了 useGeneratedKeys，插入后 id 已回填
        userDao.insertBatch(users);

        // 第二步：用回填的主键组装关联关系
        List<UserRoleRef> refs = new ArrayList<>(users.size());
        for (int i = 0; i < users.size(); i++) {
            refs.add(new UserRoleRef(users.get(i).getId(), roleIds.get(i)));
        }
        userRoleDao.insertBatchRefs(refs);
    }
}
