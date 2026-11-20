package cn.tedu.droneagent.artifact;

import java.util.List;

/**
 * 产物捕获：把回复里的 HTML 抽出来存好，返回「清洗后的回复 + 产物引用」。
 *
 * <h2>为什么单独一个接口</h2>
 * {@code ConversationService} 不该关心文件怎么存；测试里也可以用 {@link #noop()}
 * 让既有用例完全不感知产物逻辑。
 */
public interface ArtifactService {

    /**
     * @param userId         当前用户（产物目录的归属）
     * @param conversationId 会话 id（元数据排查用）
     * @param reply          模型原始回复
     * @return 清洗后的回复与产物列表（没有产物时原样返回）
     */
    Capture capture(long userId, String conversationId, String reply);

    /** 什么都不做：回复原样返回，产物为空。供不需要产物的场景与测试使用 */
    static ArtifactService noop() {
        return (userId, conversationId, reply) -> new Capture(reply, List.of());
    }

    /**
     * @param reply     清洗后的回复（已去掉 HTML）
     * @param artifacts 本次产出的文件
     */
    record Capture(String reply, List<ArtifactRef> artifacts) {
        public Capture {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }
}
