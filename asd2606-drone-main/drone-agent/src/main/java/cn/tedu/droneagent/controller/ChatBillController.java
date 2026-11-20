package cn.tedu.droneagent.controller;

import cn.tedu.droneagent.bill.BillRecord;
import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.bill.BillSummary;
import cn.tedu.droneagent.common.JsonResult;
import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.common.RequirePermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 上下文账单的查询接口（`REQ-AGENT-0025`）。
 *
 * <h2>权限边界</h2>
 * <ul>
 *   <li>默认只返回**当前登录人**的账单（`scope=self`）；</li>
 *   <li>`scope=all` 是管理端汇总口径，**必须是 admin 角色**，否则返回 403。</li>
 * </ul>
 *
 * <p>账单里只有 token、工具名与时间，没有对话正文；查询接口因此不涉及内容脱敏，
 * 但**越权仍要拦**：别人的会话 id 与用量同样属于隐私。
 */
@Slf4j
@RestController
@RequestMapping("/chat/bills")
public class ChatBillController {

    private final BillStore billStore;

    public ChatBillController(BillStore billStore) {
        this.billStore = billStore;
    }

    /** 明细：按时间倒序，默认最近 50 条 */
    @RequirePermission("agent:chat")
    @GetMapping
    public JsonResult<List<BillRecord>> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "self") String scope) {

        boolean allUsers = "all".equals(scope);
        if (allUsers && !isAdmin()) {
            return JsonResult.fail(403, "查看全部账单需要管理员权限");
        }
        long userId = allUsers ? 0 : currentUserId();
        List<BillRecord> records = billStore.query(userId, allUsers, conversationId, from, to, limit);
        log.debug("【账单】查询：userId={}，scope={}，conv={}，{}~{}，返回 {} 条",
                currentUserId(), scope, conversationId, from, to, records.size());
        return JsonResult.ok(records);
    }

    /** 汇总：管理端卡片与图表 */
    @RequirePermission("agent:chat")
    @GetMapping("/summary")
    public JsonResult<BillSummary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "self") String scope) {

        boolean allUsers = "all".equals(scope);
        if (allUsers && !isAdmin()) {
            return JsonResult.fail(403, "查看全部账单需要管理员权限");
        }
        long userId = allUsers ? 0 : currentUserId();
        return JsonResult.ok(billStore.aggregate(userId, allUsers, from, to));
    }

    private static long currentUserId() {
        LoginUser user = UserContext.get();
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("拿不到当前登录人，请检查鉴权拦截器是否生效");
        }
        return user.getId();
    }

    /** 只有 admin 能看全量；与前端菜单的可见性无关，这里是真正的边界 */
    private static boolean isAdmin() {
        LoginUser user = UserContext.get();
        return user != null && user.getRoles() != null && user.getRoles().contains("admin");
    }
}
