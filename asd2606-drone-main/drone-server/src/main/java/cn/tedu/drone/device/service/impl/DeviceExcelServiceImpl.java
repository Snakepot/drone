package cn.tedu.drone.device.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.device.dao.DeviceDao;
import cn.tedu.drone.device.excel.DeviceImportListener;
import cn.tedu.drone.device.pojo.DeviceExportDTO;
import cn.tedu.drone.device.pojo.DeviceImportDTO;
import cn.tedu.drone.device.pojo.DevicePO;
import cn.tedu.drone.device.pojo.DeviceQueryParam;
import cn.tedu.drone.device.service.DeviceExcelService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备 Excel 导入导出实现。
 *
 * <p>沿用员工模块的原则：<b>任何环节都不把全量数据一次性读进内存</b>。
 * 导入逐行回调、攒够 1000 行写一次；导出每批 5000 行、写完一批再查下一批。
 *
 * <p>完整的大数据量方案（含异步任务）见 docs/Excel导入导出.md。
 */
@Slf4j
@Service
public class DeviceExcelServiceImpl implements DeviceExcelService {

    /** 导出时每批取多少条 */
    private static final int EXPORT_BATCH_SIZE = 5000;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 类型编码 → 中文，仅供导出展示；库里存的仍是编码 */
    private static final Map<String, String> TYPE_TEXT = Map.of(
            "multi-rotor", "多旋翼",
            "fixed-wing", "固定翼");

    /** 状态编码 → 中文 */
    private static final Map<String, String> STATUS_TEXT = Map.of(
            "idle", "待命",
            "assigned", "已派出",
            "flying", "飞行中",
            "charging", "充电中",
            "offline", "离线",
            "maintenance", "维护中");

    @Autowired
    private DeviceDao deviceDao;

    // ------------------------------------------------------------------
    // 一、导入模版
    // ------------------------------------------------------------------

    @Override
    public void writeImportTemplate(OutputStream out) {
        // 模版里带一行示例，用户一眼就知道类型和状态该填什么编码。
        // 这里用普通 setter 而不是链式 —— DeviceImportDTO 刻意没加 @Accessors，原因见其类注释。
        DeviceImportDTO sample = new DeviceImportDTO();
        sample.setCode("DRN-1001");
        sample.setName("示例设备");
        sample.setModel("Matrice 300 RTK");
        sample.setType("multi-rotor");
        sample.setLocation("A 区机库");
        sample.setStatus("idle");

        EasyExcel.write(out, DeviceImportDTO.class)
                .sheet("设备导入模版")
                .doWrite(List.of(sample));

        log.info("设备导入模版已写出");
    }

    // ------------------------------------------------------------------
    // 二、导入
    // ------------------------------------------------------------------

    @Override
    public ImportResultVO importDevices(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要导入的文件");
        }

        // 预加载已存在的编号，让监听器在内存里判断，不必逐行查库
        Set<String> existingCodes = new HashSet<>(deviceDao.selectCodes());

        DeviceImportListener listener = new DeviceImportListener(existingCodes, deviceDao::insertBatch);

        long start = System.currentTimeMillis();
        try (InputStream in = file.getInputStream()) {
            // 关键：EasyExcel 按行回调 listener，不会把整个文件读进内存
            EasyExcel.read(in, DeviceImportDTO.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage());
        }

        ImportResultVO result = listener.getResult();
        log.info("设备导入完成，文件名={}，总行数={}，成功={}，失败={}，耗时={}ms",
                file.getOriginalFilename(), result.getTotal(), result.getSuccess(),
                result.getFailed(), System.currentTimeMillis() - start);
        return result;
    }

    // ------------------------------------------------------------------
    // 三、导出
    // ------------------------------------------------------------------

    @Override
    public void exportDevices(DeviceQueryParam param, OutputStream out) {
        DeviceQueryParam query = (param == null) ? new DeviceQueryParam() : param;

        ExcelWriter writer = EasyExcel.write(out, DeviceExportDTO.class).build();
        WriteSheet sheet = EasyExcel.writerSheet("设备列表").build();

        long written = 0;
        int page = 1;
        try {
            while (true) {
                // 逐批取数：写完一批就丢掉，再取下一批
                query.setPage(page).setPageSize(EXPORT_BATCH_SIZE);
                List<DevicePO> batch = deviceDao.selectByCondition(query);
                if (batch.isEmpty()) {
                    break;
                }

                writer.write(batch.stream().map(this::toExportDTO).toList(), sheet);
                written += batch.size();

                // 不足一批说明已经是最后一页了
                if (batch.size() < EXPORT_BATCH_SIZE) {
                    break;
                }
                page++;
            }
        } finally {
            // 必须 finish，否则 Excel 最后一部分数据不会落盘
            writer.finish();
        }

        log.info("设备导出完成，共 {} 条", written);
    }

    private DeviceExportDTO toExportDTO(DevicePO device) {
        return new DeviceExportDTO()
                .setCode(device.getCode())
                .setName(device.getName())
                .setModel(device.getModel())
                .setTypeText(text(TYPE_TEXT, device.getType()))
                .setPurchaseDate(device.getPurchaseDate() == null ? "" : device.getPurchaseDate().toString())
                .setLocation(device.getLocation())
                .setStatusText(text(STATUS_TEXT, device.getStatus()))
                .setFlightCount(device.getFlightCount())
                .setFlightHours(device.getFlightHours())
                .setCreateTime(device.getCreateTime() == null
                        ? ""
                        : device.getCreateTime().format(DATE_TIME_FORMATTER));
    }

    /**
     * 编码转中文；字典里没有的值原样返回。
     * <p>不认识的编码直接展示编码本身，总比显示空白强 —— 也为将来新增状态留了余地。
     */
    private String text(Map<String, String> dictionary, String code) {
        if (code == null) {
            return "";
        }
        return dictionary.getOrDefault(code, code);
    }
}
