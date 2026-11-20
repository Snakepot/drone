package cn.tedu.drone.device.excel;

import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.device.pojo.DeviceImportDTO;
import cn.tedu.drone.device.pojo.DevicePO;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 设备导入的流式监听器。
 *
 * <p>与员工导入同一个套路：EasyExcel 每解析完一行回调一次 {@link #invoke}，
 * 行数据用完即弃，内存占用只与批次大小有关、与文件多大无关。
 *
 * <p>比员工简单的地方：设备没有角色关联，批量写入只涉及一张表，
 * 所以不需要跨表事务，批次写入器可以直接是个 {@code Consumer<List<DevicePO>>}。
 */
@Slf4j
public class DeviceImportListener implements ReadListener<DeviceImportDTO> {

    /** 攒够多少行提交一次 */
    private static final int BATCH_SIZE = 1000;

    /** 库中已存在的设备编号，Service 预先查好传进来，校验全在内存里完成 */
    private final Set<String> existingCodes;

    /** 批次写入回调 */
    private final Consumer<List<DevicePO>> batchWriter;

    private final List<DevicePO> pending = new ArrayList<>(BATCH_SIZE);

    /** 本文件内已出现过的编号 —— 防止同一个文件里自己跟自己重复 */
    private final Set<String> seenInFile = new HashSet<>();

    private final ImportResultVO result = new ImportResultVO();

    public DeviceImportListener(Set<String> existingCodes, Consumer<List<DevicePO>> batchWriter) {
        this.existingCodes = existingCodes;
        this.batchWriter = batchWriter;
    }

    @Override
    public void invoke(DeviceImportDTO row, AnalysisContext context) {
        // rowIndex 从 0 开始且含表头，+1 之后才是用户在 Excel 里看到的行号
        int rowNum = context.readRowHolder().getRowIndex() + 1;
        result.setTotal(result.getTotal() + 1);

        String error = validate(row);
        if (error != null) {
            result.setFailed(result.getFailed() + 1);
            result.addError(rowNum, error);
            return;
        }

        String code = row.getCode().trim();
        seenInFile.add(code);

        pending.add(new DevicePO()
                .setCode(code)
                .setName(row.getName().trim())
                .setModel(row.getModel())
                .setType(row.getType())
                .setPurchaseDate(row.getPurchaseDate())
                .setLocation(row.getLocation())
                .setStatus(StringUtils.hasText(row.getStatus()) ? row.getStatus() : "idle")
                .setFlightCount(StringUtils.hasText(row.getFlightCount()) ? row.getFlightCount() : "0")
                .setFlightHours(StringUtils.hasText(row.getFlightHours()) ? row.getFlightHours() : "0"));

        if (pending.size() >= BATCH_SIZE) {
            flush(rowNum);
        }
    }

    /**
     * 全部解析完成后调用 —— 别忘了最后不满一批的数据。
     * <p>文件正好 500 行而批次是 1000 时，永远攒不满，不在这里补一次就一条都不入库。
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush(0);

        log.info("设备导入解析完成，总行数={}，成功={}，失败={}",
                result.getTotal(), result.getSuccess(), result.getFailed());
    }

    private void flush(int currentRowNum) {
        if (pending.isEmpty()) {
            return;
        }

        int batchSize = pending.size();
        int startRow = currentRowNum - batchSize + 1;

        try {
            batchWriter.accept(pending);
            result.setSuccess(result.getSuccess() + batchSize);
        } catch (Exception e) {
            // 这一批整体失败，但不向上抛 —— 否则后面的行也会跟着中止
            result.setFailed(result.getFailed() + batchSize);
            result.addError(Math.max(startRow, 2), batchSize + " 条记录写入失败：" + e.getMessage());
            log.warn("设备批量写入失败，本批 {} 条记为失败", batchSize, e);
        } finally {
            pending.clear();
        }
    }

    /**
     * 单行校验，返回错误原因；返回 null 表示通过。
     *
     * <p>状态值只做非空兜底、不限定取值：状态是业务流转出来的，
     * 硬编码白名单容易在新增状态时把导入功能卡住。
     */
    private String validate(DeviceImportDTO row) {
        if (!StringUtils.hasText(row.getCode())) {
            return "设备编号不能为空";
        }
        if (!StringUtils.hasText(row.getName())) {
            return "设备名称不能为空";
        }

        String code = row.getCode().trim();
        if (existingCodes.contains(code)) {
            return "设备编号已存在：" + code;
        }
        if (seenInFile.contains(code)) {
            return "文件内设备编号重复：" + code;
        }

        return null;
    }

    public ImportResultVO getResult() {
        return result;
    }
}
