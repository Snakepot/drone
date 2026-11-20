package cn.tedu.drone.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应结果
 */
@Data
@Accessors(chain = true)
@Schema(description = "统一响应结果")
public class JsonResult<T> implements Serializable {

    /** 成功状态码，与前端 SUCCESS_CODES 保持一致 */
    public static final int SUCCESS = 200;

    /** 失败状态码 */
    public static final int FAIL = 500;

    @Schema(description = "状态码，200 表示成功")
    private Integer state;

    @Schema(description = "提示消息")
    private String message;

    @Schema(description = "响应数据")
    private T data;

    public static <T> JsonResult<T> ok() {
        return new JsonResult<T>().setState(SUCCESS).setMessage("操作成功");
    }

    public static <T> JsonResult<T> ok(T data) {
        return new JsonResult<T>().setState(SUCCESS).setMessage("操作成功").setData(data);
    }

    public static <T> JsonResult<T> ok(String message, T data) {
        return new JsonResult<T>().setState(SUCCESS).setMessage(message).setData(data);
    }

    public static <T> JsonResult<T> fail(String message) {
        return new JsonResult<T>().setState(FAIL).setMessage(message);
    }

    public static <T> JsonResult<T> fail(Integer state, String message) {
        return new JsonResult<T>().setState(state).setMessage(message);
    }
}
