package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.device.dao.DeviceDao;
import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.task.dao.TaskDao;
import cn.tedu.drone.task.dao.TaskEvidenceDao;
import cn.tedu.drone.task.mqtt.DeviceMessage;
import cn.tedu.drone.task.mqtt.TelemetryEnvelope;
import cn.tedu.drone.task.mqtt.TelemetryHandler;
import cn.tedu.drone.task.pojo.EvidenceState;
import cn.tedu.drone.task.pojo.EvidenceType;
import cn.tedu.drone.task.pojo.TaskEvidencePO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 把一条设备消息变成一条证据，并在必要时把任务的依据抬到「已证实」。
 *
 * <h2>六步处理链路</h2>
 * <pre>
 * ① 反序列化     —— 在订阅器里做（见 TelemetrySubscriber.messageArrived）
 * ② 校验信封     —— type / seq / observedAt 缺一不可，不合法直接丢
 * ③ 找设备       —— 设备档案里没有这台设备就丢（并告警）
 * ④ 找任务       —— 找不到执行中的任务？数据照样存，只是 task_id 留空
 * ⑤ 幂等落库     —— 唯一索引挡重复（QoS 1 会重投）
 * ⑥ 触发业务动作 —— 首次证实时 declared / stale → confirmed
 * </pre>
 * <b>编号与 docs/MQTT与设备接入.md 第 4.5 节、docs/任务业务设计.md 的 10.8.1 完全一致</b>，
 * 日志里的 {@code 【MQTT 处理 ② 校验信封】} 这种前缀就是它 ——
 * 看到日志能直接对回文档，不用猜这一行属于哪一步。
 *
 * <p>本类负责 ②~⑥；① 在订阅器里（那里才知道报文的原始字节），
 * 所以日志里不会出现 {@code ①} 这个编号。
 *
 * <h2>为什么每一步失败都「丢」而不是「抛」</h2>
 * 调用方是消费线程（见 {@code TelemetryIngestQueue}），一条坏数据不能把线程带走。
 * 而且设备数据的性质是<b>丢一条不致命</b>：下一帧马上就来。
 * 反过来，一条报文让整个消费线程死掉，那台设备之后所有的数据都进不来了 ——
 * 这才是真的严重。所以这里的策略是「能处理就处理，处理不了记清楚原因继续」。
 *
 * <h2>为什么这个类不做事务</h2>
 * 一次处理包含「插证据」和「改任务证据状态」两步，理论上可以包一个事务。
 * 但代价不对等：包上事务意味着消费线程要多持有一个数据库连接加锁，
 * 而吞吐是设备数据接入的关键指标；不包的后果只是极端情况下
 * 「证据存下来了、任务状态没抬上去」，而且下一秒的下一条遥测会把它补上
 * （{@code markEvidenceConfirmed} 本身幂等，还会重试）。
 * <b>能自愈的最终一致，好过吞吐上的长期损失。</b>
 */
@Slf4j
@Service
public class TelemetryIngestServiceImpl implements TelemetryHandler {

    /**
     * 设备侧时间的最常见格式。放在第一位尝试 ——
     * 我们的模拟器、以及文档里推荐的报文格式都是它。
     */
    private static final DateTimeFormatter DEVICE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 单条证据最多存多少个字符，超了就截断并告警。
     *
     * <p>限制来自 {@code task_evidence.payload} 是 {@code text} 类型（上限 64KB）。
     * MySQL 在「字段超长」时默认是<b>报错</b>，但我们用的是 INSERT IGNORE，
     * 那会把超长降级成<b>静默截断</b> —— 证据被悄悄剪掉一截却没人知道，
     * 这比报错还糟。所以在应用层先截断，并且明确打一条 warn。
     * 正常遥测报文只有几百字节，走到这里说明设备发了异常大的东西，
     * 这件事本身值得被看见。
     */
    private static final int MAX_PAYLOAD_CHARS = 60000;

    private final DeviceDao deviceDao;
    private final TaskDao taskDao;
    private final TaskEvidenceDao taskEvidenceDao;
    private final AlertService alertService;

    public TelemetryIngestServiceImpl(DeviceDao deviceDao, TaskDao taskDao,
                                      TaskEvidenceDao taskEvidenceDao, AlertService alertService) {
        this.deviceDao = deviceDao;
        this.taskDao = taskDao;
        this.taskEvidenceDao = taskEvidenceDao;
        this.alertService = alertService;
    }

    @Override
    public void handle(DeviceMessage message) {
        TelemetryEnvelope envelope = message.envelope();

        // ---------- ② 校验信封 ----------
        String observedAtText = (envelope == null) ? null : envelope.getObservedAt();
        if (envelope == null || !StringUtils.hasText(envelope.getType())) {
            log.warn("【MQTT 处理 ② 校验信封】报文缺少 type 字段，已丢弃，device={}，topic={}",
                    message.deviceCode(), message.topic());
            return;
        }
        if (envelope.getSeq() == null) {
            // seq 是去重键的一部分，缺了它就没法区分「重传」和「真的又发了一条」，
            // 只能整条丢掉 —— 与其存一堆无法去重的重复数据，不如明确拒绝
            log.warn("【MQTT 处理 ② 校验信封】报文缺少 seq 字段（去重键必需），已丢弃，device={}，topic={}",
                    message.deviceCode(), message.topic());
            return;
        }

        LocalDateTime observedAt = parseObservedAt(observedAtText);
        if (observedAt == null) {
            // observed_at 在表上是 NOT NULL，而且它就是「这条证据描述的是哪一刻」。
            // 拿不到就只能丢 —— 退而用「我们收到的时刻」代替是不行的：
            // 那个时刻每次重投都会变，去重键会失效，重投会被当成新数据存进去。
            log.warn("【MQTT 处理 ② 校验信封】报文的时间无法识别（observedAt={}），已丢弃，device={}，topic={}",
                    observedAtText, message.deviceCode(), message.topic());
            return;
        }

        // ---------- ③ 找设备 ----------
        DevicePO device = deviceDao.selectByCode(message.deviceCode());
        if (device == null) {
            // 刻意丢弃而不是「先存下来再说」：设备档案里没有这台设备，
            // 它就不可能被派过任何任务，这些数据永远无法佐证任何事；
            // 而且它通常意味着设备编号配错了、或者有人拿着凭证在乱发。
            // 这两种情况都需要人来处理，所以留一条 warn 让它浮现出来，而不是悄悄收下。
            log.warn("【MQTT 处理 ③ 找设备】收到未登记设备的数据，已丢弃，deviceCode={}，topic={}。"
                            + "请确认设备编号是否填错，或该设备是否尚未在设备管理中登记",
                    message.deviceCode(), message.topic());
            return;
        }

        // ---------- ④ 找任务 ----------
        TaskPO task = taskDao.selectExecutingByDrone(device.getId());

        // ---------- ⑤ 幂等落库 ----------
        // 注意 payload 存的是报文原文而不是解析后的信封 —— 后者会丢掉我们不认识的字段，
        // 而「当时设备到底发了什么」恰恰是证据被追问时最需要的
        int inserted = taskEvidenceDao.insertIgnore(new TaskEvidencePO()
                .setTaskId(task == null ? null : task.getId())
                .setDeviceCode(device.getCode())
                .setType(EvidenceType.TELEMETRY)
                .setSource(EvidenceType.SOURCE_DEVICE)
                .setPayload(truncatePayload(message.raw()))
                .setSeq(envelope.getSeq())
                .setObservedAt(observedAt)
                .setReportedAt(message.receivedAt())
                .setValid(1));

        if (inserted == 0) {
            // 返回 0 有两种可能，值得分开想：
            //   1. QoS 1 的重复投递（同一条消息来了两遍）—— 常态，正常
            //   2. 另有别的订阅者先插了同一条 —— 多实例部署却没配共享订阅时就是这样，
            //      两个实例各收到一份、抢着写，输的那个走到这里（见 docs/MQTT与设备接入.md 4.7）
            // 两种情况都说明「这条数据已经在库里了」，所以都只记日志、不做别的
            log.debug("【MQTT 处理 ⑤ 幂等落库】insertIgnore 返回 0，撞上唯一索引 = 这条已经在库里了"
                            + "（重复投递，或另一个订阅者先插了），device={}，seq={}，observedAt={}",
                    device.getCode(), envelope.getSeq(), observedAt);
        } else {
            // 成功归档也要打一行：否则「走完整条流程」和「在第 ⑤ 步被静默丢掉」
            // 在日志上长得一模一样 —— 排查时最怕这种。
            // 返回值一起打出来，是为了让读日志的人看清 INSERT IGNORE 的语义：
            // 真的插进去了才是 1，撞键被跳过是 0
            log.debug("【MQTT 处理 ⑤ 幂等落库】insertIgnore 返回 {}，新归档一条证据，device={}，seq={}，"
                            + "observedAt={}，taskId={}", inserted,
                    device.getCode(), envelope.getSeq(), observedAt,
                    task == null ? null : task.getId());
        }

        // ---------- ⑥ 触发业务动作（证据维度）----------
        // 注意：这一步只在「有执行中的任务」时才有意义（没有任务就没有证据状态可抬），
        // 所以用 if 包起来，而不是像以前那样直接 return ——
        // 下面还有一步告警识别，它在没有任务时也必须跑（告警可以不属于任何任务）
        if (task == null) {
            // 没有正在执行的任务：数据照样存（试飞、转场、维护时设备也在发），
            // 只是不参与任何任务的证据判定。这正是 task_evidence.task_id 允许为空的意义
            log.debug("【MQTT 处理 ④ 找任务】设备数据未关联到执行中的任务，仅归档，device={}，taskId=null",
                    device.getCode());
        } else {
            confirmEvidence(task, message.receivedAt());
        }

        // ---------- ⑦ 告警识别（与证据维度并列，本轮新增）----------
        // 三个要点：
        //   1. 放在证据之后 —— 告警这步出什么事都丢不掉证据（证据已经在库里了）
        //   2. 不受「有没有执行中任务」影响 —— 告警的归属对象可能是设备而不是任务：
        //      机库自检报电池温度高，不属于任何任务，但必须记下来、必须有人管
        //   3. 一条「电量低」的遥测会同时留下证据与告警，两者回答不同问题，都要留
        // 遥测、状态、事件三种 topic 都走这里（分流在 AlertDetector 里按 topic 判断）
        alertService.raiseForDeviceMessage(message, device, task, observedAt);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 把任务的执行依据抬到「已证实」。
     *
     * <p>先看内存里这段状态再决定要不要发 UPDATE：任务读到 {@link EvidenceState#CONFIRMED}
     * 之后，后面每一条遥测都再打一次注定影响 0 行的 UPDATE 是纯浪费 ——
     * 而遥测是高频数据，这条路径上的浪费会被放大成真实的数据库压力。
     *
     * <p>但这只是<b>优化</b>，正确性靠的是 SQL 里的 {@code AND evidence_state <> 'confirmed'}：
     * 内存里的判断和数据库之间有时间差，别的地方可能刚好把它改了。
     */
    private void confirmEvidence(TaskPO task, LocalDateTime receivedAt) {
        if (EvidenceState.CONFIRMED.equals(task.getEvidenceState())) {
            return;
        }

        int updated = taskDao.markEvidenceConfirmed(task.getId(), EvidenceType.TELEMETRY, receivedAt);
        if (updated > 0) {
            // 这是整条链路上最该被看见的一行：从这一刻起，
            // 「这条任务在飞」不再只是飞手的一句话，而是有设备数据撑腰的。
            // 把「原依据」一起打出来，是为了区分两种完全不同的情况：
            // declared → confirmed 是正常流程；stale → confirmed 说明数据来得太晚，
            // 中间那段时间管理员看到的是「声明已过期」，值得回头看看是网络问题还是别的
            log.info("【MQTT 处理 ⑥ 触发业务动作】任务执行证据已证实，taskId={}，编号={}，设备={}，"
                            + "原依据={}（{}），依据类型={}",
                    task.getId(), task.getTaskNo(), task.getDroneCode(),
                    task.getEvidenceState(), EvidenceState.label(task.getEvidenceState()),
                    EvidenceType.TELEMETRY);
        }
    }

    /**
     * 宽容地解析设备侧时间，认不出来返回 null。
     *
     * <p>之所以要「宽容」，是因为<b>时间格式不由我们决定</b>：
     * 不同厂商、不同固件版本发出来的东西都不一样。四种格式按可能性从高到低依次尝试：
     * <ol>
     *   <li>{@code yyyy-MM-dd HH:mm:ss} —— 最常见，也是我们文档里推荐的格式</li>
     *   <li>纯数字时间戳 —— 有些固件直接发 epoch 毫秒，也有的发 epoch 秒</li>
     *   <li>带时区偏移的 ISO 串 {@code 2026-09-18T10:31:02+08:00} —— 国际化设备常见</li>
     *   <li>不带偏移的 ISO 串 {@code 2026-09-18T10:31:02} —— 安卓/JS 生态里很常见</li>
     * </ol>
     *
     * <p><b>为什么不用 DateTimeFormatter 的「可选段」一把梭</b>：
     * 那需要写一个非常复杂的 pattern，出错时的错误信息也难读。
     * 依次尝试虽然朴素，但每种格式对应一行明确的「这是什么设备发来的」，
     * 将来要加格式或去掉格式都很好定位。
     *
     * <p>时间戳的秒/毫秒靠位数区分（10 位以内当秒，更长的当毫秒）：
     * 这是业界通行的约定，比「猜某个数值合不合理」可靠。
     *
     * <p>带偏移量的时间会<b>换算成本地时区</b>再存：数据库里所有时间都是本地墙上时间
     * （与 {@code application.yml} 里 Jackson 的 time-zone 一致），
     * 混着存会让「按时间排序」直接失去意义。
     */
    private LocalDateTime parseObservedAt(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String value = text.trim();

        // 纯数字：epoch 时间戳
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                long numeric = Long.parseLong(value);
                long millis = (value.length() <= 10) ? numeric * 1000L : numeric;
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            } catch (RuntimeException e) {
                // 数字太长溢出、或超出可表示范围，都算认不出来
                return null;
            }
        }

        try {
            return LocalDateTime.parse(value, DEVICE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // 继续试下一种格式
        }

        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续试下一种格式
        }

        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 超过字段容量就先截断，避免 MySQL 在 INSERT IGNORE 下静默剪掉后半截 */
    private String truncatePayload(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() <= MAX_PAYLOAD_CHARS) {
            return raw;
        }
        log.warn("【MQTT 处理 ⑤ 幂等落库】设备报文过大（{} 字符），已截断到 {} 字符后归档",
                raw.length(), MAX_PAYLOAD_CHARS);
        return raw.substring(0, MAX_PAYLOAD_CHARS);
    }
}
