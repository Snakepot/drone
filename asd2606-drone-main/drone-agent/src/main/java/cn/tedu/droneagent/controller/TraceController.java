package cn.tedu.droneagent.controller;

import cn.tedu.droneagent.common.JsonResult;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.RequirePermission;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.trace.TraceRecorder;
import cn.tedu.droneagent.trace.TraceSpan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 读取一次对话的 span 列表（`REQ-AGENT-0026` P1）。
 *
 * <h2>为什么按当前登录人拼路径</h2>
 * trace 文件按 {@code {root}/{userId}/} 存放；这里用 {@link UserContext} 的 userId 去读，
 * 别人拿到 traceId 也只会得到 404——与产物接口同一套边界。
 */
@RestController
@RequestMapping("/chat/traces")
public class TraceController {

    private final TraceRecorder traceRecorder;

    public TraceController(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @RequirePermission("agent:chat")
    @GetMapping("/{traceId}")
    public ResponseEntity<JsonResult<List<TraceSpan>>> get(@PathVariable String traceId) {
        LoginUser user = UserContext.get();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<TraceSpan> spans = traceRecorder.find(user.getId(), traceId);
        if (spans.isEmpty()) {
            // 不存在与不属于本人返回同一个 404，不暴露 trace 是否存在
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(JsonResult.ok(spans));
    }
}
