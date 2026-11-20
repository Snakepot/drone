package cn.tedu.drone.common;

/**
 * 业务异常，由全局异常处理器转换为 JsonResult
 */
public class BusinessException extends RuntimeException {

    private final int state;

    public BusinessException(String message) {
        this(JsonResult.FAIL, message);
    }

    public BusinessException(int state, String message) {
        super(message);
        this.state = state;
    }

    public int getState() {
        return state;
    }
}
