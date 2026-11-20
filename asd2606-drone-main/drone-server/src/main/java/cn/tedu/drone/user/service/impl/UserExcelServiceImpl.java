package cn.tedu.drone.user.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.user.dao.RoleDao;
import cn.tedu.drone.user.dao.UserDao;
import cn.tedu.drone.user.excel.UserImportListener;
import cn.tedu.drone.user.pojo.RolePO;
import cn.tedu.drone.user.pojo.UserExportDTO;
import cn.tedu.drone.user.pojo.UserExportRow;
import cn.tedu.drone.user.pojo.UserImportDTO;
import cn.tedu.drone.user.pojo.UserQueryParam;
import cn.tedu.drone.user.service.UserExcelService;
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
import java.util.stream.Collectors;

/**
 * 员工 Excel 导入导出实现。
 *
 * <h2>贯穿全篇的一个原则</h2>
 * <b>任何环节都不要把全量数据一次性读进内存</b>：
 * <ul>
 *   <li>导入：逐行回调，攒够 1000 行写一次库，写完全部清空</li>
 *   <li>导出：每批查 5000 条，写完一批再查下一批</li>
 * </ul>
 * 所以内存占用只与「批次大小」有关，与文件/数据总量无关。
 *
 * <h2>当前是同步实现，什么情况下要改异步</h2>
 * 数据量到百万级、或者导入耗时超过网关超时（常见 30~60 秒）时，
 * 应该改成「上传即返回任务 ID，后台慢慢跑，前端轮询进度」。
 * 完整方案见 docs/Excel导入导出.md。
 */
@Slf4j
@Service
public class UserExcelServiceImpl implements UserExcelService {

    /** 导出时每批取多少条。太小则查询次数多，太大则单批内存高 */
    private static final int EXPORT_BATCH_SIZE = 5000;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /** 批次写入器单独成 Bean，这样它上面的 @Transactional 才会生效 */
    @Autowired
    private UserBatchWriter batchWriter;

    // ------------------------------------------------------------------
    // 一、导入模版
    // ------------------------------------------------------------------

    @Override
    public void writeImportTemplate(OutputStream out) {
        // 模版里带一行示例，用户一眼就知道每列该填什么格式。
        // 这里用普通 setter 而不是链式 —— 原因见 UserImportDTO 的类注释。
        UserImportDTO sample = new UserImportDTO();
        sample.setUsername("13800001007");
        sample.setName("孙悦");
        sample.setPassword("123456");
        sample.setPhone("13800001007");
        sample.setEmail("sunyue@drone.com");
        sample.setAge(26);
        sample.setGender("female");
        sample.setRoleCode("pilot");

        EasyExcel.write(out, UserImportDTO.class)
                .sheet("员工导入模版")
                .doWrite(List.of(sample));

        log.info("员工导入模版已写出");
    }

    // ------------------------------------------------------------------
    // 二、导入
    // ------------------------------------------------------------------

    @Override
    public ImportResultVO importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要导入的文件");
        }

        // 预加载两份校验数据，让监听器在内存里做判断、不必逐行查库
        Map<String, Long> roleIdByCode = roleDao.selectAll().stream()
                .collect(Collectors.toMap(RolePO::getCode, RolePO::getId, (a, b) -> a));
        Set<String> existingUsernames = new HashSet<>(userDao.selectUsernames());

        UserImportListener listener = new UserImportListener(
                roleIdByCode, existingUsernames, batchWriter::write);

        long start = System.currentTimeMillis();
        try (InputStream in = file.getInputStream()) {
            // 关键：EasyExcel 按行回调 listener，不会把整个文件读进内存
            EasyExcel.read(in, UserImportDTO.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage());
        }

        ImportResultVO result = listener.getResult();
        log.info("员工导入完成，文件名={}，总行数={}，成功={}，失败={}，耗时={}ms",
                file.getOriginalFilename(), result.getTotal(), result.getSuccess(),
                result.getFailed(), System.currentTimeMillis() - start);
        return result;
    }

    // ------------------------------------------------------------------
    // 三、导出
    // ------------------------------------------------------------------

    @Override
    public void exportUsers(UserQueryParam param, OutputStream out) {
        UserQueryParam query = (param == null) ? new UserQueryParam() : param;

        ExcelWriter writer = EasyExcel.write(out, UserExportDTO.class).build();
        WriteSheet sheet = EasyExcel.writerSheet("员工列表").build();

        long written = 0;
        int page = 1;
        try {
            while (true) {
                // 逐批取数：写完一批就丢掉，再取下一批
                query.setPage(page).setPageSize(EXPORT_BATCH_SIZE);
                List<UserExportRow> batch = userDao.selectForExport(query);
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
            // 必须 finish，否则 Excel 的最后一部分数据不会落盘
            writer.finish();
        }

        log.info("员工导出完成，共 {} 条", written);
    }

    private UserExportDTO toExportDTO(UserExportRow row) {
        return new UserExportDTO()
                .setUsername(row.getUsername())
                .setName(row.getName())
                .setPhone(row.getPhone())
                .setEmail(row.getEmail())
                .setAge(row.getAge())
                .setGender(genderText(row.getGender()))
                .setRoleNames(row.getRoleNames())
                .setStatusText("0".equals(row.getStatus()) ? "禁用" : "启用")
                .setCreateTime(row.getCreateTime() == null
                        ? ""
                        : row.getCreateTime().format(DATE_TIME_FORMATTER));
    }

    private String genderText(String gender) {
        if ("male".equals(gender)) {
            return "男";
        }
        if ("female".equals(gender)) {
            return "女";
        }
        return gender;
    }
}
