-- ============================================================================
-- 迁移脚本：到点打卡（task_checkin）
-- 适用：已建好其它表的库
-- 新库不用执行本脚本 —— schema.sql 里已含这张表
-- ============================================================================
-- 背景见 docs/地图接入方案.md 7.2 节：
--   飞手到了点位，有两种记法 ——
--     ① 设备遥测里的经纬度（客观、不可篡改，但要求飞机在飞）
--     ② 飞手点一下「打卡」（主观，但人到不了飞机那边时有用）
--   这张表存的是 ②。**它只算「飞手自述」这一档弱依据**（2026-09-28 拍板），
--   界面上必须与设备坐标区别显示 —— 别把「人点了一下」和「设备报上来的」画成同一种点。
--
-- ⚠️ 坐标一律存 **WGS84**（与设备、点位、航线、禁飞区一致）。
--    飞手手机上 `uni.getLocation({type:'gcj02'})` 拿到的是 GCJ-02，
--    接口层转成 WGS84 再入库、读出来再转回去 —— 见 CoordinateUtil。
--
-- ⚠️ 本脚本**不幂等**，重复执行会报表已存在。执行前先看一眼：
--    SHOW TABLES LIKE 'task_checkin';
-- ============================================================================

CREATE TABLE `task_checkin` (
  `id`            bigint        NOT NULL AUTO_INCREMENT,
  `task_id`       bigint        NOT NULL COMMENT '所属任务',
  `seq`           int           NOT NULL COMMENT '目标航点在航线里的序号（从 1 开始）',
  -- 目标点位也存下来（而不是每次去快照里翻）：
  -- 快照是当时的航线，而这条记录要能独立回答「他当时是奔哪个点去的、离多远」
  `target_lng`    decimal(10,6) NOT NULL COMMENT '目标点位经度 WGS84',
  `target_lat`    decimal(10,6) NOT NULL COMMENT '目标点位纬度 WGS84',
  `lng`           decimal(10,6) NOT NULL COMMENT '打卡位置上经度 WGS84',
  `lat`           decimal(10,6) NOT NULL COMMENT '打卡位置上纬度 WGS84',
  `accuracy_m`    decimal(8,2)  DEFAULT NULL COMMENT '手机定位精度（米）—— 越大越不可信，展示时要带上',
  -- 偏差由**服务端**算，不信客户端传的数：客户端那个数只能用来「提示他确认一下」，
  -- 存进库里的必须是服务端拿目标点与打卡点自己算的
  `deviation_m`   int           NOT NULL COMMENT '离目标点位的距离（米，服务端算）',
  `observed_at`   datetime      NOT NULL COMMENT '打卡那一刻（客户端给的：离线补传时就是当时那一刻）',
  `reported_at`   datetime      DEFAULT CURRENT_TIMESTAMP COMMENT '我们收到它的时刻（补传时晚于 observed_at）',
  `reporter_id`   bigint        DEFAULT NULL,
  `reporter_name` varchar(50)   DEFAULT NULL,
  `remark`        varchar(200)  DEFAULT NULL COMMENT '飞手填的说明，如「点位被树挡住，在边上拍的」',
  `create_time`   datetime      DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- **补传去重**：离线时打卡先存本地、联网后重发，同一条可能来好几遍。
  -- 按「任务 + 航点 + 时刻」唯一 —— 这三样一样就是同一次打卡，重发直接忽略。
  -- （时刻取客户端给的那个，而不是我们收到的时刻：后者每次重发都会变，去重就失效了）
  UNIQUE KEY `uk_checkin_dedup` (`task_id`, `seq`, `observed_at`),
  KEY `idx_checkin_task` (`task_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '到点打卡（坐标存 WGS84，只算飞手自述）';
