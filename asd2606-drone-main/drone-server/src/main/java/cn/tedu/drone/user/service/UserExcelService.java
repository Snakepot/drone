package cn.tedu.drone.user.service;

import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;

/**
 * 员工 Excel 导入导出业务接口。
 *
 * <p>为什么导出方法收 {@code OutputStream} 而不是 {@code HttpServletResponse}？
 * 让 Service 不依赖 Web 层 —— 它只负责「把 Excel 写进某个输出流」，
 * 至于这个流是 HTTP 响应、是本地文件还是内存，由调用方决定。
 * 这样单元测试里可以直接传一个 {@code ByteArrayOutputStream} 来验证内容。
 */
public interface UserExcelService {

    /**
     * 写出员工导入模版（表头 + 一行示例数据）
     *
     * @param out 输出流，由调用方负责关闭
     */
    void writeImportTemplate(OutputStream out);

    /**
     * 导入员工。
     *
     * <p>逐行解析、分批入库；单行校验失败不会中断整体，
     * 结果里会带上失败明细（行号 + 原因）。
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果（总数、成功数、失败数、失败明细）
     */
    ImportResultVO importUsers(MultipartFile file);

    /**
     * 按条件导出员工。
     *
     * <p>与列表查询共用同一套条件，所以「先筛选、再导出」的结果和页面所见一致。
     * 内部按批取数、流式写出，内存占用与总行数无关。
     *
     * @param param 查询条件（分页参数由方法内部逐批设置）
     * @param out   输出流，由调用方负责关闭
     */
    void exportUsers(UserQueryParam param, OutputStream out);
}
