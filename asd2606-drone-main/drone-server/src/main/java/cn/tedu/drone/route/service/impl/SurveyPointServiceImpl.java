package cn.tedu.drone.route.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.CoordinateUtil;
import cn.tedu.drone.common.GeoInput;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.route.dao.SurveyPointDao;
import cn.tedu.drone.route.pojo.PointCategory;
import cn.tedu.drone.route.pojo.SurveyPointPO;
import cn.tedu.drone.route.pojo.SurveyPointQueryParam;
import cn.tedu.drone.route.pojo.SurveyPointSaveParam;
import cn.tedu.drone.route.pojo.SurveyPointVO;
import cn.tedu.drone.route.service.SurveyPointService;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.pojo.UserPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点位库实现。
 *
 * <h2>这个类里最要紧的几行，是坐标的那两处转换</h2>
 * 库里的 {@code lng/lat} 是 WGS84，接口出入是 GCJ-02：
 * <ul>
 *   <li>{@link #toVO} —— 出：{@code wgs84ToGcj02}</li>
 *   <li>{@link #toPO} —— 入：{@code gcj02ToWgs84}</li>
 * </ul>
 * 只有这两个地方碰坐标系，别处（DAO、Mapper）一律按「就是 WGS84」处理。
 * 这样「一张表里混进两种坐标」这件事在结构上就不可能发生。
 */
@Slf4j
@Service
public class SurveyPointServiceImpl implements SurveyPointService {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int CODE_MAX_LENGTH = 50;
    private static final int REMARK_MAX_LENGTH = 500;

    @Autowired
    private SurveyPointDao surveyPointDao;

    /** 用来把 create_by 这个 id 换成人名存下来（和任务、告警模块同一个做法） */
    @Autowired
    private UserDao userDao;

    @Override
    public PageResult<SurveyPointVO> pagePoints(SurveyPointQueryParam param) {
        SurveyPointQueryParam query = (param == null) ? new SurveyPointQueryParam() : param;

        long total = surveyPointDao.countByCondition(query);
        List<SurveyPointVO> list = (total == 0)
                ? List.of()
                : surveyPointDao.selectByCondition(query).stream().map(this::toVO).toList();

        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    public SurveyPointVO getPoint(Long id) {
        return toVO(requirePoint(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SurveyPointVO addPoint(SurveyPointSaveParam param) {
        SurveyPointPO point = toPO(param, null);

        LoginUser current = UserContext.get();
        Long operatorId = current == null ? null : current.getId();
        point.setCreateBy(operatorId).setCreateByName(resolveUserName(operatorId, current));

        surveyPointDao.insert(point);
        log.info("新增点位，id={}，名称={}，类型={}", point.getId(), point.getName(), point.getCategory());

        // 回查再转 VO：create_time 是数据库的 DEFAULT CURRENT_TIMESTAMP 生成的，
        // 插入时内存里的 PO 上还是 null
        return toVO(surveyPointDao.selectById(point.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePoint(SurveyPointSaveParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("缺少点位 ID");
        }
        SurveyPointPO existing = requirePoint(param.getId());

        SurveyPointPO update = toPO(param, existing);
        surveyPointDao.update(update);
        log.info("编辑点位，id={}，名称={}", param.getId(), update.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePoint(Long id) {
        SurveyPointPO existing = requirePoint(id);
        surveyPointDao.deleteById(id);
        log.info("删除点位，id={}，名称={}", id, existing.getName());
    }

    @Override
    public Map<Long, String> namesOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        // 同一批 id 去重后再查：航线里同一个点位被用两次是常见的
        Map<Long, String> names = new HashMap<>();
        for (SurveyPointPO point : surveyPointDao.selectByIds(ids.stream().distinct().toList())) {
            names.put(point.getId(), point.getName());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 参数校验与转换
    // ------------------------------------------------------------------

    /**
     * 参数 → PO，顺带把校验做完。
     *
     * @param existing 编辑时传入当前库里的那条（新增时为 null）。
     *                 它的用处只有一个：判断「编号没被改成别人的」——
     *                 改的是自己原来那个编号时要放过
     */
    private SurveyPointPO toPO(SurveyPointSaveParam param, SurveyPointPO existing) {
        if (param == null) {
            throw new BusinessException("缺少点位参数");
        }
        String name = trimToNull(param.getName());
        if (name == null) {
            throw new BusinessException("点位名称不能为空");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException("点位名称不能超过 " + NAME_MAX_LENGTH + " 个字");
        }

        String remark = trimToNull(param.getRemark());
        if (remark != null && remark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException("备注不能超过 " + REMARK_MAX_LENGTH + " 个字");
        }

        // 编号可空。空字符串要在这里就换成 null —— 库里唯一索引把多个 NULL 当不同，
        // 但只允许有一个空串；留个空串会让「第二个不填编号的点位」插不进去
        String code = trimToNull(param.getCode());
        if (code != null) {
            if (code.length() > CODE_MAX_LENGTH) {
                throw new BusinessException("点位编号不能超过 " + CODE_MAX_LENGTH + " 个字");
            }
            SurveyPointPO other = surveyPointDao.selectByCode(code);
            boolean sameAsSelf = other != null && existing != null && other.getId().equals(existing.getId());
            if (other != null && !sameAsSelf) {
                throw new BusinessException("点位编号已存在：" + code);
            }
        }

        String category = trimToNull(param.getCategory());
        category = (category == null) ? PointCategory.DEFAULT : category;
        if (!PointCategory.isValid(category)) {
            throw new BusinessException("点位类型只能是：" + String.join(" / ", PointCategory.ALL));
        }

        double[] gcj = GeoInput.require(param.getLng(), param.getLat(), "点位");
        GeoInput.validateAlt(param.getAlt(), "点位");
        // 校验通过后才是换算：坐标合不合法与用哪种坐标系无关，
        // 先挑出明显错的，报错信息才不会被转换搅乱
        double[] wgs = CoordinateUtil.gcj02ToWgs84(gcj[0], gcj[1]);

        return new SurveyPointPO()
                .setId(param.getId())
                .setCode(code)
                .setName(name)
                .setLng(wgs[0])
                .setLat(wgs[1])
                .setAlt(param.getAlt())
                .setCategory(category)
                .setRemark(remark);
    }

    /**
     * PO → VO：<b>库里的 WGS84 在这里转成 GCJ-02</b>，并补上类型中文名。
     *
     * <p>这是「出」的唯一一处转换，见类注释。
     */
    private SurveyPointVO toVO(SurveyPointPO point) {
        if (point == null) {
            return null;
        }
        double[] gcj = CoordinateUtil.wgs84ToGcj02(point.getLng(), point.getLat());
        return new SurveyPointVO()
                .setId(point.getId())
                .setCode(point.getCode())
                .setName(point.getName())
                .setLng(gcj[0])
                .setLat(gcj[1])
                .setAlt(point.getAlt())
                .setCategory(point.getCategory())
                .setCategoryLabel(PointCategory.label(point.getCategory()))
                .setRemark(point.getRemark())
                .setCreateByName(point.getCreateByName())
                .setCreateTime(point.getCreateTime())
                .setUpdateTime(point.getUpdateTime());
    }

    private SurveyPointPO requirePoint(Long id) {
        if (id == null) {
            throw new BusinessException("缺少点位 ID");
        }
        SurveyPointPO point = surveyPointDao.selectById(id);
        if (point == null) {
            throw new BusinessException("点位不存在");
        }
        return point;
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
