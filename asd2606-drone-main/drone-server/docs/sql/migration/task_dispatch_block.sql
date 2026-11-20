-- ============================================================================
-- 迁移脚本：待派发任务的「归因点」（task_dispatch_block）
-- 适用：已建好其它表的库
-- 新库不用执行本脚本 —— schema.sql 里已含这张表
-- ============================================================================
-- 背景见 docs/业务分析方案.md 2.7：
--   任务卡在 pending，**只说明「还没派」，不说明为什么**。
--   而「当时有没有空闲飞手和设备」是**那一刻的事实** —— 事后查只能看到现在的资源池，
--   所以这个问题**不能事后推断，只能当时记下来**。
--
--   这张表就是那些「当时」的记录：定时任务每隔一段时间，给每个待派发任务
--   打一个点，记下这一刻它为什么派不出去。
--
--   ⚠️ 四个归因取值：
--     blocked_pilot 没可派的飞手（要么没有飞手角色的人，要么都和时间窗冲突）
--     blocked_drone 没待命的设备（同理）
--     blocked_both  两者都没有 —— 资源整体不足
--     idle          资源是够的，但没人点派发 ← **最该被暴露的一类**：
--                   它指向的是管理动作没跟上，也是唯一一条不花钱就能改善的
--
-- ⚠️ 本脚本**不幂等**。执行前先看一眼：SHOW TABLES LIKE 'task_dispatch_block';
-- ============================================================================

CREATE TABLE `task_dispatch_block` (
  `id`               bigint      NOT NULL AUTO_INCREMENT,
  `task_id`          bigint      NOT NULL COMMENT '待派发任务',
  `checked_at`       datetime    NOT NULL COMMENT '这次采样是什么时候打的（本地时间）',
  `reason`           varchar(20) NOT NULL COMMENT 'blocked_pilot / blocked_drone / blocked_both / idle',
  -- 记下当时的候选数，是为了回答「还差多少」——只记一个「没人」，看不出是差 1 个人还是差 5 个
  `pilot_candidates` int         NOT NULL DEFAULT 0 COMMENT '当时可派的飞手数',
  `drone_candidates` int         NOT NULL DEFAULT 0 COMMENT '当时待命且不冲突的设备数',
  PRIMARY KEY (`id`),
  -- 查「某条任务被卡了多久、先后是什么原因」时按 (task_id, checked_at) 走
  KEY `idx_block_task` (`task_id`, `checked_at`),
  -- 分析按时间出图（「这周的积压主要是缺设备」）时按 checked_at 走
  KEY `idx_block_time` (`checked_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '待派发任务的归因点（定时采样，见 docs/业务分析方案.md 2.7）';
