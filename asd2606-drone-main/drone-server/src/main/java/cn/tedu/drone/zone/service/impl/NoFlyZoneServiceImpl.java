package cn.tedu.drone.zone.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.common.GeoInput;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.pojo.UserPO;
import cn.tedu.drone.zone.dao.NoFlyZoneDao;
import cn.tedu.drone.zone.geo.CircleGeometry;
import cn.tedu.drone.zone.geo.ZoneGeometry;
import cn.tedu.drone.zone.pojo.NoFlyZonePO;
import cn.tedu.drone.zone.pojo.NoFlyZoneQueryParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneSaveParam;
import cn.tedu.drone.zone.pojo.NoFlyZoneVO;
import cn.tedu.drone.zone.pojo.ZoneCheckInput;
import cn.tedu.drone.zone.pojo.ZoneCheckPoint;
import cn.tedu.drone.zone.pojo.ZoneHitVO;
import cn.tedu.drone.zone.pojo.ZonePointVO;
import cn.tedu.drone.zone.pojo.ZoneShape;
import cn.tedu.drone.zone.pojo.ZoneSource;
import cn.tedu.drone.zone.service.NoFlyZoneService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 禁飞区实现。
 *
 * <h2>三件必须记住的事</h2>
 * <ol>
 *   <li><b>进出 GCJ-02，库里 WGS84</b>：转换只在 {@link #toVO}（出）
 *       与 {@link #toPO}（入）发生</li>
 *   <li><b>判定一律在 WGS84 上做</b>：{@link #check} 的输入是 WGS84，
 *       区域几何读出来也是 WGS84，两边同系才不会算出一个假距离</li>
 *   <li><b>「完全禁飞」与「限高区」是两种规则</b>：{@code max_alt} 为 null =
 *       完全禁飞（点在区内就命中）；填了数字 = 限高区（只拦高度已知且超过它的点）</li>
 * </ol>
 *
 * <h2>一个坏区域不能把整条链路带下水</h2>
 * 几何是 JSON 列，理论上只有我们写过，但库里可能被人手工改过。
 * 解析失败的区域<b>跳过并记一条 warn</b>，而不是抛异常 ——
 * 否则一个手抖编辑过的区域会让「创建任务」这个动作整体不可用，
 * 而且报的错和禁飞区没关系，查起来极费劲。
 */
@Slf4j
@Service
public class NoFlyZoneServiceImpl implements NoFlyZoneService {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int REMARK_MAX_LENGTH = 500;
    private static final int RING_MAX_POINTS = 500;

    /** 圆的半径上限（米）：100 公里。再大就不是「一块场地」，多半是把公里填成了米 */
    private static final double RADIUS_MAX_METERS = 100_000.0;

    /** 留痕那句话的长度上限 —— 流转流水的 note 列是有长度的，超了会直接插入失败 */
    private static final int NOTE_MAX_LENGTH = 300;

    /** 留痕里最多列出几个区域名（后面用「等 N 处」收尾） */
    private static final int NOTE_MAX_ZONES = 3;

    @Autowired
    private NoFlyZoneDao noFlyZoneDao;

    /** 读/写 geometry 那一列；Spring 容器里那个（未知字段不报错） */
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDao userDao;

    // ------------------------------------------------------------------
    // 增删改查
    // ------------------------------------------------------------------

    @Override
    public PageResult<NoFlyZoneVO> pageZones(NoFlyZoneQueryParam param) {
        NoFlyZoneQueryParam query = (param == null) ? new NoFlyZoneQueryParam() : param;

        long total = noFlyZoneDao.countByCondition(query);
        List<NoFlyZoneVO> list = (total == 0)
                ? List.of()
                : noFlyZoneDao.selectByCondition(query).stream().map(this::toVO).toList();

        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public NoFlyZoneVO getZone(Long id) {
        return toVO(requireZone(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NoFlyZoneVO addZone(NoFlyZoneSaveParam param) {
        NoFlyZonePO zone = toPO(param, null);

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();
        zone.setCreateBy(operatorId).setCreateByName(resolveUserName(operatorId, current));

        noFlyZoneDao.insert(zone);
        log.info("新增禁飞区，id={}，名称={}，形状={}，限高={}",
                zone.getId(), zone.getName(), zone.getShape(),
                zone.getMaxAlt() == null ? "完全禁飞" : zone.getMaxAlt() + "米");

        return toVO(noFlyZoneDao.selectById(zone.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateZone(NoFlyZoneSaveParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("缺少区域 ID");
        }
        NoFlyZonePO existing = requireZone(param.getId());

        noFlyZoneDao.update(toPO(param, existing));
        log.info("编辑禁飞区，id={}，名称={}", param.getId(), existing.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteZone(Long id) {
        NoFlyZonePO zone = requireZone(id);
        noFlyZoneDao.deleteById(id);
        log.info("删除禁飞区，id={}，名称={}（已创建任务当时的命中留痕不受影响）", id, zone.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long id, boolean enabled) {
        NoFlyZonePO zone = requireZone(id);
        noFlyZoneDao.updateEnabled(id, enabled ? 1 : 0);
        log.info("{}禁飞区，id={}，名称={}", enabled ? "启用" : "停用", id, zone.getName());
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Override
    public List<ZoneHitVO> check(ZoneCheckInput input) {
        if (input == null) {
            return List.of();
        }
        boolean hasPoints = input.points() != null && !input.points().isEmpty();
        boolean hasPolyline = input.polyline() != null && input.polyline().size() >= 2;
        if (!hasPoints && !hasPolyline) {
            return List.of();
        }

        List<NoFlyZonePO> zones = noFlyZoneDao.selectEffective(LocalDateTime.now());
        if (zones.isEmpty()) {
            return List.of();
        }

        List<ZoneHitVO> hits = new ArrayList<>();
        for (NoFlyZonePO zone : zones) {
            ZoneShapeData shape = parseGeometry(zone);
            if (shape == null) {
                // 坏数据：跳过 + 留痕。绝不能让它把「创建任务」整个卡住
                log.warn("禁飞区几何无法解析，本次校验跳过：id={}，name={}，shape={}",
                        zone.getId(), zone.getName(), zone.getShape());
                continue;
            }

            // 先看「有名字的点」（起降点、航点）：它们的提示信息最有用
            ZoneHitVO hit = null;
            if (hasPoints) {
                for (ZoneCheckPoint point : input.points()) {
                    if (isHit(shape, zone, point.lng(), point.lat(), point.alt())) {
                        hit = buildHit(zone, shape, point.label(), point.lng(), point.lat(), point.alt());
                        break;
                    }
                }
            }
            // 没有点命中，再看「线有没有穿过去」——一份命中就够，不重复报同一个区域
            if (hit == null && hasPolyline) {
                hit = checkPolyline(shape, zone, input.polyline());
            }
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * 折线是否穿过了这个区域。
     *
     * <p>先按间隔插值成密集点，再逐点用同一套判定（见 {@link ZoneGeometry#samplePolyline}）。
     * 插值出来的点<b>没有作业高度</b>，所以「限高区」不会因为它们而命中 ——
     * 只有「完全禁飞」的区域会。
     */
    private ZoneHitVO checkPolyline(ZoneShapeData shape, NoFlyZonePO zone, List<double[]> polyline) {
        for (double[] sample : ZoneGeometry.samplePolyline(polyline, 50.0)) {
            if (isHit(shape, zone, sample[0], sample[1], null)) {
                return buildHit(zone, shape, "航线途经点", sample[0], sample[1], null);
            }
        }
        return null;
    }

    /**
     * 一个点在不在这个区域内。
     *
     * <p>顺序不能反：<b>先外接矩形预筛，再精确判定</b>。
     * 预筛便宜几个数量级，而第四步每条遥测都要判一次。
     *
     * <p>「完全禁飞」与「限高区」的区别就落在最后一行：
     * 限高区只拦「高度已知且超过它」的点；高度未知（起降点、插值点）时不判超高 ——
     * 拿不到高度就说人家超高，是编事实。
     */
    private boolean isHit(ZoneShapeData shape, NoFlyZonePO zone, double lng, double lat, Double alt) {
        if (!shape.bounds().contains(lng, lat)) {
            return false;
        }
        boolean inside = (shape.ring() != null)
                ? ZoneGeometry.inRing(lng, lat, shape.ring())
                : ZoneGeometry.inCircle(lng, lat,
                        shape.circle().lng(), shape.circle().lat(), shape.circle().radius());
        if (!inside) {
            return false;
        }
        if (zone.getMaxAlt() == null) {
            return true;
        }
        return alt != null && alt > zone.getMaxAlt();
    }

    private ZoneHitVO buildHit(NoFlyZonePO zone, ZoneShapeData shape,
                               String pointLabel, double lng, double lat, Double alt) {
        boolean forbidden = zone.getMaxAlt() == null;
        boolean overAlt = !forbidden;

        // 命中点转成 GCJ-02 再给出去：这个字段是给地图标点用的
        double[] gcj = CoordinateUtil.wgs84ToGcj02(lng, lat);

        String message = forbidden
                ? pointLabel + "落在禁飞区「" + zone.getName() + "」内"
                : pointLabel + "落在限高区「" + zone.getName() + "」内（限高 "
                        + zone.getMaxAlt() + " 米，该点作业高度 " + (alt == null ? "未知" : alt + " 米") + "）";

        return new ZoneHitVO()
                .setZoneId(zone.getId())
                .setZoneName(zone.getName())
                .setSource(zone.getSource())
                .setShape(zone.getShape())
                .setForbidden(forbidden)
                .setMaxAlt(zone.getMaxAlt())
                .setPointLabel(pointLabel)
                .setLng(gcj[0])
                .setLat(gcj[1])
                .setOverAlt(overAlt)
                .setMessage(message);
    }

    @Override
    public String describeHits(List<ZoneHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        // 只说「撞上了哪块区域、是哪个点」——「已知晓风险」这类判断属于调用方
        // （任务那边要连「人写的原因」一起拼），本方法只负责把事实说清楚
        StringBuilder text = new StringBuilder();
        int listed = Math.min(hits.size(), NOTE_MAX_ZONES);
        for (int i = 0; i < listed; i++) {
            ZoneHitVO hit = hits.get(i);
            text.append(i == 0 ? "" : "、")
                    .append("禁飞区「").append(hit.getZoneName()).append("」（").append(hit.getPointLabel()).append("）");
        }
        if (hits.size() > listed) {
            text.append("等共 ").append(hits.size()).append(" 处");
        }

        // note 列有长度上限，超了会直接插入失败（而且失败在「建任务」这一步，很难联想到是备注太长）
        if (text.length() > NOTE_MAX_LENGTH) {
            return text.substring(0, NOTE_MAX_LENGTH) + "…";
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // 几何的解析与序列化（形状决定格式，所以只在这里做）
    // ------------------------------------------------------------------

    /**
     * 区域几何的三种表示：原始（WGS84）的多边形环 / 圆 + 算好的外接矩形。
     *
     * <p>{@code bounds} 随几何一起算好放这儿，是为了让判定那一步只做一件事 ——
     * 每次判定都重算一遍外接矩形是白费（一条航线要判几十个点）。
     */
    private record ZoneShapeData(List<double[]> ring, CircleGeometry circle, ZoneGeometry.Bounds bounds) {
    }

    private ZoneShapeData parseGeometry(NoFlyZonePO zone) {
        if (!StringUtils.hasText(zone.getGeometry())) {
            return null;
        }
        try {
            if (ZoneShape.CIRCLE.equals(zone.getShape())) {
                CircleGeometry circle = objectMapper.readValue(zone.getGeometry(), CircleGeometry.class);
                if (circle.radius() <= 0) {
                    return null;
                }
                return new ZoneShapeData(null, circle,
                        ZoneGeometry.boundsOfCircle(circle.lng(), circle.lat(), circle.radius()));
            }
            List<double[]> ring = objectMapper.readValue(
                    zone.getGeometry(), new TypeReference<List<double[]>>() {
                    });
            ZoneGeometry.Bounds bounds = ZoneGeometry.boundsOfRing(ring);
            return (bounds == null) ? null : new ZoneShapeData(ring, null, bounds);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 参数校验与转换
    // ------------------------------------------------------------------

    private NoFlyZonePO toPO(NoFlyZoneSaveParam param, NoFlyZonePO existing) {
        if (param == null) {
            throw new BusinessException("缺少区域参数");
        }
        String name = param.getName() == null ? null : param.getName().trim();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("区域名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException("区域名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }

        String remark = param.getRemark() == null ? null : param.getRemark().trim();
        if (StringUtils.hasText(remark) && remark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException("备注不能超过 " + REMARK_MAX_LENGTH + " 个字");
        }
        if (!StringUtils.hasText(remark)) {
            remark = null;
        }

        String source = StringUtils.hasText(param.getSource()) ? param.getSource().trim() : ZoneSource.DEFAULT;
        if (!ZoneSource.isValid(source)) {
            throw new BusinessException("来源只能是：" + String.join(" / ", ZoneSource.ALL));
        }

        String shape = StringUtils.hasText(param.getShape()) ? param.getShape().trim() : ZoneShape.DEFAULT;
        if (!ZoneShape.isValid(shape)) {
            throw new BusinessException("形状只能是：" + String.join(" / ", ZoneShape.ALL));
        }

        validateMaxAlt(param.getMaxAlt());
        validateEffectiveWindow(param);

        // enabled：新增默认启用；编辑时不传就沿用原来的值 ——
        // 不传就当成 false，会让一次「只改名字」的编辑把区域停掉，那是个安全控制
        boolean enabled;
        if (param.getEnabled() != null) {
            enabled = param.getEnabled();
        } else {
            enabled = (existing == null) || existing.getEnabled() == 1;
        }

        String geometry = ZoneShape.CIRCLE.equals(shape)
                ? buildCircleGeometry(param)
                : buildRingGeometry(param);

        return new NoFlyZonePO()
                .setId(param.getId())
                .setName(name)
                .setSource(source)
                .setShape(shape)
                .setGeometry(geometry)
                .setMaxAlt(param.getMaxAlt())
                .setEffectiveFrom(param.getEffectiveFrom())
                .setEffectiveTo(param.getEffectiveTo())
                .setEnabled(enabled ? 1 : 0)
                .setRemark(remark);
    }

    private void validateMaxAlt(Integer maxAlt) {
        if (maxAlt == null) {
            return;
        }
        if (maxAlt <= 0 || maxAlt > GeoInput.ALT_MAX) {
            throw new BusinessException("区域内限高应在 1 ~ " + (int) GeoInput.ALT_MAX
                    + " 米之间（不填表示完全禁飞；填 0 不是「不限」而是「限高 0 米」）");
        }
    }

    private void validateEffectiveWindow(NoFlyZoneSaveParam param) {
        if (param.getEffectiveFrom() != null && param.getEffectiveTo() != null
                && param.getEffectiveTo().isBefore(param.getEffectiveFrom())) {
            throw new BusinessException("生效结束不能早于生效开始");
        }
    }

    /** 多边形：顶点逐个校验（GCJ-02）后转 WGS84，再序列化成 {@code [[lng,lat],...]} */
    private String buildRingGeometry(NoFlyZoneSaveParam param) {
        List<ZonePointVO> points = param.getPoints();
        if (points == null || points.size() < ZoneGeometry.MIN_RING_POINTS) {
            throw new BusinessException("多边形至少要 " + ZoneGeometry.MIN_RING_POINTS + " 个顶点");
        }
        if (points.size() > RING_MAX_POINTS) {
            throw new BusinessException("多边形顶点最多 " + RING_MAX_POINTS + " 个，当前 " + points.size() + " 个");
        }

        List<double[]> ring = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            ZonePointVO point = points.get(i);
            String what = "第 " + (i + 1) + " 个顶点";
            double[] gcj = GeoInput.require(point.getLng(), point.getLat(), what);
            ring.add(CoordinateUtil.gcj02ToWgs84(gcj[0], gcj[1]));
        }
        try {
            return objectMapper.writeValueAsString(ring);
        } catch (JsonProcessingException e) {
            throw new BusinessException("保存区域形状失败：" + e.getOriginalMessage());
        }
    }

    /** 圆：圆心（GCJ-02 → WGS84）+ 半径（米） */
    private String buildCircleGeometry(NoFlyZoneSaveParam param) {
        double[] gcj = GeoInput.require(param.getCenterLng(), param.getCenterLat(), "圆心");
        Double radius = param.getRadius();
        if (radius == null || radius <= 0) {
            throw new BusinessException("请填写圆的半径（米）");
        }
        if (radius > RADIUS_MAX_METERS) {
            throw new BusinessException("半径不能超过 " + (int) (RADIUS_MAX_METERS / 1000) + " 公里");
        }
        double[] wgs = CoordinateUtil.gcj02ToWgs84(gcj[0], gcj[1]);
        try {
            return objectMapper.writeValueAsString(new CircleGeometry(wgs[0], wgs[1], radius));
        } catch (JsonProcessingException e) {
            throw new BusinessException("保存区域形状失败：" + e.getOriginalMessage());
        }
    }

    /**
     * PO → VO：<b>库里的 WGS84 在这里转成 GCJ-02</b>，并补上几个给人看的字段。
     */
    private NoFlyZoneVO toVO(NoFlyZonePO zone) {
        if (zone == null) {
            return null;
        }
        ZoneShapeData shape = parseGeometry(zone);

        NoFlyZoneVO vo = new NoFlyZoneVO()
                .setId(zone.getId())
                .setName(zone.getName())
                .setSource(zone.getSource())
                .setSourceLabel(ZoneSource.label(zone.getSource()))
                .setShape(zone.getShape())
                .setShapeLabel(ZoneShape.label(zone.getShape()))
                .setMaxAlt(zone.getMaxAlt())
                .setForbidden(zone.getMaxAlt() == null)
                .setAltRuleLabel(zone.getMaxAlt() == null ? "完全禁飞" : "限高 " + zone.getMaxAlt() + " 米")
                .setEffectiveFrom(zone.getEffectiveFrom())
                .setEffectiveTo(zone.getEffectiveTo())
                .setEnabled(zone.getEnabled() != null && zone.getEnabled() == 1)
                .setRemark(zone.getRemark())
                .setCreateByName(zone.getCreateByName())
                .setCreateTime(zone.getCreateTime())
                .setUpdateTime(zone.getUpdateTime());

        if (shape == null) {
            // 几何坏了也把这条记录返出去（否则列表上会「少一条」，比显示成空形状更难查）
            log.warn("禁飞区几何无法解析，返回空形状：id={}", zone.getId());
            return vo;
        }

        if (shape.ring() != null) {
            List<ZonePointVO> points = new ArrayList<>(shape.ring().size());
            for (double[] wgs : shape.ring()) {
                double[] gcj = CoordinateUtil.wgs84ToGcj02(wgs[0], wgs[1]);
                points.add(new ZonePointVO().setLng(gcj[0]).setLat(gcj[1]));
            }
            vo.setPoints(points);
        } else {
            CircleGeometry circle = shape.circle();
            double[] gcj = CoordinateUtil.wgs84ToGcj02(circle.lng(), circle.lat());
            vo.setCenter(new ZonePointVO().setLng(gcj[0]).setLat(gcj[1]));
            vo.setRadius(circle.radius());
        }
        return vo;
    }

    private NoFlyZonePO requireZone(Long id) {
        if (id == null) {
            throw new BusinessException("缺少区域 ID");
        }
        NoFlyZonePO zone = noFlyZoneDao.selectById(id);
        if (zone == null) {
            throw new BusinessException("禁飞区不存在");
        }
        return zone;
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
