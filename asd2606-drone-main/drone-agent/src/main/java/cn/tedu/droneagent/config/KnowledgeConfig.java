package cn.tedu.droneagent.config;

import cn.tedu.droneagent.kb.ConfigPermissionSource;
import cn.tedu.droneagent.kb.KnowledgeIngestor;
import cn.tedu.droneagent.kb.KnowledgeManifest;
import cn.tedu.droneagent.kb.KnowledgeSearchTool;
import cn.tedu.droneagent.kb.MarkdownDocumentParser;
import cn.tedu.droneagent.kb.Chunker;
import cn.tedu.droneagent.kb.PermissionSource;
import cn.tedu.droneagent.vector.ApiEmbeddingModel;
import cn.tedu.droneagent.vector.EmbeddingModel;
import cn.tedu.droneagent.vector.FieldDef;
import cn.tedu.droneagent.vector.MilvusVectorStore;
import cn.tedu.droneagent.vector.Retriever;
import cn.tedu.droneagent.vector.VectorRetriever;
import cn.tedu.droneagent.vector.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内部资料检索（RAG）的装配。见 `docs/sdd/specs/REQ-AGENT-0010-内部资料检索.md`。
 *
 * <h2>为什么单开一个配置类</h2>
 * 与 {@link ToolConfig}、{@link MemoryConfig} 同理：这些类的依赖是**显式拼**出来的
 * （向量库 → 嵌入模型 → 检索 → 入库），集中在这里一眼看清依赖方向。
 *
 * <h2>集合的字段在这里定</h2>
 * 向量库适配器属于基础设施层、**不认识业务字段**（见 `ADR-0006`），所以字段清单由**场景层**给出——
 * 就是这里的 {@link #FIELDS}。将来记忆检索给另一套字段，适配器一行不改。
 *
 * <h2>开关为什么在工具里、而不在这里</h2>
 * 关掉 kb 时若把工具 Bean 也去掉，`agent.tool.catalog` 里那条元数据就**找不到行为**，
 * `ToolRegistry` 会**让服务启动失败**；而把元数据挪进代码又违反「元数据必须外置」（`REQ-AGENT-0005`）。
 * 两头都不能碰，所以：**Bean 照建、开关在 {@code KnowledgeSearchTool} 内部生效**——
 * 关掉时它一次检索都不做。代价是模型仍"看得见"这个工具（调用会得到"未启用"）。
 */
@Configuration
public class KnowledgeConfig {

    /** 集合的标量字段（与 {@code drone_knowledge} 的 schema 一致；向量字段由适配器另加） */
    static final List<FieldDef> FIELDS = List.of(
            FieldDef.varchar("doc_id", 128),
            FieldDef.varchar("file_name", 256),
            FieldDef.varchar("section", 1024),
            FieldDef.varchar("content", 8192),
            FieldDef.int32("chunk_index"),
            FieldDef.stringArray("perm_codes", 128, 16));

    /** 入库线程池：入库是「攒一批慢慢嵌」的活，不跟着请求量涨 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService knowledgeIngestExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "kb-ingest-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public EmbeddingModel embeddingModel(KnowledgeProperties properties) {
        return new ApiEmbeddingModel(properties.embedding());
    }

    /**
     * 向量库。{@code @Lazy}：连不上 Milvus 时**服务也要能起来**（检索时再连，
     * 连不上就降级为「查不到」），不该因为一个中间件没起就整个服务起不来。
     */
    @Bean
    @Lazy
    public VectorStore vectorStore(KnowledgeProperties properties) {
        KnowledgeProperties.Milvus milvus = properties.milvus();
        return new MilvusVectorStore(
                "http://" + milvus.host() + ":" + milvus.port(),
                milvus.collection(), properties.embedding().dimension(), FIELDS);
    }

    @Bean
    public Retriever retriever(EmbeddingModel embeddingModel, VectorStore vectorStore,
                               KnowledgeProperties properties) {
        return new VectorRetriever(embeddingModel, vectorStore, properties.topK(), properties.minScore());
    }

    @Bean
    public PermissionSource permissionSource(KnowledgeProperties properties) {
        return new ConfigPermissionSource(
                Path.of(properties.directory()), properties.permRules(), properties.defaultPerms());
    }

    @Bean
    public KnowledgeManifest knowledgeManifest(KnowledgeProperties properties) {
        return KnowledgeManifest.load(Path.of(properties.directory()));
    }

    @Bean
    public KnowledgeIngestor knowledgeIngestor(KnowledgeProperties properties, EmbeddingModel embeddingModel,
                                               VectorStore vectorStore, PermissionSource permissionSource,
                                               KnowledgeManifest manifest,
                                               ExecutorService knowledgeIngestExecutor) {
        Chunker chunker = new Chunker(properties.chunk().maxChars(), properties.chunk().overlapChars());
        return new KnowledgeIngestor(Path.of(properties.directory()), List.of(new MarkdownDocumentParser()),
                chunker, embeddingModel, vectorStore, permissionSource, manifest, knowledgeIngestExecutor);
    }

    /**
     * 启动后在**后台**扫一次资料（异步，不拖慢启动；失败也只记日志）。
     *
     * <p>**受 {@code agent.kb.scan-on-startup} 控制**，测试环境必须设成 false：
     * `@SpringBootTest` 会执行启动钩子，若开着，**单测就会真去调嵌入服务、写向量库**
     * （实测踩过：跑一次测试就往库里灌了 29 块，违反宪法 §5）。
     */
    @Bean
    public ApplicationRunner knowledgeSyncRunner(KnowledgeProperties properties, KnowledgeIngestor ingestor) {
        return args -> {
            if (properties.enabled() && properties.scanOnStartup()) {
                ingestor.syncAsync();
            }
        };
    }

    @Bean
    public KnowledgeSearchTool knowledgeSearchTool(Retriever retriever, KnowledgeProperties properties) {
        return new KnowledgeSearchTool(retriever, properties.enabled());
    }
}
