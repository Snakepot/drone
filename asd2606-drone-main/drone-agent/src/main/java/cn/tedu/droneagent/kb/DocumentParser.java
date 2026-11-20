package cn.tedu.droneagent.kb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 文档**解析器**：把一种格式的文件读成一组 {@link DocumentSection}。
 *
 * <h2>为什么先立接口、且这期只实现 Markdown</h2>
 * 需求要求「资料可能很多、格式可能不同」。但多格式（PDF / Office）解析会引入重型依赖，
 * 一期不该被它拖住。所以**接口先立**，本期只给 {@link MarkdownDocumentParser}；
 * 将来加格式 = **加一个实现类**，入库与检索**一行不改**（见 `ADR-0006` 决策 3）。
 *
 * <h2>为什么入参是 {@link Path} 而不是字符串</h2>
 * Markdown 是文本，但 PDF / Word 是**二进制**。若接口只收字符串，将来接 PDF 就得改接口（进而改所有调用方）。
 * 收文件路径，各实现**自己决定怎么读**：文本按 UTF-8 读、二进制按字节读。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不切块</b>：只按**结构**（标题层级）分段，多长切一刀是 {@link Chunker} 的事。</li>
 *   <li><b>不算向量、不落库、不判权限</b>：那是链路后面的事。</li>
 * </ul>
 */
public interface DocumentParser {

    /**
     * 是否支持这个文件。
     *
     * @param fileName 文件名（含扩展名即可，不一定存在）
     */
    boolean supports(String fileName);

    /**
     * 解析成一个文档的结构化章节序列。
     *
     * @throws IOException 读文件失败
     */
    List<DocumentSection> parse(Path file) throws IOException;
}
