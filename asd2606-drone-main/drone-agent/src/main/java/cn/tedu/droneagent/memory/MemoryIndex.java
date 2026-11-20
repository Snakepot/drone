package cn.tedu.droneagent.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆的**按用户隔离**的索引：{@code <root>/<userId>/memory.md}。
 *
 * <h2>为什么索引要按用户分开，而不是全局一份</h2>
 * 全局索引会随用户数一直变大，而启动/首次访问时并不需要知道别人的会话。
 * 按用户分开之后，启动时**一个文件都不用读**；某个用户来问，才去读他自己那一份。
 * 顺带还省掉一层越权顾虑：读索引这个动作本身就跨不了用户。
 *
 * <h2>一行长什么样</h2>
 * <pre>
 * - 2026-10-12 14:32 | conv=8f3c4d21 | 12 | 电池低温报警 | memory-2026-10-12.md
 *   ↑时间(到分)        ↑会话id         ↑条数 ↑首句截断      ↑正文文件名
 * </pre>
 *
 * <h2>两处刻意的取舍</h2>
 * <ul>
 *   <li><b>时间只精确到分钟。</b>索引是给人扫的，秒没有意义；代价是同一分钟内的两个会话
 *       时间相同，排序只能靠写入顺序（{@link #list} 已按写入倒序返回）。</li>
 *   <li><b>追加不做原子替换。</b>追加写崩溃最多留下一行残缺内容，读取时会跳过并告警，
 *       不影响前面的记录；为此每次都重写整个文件不划算。只有 {@link #remove} 才走原子替换。</li>
 * </ul>
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：归档链路的**索引半边**——{@link MemoryArchiver} 写完正文之后调它记账；
 * 反过来，会话列表与懒加载都靠它定位正文文件。
 *
 * <ul>
 *   <li><b>不存正文</b>：一条消息的原文在 {@link LongTermMemoryStore} 那边，这里只记
 *       「哪个会话、多少条、在哪个文件」。</li>
 *   <li><b>不决定什么时候写</b>：什么时候归档由记忆子系统决定，本类只负责记账与查询。</li>
 *   <li><b>不做排序与分页</b>：{@link #list} 只保证「按写入倒序」，展示层的排序/分页在上层。</li>
 * </ul>
 */
@Slf4j
public class MemoryIndex {

    /** 每个用户目录下的索引文件名 */
    private static final String INDEX_FILE_NAME = "memory.md";

    /** 标题最多保留多少字符——索引是给人扫的，不是正文 */
    private static final int MAX_TITLE_LENGTH = 40;

    /** 索引里的字段分隔符 */
    private static final String FIELD_SEPARATOR = " | ";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path root;

    public MemoryIndex(Path root) {
        this.root = root;
    }

    /**
     * 列出该用户的会话索引，**按写入顺序倒序**（最近归档的在最前）。
     *
     * <p>只读索引文件，**不读任何正文**——这正是索引存在的意义。
     *
     * <p>**同一个会话只返回一条（最新的那条）**：会话被反复淘汰/恢复时索引会累积多条，
     * 但使用者要的是当前状态。文件里的历史条目保留不动，只有读的时候收敛。
     *
     * @return 该用户的索引条目；索引文件不存在时返回空列表（不是异常：新用户本来就没有）
     */
    public List<IndexEntry> list(long userId) {
        Path file = indexFile(userId);
        if (!Files.exists(file)) {
            return List.of();
        }

        List<IndexEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                IndexEntry entry = parse(line, file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话索引失败：" + file, e);
        }

        Collections.reverse(entries);
        // 同一个会话可能被归档多次（内存淘汰 → 懒加载回来 → 再淘汰），索引里就会留下多条。
        // 对使用者来说只有**最新那条**有意义，所以在读的时候收敛成一条，
        // 而不是让每个调用方各自去重（会话列表会重复、懒加载会取错文件）。
        Map<String, IndexEntry> newestPerConversation = new LinkedHashMap<>();
        for (IndexEntry entry : entries) {
            newestPerConversation.putIfAbsent(entry.conversationId(), entry);
        }
        return List.copyOf(newestPerConversation.values());
    }

    /**
     * 追加一条索引。用户目录或索引文件不存在时自动创建。
     *
     * @throws UncheckedIOException 写入失败；调用方（归档流程）应据此重试
     */
    public void append(long userId, IndexEntry entry) {
        Path file = indexFile(userId);
        String line = format(entry) + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入会话索引失败：" + file, e);
        }
    }

    /**
     * 删除某个会话的索引条目；该会话不在索引里时什么都不做。
     *
     * <p>删除必须把这条记录**从索引里也拿掉**，否则会话列表里会留下一个指向已删内容的幽灵条目。
     * 这里是过滤后整体重写，所以走原子替换——中途崩溃不能让索引变成半截。
     */
    public void remove(long userId, String conversationId) {
        Path file = indexFile(userId);
        if (!Files.exists(file)) {
            return;
        }

        List<String> kept = new ArrayList<>();
        boolean hit = false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                IndexEntry entry = parse(line, file);
                if (entry != null && entry.conversationId().equals(conversationId)) {
                    hit = true;
                    continue;
                }
                // 没命中就原样保留：包括解析不了的行也不动它，避免顺手破坏别的东西
                kept.add(line);
            }
            if (!hit) {
                return;
            }
            if (kept.isEmpty()) {
                // 与正文存储保持一致：空了就把文件删掉，不留一个 0 字节的索引在那儿
                Files.deleteIfExists(file);
                return;
            }
            String content = String.join(System.lineSeparator(), kept) + System.lineSeparator();
            TextFiles.writeAtomically(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("更新会话索引失败：" + file, e);
        }
    }

    private Path indexFile(long userId) {
        return root.resolve(String.valueOf(userId)).resolve(INDEX_FILE_NAME);
    }

    private static String format(IndexEntry entry) {
        return "- " + TIME_FORMAT.format(entry.startedAt().atZone(ZoneId.systemDefault()))
                + FIELD_SEPARATOR + "conv=" + entry.conversationId()
                + FIELD_SEPARATOR + entry.messageCount()
                + FIELD_SEPARATOR + sanitizeTitle(entry.title())
                + FIELD_SEPARATOR + entry.fileName();
    }

    /**
     * 解析一行。
     *
     * @return 解析成功返回条目；空行、格式不对或字段非法返回 {@code null}（并告警），
     *         由调用方决定跳过——单行损坏不该让整个索引不可用
     */
    private static IndexEntry parse(String line, Path file) {
        if (line == null || line.isBlank() || !line.startsWith("- ")) {
            return null;
        }

        // 标题在写入时已去掉分隔符，所以按 5 段切开是安全的
        String[] parts = line.substring(2).split("\\s*\\|\\s*", 5);
        if (parts.length != 5 || !parts[1].startsWith("conv=")) {
            log.warn("会话索引行格式不对，已跳过：{} @ {}", line, file);
            return null;
        }

        try {
            Instant startedAt = LocalDateTime.parse(parts[0], TIME_FORMAT)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
            return new IndexEntry(parts[1].substring("conv=".length()), startedAt,
                    Integer.parseInt(parts[2].trim()), parts[3], parts[4]);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            // NumberFormatException 属于 IllegalArgumentException，不必单列
            log.warn("会话索引行无法解析，已跳过：{} @ {}，原因：{}", line, file, e.getMessage());
            return null;
        }
    }

    /** 清洗标题：先脱敏，再压成一行，最后截断 */
    private static String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        // 索引也在磁盘上，因此同样要脱敏——标题取自首句用户提问，可能带手机号之类。
        // 放在这里而不是交给调用方，是因为「写盘的东西自己负责脱敏」更难被漏掉
        String masked = SensitiveMasker.mask(raw);
        String oneLine = masked.replaceAll("[\\r\\n\\t|]", "/").trim();
        return oneLine.length() <= MAX_TITLE_LENGTH ? oneLine : oneLine.substring(0, MAX_TITLE_LENGTH);
    }
}
