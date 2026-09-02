package com.shorturl;

import com.shorturl.utils.Base62Utils;
import com.shorturl.utils.FeistelUtils;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FeistelUtils 单元测试 —— 验证 3 个核心承诺:
 *  ① 可逆性  —— decrypt(encrypt(id)) == id
 *  ② 无碰撞  —— 100w 连续 id 的混淆结果两两不同（双射）
 *  ③ 非连号  —— id=1/2/3 相邻 → 短码绝不连号、甚至看起来毫无规律
 */
class FeistelUtilsTest {

    // ===== ① 可逆性 =====

    @Test
    void encryptThenDecrypt_shouldReturnOriginal() {
        // 边界值
        assertEquals(1L, FeistelUtils.decrypt(FeistelUtils.encrypt(1L)));
        assertEquals(FeistelUtils.MAX_32, FeistelUtils.decrypt(FeistelUtils.encrypt(FeistelUtils.MAX_32)));
        // 典型值
        for (long id : new long[]{10L, 1000L, 99999L, 1000000L, 123456789L, 0xABCDEF00L}) {
            long shuffled = FeistelUtils.encrypt(id);
            long restored = FeistelUtils.decrypt(shuffled);
            assertEquals(id, restored, "id=" + id + " 可逆性失败: shuffled=" + shuffled);
        }
    }

    @Test
    void encryptThenDecrypt_1MillionSequential_shouldAllInvertible() {
        // 连续 1~1,000,000 全量验证（约 0.3s，纯位运算很快）
        int N = 1_000_000;
        for (long id = 1; id <= N; id++) {
            long shuffled = FeistelUtils.encrypt(id);
            long restored = FeistelUtils.decrypt(shuffled);
            if (restored != id) {
                fail("可逆性失败: id=" + id + ", shuffled=" + shuffled + ", restored=" + restored);
            }
        }
    }

    // ===== ② 无碰撞（双射） =====

    @Test
    void encrypt_1MillionSequential_shouldHaveNoCollision() {
        int N = 1_000_000;
        Set<Long> seen = new HashSet<>(N * 2);
        Set<String> codes = new HashSet<>(N * 2);
        for (long id = 1; id <= N; id++) {
            long shuffled = FeistelUtils.encrypt(id);
            assertTrue(seen.add(shuffled), "混淆 id 碰撞: id=" + id + ", shuffled=" + shuffled);
            String shortCode = Base62Utils.encode(shuffled);
            assertTrue(codes.add(shortCode), "短码碰撞: id=" + id + ", shortCode=" + shortCode);
        }
        assertEquals(N, seen.size(), "混淆 id 数量不对");
        assertEquals(N, codes.size(), "短码数量不对");
    }

    // ===== ③ 非连号 & 不可预测 =====

    @Test
    void encrypt_adjacentIds_shouldProduceDistantResults() {
        // 原 id 连号时: Base62(id) 也是连号 (比如 1000000→4c92, 1000001→4c93)
        // 加密后连号差异必须极大: 结果的十进制差 > 1,000,000, 且短码前缀首字符不同
        long s1 = FeistelUtils.encrypt(1_000_000L);
        long s2 = FeistelUtils.encrypt(1_000_001L);
        long s3 = FeistelUtils.encrypt(1_000_002L);
        String c1 = Base62Utils.encode(s1);
        String c2 = Base62Utils.encode(s2);
        String c3 = Base62Utils.encode(s3);

        // 打印一下方便肉眼确认（正式环境可注释）
        System.out.println("  id=1000000  → shuffled=" + s1 + " → shortCode=" + c1);
        System.out.println("  id=1000001  → shuffled=" + s2 + " → shortCode=" + c2);
        System.out.println("  id=1000002  → shuffled=" + s3 + " → shortCode=" + c3);

        // 混淆值差至少 100w
        assertTrue(Math.abs(s1 - s2) > 1_000_000, "相邻 id 混淆值差太小: " + s1 + " vs " + s2);
        assertTrue(Math.abs(s2 - s3) > 1_000_000, "相邻 id 混淆值差太小: " + s2 + " vs " + s3);
        // 短码首字符不同（概率上接近必然）
        assertNotEquals(c1.charAt(0), c2.charAt(0),
                "相邻 id 短码首字符相同: " + c1 + " vs " + c2);
        assertNotEquals(c2.charAt(0), c3.charAt(0),
                "相邻 id 短码首字符相同: " + c2 + " vs " + c3);
    }

    @Test
    void encrypt_zeroOutputEdge_shouldStayBijective() {
        // 全空间恰好有一个 id w=decrypt(0) 满足 encrypt(w)==0, 这是双射的正常组成部分。
        // 回归保护: 若有人加回"是 0 就兜底改 1"的逻辑, 本用例立刻红
        // (兜底后 encrypt(w)=1 会与 encrypt(decrypt(1)) 撞车, 破坏零碰撞承诺)。
        long w = FeistelUtils.decrypt(0L);
        assertTrue(w >= 0 && w <= FeistelUtils.MAX_32, "decrypt(0) 超出 32 位: " + w);
        assertEquals(0L, FeistelUtils.encrypt(w), "encrypt(decrypt(0)) 应恰好等于 0");
        assertEquals(w, FeistelUtils.decrypt(FeistelUtils.encrypt(w)), "w 往返可逆失败");
        // id=0 业务上不存在(DB 自增从 1 开始), 但数学上必须可逆
        assertEquals(0L, FeistelUtils.decrypt(FeistelUtils.encrypt(0L)));
    }

    @Test
    void encrypt_goldenValues_shouldStayStable() {
        // 常量回归锚点: KEYS/轮数/轮函数任何一个被改动, 这里立刻红。
        // 保护目标: 老短码是用这套常量生成的, 常量一变, 老短码就永远对不上 id
        assertEquals(4195963162L, FeistelUtils.encrypt(1_000_000L));
        assertEquals("4zXPHc", Base62Utils.encode(FeistelUtils.encrypt(1_000_000L)));
        assertEquals(1_000_000L, FeistelUtils.decrypt(4195963162L));
    }

    // ===== 非法参数防护 =====

    @Test
    void encrypt_invalidInput_shouldThrow() {
        // 0 是合法输入(数学完备), 只挡负数和超 32 位
        assertThrows(IllegalArgumentException.class, () -> FeistelUtils.encrypt(-1L));
        assertThrows(IllegalArgumentException.class, () -> FeistelUtils.encrypt(FeistelUtils.MAX_32 + 1));
        assertThrows(IllegalArgumentException.class, () -> FeistelUtils.decrypt(-1L));
        assertThrows(IllegalArgumentException.class, () -> FeistelUtils.decrypt(FeistelUtils.MAX_32 + 1));
    }
}
