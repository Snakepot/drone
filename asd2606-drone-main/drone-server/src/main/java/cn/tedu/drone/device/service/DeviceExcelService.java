package cn.tedu.drone.device.service;

import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;

/**
 * 设备 Excel 导入导出业务接口。
 *
 * <p>与员工模块的设计完全一致：导出方法收 {@code OutputStream} 而不是
 * {@code HttpServletResponse}，让 Service 不依赖 Web 层。
 */
public interface DeviceExcelService {

    /**
     * 写出设备导入模版（表头 + 一行示例）
     *
     * @param out 输出流，由调用方负责关闭
     */
    void writeImportTemplate(OutputStream out);

    /**
     * 导入设备。
     *
     * <p>逐行解析、分批入库；单行校验失败不会中断整体，
     * 结果里会带上失败明细（行号 + 原因）。
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果
     */
    ImportResultVO importDevices(MultipartFile file);

    /**
     * 按条件导出设备。
     *
     * <p>与列表查询共用同一套条件，所以「先筛选、再导出」的结果和页面所见一致。
     *
     * @param param 查询条件（分页参数由方法内部逐批设置）
     * @param out   输出流，由调用方负责关闭
     */
    void exportDevices(DeviceQueryParam param, OutputStream out);
}
