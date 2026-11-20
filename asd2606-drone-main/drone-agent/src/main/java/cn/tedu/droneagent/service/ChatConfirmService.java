package cn.tedu.droneagent.service;

import cn.tedu.droneagent.common.LoginUser;
import cn.tedu.droneagent.common.TraceIds;
import cn.tedu.droneagent.common.UserContext;
import cn.tedu.droneagent.dto.ChatResponse;
import cn.tedu.droneagent.memory.SessionKey;
import cn.tedu.droneagent.tool.PendingActionStore;
import cn.tedu.droneagent.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * 危险工具的**二次确认入口**：用户点「确认 / 拒绝」后，把结果接回编排、把这轮跑完。
 *
 * <h2>顺序很关键</h2>
 * <ol>
 *   <li>先 {@link PendingActionStore#take} 把票据**取走**（消费即失效）——归属、有效期都在那一步判；
 *       取不到就直接告诉用户「已处理或已过期」；</li>
 *   <li>再核对**票据的会话**与当前会话一致（防止拿别的会话的票据来确认）；</li>
 *   <li>然后交给 {@link ToolCallingService#resume}：批准才真正执行，拒绝就当作「用户拒绝」回填。</li>
 * </ol>
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不执行工具</b>：真正执行在 {@code ToolCallingService.resume} 里（那里还会**再判一次权限**）。</li>
 *   <li><b>不管票据怎么存</b>：那是 {@link PendingActionStore} 的事。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatConfirmService {

    /** 票据取不到时的统一话术（不区分「不存在 / 非本人 / 已过期 / 已处理」，免得泄露信息） */
    private static final String HANDLED_OR_EXPIRED = "这次确认已处理或已过期，请重新发起操作。";

    /** 票据不属于当前会话 */
    private static final String WRONG_CONVERSATION = "这次确认不属于当前会话，请重新发起操作。";

    private final PendingActionStore pendingStore;

    private final ToolCallingService toolCallingService;

    @Autowired
    public ChatConfirmService(PendingActionStore pendingStore, ToolCallingService toolCallingService) {
        this.pendingStore = pendingStore;
        this.toolCallingService = toolCallingService;
    }

    /**
     * 确认或拒绝一次危险操作，返回这一轮的最终答复（可能又生成新的待确认）。
     *
     * @param key       当前用户的会话
     * @param pendingId 票据 id
     * @param approve   true=执行，false=拒绝
     */
    public ChatResponse confirm(SessionKey key, String pendingId, boolean approve) {
        String traceId = TraceIds.newId();

        Optional<PendingActionStore.PendingAction> taken = pendingStore.take(pendingId, key.userId());
        if (taken.isEmpty()) {
            log.warn("【确认】票据不存在 / 非本人 / 已过期 / 已处理：pendingId={}，userId={}，traceId={}",
                    pendingId, key.userId(), traceId);
            return new ChatResponse(HANDLED_OR_EXPIRED, null, java.util.List.of(), traceId);
        }

        PendingActionStore.PendingAction action = taken.get();
        if (!action.conversationId().equals(key.conversationId())) {
            log.warn("【确认】票据与当前会话不符：pendingId={}，票据会话={}，当前会话={}",
                    pendingId, action.conversationId(), key.conversationId());
            return new ChatResponse(WRONG_CONVERSATION, null, java.util.List.of(), traceId);
        }

        log.info("【确认】收到确认：pendingId={}，approve={}，工具=「{}」，traceId={}",
                pendingId, approve, action.toolName(), traceId);
        ToolCallingService.AskResult result =
                toolCallingService.resume(key, action, approve, traceId, currentToolContext(traceId));
        return new ChatResponse(result.reply(), result.pending(), java.util.List.of(), traceId);
    }

    /**
     * 调用方身份（角色 + 权限码），在**请求线程**上取好再传下去。
     *
     * <p>理由同 {@code ConversationService}：工具跑在执行器线程上，ThreadLocal 取不到。
     */
    private static ToolContext currentToolContext(String traceId) {
        LoginUser user = UserContext.get();
        return user == null ? ToolContext.anonymous().withTraceId(traceId)
                : ToolContext.ofUser(user.getId(), user.getRoles(), user.getPermissions(), user.getBearerToken())
                        .withTraceId(traceId);
    }
}
