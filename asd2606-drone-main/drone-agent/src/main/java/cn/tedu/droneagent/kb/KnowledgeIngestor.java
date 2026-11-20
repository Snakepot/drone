package cn.tedu.droneagent.kb;

import cn.tedu.droneagent.vector.EmbeddingModel;
import cn.tedu.droneagent.vector.VectorFilter;
import cn.tedu.droneagent.vector.VectorRecord;
import cn.tedu.droneagent.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * 入库编排：**扫目录 → 解析 → 切块 → 算向量 → 写库 → 记账**。
 *
 * <h2>增量靠「内容哈希」，不靠修改时间</h2>
 * 每份资料入库前与台账里的 {@code contentHash} 比一比：**一样就跳过，不一样才重建**。
 * 用内容而不是时间戳——时间戳会被「touch 一下」骗到，内容哈希不会。
 *
 * <h2>重建为什么是「先删后写」</h2>
 * 资料改短后（比如从 10 块变成 6 块），旧的 `…#6…#9` 不会自己消失，会**残留**成幽灵块被检索到。
 * 所以重建时**先按 {@code doc_id} 删掉旧块**再写新的——这一步比在检索侧「去重」靠谱得多。
 *
 * <h2>失败怎么重试</h2>
 * **台账只在入库成功后才写**。于是失败的文件不会被记账，**下次 {@link #sync()} 自然重来**——
 * 不需要额外的重试队列。这也意味着重试是**有边界的**：只有人去扫（或定时扫）才会重试，不会自己空转。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不决定权限从哪来</b>：交给 {@link PermissionSource}（声明机制见 T-7b）。</li>
 *   <li><b>不调度</b>：什么时候扫由调用方决定，这里只提供一次 {@link #syncAsync()}。</li>
 * </ul>
 */
@Slf4j
public class KnowledgeIngestor {

    private final Path directory;

    private final List<DocumentParser> parsers;

    private final Chunker chunker;

    private final EmbeddingModel embedder;

    private final VectorStore store;

    private final PermissionSource permissions;

    private final KnowledgeManifest manifest;

    private final Executor executor;

    public KnowledgeIngestor(Path directory, List<DocumentParser> parsers, Chunker chunker,
                             EmbeddingModel embedder, VectorStore store,
                             PermissionSource permissions, KnowledgeManifest manifest, Executor executor) {
        this.directory = directory;
        this.parsers = List.copyOf(parsers);
        this.chunker = chunker;
        this.embedder = embedder;
        this.store = store;
        this.permissions = permissions;
        this.manifest = manifest;
        this.executor = executor;
    }

    /**
     * 扫一遍知识库目录，**只重建内容变了的**。
     *
     * @return 实际重建的份数
     */
    public int sync() {
        if (!Files.isDirectory(directory)) {
            // **建出来，而不是跳过**：目录不在，多半是刚部署、还没建挂载点。
            // 这里把路径建成一个可用的空目录，并打出**绝对路径**——运维才知道该把资料放到哪儿。
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new UncheckedIOException("创建知识库目录失败：" + directory.toAbsolutePath(), e);
            }
            log.warn("【知识库】目录不存在，已创建：{}。把资料放进去后再扫一次即可入库",
                    directory.toAbsolutePath());
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描知识库目录失败：" + directory, e);
        }

        int rebuilt = 0;
        for (Path file : files) {
            if (ingestIfChanged(file)) {
                rebuilt++;
            }
        }
        // 打出**绝对路径**：日志里只说「./data/knowledge」时，人常不知道进程实际在哪个目录下找
        log.info("【知识库】扫描完成：目录={}，共 {} 个文件，重建 {} 份（跳过 {} 份未变化）",
                directory.toAbsolutePath(), files.size(), rebuilt, files.size() - rebuilt);
        return rebuilt;
    }

    /** 异步扫一遍：不阻塞调用方（服务启动、或运维触发时用） */
    public void syncAsync() {
        executor.execute(() -> {
            try {
                sync();
            } catch (RuntimeException e) {
                // 后台任务的异常没人接，必须自己记下来，否则「日志里没有」会被误读成「没发生」
                log.warn("【知识库】异步入库失败：{}", e.getMessage(), e);
            }
        });
    }

    /**
     * 单份文件：内容变了才重建。
     *
     * @return 是否真的重建了
     */
    public boolean ingestIfChanged(Path file) {
        DocumentParser parser = parserFor(file);
        if (parser == null) {
            log.debug("【知识库】跳过不支持的文件：{}", file.getFileName());
            return false;
        }
        String docId = docIdOf(file);
        String contentHash = hashOf(file);
        KnowledgeManifest.Entry known = manifest.get(docId);
        if (known != null && known.contentHash().equals(contentHash)) {
            return false;
        }
        ingest(file, parser, docId, contentHash);
        return true;
    }

    private void ingest(Path file, DocumentParser parser, String docId, String contentHash) {
        String fileName = file.getFileName().toString();
        try {
            List<Chunk> chunks = chunker.chunk(parser.parse(file));
            List<String> permissionCodes = permissions.permissionCodesOf(file);
            if (permissionCodes.isEmpty()) {
                // fail-closed：没声明可见范围 ≠ 谁都能看。写明日志，别让人以为「没配就是公开」
                log.warn("【知识库】{} 未声明可见权限码，将**无人可见**（fail-closed）", fileName);
            }

            // 先删旧块：资料改短后残留的块不会自己消失，必须显式清掉
            store.deleteByFilter(VectorFilter.eq("doc_id", docId));

            if (!chunks.isEmpty()) {
                List<float[]> vectors = embedder.embedAll(chunks.stream().map(Chunk::embeddingText).toList());
                List<VectorRecord> records = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("doc_id", docId);
                    metadata.put("file_name", fileName);
                    metadata.put("section", chunk.sectionPath());
                    metadata.put("content", chunk.text());
                    metadata.put("chunk_index", i);
                    metadata.put("perm_codes", permissionCodes);
                    records.add(new VectorRecord(docId + "#" + i, vectors.get(i), metadata));
                }
                store.upsert(records);
            }

            // 台账只在成功后写：失败不记账，下次扫描自然重试
            manifest.put(new KnowledgeManifest.Entry(
                    docId, fileName, contentHash, chunks.size(), Instant.now().toString()));
            log.info("【知识库】已入库：{}（{} 块，可见权限码 {}）", fileName, chunks.size(),
                    permissionCodes.isEmpty() ? "无（无人可见）" : permissionCodes);
        } catch (IOException e) {
            throw new UncheckedIOException("读取资料失败：" + file, e);
        }
    }

    private DocumentParser parserFor(Path file) {
        String name = file.getFileName().toString();
        return parsers.stream().filter(parser -> parser.supports(name)).findFirst().orElse(null);
    }

    /** 文档标识由**文件路径**推出（内容改了它不变），这样重建时才能把旧块删干净 */
    private static String docIdOf(Path file) {
        return md5(file.toAbsolutePath().normalize().toString());
    }

    private static String hashOf(Path file) {
        try {
            return md5(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取资料失败：" + file, e);
        }
    }

    private static String md5(String text) {
        return md5(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5", e);
        }
    }
}
