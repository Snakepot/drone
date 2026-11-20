package cn.tedu.drone.zone.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.zone.pojo.NoFlyZoneQueryParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneSaveParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneVO;
import cn.tedu.drone.zone.pojo.ZoneCheckInput;
import cn.tedu.drone.zone.pojo.ZoneHitVO;

import java.util.List;

/**
 * 禁飞区。
 *
 * <h2>它管两件事，而且这两件事的用法完全不同</h2>
 * <ol>
 *   <li><b>区域的增删改查</b> —— 管理端的日常操作，读写都是人在看</li>
 *   <li><b>校验一批点/一条线有没有撞上区域</b> —— 被任务模块调用，
 *       发生在「创建任务」「派发任务」这两个写操作里（第四步还会被每条遥测调用）</li>
 * </ol>
 * 放在一个 Service 里是对的：两者对「什么算生效」的判断必须完全一致。
 * 拆成两个类，迟早出现「列表里显示生效、校验时不算生效」这种对不上的情况。
 *
 * <h2>⚠️ 坐标：进出 GCJ-02，判定全在 WGS84</h2>
 * 与点位库、航线同一条约定（{@link NoFlyZoneVO} / {@link NoFlyZoneSaveParam}）。
 * 但 {@link #check} 的输入**是 WGS84** —— 因为它的调用方（任务模块）
 * 手里拿的就是库里的坐标，不该为了调这个接口先转一次 GCJ-02 再转回来。
 */
public interface NoFlyZoneService {

    /** 按条件分页查询，坐标为 GCJ-02 */
    PageResult<NoFlyZoneVO> pageZones(NoFlyZoneQueryParam param);

    /** 区域详情，坐标为 GCJ-02；不存在时抛业务异常 */
    NoFlyZoneVO getZone(Long id);

    /** 新增，返回落库后的详情 */
    NoFlyZoneVO addZone(NoFlyZoneSaveParam param);

    /** 编辑（整份覆盖，包含几何形状） */
    void updateZone(NoFlyZoneSaveParam param);

    /**
     * 删除区域。
     *
     * <p>不影响已创建的任务：任务只在「创建/派发的那一刻」被校验过，
     * 当时的命中与原因记在流转流水里 —— 区域后来被删，那段留痕不会跟着消失。
     */
    void deleteZone(Long id);

    /** 启用 / 停用。排查问题时要把某个区临时停掉，但**不该顺手改它的形状** */
    void setEnabled(Long id, boolean enabled);

    /**
     * 校验：点是否落在区内、折线是否从区内穿过。
     *
     * <p>只与「启用中且在生效期内」的区域比 —— 停用的、还没生效的、已经过期的
     * 一律不算，否则「机场大修期间临时停用」这个操作就白做了。
     *
     * @param input 待校验的点与折线，<b>坐标必须是 WGS84</b>
     * @return 命中记录；没有命中时是空列表（不是 null）
     */
    List<ZoneHitVO> check(ZoneCheckInput input);

    /**
     * 把命中记录拼成一句给流转流水用的话（留痕）。
     *
     * <p>为什么放在这里而不是让调用方自己拼：这句话要能被「事后对账」——
     * 看流水的人得知道<b>当时系统提示了哪几块区域</b>。
     * 换一处措辞，历史流水的读法就变了。
     *
     * @return 命中为空时返回 null（调用方据此不加这段留痕）
     */
    String describeHits(List<ZoneHitVO> hits);
}
