package cn.tedu.drone.route.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.route.pojo.RouteQueryParam;
import cn.tedu.drone.route.pojo.RouteSaveParam;
import cn.tedu.drone.route.pojo.RouteSnapshot;
import cn.tedu.drone.route.pojo.RouteVO;

/**
 * 航线（模板）。
 *
 * <h2>进出是 GCJ-02，库里是 WGS84</h2>
 * 与 {@link SurveyPointService} 同一条约定：
 * 返回的坐标已转成 GCJ-02，传进来的坐标也按 GCJ-02 解释。
 *
 * <h2>它还为任务提供「快照」这一对方法</h2>
 * 任务引用的是一条<b>会被改动</b>的模板，所以创建任务时必须复制一份存进
 * {@code task.route_snapshot}。复制与读回这两件事都归航线模块管
 * （{@link #snapshotJsonOf} / {@link #fromSnapshot}）——
 * 任务模块只管把那段 JSON 存下来和取出来，不必知道它里面长什么样。
 * 这样「快照的格式」只有一处定义，将来加字段（比如给每个点加「要不要录像」）
 * 不会漏掉解析的一侧。
 */
public interface RouteService {

    /** 按条件分页查询，<b>不带航点</b>（点在同名的详情接口里） */
    PageResult<RouteVO> pageRoutes(RouteQueryParam param);

    /** 航线详情，带航点（按飞行顺序），坐标为 GCJ-02 */
    RouteVO getRoute(Long id);

    /** 新增航线，返回落库后的详情 */
    RouteVO addRoute(RouteSaveParam param);

    /** 编辑航线：主表与航点一起替换 */
    void updateRoute(RouteSaveParam param);

    /**
     * 删除航线模板。
     *
     * <p>不会影响任何已创建的任务：它们各自留了航线快照
     * （这正是当初做快照的目的，见设计文档 6.5 与迁移脚本里的说明）。
     */
    void deleteRoute(Long id);

    /**
     * 取一条航线模板的快照 JSON，<b>坐标是 WGS84</b>（它要原样存进数据库的 json 列）。
     *
     * <p>刻意返回字符串而不是对象：任务模块把它整段存进 {@code task.route_snapshot}，
     * 不需要理解里面的结构；将来快照格式变了，任务模块一行都不用改。
     *
     * @throws cn.tedu.drone.common.BusinessException 航线不存在
     */
    String snapshotJsonOf(Long routeId);

    /**
     * 把任务上存的快照读回成一个航线对象，<b>坐标已转成 GCJ-02</b>，可直接画。
     *
     * @param snapshotJson 任务上的 {@code route_snapshot} 原文；为空返回 null
     * @return 解析出来的航线；内容不是合法 JSON 时返回 null 并记一条警告 ——
     *         宁可地图上少画一条计划航线，也不该让整个任务详情页打不开
     */
    RouteVO fromSnapshot(String snapshotJson);

    /**
     * 把快照读成「库里的那种形状」—— <b>坐标是 WGS84</b>，没有任何转换。
     *
     * <h2>为什么还要这个方法，{@link #fromSnapshot} 不够吗</h2>
     * 不够，因为两者的用途完全不同，而<b>坐标混用是这个项目里最容易犯、又最难发现的错</b>：
     * <ul>
     *   <li>{@code fromSnapshot} 给的是 <b>GCJ-02</b>，用途是「画在地图上」</li>
     *   <li>本方法给的是 <b>WGS84</b>，用途是「和库里别的东西比」——
     *       目前唯一的调用方是禁飞区校验（区域几何、设备轨迹都在 WGS84 上）</li>
     * </ul>
     * 拿 GCJ-02 的坐标去和 WGS84 的禁飞区比，会得到几百米的假偏差 ——
     * 而它看起来「差不多对」，只是偶尔拦错或不拦。
     * 所以宁可有这两个名字不同、返回值不同的方法，也不要一个「万能」的
     * {@code fromSnapshot(json, boolean toGcj02)}。
     *
     * @return 解析出来的快照；为空或内容非法时返回 null
     */
    RouteSnapshot parseSnapshot(String snapshotJson);
}
