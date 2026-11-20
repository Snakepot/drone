package cn.tedu.droneagent.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 记忆文件的读写小工具。
 *
 * <h2>为什么值得抽出来</h2>
 * 索引（{@code memory.md}）与正文（按日期分片）都需要「整体重写时必须原子」——
 * 删除一个会话要过滤后重写整个文件。这段逻辑各写一份，早晚会在一边修了、另一边漏了。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：最底层 —— {@link MemoryIndex} 与 {@link LongTermMemoryStore} 的重写路径
 * 都调它。包可见（不是 public），因为它只服务本包。</p>
 *
 * <ul>
 *   <li><b>不懂文件格式</b>：它只把一段字符串原子地写进某个路径，内容是什么它不关心。</li>
 *   <li><b>不做并发控制</b>：「同一个文件被两个线程同时重写」它不管 ——
 *       当前的调用方（归档单线程、删除走请求线程）自己保证。</li>
 * </ul>
 */
@Slf4j
final class TextFiles {

    private TextFiles() {
    }

    /**
     * 原子重写：先写同目录下的临时文件，再改名覆盖目标。
     *
     * <p>中途失败（进程被杀、磁盘满）最多留下一个 {@code .tmp} 文件，**不会**把目标文件
     * 写成半截——半截的记忆文件比没有更糟：读的时候会当成正常内容。
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统不支持原子改名；退回普通替换，至少不残留临时文件
            log.warn("文件系统不支持原子改名，退回普通替换：{}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
