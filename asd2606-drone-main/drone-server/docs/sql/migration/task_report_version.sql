-- ============================================================================
-- 迁移脚本：task_report 改成多版本（报告可修订、且改过要看得出来）
-- 适用：已建好 task_report 的库
-- 新库不用执行本脚本 —— schema.sql 里已经是最终状态
-- ============================================================================
-- 背景见 docs/任务收尾与执行报告.md 第六章：
--   原来 task_report 上有 uk_task_id，等于「一个任务只能有一份报告、且不能改」。
--   但报告是**交付物**（交给客户、作结算依据），飞手笔误、管理员要求补充、
--   客户提异议都会导致要改 —— 原地覆盖的话，「改了什么、谁改的、什么时候改的」
--   一个都答不上来。所以改成多版本：旧版本保留，新增一列 is_latest 标出当前版本。
--
-- 注意：下面的 ALTER **不可重复执行**（跑第二遍会报
-- Can't DROP 'uk_task_id'; check that column/key exists），那是正常的、忽略即可。
-- 之所以不写成幂等形式：MySQL 8 的 DROP INDEX / ADD COLUMN 都不支持 IF EXISTS，
-- 要幂等就得去查 information_schema 再动态拼 SQL，为一条一次性脚本不值得。
-- ============================================================================

ALTER TABLE `task_report`
  DROP INDEX `uk_task_id`,
  ADD COLUMN `version`     int      NOT NULL DEFAULT 1 COMMENT '版本号，从 1 开始；每次修订 +1' AFTER `task_id`,
  ADD COLUMN `is_latest`   tinyint  NOT NULL DEFAULT 1 COMMENT '1 当前版本 / 0 历史版本' AFTER `version`,
  ADD COLUMN `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD UNIQUE KEY `uk_task_version` (`task_id`, `version`),
  ADD KEY `idx_task_latest` (`task_id`, `is_latest`);

-- ---------------------------------------------------------------------------
-- 执行后自检
-- ---------------------------------------------------------------------------
-- 期望：uk_task_id 没了，多出 version / is_latest / update_time 三列，
--       以及 uk_task_version 与 idx_task_latest 两个索引
-- SHOW COLUMNS FROM `task_report` LIKE 'version';
-- SHOW INDEX FROM `task_report`;
--
-- 存量数据不用处理：已有报告的 version 默认 1、is_latest 默认 1，正好是「唯一且当前」
-- 那一版，语义上是对的。
