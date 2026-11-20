package cn.tedu.droneagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MemoryProperties} 的绑定测试。
 *
 * <h2>为什么这个测试值得存在</h2>
 * 这是一个按构造方法绑定的 record。**yml 里漏了某个键、或者键名拼错时，配置绑定是最容易
 * 悄悄失效的一环**：服务照常启动，直到运行时才以「会话永不过期」这类诡异表现暴露出来。
 *
 * <p>所以这里逐个断言取值，而不只断言「上下文能启动」。它同时是「yml 键名写错了」的
 * 探测器：把 {@code idle-timeout} 之类拼错，这个测试就会红——不依赖 Spring 在缺值时
 * 是静默填空值还是直接报错。
 */
@SpringBootTest
class MemoryPropertiesTest {

    @Autowired
    private MemoryProperties memory;

    @Test
    @DisplayName("agent.memory.* 全部能绑定，且与 yml 中的默认值一致")
    void bindsAllKeysFromYml() {
        assertEquals("./data/agent-memory", memory.root());
        assertEquals(500, memory.sessionMaxCount());
        assertEquals(Duration.ofMinutes(30), memory.idleTimeout());
        assertEquals(200, memory.maxMessagesPerSession());
        assertEquals(DataSize.ofMegabytes(2), memory.fileMaxBytes());
        assertEquals(10, memory.injectMaxTurns());
        assertEquals(8000, memory.injectMaxChars());
    }

    @Test
    @DisplayName("带单位的配置确实被解析成了时长与字节数，而不是当成裸数字")
    void parsesUnits() {
        assertNotNull(memory.idleTimeout());
        assertEquals(1800L, memory.idleTimeout().getSeconds());
        assertEquals(2 * 1024 * 1024L, memory.fileMaxBytes().toBytes());
    }
}
