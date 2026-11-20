package cn.tedu.droneagent.common;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应结果，字段与前端 request.ts 的解包逻辑保持一致。
 */
@Data
@Accessors(chain = true)
public class JsonResult<T> implements Serializable {

    /** 成功状态码，与前端 SUCCESS_CODES 保持一致 */
    public static final int SUCCESS = 200;

    /** 失败状态码 */
    public static final int FAIL = 500;

    private Integer state;

    private String message;

    private T data;

    public static <T> JsonResult<T> ok(T data) {
        return new JsonResult<T>().setState(SUCCESS).setMessage("操作成功").setData(data);
    }

    public static <T> JsonResult<T> fail(String message) {
        return new JsonResult<T>().setState(FAIL).setMessage(message);
    }

    /**
     * 指定状态码的失败结果。拦截器返回 401 / 403 / 503 时用，
     * 让前端能从响应体里读到与实际 HTTP 状态一致的状态码。
     */
    public static <T> JsonResult<T> fail(int state, String message) {
        return new JsonResult<T>().setState(state).setMessage(message);
    }
}
