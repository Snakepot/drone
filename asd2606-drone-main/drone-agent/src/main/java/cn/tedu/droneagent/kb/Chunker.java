package cn.tedu.droneagent.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * 切块器：把 {@link DocumentSection} 切成受**长度上限**约束的 {@link Chunk}。
 *
 * <h2>为什么不「一刀切」</h2>
 * 纯按字符数硬切，会把一句话、一个要点**从中间切断**，两边都不完整——检索出来读着别扭，也更容易丢信息。
 * 所以：
 * <ol>
 *   <li>章节正文**不超过上限**时，整段作一块（**不切**）；</li>
 *   <li>超长时，尽量**在换行处**断开（标题、要点列表通常一行一条，切在行边界语义最完整）；</li>
 *   <li>一整行本身超过上限（如超长表格行），退化为**按字符硬切**；</li>
 *   <li>相邻两块**重叠**若干字符，避免关键句恰好落在边界上、两边都不完整。</li>
 * </ol>
 *
 * <h2>为什么参数在这里而不是写死</h2>
 * 上限与重叠来自 {@code agent.kb.chunk.*}：不同资料（手册 vs 表格）合适的块大小不同，
 * 调参不该改代码、更不该重新打包。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不打乱顺序</b>：输出顺序 = 输入顺序，便于「同一章节的块相邻」。</li>
 *   <li><b>不算向量、不落库</b>：只产出块。</li>
 * </ul>
 */
public class Chunker {

    private final int maxChars;

    private final int overlapChars;

    /**
     * @param maxChars     单块最大字符数；&lt; 1 时按 1 处理
     * @param overlapChars 相邻块重叠字符数；会被夹到 {@code [0, maxChars-1]}，避免重叠 ≥ 上限导致原地打转
     */
    public Chunker(int maxChars, int overlapChars) {
        this.maxChars = Math.max(1, maxChars);
        this.overlapChars = Math.min(Math.max(0, overlapChars), this.maxChars - 1);
    }

    public List<Chunk> chunk(List<DocumentSection> sections) {
        List<Chunk> chunks = new ArrayList<>();
        for (DocumentSection section : sections) {
            String body = section.body() == null ? "" : section.body();
            if (body.isBlank()) {
                continue;
            }
            String sectionPath = section.pathText();
            if (body.length() <= maxChars) {
                chunks.add(new Chunk(sectionPath, body.strip()));
                continue;
            }
            for (String piece : split(body)) {
                chunks.add(new Chunk(sectionPath, piece));
            }
        }
        return chunks;
    }

    /** 按「尽量在换行处断开 + 相邻重叠」把超长正文切成不超过 {@link #maxChars} 的片段 */
    private List<String> split(String body) {
        List<String> pieces = new ArrayList<>();
        int length = body.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + maxChars, length);
            if (end < length) {
                // 在窗口内找最后一个换行，切在它后面：一行一条的要点列表就不会被劈开
                int newline = body.lastIndexOf('\n', end - 1);
                if (newline > start) {
                    end = newline + 1;
                }
            }
            String piece = body.substring(start, end).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= length) {
                break;
            }
            // 重叠回退：至少前进 1 个字符，保证循环一定终止
            start = Math.max(start + 1, end - overlapChars);
        }
        return pieces;
    }
}
