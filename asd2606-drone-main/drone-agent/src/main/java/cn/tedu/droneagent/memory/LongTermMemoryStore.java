package cn.tedu.droneagent.memory;

import cn.tedu.droneagent.dto.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 长期记忆的**正文**存储：按用户分目录、按日期分片、单文件超限滚动。
 *
 * <pre>
 * &lt;root&gt;/&lt;userId&gt;/memory-2026-10-12.md      第 1 个分片
 *                  memory-2026-10-12-2.md    超过 2MB 后的第 2 个分片
 * </pre>
 *
 * <h2>一个会话长什么样</h2>
 * <pre>
 * ## conv=8f3c4d21 | 2026-10-12 14:32 | 12 条
 * user: 电池低温报警该怎么处理？
 * assistant: 电池低温告警的核心是……\n按下面处理：
 * </pre>
 *
 * <h2>为什么正文里的换行要转义成 {@code \n}，而不是缩进续行</h2>
 * 缩进续行更好看，但**正文本身完全可能出现以 {@code user: } 开头的行**
 * （模型在解释对话格式、用户粘贴日志都会）。按行首标记解析时，那种内容会被当成新消息，
 * 把一段话切成两截。转义后每条消息严格占一行，解析不再依赖内容长什么样。
 * 代价是读文件时多行内容显示为 {@code \n}——这是为正确性付的。
 *
 * <h2>两处刻意的取舍</h2>
 * <ul>
 *   <li><b>追加不原子，删除才原子。</b>追加崩溃最多在文件末尾留半截会话段；
 *       为它每次重写整个文件（可到 2MB）不划算。删除是过滤后整体重写，走原子替换。</li>
 *   <li><b>按会话的<b>开始日期</b>分片</b>，不是归档日期。这样索引里的时间与文件名同一天，
 *       对得上。</li>
 * </ul>
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：归档链路的**正文半边**——由 {@link MemoryArchiver} 在「先正文、后索引」
 * 的两步里先调它。上面只暴露三个动作：写入一个会话、按会话读回、按会话物理删除。
 *
 * <ul>
 *   <li><b>不维护索引</b>：哪条会话在哪个文件里，是 {@link MemoryIndex} 的事。
 *       本类只管「按你给的文件名读写」。</li>
 *   <li><b>不决定什么时候写</b>：什么时候算一条会话结束、要不要重试，由归档那边决定。</li>
 *   <li><b>不做并发控制</b>：「同一个文件同时被两个线程写」本类不管，
 *       由调用方保证（归档只有一条线程在跑）。</li>
 * </ul>
 */
@Slf4j
public class LongTermMemoryStore {

    private static final String SECTION_PREFIX = "## conv=";

    private static final String FIELD_SEPARATOR = " | ";

    private static final String FILE_SUFFIX = ".md";

    private static final String FILE_PREFIX = "memory-";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final Path root;

    private final long fileMaxBytes;

    public LongTermMemoryStore(Path root, DataSize fileMaxBytes) {
        this.root = root;
        this.fileMaxBytes = fileMaxBytes.toBytes();
    }

    /**
     * 落盘一个会话（内容先脱敏），返回写入的文件名——索引要记它。
     *
     * @throws UncheckedIOException 写入失败；调用方（归档流程）应据此重试
     */
    public String write(Session session) {
        String day = DAY_FORMAT.format(session.startedAt().atZone(ZONE));
        String section = renderSection(session);
        try {
            Path file = pickFileForAppend(session.key().userId(), day,
                    section.getBytes(StandardCharsets.UTF_8).length);
            Files.createDirectories(file.getParent());
            Files.writeString(file, section, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return file.getFileName().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("写入长期记忆失败：" + session.key(), e);
        }
    }

    /**
     * 读回某个会话的正文。
     *
     * @param fileName 索引里记的文件名（避免每次遍历整个目录）
     * @return 该会话的消息；文件不存在或文件里没有这个会话时为空
     */
    public Optional<List<ChatMessage>> read(long userId, String fileName, String conversationId) {
        Path file = userDirectory(userId).resolve(fileName);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(extractSection(Files.readAllLines(file, StandardCharsets.UTF_8), conversationId));
        } catch (IOException e) {
            throw new UncheckedIOException("读取长期记忆失败：" + file, e);
        }
    }

    /**
     * 读回某用户**全部**归档会话，供 {@code search_memory} 按需检索（`REQ-AGENT-0021`）。
     *
     * <p>同一会话可能因为「淘汰 → 重新打开 → 再淘汰」被追加多段；与 {@link #read} 一致，
     * **只保留最后一段**——它内容最全，也避免检索结果里同一段对话出现两遍。
     *
     * @return 按文件顺序（即归档先后）返回；用户目录不存在时为空列表
     * @throws UncheckedIOException 某个文件读失败；调用方（检索工具）应转成失败结果
     */
    public List<ArchivedSection> readAll(long userId) {
        Path directory = userDirectory(userId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描长期记忆目录失败：" + directory, e);
        }

        Map<String, ArchivedSection> latest = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                parseFileIntoSections(file, latest);
            } catch (IOException e) {
                throw new UncheckedIOException("读取长期记忆失败：" + file, e);
            }
        }
        return List.copyOf(latest.values());
    }

    /** 一个归档会话段（只读视图） */
    public record ArchivedSection(String conversationId, String startedAtText, String fileName,
                                  List<ChatMessage> messages) {
    }

    private static void parseFileIntoSections(Path file, Map<String, ArchivedSection> out) throws IOException {
        String currentId = null;
        String currentTime = null;
        List<ChatMessage> current = null;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            SectionHeader header = parseHeader(line);
            if (header != null) {
                if (currentId != null) {
                    out.put(currentId, new ArchivedSection(currentId, currentTime,
                            file.getFileName().toString(), List.copyOf(current)));
                }
                currentId = header.id();
                currentTime = header.time();
                current = new ArrayList<>();
                continue;
            }
            if (current == null) {
                continue;
            }
            ChatMessage message = parseMessage(line);
            if (message != null) {
                current.add(message);
            }
        }
        if (currentId != null) {
            out.put(currentId, new ArchivedSection(currentId, currentTime,
                    file.getFileName().toString(), List.copyOf(current)));
        }
    }

    private static SectionHeader parseHeader(String line) {
        if (line == null || !line.startsWith(SECTION_PREFIX)) {
            return null;
        }
        String[] parts = line.substring(SECTION_PREFIX.length()).split("\\s*\\|\\s*");
        if (parts.length < 2) {
            return null;
        }
        return new SectionHeader(parts[0], parts[1]);
    }

    private record SectionHeader(String id, String time) {
    }

    /**
     * 按会话物理删除：从该用户**所有**正文分片里移除这个会话段。
     *
     * <p>刻意按会话扫描而不是只删索引指的那个文件——索引与正文万一不同步，
     * 只删一个文件会把内容留在别处，「删除」就变成了假的。删除是低频操作，
     * 扫一遍目录可以接受。
     *
     * @return 是否找到并删除；没找到返回 {@code false}（不报错，与「已经没有了」等价）
     */
    public boolean delete(long userId, String conversationId) {
        Path directory = userDirectory(userId);
        if (!Files.isDirectory(directory)) {
            return false;
        }

        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描长期记忆目录失败：" + directory, e);
        }

        boolean removed = false;
        for (Path file : files) {
            try {
                Optional<List<String>> kept = removeSection(Files.readAllLines(file, StandardCharsets.UTF_8), conversationId);
                if (kept.isEmpty()) {
                    continue;
                }
                removed = true;
                List<String> remaining = kept.get();
                if (remaining.isEmpty()) {
                    // 整个文件只剩它，删掉空文件——留着只会让目录越积越多
                    Files.deleteIfExists(file);
                } else {
                    TextFiles.writeAtomically(file, String.join(System.lineSeparator(), remaining) + System.lineSeparator());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("删除长期记忆内容失败：" + file, e);
            }
        }
        return removed;
    }

    private Path userDirectory(long userId) {
        return root.resolve(String.valueOf(userId));
    }

    /** 渲染一个会话段：内容先脱敏，换行转义，保证一条消息严格占一行 */
    private static String renderSection(Session session) {
        List<ChatMessage> messages = session.messages();
        String newLine = System.lineSeparator();

        StringBuilder section = new StringBuilder();
        section.append(SECTION_PREFIX).append(session.key().conversationId())
                .append(FIELD_SEPARATOR).append(TIME_FORMAT.format(session.startedAt().atZone(ZONE)))
                .append(FIELD_SEPARATOR).append(messages.size()).append(" 条")
                .append(newLine);
        for (ChatMessage message : messages) {
            section.append(message.role()).append(": ")
                    .append(escape(SensitiveMasker.mask(message.content())))
                    .append(newLine);
        }
        // 段间空一行，纯为可读性
        section.append(newLine);
        return section.toString();
    }

    /**
     * 挑这次该写到哪个文件：当天最后一个分片放得下就追加，放不下就新开一个分片。
     */
    private Path pickFileForAppend(long userId, String day, int incomingBytes) throws IOException {
        Path directory = userDirectory(userId);
        int highestRoll = 0;
        if (Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                for (Path path : stream.toList()) {
                    highestRoll = Math.max(highestRoll, rollIndexOf(path.getFileName().toString(), day));
                }
            }
        }

        if (highestRoll == 0) {
            return directory.resolve(fileNameOf(day, 1));
        }
        Path tail = directory.resolve(fileNameOf(day, highestRoll));
        long size = Files.exists(tail) ? Files.size(tail) : 0L;
        return size + incomingBytes > fileMaxBytes
                ? directory.resolve(fileNameOf(day, highestRoll + 1))
                : tail;
    }

    /**
     * 文件名里蕴含的分片序号：{@code memory-<day>.md} 是 1，{@code memory-<day>-2.md} 是 2。
     *
     * @return 属于该日期时返回序号；不属于（含格式不认识）返回 0
     */
    private static int rollIndexOf(String fileName, String day) {
        String prefix = FILE_PREFIX + day;
        if (!fileName.startsWith(prefix) || !fileName.endsWith(FILE_SUFFIX)) {
            return 0;
        }
        String middle = fileName.substring(prefix.length(), fileName.length() - FILE_SUFFIX.length());
        if (middle.isEmpty()) {
            return 1;
        }
        if (!middle.startsWith("-")) {
            return 0;
        }
        try {
            return Integer.parseInt(middle.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fileNameOf(String day, int roll) {
        return roll <= 1 ? FILE_PREFIX + day + FILE_SUFFIX : FILE_PREFIX + day + "-" + roll + FILE_SUFFIX;
    }

    /**
     * 取某文件里指定会话的消息；该会话不在其中（或文件是空的）返回 null。
     *
     * <p><b>同一个会话可能出现多段，取最后一段。</b>这不是防御性写法，是必然会发生的情形：
     * 会话被淘汰时归档一次，用户回来再聊、再被淘汰，就又追加一段。
     * 取第一段的话，恢复出来的会是**上一次归档时的旧内容**——越用越倒退。
     */
    private static List<ChatMessage> extractSection(List<String> lines, String conversationId) {
        List<ChatMessage> latest = null;
        List<ChatMessage> current = null;

        for (String line : lines) {
            String id = parseSectionId(line);
            if (id != null) {
                // 每遇到段头就换段；只收集命中目标 id 的段
                current = id.equals(conversationId) ? new ArrayList<>() : null;
                if (current != null) {
                    latest = current;
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            ChatMessage message = parseMessage(line);
            if (message != null) {
                current.add(message);
            }
        }
        return latest;
    }

    /** 移除指定会话段；该会话不在其中时返回 empty */
    private static Optional<List<String>> removeSection(List<String> lines, String conversationId) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        boolean hit = false;

        for (String line : lines) {
            String id = parseSectionId(line);
            if (id != null) {
                inside = id.equals(conversationId);
                if (inside) {
                    hit = true;
                    continue;
                }
            }
            if (!inside) {
                kept.add(line);
            }
        }
        return hit ? Optional.of(kept) : Optional.empty();
    }

    /** 段头形如 {@code ## conv=<id> | <时间> | <n> 条}；不是段头返回 null */
    private static String parseSectionId(String line) {
        if (line == null || !line.startsWith(SECTION_PREFIX)) {
            return null;
        }
        String rest = line.substring(SECTION_PREFIX.length());
        int separator = rest.indexOf(FIELD_SEPARATOR);
        return separator < 0 ? null : rest.substring(0, separator);
    }

    private static ChatMessage parseMessage(String line) {
        int separator = line.indexOf(": ");
        if (separator <= 0) {
            return null;
        }
        String role = line.substring(0, separator);
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return null;
        }
        return new ChatMessage(role, unescape(line.substring(separator + 2)));
    }

    /**
     * 转义：先处理反斜杠，再处理换行。
     * 顺序不能反——先转换行会引入新的反斜杠，再转反斜杠就把它们一起加倍了。
     */
    private static String escape(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\\", "\\\\")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n");
    }

    private static String unescape(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current != '\\' || i + 1 >= line.length()) {
                out.append(current);
                continue;
            }
            char next = line.charAt(++i);
            if (next == 'n') {
                out.append('\n');
            } else if (next == '\\') {
                out.append('\\');
            } else {
                // 不认识的转义原样保留，避免把内容悄悄改掉
                out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
