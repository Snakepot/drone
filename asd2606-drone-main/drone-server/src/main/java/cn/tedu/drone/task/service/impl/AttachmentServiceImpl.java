package cn.tedu.drone.task.service.impl;

import cn.tedu.drone.common.BusinessException;
import cn.tedu.drone.common.LoginUser;
import cn.tedu.drone.common.UserContext;
import cn.tedu.drone.common.storage.StorageService;
import cn.tedu.drone.common.storage.StoredObject;
import cn.tedu.drone.task.dao.TaskAttachmentDao;
import cn.tedu.drone.task.pojo.AttachmentType;
import cn.tedu.drone.task.pojo.TaskAttachmentPO;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskPO;
import cn.tedu.drone.task.service.AttachmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件业务实现。
 *
 * <h2>上传这条路上有三个判断，顺序不能乱</h2>
 * <ol>
 *   <li><b>先看扩展名（白名单）</b>：不合法就直接拒，一个字节都不写盘</li>
 *   <li><b>再看大小上限</b>：按类型判（照片 20MB / 视频 500MB / 日志 200MB），
 *       不合法也一个字节都不写盘</li>
 *   <li><b>最后才落盘 + 插库</b>：先落盘再插库，
 *       最坏是「磁盘上多一个没人引用的文件」（能扫出来），
 *       反过来则是「库里有记录、文件却没有」（下载必然报错，只能人工清理）</li>
 * </ol>
 * 前两步都发生在写文件之前，所以一个被拒的上传不会在磁盘上留下任何东西。
 *
 * <h2>不做的事</h2>
 * <b>不校验文件真实内容</b>（扩展名可以改），<b>不做病毒扫描</b>，
 * <b>不解析 EXIF</b>。前两条见 {@link AttachmentType} 的类注释；
 * EXIF 留给报告线 —— 它的用途是拿拍摄时间去核对「这条任务到底什么时候飞的」，
 * 那是报告上的事，这里没有可核对的对象。
 */
@Slf4j
@Service
public class AttachmentServiceImpl implements AttachmentService {

    /** 目录里的日期层 */
    private static final DateTimeFormatter DIR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 库里几列的长度上限，超长会在 INSERT 时被 MySQL 报错（或静默截断），先在应用层截 */
    private static final int MAX_FILE_NAME = 200;
    private static final int MAX_DESCRIPTION = 500;

    private final StorageService storage;
    private final TaskAttachmentDao attachmentDao;

    public AttachmentServiceImpl(StorageService storage, TaskAttachmentDao attachmentDao) {
        this.storage = storage;
        this.attachmentDao = attachmentDao;
    }

    @Override
    public TaskAttachmentVO upload(TaskPO task, MultipartFile file, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }

        String originalName = normalizeFileName(file.getOriginalFilename());

        // ---- 第一关：扩展名白名单 ----
        String fileType = AttachmentType.ofFileName(originalName);
        if (fileType == null) {
            throw new BusinessException("不支持的文件类型：" + extensionHint(originalName)
                    + "。允许的类型：" + String.join("、", AttachmentType.allowedExtensions()));
        }

        // ---- 第二关：分类型的大小上限 ----
        long maxSize = AttachmentType.maxSizeOf(fileType);
        if (file.getSize() > maxSize) {
            throw new BusinessException(AttachmentType.label(fileType) + "不能超过 "
                    + (maxSize / 1024 / 1024) + "MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }

        // ---- 第三关：落盘 ----
        String dir = task.getTaskNo() + "/" + LocalDate.now().format(DIR_DATE);
        String extension = AttachmentType.extensionOf(originalName);
        StoredObject stored;
        try (InputStream in = file.getInputStream()) {
            stored = storage.store(dir, extension, in);
        } catch (IOException e) {
            // 写盘失败是环境问题（磁盘满、权限不对），不是用户操作错了 ——
            // 所以日志要带上任务与文件名，让人能立刻去查
            log.error("附件写盘失败：taskId={}，taskNo={}，fileName={}",
                    task.getId(), task.getTaskNo(), originalName, e);
            throw new BusinessException("文件保存失败，请稍后重试");
        }

        // ---- 记元信息 ----
        LoginUser current = UserContext.get();
        TaskAttachmentPO attachment = new TaskAttachmentPO()
                .setTaskId(task.getId())
                // alert_id 此刻还不存在（告警要在飞手填完说明、提交上报时才创建），
                // 上传与上报是两步。这也是设计文档里「附件先传、拿到 id 再提交」的思路
                .setAlertId(null)
                .setFileName(truncate(originalName, MAX_FILE_NAME))
                // 本地存储时，相对路径与 key 是同一个东西：key 就是根目录下的相对路径。
                // 两列都写上，是为了让「读的时候该看哪一列」这条规则保持唯一
                // （否则本地这行只有 file_path 有值，代码里就得到处判空）
                .setFilePath(stored.key())
                .setStorage(storage.name())
                .setStorageKey(stored.key())
                .setSha256(stored.sha256())
                // MIME 按扩展名查我们自己的表，不用 file.getContentType()（那是客户端说了算的）
                .setMimeType(AttachmentType.mimeTypeOf(originalName))
                .setFileType(fileType)
                .setFileSize(stored.size())
                .setSortNo(0)
                .setDescription(truncate(description, MAX_DESCRIPTION))
                .setUploadBy(current == null ? null : current.getId())
                .setUploadByName(current == null ? null : current.getUsername());

        attachmentDao.insert(attachment);

        log.info("附件已保存：taskId={}，taskNo={}，id={}，名称={}，类型={}，大小={} 字节",
                task.getId(), task.getTaskNo(), attachment.getId(),
                attachment.getFileName(), fileType, stored.size());

        return toVO(attachment);
    }

    @Override
    public TaskAttachmentPO getById(Long id) {
        return (id == null) ? null : attachmentDao.selectById(id);
    }

    @Override
    public LoadedAttachment load(TaskAttachmentPO attachment) {
        if (attachment == null) {
            throw new BusinessException("附件不存在");
        }
        // 记录说它存在哪里，就得由对应的存储实现来读。
        // 迁移期 local 与 oss 并存，一条记录用错的实现去读会得到「文件不存在」，
        // 那个错误信息会把人带到完全错误的方向（去查磁盘权限），所以说清楚原因
        if (!storage.name().equals(attachment.getStorage())) {
            log.warn("附件存放在 {}，当前生效的存储是 {}，无法读取：attachmentId={}",
                    attachment.getStorage(), storage.name(), attachment.getId());
            throw new BusinessException("该附件存放在 " + attachment.getStorage()
                    + "，当前服务未启用该存储，暂时无法下载");
        }
        // 老数据的 storage_key 可能为空（加列之前传上来的），回退到 file_path
        String key = StringUtils.hasText(attachment.getStorageKey())
                ? attachment.getStorageKey()
                : attachment.getFilePath();
        return new LoadedAttachment(attachment, storage.load(key));
    }

    @Override
    public void bindToAlert(Long alertId, Long taskId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        int bound = attachmentDao.bindToAlert(alertId, taskId, attachmentIds);
        if (bound < attachmentIds.size()) {
            // 绑少了只有两种可能：附件不属于这条任务（越权），
            // 或者已经被别的告警挂走了。两种都不该被悄悄忽略 ——
            // 抛出去让整笔上报回滚，飞手重试一次就能看清问题
            log.warn("附件绑定到告警时数量不符：alertId={}，taskId={}，期望 {} 条，实际 {} 条",
                    alertId, taskId, attachmentIds.size(), bound);
            throw new BusinessException("有附件不属于这条任务或已被使用，上报未保存，请重新提交");
        }
    }

    @Override
    public Map<Long, List<TaskAttachmentVO>> mapByAlertIds(Collection<Long> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TaskAttachmentVO>> grouped = new LinkedHashMap<>();
        for (TaskAttachmentPO po : attachmentDao.selectByAlertIds(alertIds)) {
            grouped.computeIfAbsent(po.getAlertId(), k -> new java.util.ArrayList<>()).add(toVO(po));
        }
        return grouped;
    }

    @Override
    public List<TaskAttachmentVO> mapByTaskId(Long taskId) {
        if (taskId == null) {
            return List.of();
        }
        return attachmentDao.selectByTaskId(taskId).stream().map(AttachmentServiceImpl::toVO).toList();
    }

    @Override
    public void prepareReportAttachments(Long taskId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 先校验归属再看要不要写顺序：不校验的话，
        // 有人能把自己任务里填上别人的附件 id，别人的现场照片就出现在他的报告里
        int owned = attachmentDao.countByIdsAndTask(taskId, ids);
        if (owned < ids.size()) {
            log.warn("提交报告时附件归属校验不通过：taskId={}，期望 {} 条，实际命中 {} 条",
                    taskId, ids.size(), owned);
            throw new BusinessException("有附件不属于这条任务，提交未保存，请重新选择附件");
        }
        // 提交顺序就是展示顺序 —— 报告里的照片要按飞手选定的先后排，不是上传先后
        for (int i = 0; i < ids.size(); i++) {
            attachmentDao.updateSortNo(taskId, ids.get(i), i);
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 清理原始文件名：去掉客户端可能带上的路径，掐掉空白。
     *
     * <p>有些客户端（尤其是浏览器之外的）会把完整路径当文件名发过来，
     * 比如 {@code C:\Users\me\Photos\IMG_001.jpg}。这个名字<b>只进数据库、
     * 不参与拼路径</b>（真实文件名是 uuid），所以不构成安全风险，
     * 但把它整段存进去会让文件列表难看得莫名其妙。
     *
     * <p>拿不到文件名时给一个兜底名：{@code file_name} 是 NOT NULL，
     * 存 null 会直接插入失败。
     */
    private static String normalizeFileName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "未命名文件";
        }
        String name = originalFilename.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? "未命名文件" : name;
    }

    /** 错误提示里回显一下拿到的扩展名，帮飞手判断是不是附件选错了 */
    private static String extensionHint(String fileName) {
        String extension = AttachmentType.extensionOf(fileName);
        return extension == null ? "（没有扩展名）" : "." + extension;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 3) + "...";
    }

    private static TaskAttachmentVO toVO(TaskAttachmentPO po) {
        TaskAttachmentVO vo = new TaskAttachmentVO();
        BeanUtils.copyProperties(po, vo);
        return vo;
    }
}
