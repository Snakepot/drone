package cn.tedu.droneagent.tool;

import cn.tedu.droneagent.memory.SensitiveMasker;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 危险工具执行的审计日志：{@code <root>/tool-audit.log}，一行一条，只追加。
 *
 * <h2>为什么危险操作要单独记账</h2>
 * 应用日志会滚动、会被清掉、也可能被调低级别；而「谁在什么时候删了哪台设备」是要长期可查的。
 * 危险操作**不可逆**，它的留痕不该比内容更短命。
 *
 * <h2>为什么写失败**只记日志、不抛异常**</h2>
 * 与 {@code MemoryAuditLog} 刻意的不同：那边是「先记后删」，记不上就不该删；
 * 这里是在**执行之后**才记账 —— 动作已经发生，抛异常也收不回来，只会让调用方误判成失败。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <ul>
 *   <li><b>不执行、不批准</b>：它只是记账，执行与授权在别处。</li>
 *   <li><b>不提供查询</b>：要查「谁执行过什么」直接看文件。</li>
 *   <li><b>不做轮转与清理</b>：只追加、不删除。</li>
 * </ul>
 */
@Slf4j
public class ToolAuditLog {

    private static final String FILE_NAME = "tool-audit.log";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 为 null 表示不落盘（未配置目录 / 测试用的 no-op 实例） */
    private final Path file;

    public ToolAuditLog(Path root) {
        this.file = root == null ? null : root.resolve(FILE_NAME);
    }

    /** 什么都不写的实例（测试或未配置落盘目录时用） */
    public static ToolAuditLog noop() {
        return new ToolAuditLog(null);
    }

    /**
     * 记一次危险工具的执行。
     *
     * @param userId           执行人
     * @param toolName         工具名
     * @param argumentsSummary 模型给的参数（入日志前会脱敏）
     * @param ok               执行结果
     */
    public void recordExecution(long userId, String toolName, String argumentsSummary, boolean ok) {
        if (file == null) {
            return;
        }
        String line = TIME_FORMAT.format(LocalDateTime.now())
                + " | TOOL | userId=" + userId
                + " | tool=" + toolName
                + " | result=" + (ok ? "OK" : "FAILED")
                + " | args=" + SensitiveMasker.mask(argumentsSummary)
                + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("写工具审计失败（动作已发生，无法回退）：{}", file, e);
        }
    }
}
