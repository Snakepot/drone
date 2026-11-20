-- ============================================================================
-- 把「智能体对话」（agent:chat）授予飞手与机务
-- 数据库：drone
-- 执行顺序：**在 agent_permission.sql 之后**（那个文件负责登记 agent:chat 权限点）
-- 幂等：可重复执行
-- ============================================================================
-- 背景：REQ-AGENT-0018 按角色与权限裁剪工具之后，非 admin 角色需要能进入
-- /chat 才能体现裁剪效果；而 agent_permission.sql 默认只授予 admin。
--
-- 取舍：
--   * pilot（飞手）：能问设备与自己的任务，看不到员工/系统/统计工具；
--   * maintainer（机务）：能问设备档案、点位、禁飞区与告警；
--   * screen（大屏）：**不授予**。大屏 token 存在开放房间的浏览器里，
--     对话可能触及设备与任务细节，保持只读大屏的最小暴露面。
-- ============================================================================

INSERT INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `role` r
JOIN `permission` p ON p.code = 'agent:chat'
WHERE r.code IN ('pilot', 'maintainer')
  AND NOT EXISTS (
    SELECT 1 FROM `role_permission` rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 校验：
-- SELECT r.code AS role_code, p.code AS permission_code
-- FROM `role` r
-- JOIN `role_permission` rp ON rp.role_id = r.id
-- JOIN `permission` p ON p.id = rp.permission_id
-- WHERE p.code = 'agent:chat';
