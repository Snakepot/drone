package cn.tedu.droneagent.kb;

import java.nio.file.Path;
import java.util.List;

/**
 * 「这份资料的**可见权限码**从哪来」——入库管线与**声明机制**之间的缝。
 *
 * <h2>为什么要留这条缝</h2>
 * 入库管线只需要知道「每份资料对应哪些权限码」，**不需要**知道这些码写在哪
 * （Markdown 的 frontmatter？目录约定？全局默认？）。把来源抽成接口后：
 * 声明机制怎么定，都**不改管线**；管线也能用桩来单测。
 *
 * <h2>【安全】返回空 = 谁都看不到</h2>
 * 实现**不得**用「空列表」表达「不限制」。空列表的含义是 **fail-closed（无人可见）**；
 * 「不限制」在检索侧由 {@code VectorFilter.Always} 显式表达，绝不该是入库的默认。
 *
 * <p>见 `docs/sdd/tasks/REQ-AGENT-0010` 的 T-7b（声明机制待定）。
 */
@FunctionalInterface
public interface PermissionSource {

    /**
     * @param file 资料文件
     * @return 可见权限码，如 {@code [agent:chat]}；**返回空表示谁都看不到**
     */
    List<String> permissionCodesOf(Path file);
}
