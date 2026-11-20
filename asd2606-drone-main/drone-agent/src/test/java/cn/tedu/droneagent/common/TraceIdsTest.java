package cn.tedu.droneagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceIds} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0005-工具框架增强.md` 的
 * <b>AC-4</b>（traceId）的生成侧——只需「格式固定、不易重复」。
 */
class TraceIdsTest {

    @Test
    @DisplayName("生成 8 个十六进制字符")
    void formatIsEightHexChars() {
        String id = TraceIds.newId();
        assertEquals(8, id.length());
        assertTrue(id.matches("[0-9a-f]{8}"), "应是十六进制，实际=" + id);
    }

    @Test
    @DisplayName("连续生成不重复（并发下够用）")
    void idsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(TraceIds.newId()), "出现重复 id");
        }
    }
}
