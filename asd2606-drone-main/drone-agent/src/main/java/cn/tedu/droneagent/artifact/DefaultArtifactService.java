package cn.tedu.droneagent.artifact;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 默认实现：抽取 → 落盘 → 返回清洗后的回复。
 *
 * <p>落盘失败（磁盘满、权限）**不能让整次对话失败**：记 WARN，按原文返回，
 * 用户至少还能看到内容。
 */
@Slf4j
public class DefaultArtifactService implements ArtifactService {

    private final ArtifactExtractor extractor;
    private final ArtifactStore store;

    public DefaultArtifactService(ArtifactExtractor extractor, ArtifactStore store) {
        this.extractor = extractor;
        this.store = store;
    }

    @Override
    public Capture capture(long userId, String conversationId, String reply) {
        Optional<ArtifactExtractor.Extracted> extracted = extractor.extract(reply);
        if (extracted.isEmpty()) {
            return new Capture(reply, List.of());
        }
        ArtifactExtractor.Extracted document = extracted.get();
        try {
            ArtifactRef ref = store.save(userId, conversationId, document);
            return new Capture(document.cleanedReply(), List.of(ref));
        } catch (RuntimeException e) {
            log.warn("【产物】保存失败，回复按原文返回：userId={}，原因={}", userId, e.getMessage());
            return new Capture(reply, List.of());
        }
    }
}
