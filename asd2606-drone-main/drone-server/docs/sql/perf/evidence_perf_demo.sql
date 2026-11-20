-- ===========================================================================
-- 预聚合演示：造一份够大的遥测证据，用来对比「常规查询」与「预聚合」
--
-- 为什么需要它：`task_evidence` 是全系统唯一会无限增长的表（1 台设备 1 条/秒），
-- 而「按天 / 按设备做统计」这件事在几百万行上就开始明显变慢 ——
-- 但开发库里现在只有几百行，快得看不出差别。
-- 为了让第四章那些结论（索引用不上、预聚合能压住）能被**看见、被量出来**，
-- 这里专门造一份够大又够真的数据。
--
-- ⚠️ 它不碰开发库：整份数据建在一个一次性库 `drone_perf_demo` 里，
-- 表结构与索引与 `drone.task_evidence` **完全一致**（否则测出来的差别不算数）。
-- 看完想删：DROP DATABASE drone_perf_demo;
--
-- 用法：
--   mysql -h127.0.0.1 -uroot -p < evidence_perf_demo.sql
-- 跑完大约 190 万行（365 天 × 10 台设备 × 每天 540 秒），占磁盘约 1 GB。
--
-- 量什么（脚本末尾几个「对比一 / 对比二」，直接拿去 EXPLAIN ANALYZE）：
--   ① 每天多少条证据        —— 常规要在几百万行上分组，预聚合只读几千行
--   ② 每天多少台设备活跃    —— 常规要 COUNT(DISTINCT)，预聚合靠粒度「加」出来
-- ===========================================================================

DROP DATABASE IF EXISTS drone_perf_demo;
CREATE DATABASE drone_perf_demo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE drone_perf_demo;

-- ---------------------------------------------------------------------------
-- 1. 与 drone.task_evidence 同结构、同索引（抄自 docs/sql/schema.sql）
-- ---------------------------------------------------------------------------
CREATE TABLE `task_evidence` (
  `id`          bigint      NOT NULL AUTO_INCREMENT,
  `task_id`     bigint      DEFAULT NULL COMMENT '所属任务，为空表示这条数据没有关联到任何任务',
  `device_code` varchar(50) NOT NULL COMMENT '设备编号，对应 drone.code',
  `type`        varchar(20) NOT NULL COMMENT '证据类型：telemetry / flight_log / witness / manual',
  `source`      varchar(50) DEFAULT NULL COMMENT '来源：device / app / operator',
  `attach_id`   bigint      DEFAULT NULL COMMENT '证据是文件时指向 task_attachment',
  `payload`     text        COMMENT '证据内容，存 JSON 文本',
  `seq`         bigint      DEFAULT NULL COMMENT '设备自增序号，参与唯一键去重',
  `observed_at` datetime    NOT NULL COMMENT '证据描述的那个时刻',
  `reported_at` datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到它的时刻',
  `valid`       tinyint     NOT NULL DEFAULT 1 COMMENT '1 有效 / 0 已被推翻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evidence_dedup` (`device_code`, `type`, `observed_at`, `seq`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_device_time` (`device_code`, `observed_at`),
  KEY `idx_observed_at` (`observed_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 2. 造数：365 天 × 10 台设备 × 每天 540 秒 = 1,971,000 行
--
-- 为什么用「数字表交叉连接」而不是递归 CTE：递归 CTE 是一行一行长出来的
-- （默认还只允许 1000 层），190 万行会跑到天荒地老；
-- 交叉连接是一次性的集合运算，几秒就完事。
-- 这一段同时是给后来人看的：**造大数据不需要写程序，SQL 自己就行**。
--
-- ⚠️ 这张数字表**不能用 CREATE TEMPORARY TABLE**：MySQL 规定
-- 「同一条 SQL 里临时表只能被引用一次」，而这里的交叉连接要引用它四次 ——
-- 报错是 1137 Can't reopen table。用普通表就行（整个库是演示库，造完再 DROP）。
-- ---------------------------------------------------------------------------
CREATE TABLE digits (d INT);
INSERT INTO digits (d) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

INSERT INTO task_evidence (device_code, type, source, payload, seq, observed_at, reported_at, valid)
SELECT CONCAT('SIM-', LPAD(dev.d, 3, '0'))                    AS device_code,
       'telemetry'                                            AS type,
       'device'                                               AS source,
       -- 一份像模像样的报文（大小也接近真实：几十字节）
       CONCAT('{"battery":', 40 + (sec.n MOD 60), ',"signal":', sec.n MOD 5,
              ',"flightState":"flying"}')                     AS payload,
       sec.n                                                  AS seq,
       -- 起飞窗口：每天 08:00 起飞 9 分钟 —— 形状像真的作业，不是均匀撒满 24 小时
       DATE_ADD('2025-10-01 08:00:00',
                INTERVAL (day.n * 86400 + sec.n) SECOND)      AS observed_at,
       DATE_ADD('2025-10-01 08:00:00',
                INTERVAL (day.n * 86400 + sec.n) SECOND)      AS reported_at,
       1                                                      AS valid
FROM (SELECT dy1.d * 100 + dy2.d * 10 + dy3.d AS n FROM digits dy1, digits dy2, digits dy3) day
CROSS JOIN digits dev
CROSS JOIN (SELECT sc1.d * 100 + sc2.d * 10 + sc3.d AS n FROM digits sc1, digits sc2, digits sc3) sec
WHERE day.n < 365      -- 一年
  AND sec.n < 540;     -- 每天 9 分钟（540 秒 × 10 台设备 ≈ 5,400 行/天）

-- 数字表用完就扔：留在库里会让人以为它是业务表
DROP TABLE digits;

-- ---------------------------------------------------------------------------
-- 3. 预聚合表：粒度是「天 × 设备」
--
-- ⚠️ 粒度为什么不能是「天」：那样只能回答「每天多少条」，
-- 回答不了「每天多少台设备活跃」—— COUNT(DISTINCT) **不可加**，
-- 存进去的「今天 5 台」和「明天 5 台」加起来得不出「这两天一共几台」。
-- 存到 (天 × 设备) 这一层，两个问题都能「加」出来：
--   每天多少条       = SUM(evidence_count)
--   每天多少台活跃   = COUNT(*)           ← 这一天有行，就说明这台设备活跃
--
-- 这也是第三步 `stat_daily` 要定下来的事情之一（粒度决定后能回答哪些问题）。
-- ---------------------------------------------------------------------------
CREATE TABLE `evidence_daily` (
  `day`            date        NOT NULL COMMENT '哪一天（按业务口径的那天）',
  `device_code`    varchar(50) NOT NULL,
  `evidence_count` int         NOT NULL COMMENT '这一天这台设备多少条证据',
  PRIMARY KEY (`day`, `device_code`),
  KEY `idx_device` (`device_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 这一步就是「凌晨定时任务」要付的钱：一次性把明细算成汇总。
-- 单独跑一次并把耗时记下来 —— 预聚合不是不要钱，是把钱挪到没人看的时候花。
INSERT INTO evidence_daily (day, device_code, evidence_count)
SELECT DATE(observed_at), device_code, COUNT(*)
FROM task_evidence
GROUP BY DATE(observed_at), device_code;

-- ---------------------------------------------------------------------------
-- 4. 两个对比（量的时候用 EXPLAIN ANALYZE，别看「直觉」）
-- ---------------------------------------------------------------------------
-- 对比一：每天多少条证据（近一年）
-- 常规（要读几百万行）：
--   SELECT DATE(observed_at) AS day, COUNT(*) AS cnt
--   FROM task_evidence
--   WHERE observed_at >= '2025-10-01' AND observed_at < '2026-10-01'
--   GROUP BY day;
-- 预聚合（只读几千行）：
--   SELECT day, SUM(evidence_count) AS cnt FROM evidence_daily GROUP BY day;
--
-- 对比二：每天多少台设备活跃
-- 常规（COUNT(DISTINCT) 要压临时表）：
--   SELECT DATE(observed_at) AS day, COUNT(DISTINCT device_code) AS devices
--   FROM task_evidence
--   WHERE observed_at >= '2025-10-01' AND observed_at < '2026-10-01'
--   GROUP BY day;
-- 预聚合：
--   SELECT day, COUNT(*) AS devices FROM evidence_daily GROUP BY day;
