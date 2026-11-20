package cn.tedu.drone.device.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.device.dao.DeviceDao;
import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import cn.tedu.drone.device.pojo.DeviceSaveParam;
import cn.tedu.drone.device.pojo.DeviceVO;
import cn.tedu.drone.device.service.DeviceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 设备业务实现。
 *
 * <p>整体结构与员工模块保持一致：列表走「先查总数、为 0 就不查数据」，
 * 新增校验编号唯一，编辑不允许改编号。
 */
@Slf4j
@Service
public class DeviceServiceImpl implements DeviceService {

    /** 新增时的默认状态：待命 */
    private static final String DEFAULT_STATUS = "idle";

    @Autowired
    private DeviceDao deviceDao;

    @Override
    public PageResult<DeviceVO> pageDevices(DeviceQueryParam param) {
        DeviceQueryParam query = (param == null) ? new DeviceQueryParam() : param;

        // 先查总数：为 0 就不用再查数据了，省一次数据库往返
        long total = deviceDao.countByCondition(query);
        List<DeviceVO> list = (total == 0)
                ? List.of()
                : deviceDao.selectByCondition(query).stream().map(this::toVO).toList();

        log.debug("分页查询设备，命中 {} 条，取第 {} 页", total, query.getPage());
        return PageResult.of(list, total,
                query.getPage() == null ? 1 : query.getPage(),
                query.getLimit());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addDevice(DeviceSaveParam param) {
        if (param == null || !StringUtils.hasText(param.getCode())) {
            throw new BusinessException("设备编号不能为空");
        }
        if (!StringUtils.hasText(param.getName())) {
            throw new BusinessException("设备名称不能为空");
        }

        String code = param.getCode().trim();
        if (deviceDao.selectByCode(code) != null) {
            throw new BusinessException("设备编号已存在：" + code);
        }

        DevicePO device = new DevicePO()
                .setCode(code)
                .setName(param.getName().trim())
                .setModel(param.getModel())
                .setType(param.getType())
                .setPurchaseDate(param.getPurchaseDate())
                .setLocation(param.getLocation())
                // 状态与累计值给了默认值：新设备必然是待命、还没飞过
                .setStatus(StringUtils.hasText(param.getStatus()) ? param.getStatus() : DEFAULT_STATUS)
                .setFlightCount(StringUtils.hasText(param.getFlightCount()) ? param.getFlightCount() : "0")
                .setFlightHours(StringUtils.hasText(param.getFlightHours()) ? param.getFlightHours() : "0");

        deviceDao.insert(device);
        log.info("新增设备，id={}，编号={}，名称={}", device.getId(), device.getCode(), device.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDevice(DeviceSaveParam param) {
        if (param == null || param.getId() == null) {
            throw new BusinessException("缺少设备 ID");
        }
        if (deviceDao.selectById(param.getId()) == null) {
            throw new BusinessException("设备不存在");
        }

        // 按非空字段更新；编号不在其中 —— 它是业务主键，不允许改
        DevicePO update = new DevicePO()
                .setId(param.getId())
                .setName(param.getName())
                .setModel(param.getModel())
                .setType(param.getType())
                .setPurchaseDate(param.getPurchaseDate())
                .setLocation(param.getLocation())
                .setStatus(param.getStatus())
                .setFlightCount(param.getFlightCount())
                .setFlightHours(param.getFlightHours());

        deviceDao.update(update);
        log.info("编辑设备，id={}", param.getId());
    }

    @Override
    public DeviceVO getDeviceDetail(Long id) {
        DevicePO device = deviceDao.selectById(id);
        if (device == null) {
            throw new BusinessException("设备不存在");
        }
        return toVO(device);
    }

    @Override
    public boolean changeStatusIfMatch(Long deviceId, String expectStatus, String newStatus) {
        if (deviceId == null) {
            return false;
        }
        int rows = deviceDao.updateStatusIfMatch(deviceId, expectStatus, newStatus);
        if (rows == 0) {
            // 不是异常：只说明设备状态已经不是我们以为的样子（别人改过，或刚被派给了别的任务）
            log.debug("设备状态未变更（当前值与预期 {} 不符），deviceId={}", expectStatus, deviceId);
        }
        return rows > 0;
    }

    private DeviceVO toVO(DevicePO device) {
        DeviceVO vo = new DeviceVO();
        BeanUtils.copyProperties(device, vo);
        return vo;
    }
}
