package cn.tedu.droneagent.config;

import cn.tedu.droneagent.kb.PermissionRule;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 内部资料检索（RAG）的配置，对应 yml 节点 {@code agent.kb.*}。
 *
 * <p>见 `docs/sdd/specs/REQ-AGENT-0010-内部资料检索.md` 与 `decisions/ADR-0006`。
 *
 * <h2>为什么这些值要可配，而不是写死在代码里</h2>
 * 「资料放哪」「取几条」「用哪个嵌入模型」「连哪个 Milvus」都是**部署时才知道**的事：
 * 换嵌入供应商、换模型维度、换向量库地址都不该改代码、更不该重新打包。
 * 尤其 {@code embedding.dimension} —— 它**必须与所用模型一致**，否则写进 Milvus 会被拒。
 *
 * <h2>为什么用嵌套 record 分组</h2>
 * 平铺十来个字段会看不清归属；分成 {@link Chunk}（怎么切）、{@link Embedding}（怎么算向量）、
 * {@link Milvus}（存哪里）三组，yml 里也是同样的层级，改的人一眼知道在动哪一块。
 *
 * @param enabled   总开关。关掉即回到「不检索任何内部资料」的纯对话（可灰度 / 回滚用）
 * @param scanOnStartup 启动后是否**在后台扫一次资料目录**。生产要开（否则新放的资料不入库）；
 *                      **测试环境必须关**——`@SpringBootTest` 会执行启动钩子，若开着，
 *                      单测就会真的去调嵌入服务、写向量库（违反宪法 §5「单测不依赖真实外部环境」）
 * @param directory 知识库**原料目录**（当前只支持 Markdown）。运行时按需创建；部署应挂卷
 * @param topK      检索返回的条数上限
 * @param minScore  相似度下限：低于它的结果不返回（{@code 0} 表示不过滤）；
 *                  接入真实嵌入模型后**按评估集调**，不要凭感觉拍
 * @param permRules    可见范围规则（**在配置里声明，不改原始资料**）：按**相对路径**匹配，先命中先用。
 *                     见 {@link PermissionRule}
 * @param defaultPerms 没命中任何规则时用的默认权限码。为空表示**没有默认**——
 *                     这类资料**无人可见**（fail-closed，宁可查不到也不能默认公开）
 * @param chunk     切块参数，见 {@link Chunk}
 * @param embedding 嵌入模型参数，见 {@link Embedding}
 * @param milvus    向量库参数，见 {@link Milvus}
 */
@ConfigurationProperties(prefix = "agent.kb")
public record KnowledgeProperties(
        boolean enabled,
        boolean scanOnStartup,
        String directory,
        int topK,
        double minScore,
        List<PermissionRule> permRules,
        List<String> defaultPerms,
        Chunk chunk,
        Embedding embedding,
        Milvus milvus
) {

    public KnowledgeProperties {
        permRules = permRules == null ? List.of() : List.copyOf(permRules);
        defaultPerms = defaultPerms == null ? List.of() : List.copyOf(defaultPerms);
    }

    /**
     * 切块参数。
     *
     * @param maxChars     单个块的最大字符数。太大则检索不精、太小则丢上下文
     * @param overlapChars 相邻块**重叠**的字符数。避免关键句被恰好切在边界上、两边都不完整
     */
    public record Chunk(int maxChars, int overlapChars) {
    }

    /**
     * 嵌入模型参数：**走 embedding API**（见 `ADR-0006` 决策 5）。
     *
     * <p>供应商与模型名都从配置 / 环境变量来，代码里不写死；{@code api-key} **只从环境变量读**，
     * 不落配置文件（与 `DeepSeekProperties` 同一红线）。
     *
     * @param baseUrl   嵌入服务的地址
     * @param apiKey    API-KEY，从环境变量注入
     * @param model     模型名（不同模型维度不同）
     * @param dimension 向量维度，**必须与 {@code model} 一致**
     */
    public record Embedding(String baseUrl, String apiKey, String model, int dimension) {

        /** 是否已配置可用的嵌入服务；未配置时检索应降级为「查不到」，而不是抛异常炸对话 */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }
    }

    /**
     * 向量库参数（自托管 Milvus，Docker 单机，见 `ADR-0006`）。
     *
     * @param host       地址
     * @param port       端口（Milvus 默认 19530）
     * @param collection 集合名。块文本、向量、来源与可见权限码都在这个集合里
     */
    public record Milvus(String host, int port, String collection) {
    }
}
