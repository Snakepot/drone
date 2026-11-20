package cn.tedu.drone.user.excel;

import cn.tedu.drone.common.ImportResultVO;
import cn.tedu.drone.user.pojo.UserImportDTO;
import cn.tedu.drone.user.pojo.UserPO;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 员工导入的流式监听器。
 *
 * <h2>为什么要用监听器，而不是先读成一个 List</h2>
 * EasyExcel 每解析完一行就回调一次 {@link #invoke}，行数据用完即弃。
 * 如果把整个文件读进 {@code List<UserImportDTO>} 再处理，10 万行的文件
 * 会在内存里堆 10 万个对象 —— 用户量再大一点直接把服务撑爆。
 * 用监听器则**内存占用只与批次大小有关，与文件多大无关**。
 *
 * <h2>两个刻意的设计</h2>
 * <ol>
 *   <li><b>单行失败不中断整体</b>：某一行账号重复，不该让后面几百行都白填。
 *       失败的行记进错误清单，成功的照常入库。</li>
 *   <li><b>攒够 1000 行才写一次库</b>：一行一次 insert 意味着 10 万行就是 10 万次
 *       数据库往返，慢到不可接受；批量插入能把往返次数降到千分之一。</li>
 * </ol>
 *
 * <h2>为什么要外部传入角色映射和已有账号</h2>
 * 监听器由 EasyExcel 内部 new 出来，不是 Spring Bean，拿不到 Dao。
 * 与其在里面反查数据库（10 万行就是 20 万次查询），不如由 Service 预先查好两份数据传进来，
 * 校验全在内存里完成。
 */
@Slf4j
public class UserImportListener implements ReadListener<UserImportDTO> {

    /** 攒够多少行提交一次。太小则数据库往返多，太大则内存占用高 */
    private static final int BATCH_SIZE = 1000;

    /** 简单的手机号格式校验 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** 角色编码 → 角色 ID，Service 预先查好 */
    private final Map<String, Long> roleIdByCode;

    /** 库中已存在的登录账号，用于唯一性校验 */
    private final Set<String> existingUsernames;

    /** 批次写入回调，由 Service 提供（内部是批量插入） */
    private final BiConsumer<List<UserPO>, List<Long>> batchWriter;

    /** 待写入的用户 */
    private final List<UserPO> pending = new ArrayList<>(BATCH_SIZE);

    /** 与 pending 一一对应的角色 ID */
    private final List<Long> pendingRoleIds = new ArrayList<>(BATCH_SIZE);

    /** 本文件内已出现过的账号 —— 防止同一个文件里自己跟自己重复 */
    private final Set<String> seenInFile = new HashSet<>();

    private final ImportResultVO result = new ImportResultVO();

    public UserImportListener(Map<String, Long> roleIdByCode,
                              Set<String> existingUsernames,
                              BiConsumer<List<UserPO>, List<Long>> batchWriter) {
        this.roleIdByCode = roleIdByCode;
        this.existingUsernames = existingUsernames;
        this.batchWriter = batchWriter;
    }

    /**
     * 每解析完一行调用一次。
     */
    @Override
    public void invoke(UserImportDTO row, AnalysisContext context) {
        // rowIndex 从 0 开始且含表头，+1 之后才是用户在 Excel 里看到的行号
        int rowNum = context.readRowHolder().getRowIndex() + 1;
        result.setTotal(result.getTotal() + 1);

        String error = validate(row);
        if (error != null) {
            result.setFailed(result.getFailed() + 1);
            result.addError(rowNum, error);
            return;
        }

        String username = row.getUsername().trim();
        seenInFile.add(username);

        pending.add(new UserPO()
                .setUsername(username)
                .setName(row.getName())
                .setPassword(row.getPassword())
                .setPhone(StringUtils.hasText(row.getPhone()) ? row.getPhone().trim() : null)
                .setEmail(StringUtils.hasText(row.getEmail()) ? row.getEmail().trim() : null)
                .setAge(row.getAge())
                .setGender(row.getGender())
                .setStatus("1")
                .setState("idle"));

        pendingRoleIds.add(roleIdByCode.get(row.getRoleCode().trim()));

        if (pending.size() >= BATCH_SIZE) {
            flush(rowNum);
        }
    }

    /**
     * 全部解析完成后调用 —— 把最后不满一批的数据也写进去。
     * <p>这里容易漏：如果文件正好是 500 行，永远攒不满 1000 行，
     * 不在这里补一次 flush 就会一条都不入库。
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush(0);

        log.info("员工导入解析完成，总行数={}，成功={}，失败={}",
                result.getTotal(), result.getSuccess(), result.getFailed());
    }

    /**
     * 提交当前批次。
     * <p>写入失败时把这批全部计入失败，但<b>不向上抛异常</b> ——
     * 否则后面的行会因为这一批的问题全部中止，用户还得重来。
     */
    private void flush(int currentRowNum) {
        if (pending.isEmpty()) {
            return;
        }

        int batchSize = pending.size();
        int startRow = currentRowNum - batchSize + 1;

        try {
            batchWriter.accept(pending, pendingRoleIds);
            result.setSuccess(result.getSuccess() + batchSize);
            log.debug("已写入一批，{} 条，累计成功 {}", batchSize, result.getSuccess());
        } catch (Exception e) {
            result.setFailed(result.getFailed() + batchSize);
            result.addError(Math.max(startRow, 2), batchSize + " 条记录写入失败：" + e.getMessage());
            log.warn("批量写入失败，本批 {} 条记为失败", batchSize, e);
        } finally {
            // 无论成败都清空，保证内存不随文件增长
            pending.clear();
            pendingRoleIds.clear();
        }
    }

    /**
     * 单行校验，返回错误原因；返回 null 表示通过。
     */
    private String validate(UserImportDTO row) {
        if (!StringUtils.hasText(row.getUsername())) {
            return "登录账号不能为空";
        }
        if (!StringUtils.hasText(row.getPassword())) {
            return "密码不能为空";
        }

        String username = row.getUsername().trim();
        if (existingUsernames.contains(username)) {
            return "登录账号已存在：" + username;
        }
        if (seenInFile.contains(username)) {
            return "文件内登录账号重复：" + username;
        }

        if (!StringUtils.hasText(row.getRoleCode())) {
            return "角色编码不能为空";
        }
        if (!roleIdByCode.containsKey(row.getRoleCode().trim())) {
            return "角色编码不存在：" + row.getRoleCode().trim();
        }

        if (StringUtils.hasText(row.getPhone()) && !PHONE_PATTERN.matcher(row.getPhone().trim()).matches()) {
            return "手机号格式不正确：" + row.getPhone();
        }

        return null;
    }

    public ImportResultVO getResult() {
        return result;
    }
}
