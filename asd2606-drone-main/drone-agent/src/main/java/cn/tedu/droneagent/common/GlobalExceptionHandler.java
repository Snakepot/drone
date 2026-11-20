package cn.tedu.droneagent.common;

import cn.tedu.droneagent.memory.SessionBusyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：把业务异常转成统一响应体，前端拦截器能直接读到 message 并提示。
 *
 * <h2>为什么这里也要记日志</h2>
 * 不记的话，前端弹了「请求失败」、后端日志里**一行都没有** ——
 * 排查时只能靠猜。（这个坑是实际的审计发现的：这个类原本三条处理都不打日志。）
 *
 * <h2>分级原则</h2>
 * 400/409 是「请求本身的问题」，记 {@code warn} 且不带堆栈 ——
 * 堆栈对这类问题没有价值，只会把有用的信息淹掉；
 * 500 是「我们这边出问题了」，记 {@code error} 并带堆栈。
 *
 * <h2>它在链路里的位置，以及它不负责什么</h2>
 * <p><b>位置</b>：**所有 Controller 异常的收口** —— 抛出的异常在这里变成统一响应体，
 * 同时补上日志（拦截器里鉴权失败的那几条由拦截器自己记，不经过这里）。
 *
 * <ul>
 *   <li><b>不处理异常</b>：它只负责「转成响应 + 记一条」；异常该不该被吞、要不要重试，
 *       由抛出方决定。</li>
 *   <li><b>不区分具体业务</b>：它只按异常**类型**分档（入参、忙、其它），
 *       不认识「会话」「模型」这些概念。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public JsonResult<Void> handleIllegalState(IllegalStateException e) {
        log.error("服务端异常：{}", e.getMessage(), e);
        return JsonResult.fail(e.getMessage());
    }

    /**
     * 入参不合法（例如 conversationId 格式不对、message 为空）。
     * 用 400 而不是 500，前端能一眼看出这是「请求本身有问题」。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public JsonResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法：{}", e.getMessage());
        return JsonResult.fail(400, e.getMessage());
    }

    /**
     * 同一会话上已有请求在处理，等不到锁。
     * 用 409：这不是请求写错了，而是**时机不对、重发就行**，
     * 前端可以据此提示「上一条还在处理」而不是去改请求参数。
     */
    @ExceptionHandler(SessionBusyException.class)
    public JsonResult<Void> handleSessionBusy(SessionBusyException e) {
        log.warn("会话正忙，本次请求被拒：{}", e.getMessage());
        return JsonResult.fail(409, e.getMessage());
    }
}
