package cn.tedu.drone.task.dao;

import cn.tedu.drone.task.pojo.TaskAttachmentPO;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 附件数据访问层。
 */
public interface TaskAttachmentDao {

    /**
     * 插入一条附件记录（文件此时已经落盘）。
     *
     * <p>顺序是**先落盘、再插库**：反过来的话，插了库却没有文件，
     * 下载时必然报「文件不存在」，而那种脏数据只能靠人工清理。
     * 先落盘再插库，最坏是「磁盘上多一个没人引用的文件」——那是可以扫出来的。
     *
     * @return 影响行数
     */
    int insert(TaskAttachmentPO attachment);

    /** 按主键查（下载前要先拿到 storage / storage_key 才能去读文件） */
    TaskAttachmentPO selectById(@Param("id") Long id);

    /**
     * 按任务查附件（报告详情页的「附件清单」用它）。
     *
     * <p>排序按 {@code sort_no} 再按 id —— 报告里的照片应当按**飞手选定的顺序**排，
     * 而不是上传先后。
     */
    List<TaskAttachmentPO> selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 数一批附件里有几条属于这条任务（<b>只查不改</b>，用于提交报告时的归属校验）。
     *
     * <p>为什么要有这一道：附件 id 是客户端传上来的。不校验归属的话，
     * 有人可以把自己任务里**别人的附件 id** 填进报告 ——
     * 于是别人的现场照片出现在他的报告里。这是个真实的越权点。
     *
     * @return 命中的条数；调用方拿它和 {@code ids.size()} 比，少了就说明有越权
     */
    int countByIdsAndTask(@Param("taskId") Long taskId, @Param("ids") Collection<Long> ids);

    /**
     * 给一张附件排个展示顺序（报告里的照片顺序由飞手选定的先后决定）。
     *
     * <p>逐张更新而不是一条 SQL 搞定：一次提交的附件就几张，
     * 而拼一条 {@code CASE WHEN} 的 SQL 会让这段代码难读得多。
     */
    int updateSortNo(@Param("taskId") Long taskId,
                     @Param("id") Long id,
                     @Param("sortNo") Integer sortNo);

    /**
     * 按<b>一批</b>告警 id 查附件。
     *
     * <p>为什么是批量而不是「按单个告警查」：告警列表页一页 15 条，
     * 逐条去查就是 15 次往返（典型的 N+1）。这里一次查回来，在内存里按 alertId 分组。
     *
     * @param alertIds 告警 id 集合，<b>调用方保证非空</b>
     *                （空的 IN 会拼出 {@code IN ()} 这种语法错误，所以由调用方先判）
     */
    List<TaskAttachmentPO> selectByAlertIds(@Param("alertIds") Collection<Long> alertIds);

    /**
     * 把一批附件挂到某条告警上。
     *
     * <p>{@code AND task_id = #{taskId}} 是<b>越权保护</b>：
     * 附件 id 是客户端传上来的，不加这个条件的话，
     * 有人可以拿到别人的附件 id 把它挂到自己的告警上（等于窃取别人的证据）。
     *
     * @param ids 附件 id 集合（调用方保证非空）
     * @return 实际绑定成功的行数；小于 {@code ids.size()} 说明有附件不属于这条任务
     */
    int bindToAlert(@Param("alertId") Long alertId,
                    @Param("taskId") Long taskId,
                    @Param("ids") Collection<Long> ids);
}
