package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.vector.EmbeddingModel;
import cn.tedu.droneagent.vector.InMemoryVectorStore;
import cn.tedu.droneagent.vector.ScoredRecord;
import cn.tedu.droneagent.vector.VectorFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeIngestor} 的单元测试。
 *
 * <p>对应验收条件：`REQ-AGENT-0010` 的 <b>AC-6</b>（放入资料即被解析、切块、可检索）与
 * <b>AC-3</b> 的一半（未声明可见范围 → **无人可见**）。
 *
 * <p>全程用内存向量库 + 桩嵌入模型 + 临时目录，**不连真实外部环境**（宪法 §5）。
 */
class KnowledgeIngestorTest {

    private static final int DIM = 2;

    /** 桩嵌入模型：向量只由文本长度决定，够用且可预测；顺带记调用次数 */
    private static final class StubEmbedder implements EmbeddingModel {
        private int calls;

        @Override
        public int dimension() {
            return DIM;
        }

        @Override
        public float[] embed(String text) {
            calls++;
            return new float[]{text.length(), 1};
        }
    }

    /** 一行 15 字；切块上限 100，故 10 行（约 159 字）能切出 2 块以上 */
    private static final String LINE = "内容行内容行内容行内容行内容行";

    @TempDir
    Path dir;

    private final StubEmbedder embedder = new StubEmbedder();

    private final InMemoryVectorStore store = new InMemoryVectorStore(DIM);

    private Path kbDir;

    private KnowledgeIngestor ingestor(List<String> permissionCodes) {
        kbDir = dir.resolve("kb");
        KnowledgeManifest manifest = KnowledgeManifest.load(dir);
        return new KnowledgeIngestor(kbDir, List.of(new MarkdownDocumentParser()), new Chunker(100, 10),
                embedder, store, file -> permissionCodes, manifest, Runnable::run);
    }

    private Path write(String name, String content) throws IOException {
        Files.createDirectories(kbDir);
        Path file = kbDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String md(String body) {
        return "# 手册\n\n" + body + "\n";
    }

    @Test
    @DisplayName("首次入库：解析→切块→写库→记账")
    void firstIngestFillsStore() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of("agent:chat"));
        write("手册.md", md((LINE + "\n").repeat(10)));

        int rebuilt = ingestor.sync();

        assertEquals(1, rebuilt);
        assertTrue(store.size() >= 2, "长正文应切成多块，实际 " + store.size());
        assertEquals(1, KnowledgeManifest.load(dir).size(), "台账应记一行");
    }

    @Test
    @DisplayName("内容没变：再次扫描跳过，不重复写、也不重复嵌入（省钱）")
    void unchangedIsSkipped() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of("agent:chat"));
        write("手册.md", md((LINE + "\n").repeat(10)));
        ingestor.sync();
        int sizeAfterFirst = store.size();
        int callsAfterFirst = embedder.calls;

        int rebuilt = ingestor.sync();

        assertEquals(0, rebuilt, "没变就该跳过");
        assertEquals(sizeAfterFirst, store.size());
        assertEquals(callsAfterFirst, embedder.calls, "跳过时不该再调嵌入服务");
    }

    @Test
    @DisplayName("内容变了：增量重建，且**旧块被清干净**（不会残留幽灵块）")
    void changedRebuildsAndRemovesOldChunks() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of("agent:chat"));
        Path file = write("手册.md", md(("旧内容旧内容旧内容旧内容旧内容旧\n").repeat(10)));
        ingestor.sync();
        int before = store.size();
        assertTrue(before >= 2, "先要有多块才能验「清旧块」，实际 " + before);

        Files.writeString(file, md("新的唯一内容"), StandardCharsets.UTF_8);
        int rebuilt = ingestor.sync();

        assertEquals(1, rebuilt);
        assertEquals(1, store.size(), "改短后旧块应被删除，只剩新的一块");
        List<ScoredRecord> hits = store.search(new float[]{1, 1}, VectorFilter.always(), 5);
        assertEquals("新的唯一内容", hits.get(0).metadata().get("content"));
    }

    @Test
    @DisplayName("目录不存在：**建出来**而不是静默跳过；建好后放进资料就能入库")
    void createsMissingDirectory() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of("agent:chat"));
        assertFalse(Files.exists(kbDir), "前置：目录还不存在");

        assertEquals(0, ingestor.sync(), "空目录没有可入库的东西");
        assertTrue(Files.isDirectory(kbDir), "应把知识库目录建出来（运维才知道往哪放资料）");

        write("手册.md", md("电池要保养"));
        assertEquals(1, ingestor.sync(), "目录就绪后放进资料即可入库");
    }

    @Test
    @DisplayName("不支持的文件类型跳过（解析器接口留着，格式以后再加）")
    void unsupportedFileIsSkipped() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of("agent:chat"));
        write("说明.txt", "这不是 Markdown");

        assertEquals(0, ingestor.sync());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("未声明可见范围 → 入库但**无人可见**（fail-closed，不是公开）")
    void emptyPermissionCodesMeansInvisible() throws IOException {
        KnowledgeIngestor ingestor = ingestor(List.of());
        write("手册.md", md("电池要保养"));

        assertEquals(1, ingestor.sync());

        assertTrue(store.search(new float[]{1, 1},
                KnowledgeScope.visibleTo(List.of("agent:chat")), 5).isEmpty(),
                "没声明可见范围的资料不该被任何人检索到");
    }
}
