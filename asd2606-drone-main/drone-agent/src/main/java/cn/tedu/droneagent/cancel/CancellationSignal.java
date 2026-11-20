package cn.tedu.droneagent.cancel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次请求的取消信号（`REQ-AGENT-0029`）。
 *
 * <p>只做一件事：被置位后，编排层在**下一个检查点**停止继续调用模型/工具。
 * 它不负责中断已经发出的 HTTP 请求——写操作在途时强行中断只会让我们丢失结果，
 * 正确做法是等它结算再如实报告。
 */
public final class CancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 永不取消：给不需要取消能力的调用与测试用 */
    public static CancellationSignal never() {
        return new CancellationSignal();
    }
}
