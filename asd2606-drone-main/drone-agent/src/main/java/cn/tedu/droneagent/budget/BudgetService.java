package cn.tedu.droneagent.budget;

import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.bill.BillSummary;
import cn.tedu.droneagent.bill.RequestBill;
import cn.tedu.droneagent.config.BudgetProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * 预算检查（`REQ-AGENT-0028`）：把已有的账单从「观测」升级为「控制」。
 *
 * <h2>两个口径</h2>
 * <ul>
 *   <li><b>日额度</b>：当天该用户所有请求的 prompt token 之和；超了直接拒绝新请求；</li>
 *   <li><b>单请求上限</b>：本次 trace 所有轮次的 prompt token 之和；超了停止工具循环。</li>
 * </ul>
 *
 * <h2>为什么读账单而不是自己计数</h2>
 * 账单已经按「每次模型调用」记录了真实 `prompt_tokens`，再维护一套计数器会出现
 * 「账单说 5 万、预算说 3 万」的双份真相。预算只读，不写。
 *
 * <h2>失败策略</h2>
 * 读账单失败（文件损坏/权限）时**放行**并 WARN：预算是成本保护，不能变成可用性故障。
 */
@Slf4j
public class BudgetService {

    private final boolean enabled;

    private final int maxRequestTokens;

    private final int maxDailyTokensPerUser;

    private final LongFunction<Long> dailyUsage;

    private final Function<String, Long> requestUsage;

    /** 生产装配：从账单读用量 */
    public BudgetService(BillStore billStore, BudgetProperties properties) {
        this(properties, userId -> {
            BillSummary summary = billStore.aggregate(userId, false,
                    LocalDate.now(), LocalDate.now());
            return summary.promptTokens();
        }, traceId -> {
            RequestBill bill = billStore.summary(traceId);
            return bill == null ? 0L : bill.promptTokens();
        });
    }

    /** 测试/自定义数据源：直接给两个用量函数 */
    public BudgetService(BudgetProperties properties, LongFunction<Long> dailyUsage,
                         Function<String, Long> requestUsage) {
        this.enabled = properties.enabled();
        this.maxRequestTokens = Math.max(1, properties.maxRequestTokens());
        this.maxDailyTokensPerUser = Math.max(1, properties.maxDailyTokensPerUser());
        this.dailyUsage = dailyUsage;
        this.requestUsage = requestUsage;
    }

    /** 关闭预算（测试与不需要拦截的场景） */
    public static BudgetService noop() {
        return new BudgetService(new BudgetProperties(false, Integer.MAX_VALUE, Integer.MAX_VALUE),
                userId -> 0L, traceId -> 0L);
    }

    public Check checkDaily(long userId) {
        return check("daily", maxDailyTokensPerUser, () -> dailyUsage.apply(userId),
                "userId=" + userId);
    }

    public Check checkRequest(String traceId) {
        return check("request", maxRequestTokens, () -> requestUsage.apply(traceId),
                "traceId=" + traceId);
    }

    private Check check(String scope, int limit, java.util.function.LongSupplier usage, String who) {
        if (!enabled) {
            return new Check(scope, false, 0, limit);
        }
        long used;
        try {
            used = usage.getAsLong();
        } catch (RuntimeException e) {
            log.warn("【预算】读取用量失败，按未超限放行：scope={}，{}，原因={}", scope, who, e.getMessage());
            return new Check(scope, false, 0, limit);
        }
        boolean exceeded = used >= limit;
        if (exceeded) {
            log.warn("【预算】已超限：scope={}，{}，已用 {}，上限 {}", scope, who, used, limit);
        }
        return new Check(scope, exceeded, used, limit);
    }

    /** 日额度超限时给用户的话术 */
    public static String dailyReply(Check check) {
        return "今日智能助手额度已用完（已用 " + check.used() + " / 上限 " + check.limit()
                + " token），请明天再试或联系管理员。";
    }

    /** 单请求超限时给用户的话术 */
    public static String requestReply(Check check) {
        return "本次请求的上下文预算已用完（已用 " + check.used() + " / 上限 " + check.limit()
                + " token）。我先收尾；请把问题拆小一点再试。";
    }

    /** 一次检查结果 */
    public record Check(String scope, boolean exceeded, long used, int limit) {
    }
}
