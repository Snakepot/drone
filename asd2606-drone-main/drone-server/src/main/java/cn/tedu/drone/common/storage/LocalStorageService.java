package cn.tedu.drone.common.storage;

import cn.tedu.drone.common.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 本地磁盘存储。
 *
 * <h2>目录结构：{@code {根目录}/{目录前缀}/{uuid}.{扩展名}}</h2>
 * <pre>
 * ./data/upload/TK-20260924-001/20260924/6f1c...e2.jpg
 * </pre>
 *
 * <h2>三个刻意的决定</h2>
 * <ol>
 *   <li><b>文件名用 uuid，不用原始文件名。</b>一举三得：
 *       ① 天然防重名 ② <b>防路径穿越</b>（原始文件名里可能有 {@code ../../}）
 *       ③ 防中文与空格在 URL 里出问题。
 *       用户要看的是原始文件名，那个**只存数据库**（{@code task_attachment.file_name}），
 *       绝不参与拼路径</li>
 *   <li><b>按任务分目录</b>：便于按任务打包、清理、核算占用</li>
 *   <li><b>按日期再分一层</b>：单个目录下文件过多时，文件系统的目录项查找会变慢
 *       （不严重，但便宜的时候顺手做了）</li>
 * </ol>
 *
 * <h2>关于路径校验</h2>
 * {@link #resolveSafely} 是这一层最重要的一段代码：任何 key 拼出来的路径都必须
 * <b>仍然落在根目录内</b>。防的是「数据库里的 key 被塞进 {@code ../../etc/passwd}」——
 * 数据库里的值不是可信输入，它可能来自一次漏洞、一次手工改库、一次迁移脚本写错。
 * 校验一次的成本极低，漏一次的代价是读取服务器上任意文件。
 */
@Slf4j
@Service
public class LocalStorageService implements StorageService {

    /** 写进 {@code task_attachment.storage} 的值 */
    public static final String NAME = "local";

    private final Path root;

    public LocalStorageService(@Value("${drone.upload.root:./data/upload}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 启动时把根目录建好，并把**绝对路径**打进日志。
     *
     * <p>为什么日志要打绝对路径：配的是相对路径，而「相对谁」取决于进程的工作目录
     * —— 在 IDE 里跑和在容器里跑不是同一个地方。联调时「文件明明传上去了，
     * 去目录里找不到」十有八九是看错了目录，打出来就不用猜。
     */
    @PostConstruct
    void prepareRoot() throws IOException {
        Files.createDirectories(root);
        log.info("附件存储根目录已就绪：{}（本地磁盘）", root);
    }

    @Override
    public StoredObject store(String dir, String extension, InputStream in) throws IOException {
        String safeDir = sanitizeDir(dir);
        String fileName = UUID.randomUUID().toString().replace("-", "");
        if (extension != null && !extension.isBlank()) {
            fileName = fileName + "." + extension.toLowerCase();
        }
        String key = safeDir.isEmpty() ? fileName : safeDir + "/" + fileName;

        Path target = resolveSafely(key);
        Files.createDirectories(target.getParent());

        MessageDigest digest = newSha256Digest();
        long size;
        // 边写边算摘要：文件只读一遍。写完再重读一遍去算 sha256，
        // 大文件（飞行日志几百 MB）上就是白白的双倍 IO
        try (DigestInputStream source = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(target)) {
            size = source.transferTo(out);
        }

        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.debug("附件已落盘：key={}，大小={} 字节，sha256={}", key, size, sha256);
        return new StoredObject(key, size, sha256);
    }

    @Override
    public Resource load(String key) {
        Path file = resolveSafely(key);
        if (!Files.isRegularFile(file)) {
            // 库里有一条记录、磁盘上却没有文件 —— 这属于要人来查的异常，
            // 不是「用户操作错了」，所以日志要能看出是哪个 key
            log.warn("附件记录存在但文件不在磁盘上：key={}，解析后的路径={}", key, file);
            throw new BusinessException("附件文件不存在或已被移动，请联系管理员核对");
        }
        return new FileSystemResource(file);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolveSafely(key));
        } catch (IOException e) {
            // 删除失败不该让业务流程崩掉（比如文件被占用），记一条 warn 就够
            log.warn("附件删除失败：key={}", key, e);
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 把 key 解析成根目录下的真实路径，并确认它<b>没有跑出根目录</b>。
     *
     * <p>{@code normalize()} 会把 {@code a/../../b} 这类路径「算平」，
     * 但**它只负责算，不负责拦** —— 算完必须再用 {@code startsWith} 判一次，
     * 因为算平之后完全可能落在根目录外面。这两步缺一不可，
     * 只做 normalize 是个很常见的错。
     */
    private Path resolveSafely(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("附件存储标识为空");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            // 走到这里说明 key 里有 ../ 之类的越界片段。这不是用户能触发的正常路径，
            // 所以按「异常」处理并记 warn —— 值得让人看见
            log.warn("拒绝越界的附件存储标识：key={}，解析后={}，根目录={}", key, resolved, root);
            throw new BusinessException("附件存储标识非法");
        }
        return resolved;
    }

    /**
     * 校验目录前缀：不许为空串以外的绝对路径、不许有 {@code ..} 片段。
     *
     * <p>与 {@link #resolveSafely} 是两道不同的关：那一道管「最终落在哪」，
     * 这一道管「传进来的前缀本身长什么样」。上游传进来的前缀是我们自己拼的
     * （任务编号 + 日期），按理不会越界，但这里多判一次的成本是几行代码。
     */
    private String sanitizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "";
        }
        String cleaned = dir.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..") || cleaned.contains(":")) {
            log.warn("附件目录前缀不合法，已拒绝：{}", dir);
            throw new BusinessException("附件目录前缀非法");
        }
        // 去掉首尾的斜杠，避免拼出 "a//b" 这种双斜杠 key
        return cleaned.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里；真走到了说明 JRE 被人动过
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    /** 供测试与排查使用：当前实际生效的根目录（绝对路径） */
    public Path root() {
        return root;
    }
}
