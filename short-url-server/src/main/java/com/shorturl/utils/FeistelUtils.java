package com.shorturl.utils;

/**
 * Feistel 网络（分组密码置换）工具 —— 用于短码防枚举。
 *
 * <h3>核心作用</h3>
 * 短码原本是 {@code Base62.encode(id)}，自增 id 1/2/3 → 短码 4c92/4c93/4c94 <b>完全连号</b>，
 * 攻击者 for 循环就能遍历全部短链。
 * <p>
 * 本类把 id 做一次 <b>双射置换（bijection）</b>：{@code shuffledId = encrypt(id)}，
 * 再 {@code Base62.encode(shuffledId)} 得到短码。
 * 双射保证：① 绝不碰撞 ② 可还原（decrypt 反推 id，方便工具排查）。
 *
 * <h3>参数</h3>
 * <ul>
 *   <li>块大小：32 位（左 16 位 + 右 16 位），容量 2^32 ≈ 43 亿，够用</li>
 *   <li>轮数：10 轮（经典配置，混淆强度足够，纯位运算几纳秒）</li>
 *   <li>轮函数 F：乘法 + 异或 + 移位（非线性扩散，非密码学强度但不可预测性足够）</li>
 *   <li>子密钥：10 个固定 16 位乱码常量，保证同 id 每次混淆结果 <b>稳定一致</b></li>
 * </ul>
 *
 * <h3>为什么选 Feistel 而不是 hash / 随机 / UUID</h3>
 * <pre>
 * 方案        | 碰撞风险      | 查重成本  |  可逆/可定位
 * ----------- |------------- |-------- |--------------
 * MD5/Hash    | 有（生日悖论）| 布隆/DB  | 不可逆
 * 随机 62 进制 | 有，越长越低  | DB 唯一索引| 不可逆
 * UUID 截断   | 有           | DB 唯一索引| 不可逆
 * Feistel     | 零（数学双射） | 零       | 可还原原 id ✅
 * </pre>
 *
 * <h3>用法</h3>
 * <pre>
 *   // 生成短码时
 *   long shuffled = FeistelUtils.encrypt(id);     // 连号 id → 无规律的 32 位乱数
 *   String shortCode = Base62Utils.encode(shuffled);
 *
 *   // 需要从短码反查 id 时（工具/排查用，跳转链路用不到）
 *   long s2 = Base62Utils.decode(shortCode);
 *   long id = FeistelUtils.decrypt(s2);
 * </pre>
 */
public final class FeistelUtils {

    private FeistelUtils() {}

    /** 32 位块容量上限（当无符号 32 位处理，约 43 亿） */
    public static final long MAX_32 = 0xFFFFFFFFL;

    /** Feistel 轮数：越多混淆越强，10 轮在速度与不可预测性间平衡 */
    private static final int ROUNDS = 10;

    /**
     * 10 轮的固定子密钥。
     * 取 0x9E3779B9 (黄金比例常数) 分段切片的 16 位片段，保证非零且无规律。
     * 一旦上线后 <b>绝对不能改</b>，改了之后老短码全部对不上 id。
     */
    private static final int[] KEYS = {
            0x9E37, 0x79B9, 0x243F, 0x6A88, 0x85A3,
            0x08D3, 0x1319, 0x8A2E, 0x0370, 0x7344
    };

    // ==================== 对外 API ====================

    /**
     * 32 位 Feistel 加密（id → 打乱后的混淆 id）。
     *
     * @param id 原始自增 id，范围 [0, 2^32-1]；业务上 DB 自增从 1 开始，0 永远不会出现，
     *           放行 0 仅为保证「全空间双射」的数学完备性（encrypt/decrypt 互为完美逆函数）
     * @return 混淆后的 id，范围 [0, 2^32-1]，与原 id 一一对应。
     *         注意 0 是合法输出：全空间恰好有一个 id（即 decrypt(0)）映射到 0，
     *         对应短码 "0"（1 位，格式校验允许），绝不能对它做任何"兜底改值"
     */
    public static long encrypt(long id) {
        if (id < 0L || id > MAX_32) {
            throw new IllegalArgumentException("id 必须在 32 位无符号范围内 (0 ~ " + MAX_32 + "), 实际: " + id);
        }
        int v = (int) id;
        int L = (v >>> 16) & 0xFFFF;   // 左 16 位块
        int R = v & 0xFFFF;             // 右 16 位块

        // 标准 Feistel：L_{i+1}=R_i,  R_{i+1}=L_i XOR F(R_i, K_i)
        for (int i = 0; i < ROUNDS; i++) {
            int nL = R;
            int nR = L ^ roundF(R, KEYS[i]);
            L = nL & 0xFFFF;
            R = nR & 0xFFFF;
        }

        // 合并：高 16 位 L10，低 16 位 R10，范围 [0, 2^32-1]
        // ⚠️ result=0 是合法输出（全空间恰好有一个 id 映射到 0），必须原样返回。
        //    任何"是 0 就改成 1"的兜底都会与 encrypt(decrypt(1)) 撞车，直接破坏零碰撞双射！
        return ((long) (L & 0xFFFF) << 16) | (R & 0xFFFF);
    }

    /**
     * 32 位 Feistel 解密（混淆 id → 原始 id）。
     * 与加密严格对称（轮密钥逆序 + 轮函数相同），保证 decrypt(encrypt(id)) == id。
     *
     * @param shuffledId 混淆 id，范围 [0, 2^32-1]；0 是合法输入（对应唯一的 id decrypt(0)）
     * @return 原始 id。注意：阶段1~4 的老短码是 Base62(id) 直编、不经 Feistel，
     *         反解老短码时不要调用本方法（decode 结果就是 id 本身）
     */
    public static long decrypt(long shuffledId) {
        if (shuffledId < 0L || shuffledId > MAX_32) {
            throw new IllegalArgumentException("混淆 id 必须在 32 位无符号范围内 (0 ~ " + MAX_32 + "), 实际: " + shuffledId);
        }
        int v = (int) shuffledId;
        int L = (v >>> 16) & 0xFFFF;   // L10
        int R = v & 0xFFFF;             // R10

        // 逆向 Feistel：轮密钥从后往前，用代数关系反推
        //   L_{i-1} = R_i XOR F(L_i, K_{i-1})
        //   R_{i-1} = L_i
        for (int i = ROUNDS - 1; i >= 0; i--) {
            int prevL = R ^ roundF(L, KEYS[i]);
            int prevR = L;
            L = prevL & 0xFFFF;
            R = prevR & 0xFFFF;
        }

        long result = ((long) (L & 0xFFFF) << 16) | (R & 0xFFFF);
        return result & MAX_32;
    }

    // ==================== 内部工具 ====================

    /**
     * Feistel 轮函数 F。
     * 不需要可逆（Feistel 结构天然保证整体可逆），只要非线性扩散够就行。
     * 结构：乘法 + 常量加法 + 异或移位 S 盒，纯位运算几纳秒。
     */
    private static int roundF(int r, int key) {
        // r 和 key 都是 [0, 0xFFFF]，乘法扩散后截断回 16 位
        int x = ((r * 0x85EB) + (key * 0x1319)) & 0xFFFF;
        x ^= (x << 7) & 0xFFFF;     // 左移异或 = 高位扩散
        x ^= (x >>> 9) & 0xFFFF;    // 右移异或 = 低位扩散
        return x & 0xFFFF;
    }
}
