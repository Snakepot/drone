package cn.tedu.drone.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把抛出的异常统一转换成 {@link JsonResult}，
 * 前端永远只看到规整的 JSON，而不是一大段 Java 堆栈。
 *
 * <h2>它是怎么生效的</h2>
 * {@code @RestControllerAdvice} 相当于给所有 Controller 加了一层「兜底 catch」：
 * 任何 Controller（或它调用的 Service）抛出的异常，都会先被这里接住，
 * 再按异常类型匹配到对应的处理方法。
 *
 * <h2>两类异常，两种处理方式</h2>
 * <ul>
 *   <li>{@link BusinessException}：业务上可预期的错误，例如「密码错误」「账号被禁用」。
 *       这类提示是写给用户看的，所以要原样返回，状态码用异常里带的 state。</li>
 *   <li>其他 Exception：代码 bug 或环境问题，用户看不懂也没必要看懂。
 *       这里记一条 error 日志留给开发排查，对外只回一句笼统的「服务器繁忙」——
 *       既不会把内部细节（SQL 语句、类名等）暴露出去，也不会吓到用户。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：把异常里的提示语原样返回给前端
     */
    @ExceptionHandler(BusinessException.class)
    public JsonResult<Void> handleBusinessException(BusinessException e) {
        log.debug("业务异常返回给前端：state={}，message={}", e.getState(), e.getMessage());
        return JsonResult.fail(e.getState(), e.getMessage());
    }

    /**
     * 处理所有未预期的异常：对外只给一句笼统提示，详细信息写进日志
     */
    @ExceptionHandler(Exception.class)
    public JsonResult<Void> handleException(Exception e) {
        log.error("服务器内部异常", e);
        return JsonResult.fail("服务器繁忙，请稍后再试");
    }
}
