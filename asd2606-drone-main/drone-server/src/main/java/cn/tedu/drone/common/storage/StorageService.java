package cn.tedu.drone.common.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件的存取抽象 —— <b>业务代码只依赖这个接口，不依赖「文件存在哪」</b>。
 *
 * <h2>为什么现在就要抽这一层</h2>
 * 当前只有本地磁盘一种实现（{@link LocalStorageService}），看起来抽象是多余的。
 * 但**换存储的成本几乎全在调用方**：如果业务代码里到处是
 * {@code new File(root + path)}，将来上对象存储就得把这些地方全找出来改一遍；
 * 而只要大家都调 {@link #store} / {@link #load}，换存储就是新增一个实现类。
 *
 * <p>这也是 {@code task_attachment} 里 {@code storage} 与 {@code storage_key}
 * 两列存在的理由：迁移期两种实现并存，靠 {@code storage} 决定读哪一边。
 * 完整手册（什么时候该换、MinIO 怎么起、怎么迁）见
 * {@code docs/任务收尾与执行报告.md} 第八章。
 *
 * <h2>两条约定</h2>
 * <ol>
 *   <li><b>文件名由实现决定</b>，调用方只给目录前缀和扩展名。
 *       这样「用 uuid 命名」这件事只在实现里说一遍 ——
 *       而且它同时挡住了重名与路径穿越，属于存储的职责，不是调用方的</li>
 *   <li><b>{@code key} 是不透明的</b>：调用方拿到就存进数据库，读的时候原样传回来，
 *       不要自己去拼、也不要解析它的结构</li>
 * </ol>
 */
public interface StorageService {

    /**
     * 本实现对应的存储位置标识，写进 {@code task_attachment.storage} 列。
     *
     * <p>为什么接口上要有这个方法：库里那一列必须有人填、也必须在读的时候对得上。
     * 让实现自己声明「我是谁」，比在业务代码里散落一个字符串常量可靠 ——
     * 迁移期 local 与 oss 并存时，读一条记录要先确认「它存在哪、我现在能不能读到」，
     * 判据就是这个标识。
     *
     * @return {@code local} 或 {@code oss}
     */
    String name();

    /**
     * 存一个文件。
     *
     * @param dir       目录前缀，如 {@code TK-20260924-001/20260924}。
     *                  实现会校验它不越出根目录（防 {@code ../}）
     * @param extension 扩展名（不含点），如 {@code jpg}；可以为空
     * @param in        内容输入流。<b>由本方法负责关闭</b>
     * @return 存储结果（key / 字节数 / sha256）
     * @throws IOException 写盘失败
     */
    StoredObject store(String dir, String extension, InputStream in) throws IOException;

    /**
     * 按 key 取文件。
     *
     * @return 可直接流式读取的资源（{@link Resource#getInputStream()}），
     *         带 {@code contentLength()}
     * @throws cn.tedu.drone.common.BusinessException key 非法、越界，或文件已不在
     */
    Resource load(String key);

    /**
     * 删除一个文件；文件本就不存在时**不报错**（幂等）。
     *
     * <p>为什么现在就要它：单元测试里「上传」写的是**真文件**，
     * 而 {@code @Transactional} 只能回滚数据库、回滚不了磁盘。
     * 没有一个正经的删除入口，测试就只能去拼路径删文件，那等于把存储布局泄漏到测试里。
     *
     * @param key 存储键
     */
    void delete(String key);
}
