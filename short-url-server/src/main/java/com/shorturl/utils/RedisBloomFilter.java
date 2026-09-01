package com.shorturl.utils;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 布隆过滤器 (阶段4 缓存穿透治理), 基于 Redisson 的 RBloomFilter
 *
 * 原理:
 *  插入: 对元素算 k 个 hash, 把位数组上这 k 个位置置 1
 *  查询: 算同样的 k 个位置, 只要有一个是 0 -> 一定不存在; 全是 1 -> 可能存在
 *  特性: 不会漏判 (false negative), 但会误判 (false positive)
 *         -> 正好适合"挡住不存在的短码"这个场景: 误判只是多查一次 DB, 不会出错
 *
 * 为什么选 Redisson 而不是 Guava/RedisBloom 模块:
 *  1. Guava 是单机的: 多实例部署时"A 实例 add 了、B 实例查不到"
 *  2. RedisBloom 模块要求 Redis 4.0+ 且需装插件, 本项目 Redis 是 3.2
 *  3. Redisson 底层就是 Redis Bitmap + hash + Lua, 天然分布式共享, 重启不丢位图
 *
 * 参数:
 *  expected-insertions (n) 与 false-probability (p) 由 application.yml 配置,
 *  Redisson 内部按经典公式自动推导位数组长度 m 与 hash 函数个数 k:
 *  m = -n * ln(p) / (ln2)^2,  k = m/n * ln2
 *  例: n=100万, p=1% -> m≈9.6M bit (1.2MB), k≈7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisBloomFilter {

    private final RedissonClient redissonClient;

    // ===== 配置 =====
    @Value("${short-url.cache-guard.bloom.key:short:url:bloom}")
    private String bloomKey;

    @Value("${short-url.cache-guard.bloom.expected-insertions:1000000}")
    private long expectedInsertions;

    @Value("${short-url.cache-guard.bloom.false-probability:0.01}")
    private double falseProbability;

    private RBloomFilter<String> bloomFilter;

    /** tryInit 完成后置 true; 之后 mightContain/add 才可用 */
    private volatile boolean ready = false;

    @PostConstruct
    public void init() {
        bloomFilter = redissonClient.getBloomFilter(bloomKey);
        // tryInit 返回 false 表示该 key 已初始化过 (如应用重启后位图仍在 Redis),
        // 此时直接复用 Redis 中已有的位图与参数, 不重复初始化
        boolean created = bloomFilter.tryInit(expectedInsertions, falseProbability);
        ready = true;
        log.info("布隆过滤器就绪: key={}, n={}, p={}, 首次初始化={}",
                bloomKey, expectedInsertions, falseProbability, created);
    }

    /**
     * 判断元素是否可能存在
     *
     * @return true=可能存在(需继续查缓存/DB); false=一定不存在(可直接返回 404)
     */
    public boolean mightContain(String value) {
        return ready && bloomFilter.contains(value);
    }

    /**
     * 加入一个元素
     *
     * @return true=本次插入改变了位图(新元素); false=位图无变化(大概率已存在)
     */
    public boolean add(String value) {
        return ready && bloomFilter.add(value);
    }

    /**
     * 批量预热 (启动时从 DB 全量灌入)
     *
     * 说明: RBloomFilter 没有批量 API, 这里逐条 add。
     * 每条是一次 Lua 往返, 数据量大时预热耗时线性增长
     * (百万级约几分钟, 属一次性启动开销, 可接受)。
     *
     * @return 实际处理的元素个数
     */
    public int addBatch(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        for (String v : values) {
            bloomFilter.add(v);
        }
        log.info("布隆过滤器批量写入完成: {} 条短码", values.size());
        return values.size();
    }

    /** 清空位图 (仅用于测试与重建), 清空后立即按当前配置重新初始化 */
    public void reset() {
        bloomFilter.delete();
        bloomFilter.tryInit(expectedInsertions, falseProbability);
        log.warn("布隆过滤器已清空并重新初始化: {}", bloomKey);
    }

    public boolean isReady() {
        return ready;
    }

    public long getExpectedInsertions() {
        return bloomFilter.getExpectedInsertions();
    }

    public double getFalseProbability() {
        return bloomFilter.getFalseProbability();
    }
}
