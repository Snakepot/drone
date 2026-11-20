package cn.tedu.droneagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SensitiveMasker} 的单元测试。
 *
 * <p>对应验收条件：`docs/sdd/specs/REQ-AGENT-0003-会话与长期记忆.md` 的
 * <b>AC-18</b>（敏感字段被按规则脱敏）与 <b>AC-19</b>（非敏感内容逐字保留）。
 *
 * <p>其中两组用例是**顺序守卫**：如果规则顺序被调换（例如把数字类规则提到邮箱/令牌之前），
 * 它们会失败。这类缺陷不会让程序崩，只会让脱敏结果悄悄变残缺，靠肉眼很难发现。
 */
class SensitiveMaskerTest {

    @Test
    @DisplayName("AC-18 手机号：保留前 3 后 4，中间遮掉")
    void masksPhone() {
        assertEquals("联系我 138****8000 谢谢", SensitiveMasker.mask("联系我 13800138000 谢谢"));
    }

    @Test
    @DisplayName("AC-18 身份证：保留前 6 后 4")
    void masksIdCard() {
        assertEquals("身份证 110101****1234", SensitiveMasker.mask("身份证 110101199001011234"));
    }

    @Test
    @DisplayName("AC-18 身份证末位为 X 时同样能识别")
    void masksIdCardEndingWithX() {
        assertEquals("110101****123X", SensitiveMasker.mask("11010119900101123X"));
    }

    @Test
    @DisplayName("AC-18 银行卡：保留后 4 位")
    void masksBankCard() {
        assertEquals("卡号 ****7890", SensitiveMasker.mask("卡号 6222021234567890"));
    }

    @Test
    @DisplayName("AC-18 邮箱：保留首字符与域名")
    void masksEmail() {
        assertEquals("u***@example.com", SensitiveMasker.mask("user.name@example.com"));
    }

    @Test
    @DisplayName("AC-18 令牌：遮掉值、保留前缀标签")
    void masksToken() {
        assertEquals("sk-***", SensitiveMasker.mask("sk-abcdefghijklmnop"));
        assertEquals("Bearer ***", SensitiveMasker.mask("Bearer eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    @DisplayName("AC-18 密码：保留标签与分隔符，值整体遮掉，且不吃掉后面的句子")
    void masksPassword() {
        assertEquals("密码：***，请勿外传", SensitiveMasker.mask("密码：Abc12345，请勿外传"));
        assertEquals("password=*** 已重置", SensitiveMasker.mask("password=Secret99 已重置"));
    }

    @Test
    @DisplayName("AC-19 非敏感文本逐字保留（含普通数字与中文标点）")
    void leavesNonSensitiveTextUntouched() {
        String text = "M300 电量 78%，循环 120 次，限高 120 米；华东区巡检。";
        assertEquals(text, SensitiveMasker.mask(text));
    }

    @Test
    @DisplayName("AC-19 没有命中任何规则时，返回的是同一个字符串内容")
    void noRuleHitKeepsContent() {
        String text = "电池低温报警该怎么处理？";
        assertEquals(text, SensitiveMasker.mask(text));
    }

    @Test
    @DisplayName("顺序守卫：邮箱里的手机号按整封邮箱处理，不留下残缺结构")
    void masksWholeEmailWhenLocalPartLooksLikePhone() {
        assertEquals("u***@example.com", SensitiveMasker.mask("user13800138000@example.com"));
    }

    @Test
    @DisplayName("顺序守卫：令牌里含长数字串时整体遮掉，不被数字规则抢先咬一口")
    void masksTokenContainingLongDigitRun() {
        assertEquals("sk-***", SensitiveMasker.mask("sk-1234567890123456"));
    }

    @Test
    @DisplayName("边界守卫：更长的数字串不会被身份证规则咬掉 18 位后剩一位裸露")
    void doesNotLeaveTrailingDigitFromLongerRun() {
        // 19 位纯数字：身份证规则要求恰好 18 位且后面不是数字，故不匹配；
        // 整体按银行卡规则处理，保留后 4 位
        assertEquals("****6789", SensitiveMasker.mask("1234567890123456789"));
    }

    @Test
    @DisplayName("null 与空串原样返回，不抛异常")
    void handlesNullAndEmpty() {
        assertNull(SensitiveMasker.mask(null));
        assertEquals("", SensitiveMasker.mask(""));
    }
}
