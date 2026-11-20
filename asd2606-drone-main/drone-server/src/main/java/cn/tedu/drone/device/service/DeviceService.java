package cn.tedu.drone.device.service;

import cn.tedu.drone.common.PageResult;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import cn.tedu.drone.device.pojo.DeviceSaveParam;
import cn.tedu.drone.device.pojo.DeviceVO;

/**
 * 设备业务接口
 */
public interface DeviceService {

    /**
     * 按条件分页查询设备。
     *
     * @param param 查询条件（关键字 / 类型 / 状态 + 分页）
     * @return 分页结果（含总条数）
     */
    PageResult<DeviceVO> pageDevices(DeviceQueryParam param);

    /**
     * 新增设备。
     *
     * @param param 设备信息（编号与名称必填）
     * @throws cn.tedu.drone.common.BusinessException 编号重复、必填项为空
     */
    void addDevice(DeviceSaveParam param);

    /**
     * 编辑设备。
     *
     * <p>设备编号不可修改（它是档案的业务主键，历史记录可能引用它）。
     *
     * @param param 设备信息（必须带 id）
     * @throws cn.tedu.drone.common.BusinessException 设备不存在
     */
    void updateDevice(DeviceSaveParam param);

    /**
     * 查询设备详情，供编辑弹窗回显。
     *
     * @param id 设备 ID
     * @return 设备信息
     */
    DeviceVO getDeviceDetail(Long id);

    /**
     * 改工作状态，但只在它当前确实是 {@code expectStatus} 时才改（比较并交换）。
     *
     * <p>给任务模块联动用的：派发任务时把设备从「待命」置为「已派出」，撤回时置回「待命」。
     * 之所以要「预期值」这个参数，是为了不踩掉人工设置的状态 ——
     * 比如设备刚被改成「维护中」，派发流程就不该把它冲成「已派出」。
     *
     * @param deviceId     设备 ID
     * @param expectStatus 预期当前状态，如 {@code idle}
     * @param newStatus    目标状态，如 {@code assigned}
     * @return true 表示确实改了；false 表示当前状态与预期不符，没动
     */
    boolean changeStatusIfMatch(Long deviceId, String expectStatus, String newStatus);
}
