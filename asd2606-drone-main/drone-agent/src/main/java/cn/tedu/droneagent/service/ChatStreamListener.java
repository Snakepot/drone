package cn.tedu.droneagent.service;

/**
 * 流式模型调用的回调：把「思考」「正文」「进度」实时交给上层（最终推给 SSE）。
 *
 * <p>三种事件语义不同，前端要分别渲染：
 * <ul>
 *   <li>{@link #onReasoning}：深度思考的文字（可折叠展示）；</li>
 *   <li>{@link #onContent}：最终答复的正文（逐字追加）；</li>
 *   <li>{@link #onStatus}：等待类提示（如「正在查询任务数据…」），不是模型输出。</li>
 * </ul>
 *
 * <p>实现方**不应抛异常**：客户端断开由上层（SSE 适配器）决定如何中止。
 */
public interface ChatStreamListener {

    void onReasoning(String delta);

    void onContent(String delta);

    void onStatus(String text);

    /** 什么都不做：测试与不需要流式回调的场景 */
    ChatStreamListener NOOP = new ChatStreamListener() {
        @Override
        public void onReasoning(String delta) {
        }

        @Override
        public void onContent(String delta) {
        }

        @Override
        public void onStatus(String text) {
        }
    };
}
