package cn.tedu.drone.route.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.common.GeoDistance;
import cn.tedu.drone.common.GeoInput;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.route.dao.RouteDao;
import cn.tedu.drone.route.pojo.RouteAction;
import cn.tedu.drone.route.pojo.RoutePO;
import cn.tedu.drone.route.pojo.RoutePointPO;
import cn.tedu.drone.route.pojo.RoutePointSaveParam;
import cn.tedu.drone.route.pojo.RoutePointVO;
import cn.tedu.drone.route.pojo.RouteQueryParam;
import cn.tedu.drone.route.pojo.RouteSaveParam;
import cn.tedu.drone.route.pojo.RouteSnapshot;
import cn.tedu.drone.route.pojo.RouteVO;
import cn.tedu.drone.route.service.RouteService;
import cn.tedu.drone.route.service.SurveyPointService;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.pojo.UserPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 航线实现。
 *
 * <h2>三条要记住的规则</h2>
 * <ol>
 *   <li><b>进出 GCJ-02，库里 WGS84</b>：转换只在 {@link #toPointVO}（出）
 *       和 {@link #buildPoints}（入）发生，别处一律当 WGS84 看</li>
 *   <li><b>航点整份替换</b>：改航线 = 删掉旧点 + 按新顺序重插。
 *       不做逐点 diff —— 顺序变化本来就不好 diff，而一条航线只有几十个点</li>
 *   <li><b>seq 由数组下标生成</b>：前端只表达顺序，编号是服务端的事</li>
 * </ol>
 */
@Slf4j
@Service
public class RouteServiceImpl implements RouteService {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int DESC_MAX_LENGTH = 500;

    /** 一个点连不成线，所以下限是 2 */
    private static final int MIN_POINTS = 2;

    /**
     * 航点数上限。
     *
     * <p>取 500：和轨迹抽稀的上限同一个量级。一条巡检航线几十个点，
     * 真到这个数说明要么是导入出了错、要么是有人拿它当轨迹存了。
     * 挡在这里能让「一条航线几万个点」这种情况在写入时就暴露。
     */
    private static final int MAX_POINTS = 500;

    /** 悬停秒数上限（1 小时）；超过它多半是把分钟当秒填了 */
    private static final int HOVER_SECONDS_MAX = 3600;

    @Autowired
    private RouteDao routeDao;

    /** 拿点位名称（详情里显示「这个航点来自哪个点位」），以及校验 pointId 真的存在 */
    @Autowired
    private SurveyPointService surveyPointService;

    /** 用来把 create_by 这个 id 换成人名存下来（和任务、告警模块同一个做法） */
    @Autowired
    private UserDao userDao;

    /** Spring 容器里那个（未知字段不报错，所以以后给快照加字段不会读坏老数据） */
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public PageResult<RouteVO> pageRoutes(RouteQueryParam param) {
        RouteQueryParam query = (param == null) ? new RouteQueryParam() : param;

        long total = routeDao.countByCondition(query);
        // 列表不带航点：见 RouteVO 的说明（避免 N+1）
        List<RouteVO> list = (total == 0)
                ? List.of()
                : routeDao.selectByCondition(query).stream()
                        .map(route -> toVO(route, null)).toList();

        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public RouteVO getRoute(Long id) {
        RoutePO route = requireRoute(id);
        return toVO(route, routeDao.selectPointsByRouteId(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RouteVO addRoute(RouteSaveParam param) {
        String name = requireName(param);
        String description = normalizeDescription(param);
        List<RoutePointPO> points = buildPoints(param);

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();

        RoutePO route = new RoutePO()
                .setName(name)
                .setDescription(description)
                .setPointCount(points.size())
                .setDistanceM(totalDistanceMeters(points))
                .setCreateBy(operatorId)
                .setCreateByName(resolveUserName(operatorId, current));

        routeDao.insert(route);
        insertPoints(route.getId(), points);

        log.info("新增航线，id={}，名称={}，航点 {} 个，航程 {} 米",
                route.getId(), route.getName(), route.getPointCount(), route.getDistanceM());

        return getRoute(route.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoute(RouteSaveParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("缺少航线 ID");
        }
        requireRoute(param.getId());

        String name = requireName(param);
        String description = normalizeDescription(param);
        List<RoutePointPO> points = buildPoints(param);

        RoutePO update = new RoutePO()
                .setId(param.getId())
                .setName(name)
                .setDescription(description)
                .setPointCount(points.size())
                .setDistanceM(totalDistanceMeters(points));
        routeDao.update(update);

        replacePoints(param.getId(), points);

        log.info("编辑航线，id={}，名称={}，航点 {} 个",
                param.getId(), name, points.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(Long id) {
        RoutePO route = requireRoute(id);

        // 先删点再删主表。没有外键约束，顺序其实无所谓，
        // 但按「先子后父」写，将来真加了外键也不用改这里
        routeDao.deletePointsByRouteId(id);
        routeDao.deleteById(id);

        log.info("删除航线，id={}，名称={}（已创建的任务各自留有快照，不受影响）", id, route.getName());
    }

    @Override
    public String snapshotJsonOf(Long routeId) {
        RoutePO route = requireRoute(routeId);

        RouteSnapshot snapshot = new RouteSnapshot()
                .setRouteId(route.getId())
                .setName(route.getName())
                .setPointCount(route.getPointCount())
                .setDistanceM(route.getDistanceM())
                // 直接取库里的点：它们本来就是 WGS84，正好是快照要存的那种
                .setPoints(routeDao.selectPointsByRouteId(routeId));

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // 序列化自己的 PO 理论上不会失败（没有循环引用、没有自定义类型），
            // 但它是受检异常。真出错时必须让创建任务整体回滚 ——
            // 宁可这次建不了任务，也不能存下一条没有航线的任务
            throw new BusinessException("生成航线快照失败：" + e.getOriginalMessage());
        }
    }

    @Override
    public RouteVO fromSnapshot(String snapshotJson) {
        RouteSnapshot snapshot = parseSnapshot(snapshotJson);
        if (snapshot == null) {
            return null;
        }
        return new RouteVO()
                .setId(snapshot.getRouteId())
                .setName(snapshot.getName())
                .setPointCount(snapshot.getPointCount())
                .setDistanceM(snapshot.getDistanceM())
                .setPoints(toPointVOs(snapshot.getPoints()));
    }

    @Override
    public RouteSnapshot parseSnapshot(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotJson, RouteSnapshot.class);
        } catch (JsonProcessingException e) {
            // 一个任务的历史快照读不出来，不该让整个详情页打不开。
            // 少画一条「计划航线」而已，人还能看到轨迹、报告、告警
            log.warn("航线快照解析失败，按「没有这条航线」处理：{}", e.getOriginalMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 航点：校验、换算、落库
    // ------------------------------------------------------------------

    /**
     * 校验并组装航点。
     *
     * <p>返回的每个点：{@code seq} 由数组下标生成、坐标已转成 WGS84、
     * {@code routeId} 还没设（新增时尚不知道 id，由 {@link #insertPoints} 统一补上）。
     */
    private List<RoutePointPO> buildPoints(RouteSaveParam param) {
        List<RoutePointSaveParam> raw = param.getPoints();
        if (raw == null || raw.size() < MIN_POINTS) {
            throw new BusinessException("一条航线至少要有 " + MIN_POINTS + " 个航点");
        }
        if (raw.size() > MAX_POINTS) {
            throw new BusinessException("一条航线最多 " + MAX_POINTS + " 个航点，当前 " + raw.size() + " 个");
        }

        // 先把这一批点引用到的点位一次查出来，用来校验「引用的点位真的存在」。
        // 逐个查就是 N+1；不校验的话，库里会出现点不进去的引用，界面上只显示「没有来源」
        List<Long> pointIds = raw.stream()
                .map(RoutePointSaveParam::getPointId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> knownPointIds = surveyPointService.namesOf(pointIds).keySet();

        List<RoutePointPO> points = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RoutePointSaveParam p = raw.get(i);
            String what = "第 " + (i + 1) + " 个航点";

            double[] gcj = GeoInput.require(p.getLng(), p.getLat(), what);
            GeoInput.validateAlt(p.getAlt(), what);
            double[] wgs = CoordinateUtil.gcj02ToWgs84(gcj[0], gcj[1]);

            String action = p.getAction();
            action = StringUtils.hasText(action) ? action.trim() : RouteAction.DEFAULT;
            if (!RouteAction.isValid(action)) {
                throw new BusinessException(what + "的动作只能是："
                        + String.join(" / ", RouteAction.ALL));
            }
            // 不是悬停就必须把秒数清空：不清的话，「原来悬停 15 秒、改成飞过」
            // 之后库里还留着 15，界面上会显示出一条不存在的悬停要求
            Integer hoverSeconds = null;
            if (RouteAction.HOVER.equals(action)) {
                hoverSeconds = requireHoverSeconds(p.getHoverSeconds(), what);
            }

            if (p.getPointId() != null && !knownPointIds.contains(p.getPointId())) {
                throw new BusinessException(what + "引用的点位不存在：id=" + p.getPointId());
            }

            points.add(new RoutePointPO()
                    .setSeq(i + 1)
                    .setLng(wgs[0])
                    .setLat(wgs[1])
                    .setAlt(p.getAlt())
                    .setAction(action)
                    .setHoverSeconds(hoverSeconds)
                    .setPointId(p.getPointId()));
        }
        return points;
    }

    private Integer requireHoverSeconds(Integer hoverSeconds, String what) {
        if (hoverSeconds == null || hoverSeconds <= 0) {
            throw new BusinessException(what + "选了悬停，请填写悬停秒数");
        }
        if (hoverSeconds > HOVER_SECONDS_MAX) {
            throw new BusinessException(what + "的悬停秒数不能超过 " + HOVER_SECONDS_MAX + " 秒（1 小时）");
        }
        return hoverSeconds;
    }

    /**
     * 整份替换航点：先删光、再按新顺序插入。
     *
     * <p>为什么不做「逐点增删改」：顺序本身就是航线的一部分，
     * 而「把第 2 个点拖到第 5 个位置」在 diff 视角下是四条记录的 seq 都变了 ——
     * 为了省几条 INSERT 去处理这些，得不偿失。删了重插天生就是对的。
     */
    private void replacePoints(Long routeId, List<RoutePointPO> points) {
        routeDao.deletePointsByRouteId(routeId);
        insertPoints(routeId, points);
    }

    private void insertPoints(Long routeId, List<RoutePointPO> points) {
        points.forEach(p -> p.setRouteId(routeId));
        routeDao.insertPoints(points);
    }

    /** 总航程（米，四舍五入到整米）：相邻航点依次累加 */
    private int totalDistanceMeters(List<RoutePointPO> points) {
        double[] lngLat = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            lngLat[i * 2] = points.get(i).getLng();
            lngLat[i * 2 + 1] = points.get(i).getLat();
        }
        return (int) Math.round(GeoDistance.polylineMeters(lngLat));
    }

    // ------------------------------------------------------------------
    // 转换
    // ------------------------------------------------------------------

    private RouteVO toVO(RoutePO route, List<RoutePointPO> points) {
        if (route == null) {
            return null;
        }
        return new RouteVO()
                .setId(route.getId())
                .setName(route.getName())
                .setDescription(route.getDescription())
                .setPointCount(route.getPointCount())
                .setDistanceM(route.getDistanceM())
                .setCreateByName(route.getCreateByName())
                .setCreateTime(route.getCreateTime())
                .setUpdateTime(route.getUpdateTime())
                .setPoints(points == null ? null : toPointVOs(points));
    }

    /**
     * 航点 PO → VO：<b>库里的 WGS84 在这里转成 GCJ-02</b>，并补上两个给人看的字段。
     *
     * <p>来源点位名称是一次批量查出来的（整条航线只用一次查询），不是每个点查一次。
     */
    private List<RoutePointVO> toPointVOs(List<RoutePointPO> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        Map<Long, String> pointNames = surveyPointService.namesOf(points.stream()
                .map(RoutePointPO::getPointId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());

        List<RoutePointVO> result = new ArrayList<>(points.size());
        for (RoutePointPO p : points) {
            double[] gcj = CoordinateUtil.wgs84ToGcj02(p.getLng(), p.getLat());
            result.add(new RoutePointVO()
                    .setSeq(p.getSeq())
                    .setLng(gcj[0])
                    .setLat(gcj[1])
                    .setAlt(p.getAlt())
                    .setAction(p.getAction())
                    .setActionLabel(RouteAction.label(p.getAction()))
                    .setHoverSeconds(p.getHoverSeconds())
                    .setPointId(p.getPointId())
                    .setPointName(p.getPointId() == null ? null : pointNames.get(p.getPointId())));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 校验与查库
    // ------------------------------------------------------------------

    private String requireName(RouteSaveParam param) {
        if (param == null) {
            throw new BusinessException("缺少航线参数");
        }
        String name = param.getName() == null ? null : param.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("航线名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException("航线名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }
        return name;
    }

    /** 描述可空；空字符串归一成 null，免得库里出现「空串描述」和「没有描述」两种空 */
    private String normalizeDescription(RouteSaveParam param) {
        String description = param.getDescription() == null ? null : param.getDescription().trim();
        if (!StringUtils.hasText(description)) {
            return null;
        }
        if (description.length() > DESC_MAX_LENGTH) {
            throw new BusinessException("航线说明不能超过 " + DESC_MAX_LENGTH + " 个字");
        }
        return description;
    }

    private RoutePO requireRoute(Long id) {
        if (id == null) {
            throw new BusinessException("缺少航线 ID");
        }
        RoutePO route = routeDao.selectById(id);
        if (route == null) {
            throw new BusinessException("航线不存在");
        }
        return route;
    }

    private String resolveUserName(Long userId, LoginUser current) {
        if (userId != null) {
            UserPO user = userDao.selectById(userId);
            if (user != null && StringUtils.hasText(user.getName())) {
                return user.getName();
            }
        }
        return current == null ? null : current.getUsername();
    }
}
