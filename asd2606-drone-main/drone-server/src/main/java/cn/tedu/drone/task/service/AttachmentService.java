package cn.tedu.drone.task.service;

import cn.tedu.drone.task.pojo.TaskAttachmentPO;
import cn.tedu.drone.task.pojo.TaskAttachmentVO;
import cn.tedu.drone.task.pojo.TaskPO;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 附件业务接口（存文件 + 记元信息 + 按告警反查）。
 *
 * <p><b>本接口不做鉴权</b>：谁能上传、谁能下载，判据是「这条任务归不归他」，
 * 而那套规则住在 {@code TaskService}（{@code requireMyTask} 与下载时那条
 * 「有 task:list 看全部，否则必须是本人」）。让鉴权只有一处实现，
 * 比在附件这里再抄一份「本人判断」可靠 —— 抄出来的第二份迟早会和第一份不一致。
 */
public interface AttachmentService {

    /**
     * 保存一个上传的文件。
     *
     * <p>顺序是**先落盘、再插库**（理由见 {@code TaskAttachmentDao.insert}）。
     * 目录按 {@code {任务编号}/{yyyyMMdd}} 分层，文件名是 uuid。
     *
     * @param task        附件归属的任务（调用方已经校验过归属）
     * @param file        上传的文件
     * @param description 说明，选填
     * @return 附件 VO（含 id，前端拿着它去下载或提交上报）
     * @throws cn.tedu.drone.common.BusinessException 文件为空、扩展名不在白名单、超过该类上限
     */
    TaskAttachmentVO upload(TaskPO task, MultipartFile file, String description);

    /** 按主键取记录（下载要先拿到 storage / storage_key）；不存在返回 null */
    TaskAttachmentPO getById(Long id);

    /**
     * 把记录对应的文件读出来（流式）。
     *
     * @throws cn.tedu.drone.common.BusinessException 记录存在但文件不在、
     *         或该记录所在的存储当前未启用
     */
    LoadedAttachment load(TaskAttachmentPO attachment);

    /**
     * 把一批附件挂到某条告警上。
     *
     * @param taskId 这些附件必须属于的任务（越权保护，见 DAO 注释）
     * @throws cn.tedu.drone.common.BusinessException 有附件不属于这条任务，
     *         或已经被别的告警挂走了 —— 整笔上报会因此回滚（宁可整笔失败，
     *         也不能悄悄丢掉飞手传的照片）
     */
    void bindToAlert(Long alertId, Long taskId, List<Long> attachmentIds);

    /**
     * 按一批告警 id 取它们的附件，并按告警 id 分好组。
     *
     * <p>列表接口靠它避开 N+1：一次查回来，在内存里分组。
     *
     * @param alertIds 告警 id 集合；空集合直接返回空 Map（不去查库，也避免拼出
     *                 {@code IN ()} 这种语法错误）
     */
    Map<Long, List<TaskAttachmentVO>> mapByAlertIds(Collection<Long> alertIds);

    /**
     * 取某条任务的全部附件（报告详情页的「附件清单」用它）。
     *
     * <p>与 {@link #mapByAlertIds} 的区别只是关联方式：告警附件靠 {@code alert_id}，
     * 报告附件靠 {@code task_id}。
     */
    List<TaskAttachmentVO> mapByTaskId(Long taskId);

    /**
     * 为一次报告提交准备附件：<b>校验归属</b> + 按提交顺序排好展示次序。
     *
     * <p>做两件事：
     * <ol>
     *   <li><b>校验每个附件都属于这条任务</b>。附件 id 是客户端传上来的，
     *       不校验的话，有人能把自己任务里填上别人的附件 id ——
     *       于是别人的现场照片出现在他的报告里。这是个真实的越权点</li>
     *   <li>按提交顺序写 {@code sort_no}，让照片按飞手选定的先后展示，
     *       而不是上传先后</li>
     * </ol>
     *
     * <h2>为什么它要在「改任务状态」之前调用</h2>
     * 这样校验不通过时<b>什么都还没发生</b> —— 不用依赖「抛异常让事务回滚」
     * 来收拾残局。先校验、后写入，是这类流程该有的顺序
     * （设计文档 2.3 的校验清单里，附件归属本就是一项前置校验）。
     *
     * @param ids 附件 id，可空（没有附件就直接返回）
     * @throws cn.tedu.drone.common.BusinessException 有附件不属于这条任务
     */
    void prepareReportAttachments(Long taskId, List<Long> ids);

    /**
     * 一次读取的结果：元信息 + 可流式读的内容。
     *
     * <p>为什么把两者放一起返回：下载响应既需要 {@code Content-Type} /
     * 文件名（来自元信息），也需要内容。分两次查会让调用方多一次往返，
     * 而它们本来就是同一件事的两半。
     */
    record LoadedAttachment(TaskAttachmentPO meta, Resource resource) {
    }
}
