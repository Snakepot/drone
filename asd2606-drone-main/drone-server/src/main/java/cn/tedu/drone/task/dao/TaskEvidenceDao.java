package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskEvidencePO;
import cn.tedu.drone.task.pojo.TaskLivePointVO;
import cn.tedu.drone.task.pojo.TaskEvidenceSummary;
import cn.tedu.drone.task.pojo.TaskTrackPoint;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务证据数据访问层。
 *
 * <p>写只有 {@code insertIgnore}（证据是只追加的流水）；
 * 读目前只有报告详情页要的那个汇总 —— 独立的「证据详情页」还没做，
 * 等它来了再按真实需求加查询条件，现在猜大概率猜错。
 */
public interface TaskEvidenceDao {

    /**
     * 插入一条证据，<b>重复即忽略</b>。
     *
     * <h2>为什么用 INSERT IGNORE，而不是先 SELECT 再判断</h2>
     * 「先查有没有，没有再插」在并发下是有洞的：两个线程同时查到「没有」，
     * 然后都去插 —— 重复插入还是发生了。靠应用层判断永远堵不住这个洞，
     * 只有数据库的<b>唯一索引</b>能。所以正确的做法是：
     * 唯一索引放在表上（{@code uk_evidence_dedup}），插入语句用 IGNORE
     * 让数据库在撞键时静默跳过。判断与写入是同一条语句，中间没有窗口。
     *
     * <h2>返回值怎么读</h2>
     * MySQL 对 {@code INSERT IGNORE} 的语义是「跳过」而不是「报错」，
     * 所以返回 <b>1 表示真的插入了新数据，0 表示撞上了唯一键、这条已经在库里了</b>。
     *
     * <p>「已经在库里」有两种来源，调用方看到 0 时值得分一下是哪一种：
     * <ul>
     *   <li><b>重复投递</b>：QoS 1 是「至少一次」，同一条消息被投递两次是常态</li>
     *   <li><b>另一个订阅者先插了</b>：多实例部署却没配共享订阅时，
     *       每个实例都会收到一份、抢着写，输的那个拿到 0（见 docs/MQTT与设备接入.md 4.7）</li>
     * </ul>
     * 两种情况都说明「这条数据已经在库里了」，所以都不该再触发后续的业务动作 ——
     * 这也是本方法要返回行数、而不是返回 void 的原因。
     *
     * <p><b>代价</b>：INSERT IGNORE 会把「唯一键冲突」之外的一些错误也降级成警告
     * （比如字段超长被截断）。对我们可接受 —— 表里没有会导致静默截断的窄字段，
     * 且真正的错误（连接断了、表不存在）依然会抛异常。
     *
     * @return 1 插入成功；0 已存在（重复投递，或别的订阅者先插了）
     */
    int insertIgnore(TaskEvidencePO evidence);

    /**
     * 汇总某条任务的证据（报告详情页的「自动汇总」用它）。
     *
     * <p>口径写在 {@code docs/任务收尾与执行报告.md} 5.2 节，改动要两边一起改。
     * <b>一条证据都没有时也会返回一行</b>（COUNT = 0、其余为 null），
     * 而不是返回 null —— 调用方少一个判空分支。
     *
     * @param taskId 任务 id
     */
    TaskEvidenceSummary selectSummary(@Param("taskId") Long taskId);

    /**
     * 取某条任务的轨迹点（按时间升序）。
     *
     * <p>坐标从报文原文里取 —— 详见 mapper 里的注释（那里写了四个刻意的写法，
     * 包括「非法 JSON 会让 JSON_EXTRACT 直接报错」这个坑）。
     *
     * <p>返回的是 <b>WGS84</b>（设备报的原值），转 GCJ-02 是上层的事。
     *
     * @param taskId 任务 id
     */
    List<TaskTrackPoint> selectTrack(@Param("taskId") Long taskId);

    /**
     * 一次取出**多条任务各自的「当前位置」**（最新一条带坐标的遥测）。
     *
     * <p>监控看板要的是「现在所有飞机都在哪」，一页可能有十几条任务 ——
     * 逐条查就是十几次往返，而这个接口每几秒就会被调一次。
     * 一条查询搞定：用 {@code ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY id DESC)}
     * 给每条任务的行编号，取编号 1 的就是最新那条。
     *
     * <p>⚠️ 「带坐标」这个条件不能省：设备还会发不带经纬度的报文（状态、事件），
     * 最新一条恰好是那种报文时，直接取最新会把飞机标到上一次的位置上。
     *
     * @param taskIds 任务 id 列表，<b>不能为空</b>（空集合会拼出语法错误的 SQL）
     */
    List<TaskLivePointVO> selectLatestPositions(@Param("taskIds") List<Long> taskIds);

    /** 某个时刻之后的证据条数 —— 大屏上「设备到底有没有在发数据」 */
    long countSince(@Param("from") LocalDateTime from);

    /**
     * 最近一段时间内有数据上报的设备数。
     *
     * <p>「设备在线」我们没有实时心跳表 —— 现有的是「它最后一次报数是什么时候」。
     * 所以在线率的口径只能是「最近 N 分钟有数据的设备 / 设备总数」，
     * N 由调用方给（见 {@code Devices.onlineWindowMinutes}）。
     */
    long countActiveDevicesSince(@Param("from") LocalDateTime from);

    /**
     * 一次取出多条任务各自的**最近一小段航迹**（每条最多 {@code perTaskLimit} 个原始点）。
     *
     * <p>看板上每架飞机都要画一条「刚才怎么飞的」，但完整轨迹动辄几千个点 ——
     * 乘上任务数会把响应撑爆。所以限制每条任务取最近 N 条原始点
     * （调用方拿到后再抽稀），点开单条任务时再走 {@link #selectTrack} 拿完整的。
     *
     * <p>同样用 {@code ROW_NUMBER()} 限制「每条前 N 条」，而不是给整条 SQL 加 LIMIT ——
     * 后者会变成「总共只取 N 条」，任务一多就有的任务一个点都拿不到。
     *
     * @param taskIds       任务 id 列表，<b>不能为空</b>
     * @param perTaskLimit  每条任务最多取多少个原始点（按 id 倒序，即最新的 N 个）
     */
    List<TaskLivePointVO> selectRecentTrails(@Param("taskIds") List<Long> taskIds,
                                             @Param("perTaskLimit") int perTaskLimit);
}
