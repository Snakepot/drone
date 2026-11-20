-- ============================================================================
-- 迁移脚本：建「按天预聚合表」（业务分析方案 第三步 / 4.3 ③）
-- 适用：已建好其它表的库（新库不用执行 —— schema.sql 里已含这张表）
--
-- 背景与实测见 docs/业务分析方案.md 4.5：
--   明细表上「按天分组看一年」（197 万行）要 0.58 s、读 197 万行；
--   换成这张汇总表（3650 行）只要 0.04 s。预聚合不是不要钱，
--   是把钱挪到没人看的时候花（凌晨算一次 4.95 s，换掉每一次的 0.6~1 s）。
--
-- ⚠️ 可重复执行（CREATE TABLE IF NOT EXISTS）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `stat_daily` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `stat_date`       date         NOT NULL COMMENT '哪一天（按应用本地时间的那天，边界由 Java 传）',
  `metric`          varchar(50)  NOT NULL COMMENT '指标名，如 evidence_count',
  `dimension`       varchar(80)  NOT NULL DEFAULT '' COMMENT '维度取值（设备编号等）；空串表示「不分维度」',
  `value`           decimal(18,4) NOT NULL COMMENT '值；用 decimal 是为了同时装「条数」与「小时」两类指标',
  `caliber_version` int          NOT NULL DEFAULT 1 COMMENT '口径版本：口径改了要 +1 并整段重算',
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次算它的时刻',
  PRIMARY KEY (`id`),
  -- 唯一键让「按日期重算」是幂等的（重算同一天不会翻倍）
  UNIQUE KEY `uk_stat_daily` (`stat_date`, `metric`, `dimension`, `caliber_version`),
  -- 查询永远是「某个指标 + 一段日期」
  KEY `idx_metric_date` (`metric`, `stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '按天预聚合（业务分析第三步）';

-- 自检：建好了就报一行，方便脚本化执行时确认
SELECT CONCAT('stat_daily ', IF(COUNT(*) > 0, '已就绪', '缺失')) AS check_result
FROM information_schema.TABLES
WHERE table_schema = DATABASE() AND table_name = 'stat_daily';
