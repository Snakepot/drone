-- ============================================================================
-- 迁移脚本：给「统计用的查询」补索引（业务分析方案 第零步）
-- 适用：已建好其它表的库
-- 新库不用执行本脚本 —— schema.sql 里已含这几个索引
-- ============================================================================
-- 背景见 docs/业务分析方案.md 4.3 ① 与 5.2 ①：
--   大屏与分析两条线都要做「按时间的聚合」，而现有索引**不是为聚合准备的**。
--   实测（开发库 514 行）大屏那条「今日数据条数」：
--     SELECT COUNT(*) FROM task_evidence WHERE observed_at >= ?
--     EXPLAIN → type=range  key=idx_device_time  Extra=Using index for skip scan
--   ⚠️ `Using index for skip scan` 不是好事，是**退而求其次**：
--       observed_at 不是任何索引的首列（现有的是 (device_code, ...)），
--       所以 MySQL 只能跳着扫。数据量小时它是毫秒级，上千万行时会差一个量级。
--
--   同样的问题还有三条（它们现在**根本没索引可走**，只能全表扫）：
--     · task  WHERE create_time >= ?                        （今日新增 / 趋势）
--     · task  WHERE status='executing' AND plan_end_time < ?（超期）
--     · task_alert WHERE observed_at >= ?                   （告警趋势 / MTTR）
--
-- ⚠️ 本脚本可重复执行（MySQL 8 不支持 ADD INDEX IF NOT EXISTS，
--    所以用「先看有没有、再决定加不加」的写法包一层）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. task_evidence：让「按时间的计数」走正经的范围扫描
--    这是唯一会无限增长的表（一台设备一天 8.6 万行），所以它最要紧
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'task_evidence'
                  AND index_name = 'idx_observed_at');
SET @sql := IF(@exists = 0,
               'ALTER TABLE `task_evidence` ADD KEY `idx_observed_at` (`observed_at`)',
               'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. task：今日新增（create_time）与超期（status + plan_end_time）
--    两条都加，因为它们的组合条件各自不同，MySQL 只能各走一条索引
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'task'
                  AND index_name = 'idx_create_time');
SET @sql := IF(@exists = 0,
               'ALTER TABLE `task` ADD KEY `idx_create_time` (`create_time`)',
               'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'task'
                  AND index_name = 'idx_status_plan_end');
SET @sql := IF(@exists = 0,
               'ALTER TABLE `task` ADD KEY `idx_status_plan_end` (`status`, `plan_end_time`)',
               'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. task_alert：告警趋势与 MTTR（按 observed_at 的开区间）
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'task_alert'
                  AND index_name = 'idx_observed_at');
SET @sql := IF(@exists = 0,
               'ALTER TABLE `task_alert` ADD KEY `idx_observed_at` (`observed_at`)',
               'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 执行后自检：四条查询的 EXPLAIN 里不该再出现 ALL 或 skip scan
-- ---------------------------------------------------------------------------
-- EXPLAIN SELECT COUNT(*) FROM task_evidence WHERE observed_at >= NOW() - INTERVAL 1 DAY;
-- EXPLAIN SELECT COUNT(*) FROM task          WHERE create_time   >= NOW() - INTERVAL 1 DAY;
-- EXPLAIN SELECT COUNT(*) FROM task          WHERE status='executing' AND plan_end_time < NOW();
-- EXPLAIN SELECT COUNT(*) FROM task_alert    WHERE observed_at   >= NOW() - INTERVAL 1 DAY;
