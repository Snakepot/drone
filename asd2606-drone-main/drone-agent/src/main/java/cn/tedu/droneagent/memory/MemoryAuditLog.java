package cn.tedu.droneagent.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 删除操作的审计日志：{@code <root>/audit.log}，一行一条，只追加。
 *
 * <h2>为什么单独一个文件，不写进应用日志</h2>
 * 应用日志会滚动、会被清理、也可能被调低级别；而「谁删了哪条会话」是要长期可查的。
 * 何况记忆文件本身永久保留，删除是唯一让内容消失的动作——它的留痕不该比内容更短命。
 *
 * <h2>为什么写失败要抛异常</h2>
 * 审计与删除的先后顺序是**先记后删**：留痕失败时删除就不该发生。
 * 反过来（先删再记、记不上就算了）等于让「删除」变成可以悄悄完成的操作，
 * 那这条审计就没意义了。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：删除链路的**第一步** —— 由 {@code ChatController} 在真正删数据之前调用。
 *
 * <ul>
 *   <li><b>不决定删不删</b>：它只是记账的，删哪些、谁来删是上层的事。</li>
 *   <li><b>不提供查询与展示</b>：要查「谁删过什么」得直接看文件，
 *       本类没有也不打算有查询接口。</li>
 *   <li><b>不做轮转与清理</b>：只追加、不删除 —— 审计记录比它记的那些内容更该长期留着。</li>
 * </ul>
 */
@Slf4j
public class MemoryAuditLog {

    private static final String FILE_NAME = "audit.log";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;

    public MemoryAuditLog(Path root) {
        this.file = root.resolve(FILE_NAME);
    }

    /**
     * 记录一次会话删除。
     *
     * @throws UncheckedIOException 写不进去时抛出；调用方应据此中止删除
     */
    public void recordDelete(long userId, String conversationId) {
        String line = TIME_FORMAT.format(LocalDateTime.now())
                + " | DELETE | userId=" + userId
                + " | conv=" + conversationId
                + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("写删除审计失败，删除将被中止：{}", file, e);
            throw new UncheckedIOException("写删除审计失败：" + file, e);
        }
    }
}
