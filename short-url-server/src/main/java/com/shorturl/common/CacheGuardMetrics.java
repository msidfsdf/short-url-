package com.shorturl.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存防护计数器 (阶段4)
 *
 * 用途: 压测时量化三层防护各自挡掉了多少流量, 让"治理有效"从定性变成定量。
 * 这些数字是 A/B 报告的核心数据来源。
 *
 * 全部用 AtomicLong 而非 synchronized: 跳转链路是绝对热点, 不能引入锁竞争。
 * 数值允许在极端并发下有极小漂移(非原子自增), 但统计型数据不需要精确。
 *
 * 可通过 GET /api/short-url/cache-guard/metrics 实时读取, POST .../reset 清零。
 */
@Slf4j
@Component
public class CacheGuardMetrics {

    /** 布隆过滤器判定"一定不存在"而直接拦截的次数 (本该打到 DB 的流量) */
    private final AtomicLong bloomRejected = new AtomicLong();

    /** 短码格式非法(长度/字符不合法)被直接拒绝的次数 */
    private final AtomicLong invalidFormat = new AtomicLong();

    /** 缓存命中次数(真值) */
    private final AtomicLong cacheHit = new AtomicLong();

    /** 缓存未命中次数 */
    private final AtomicLong cacheMiss = new AtomicLong();

    /** 命中空值标记的次数(布隆误判漏过来的非法短码) */
    private final AtomicLong nullCacheHit = new AtomicLong();

    /** 等待锁期间双重检查命中(说明别的线程已经重建好了) */
    private final AtomicLong cacheHitAfterWait = new AtomicLong();

    /**
     * 真正打到 DB 的查询次数 —— 这是最关键的指标
     * 治理前: 每个非法短码都算 1 次; 治理后: 应接近 0
     */
    private final AtomicLong dbQuery = new AtomicLong();

    /** 成功抢到重建锁的次数 */
    private final AtomicLong lockAcquired = new AtomicLong();

    /** 抢锁超时(等待超过 lockWaitMs)后降级直查 DB 的次数 */
    private final AtomicLong lockTimeout = new AtomicLong();

    /** 命中缓存但逻辑已过期的次数(本次返回旧值, 后台异步刷新) */
    private final AtomicLong logicalExpired = new AtomicLong();

    /** 逻辑过期触发异步重建的次数 */
    private final AtomicLong asyncRebuild = new AtomicLong();

    // ==================== 自增 ====================

    public void bloomRejected() { bloomRejected.incrementAndGet(); }
    public void invalidFormat() { invalidFormat.incrementAndGet(); }
    public void cacheHit() { cacheHit.incrementAndGet(); }
    public void cacheMiss() { cacheMiss.incrementAndGet(); }
    public void nullCacheHit() { nullCacheHit.incrementAndGet(); }
    public void cacheHitAfterWait() { cacheHitAfterWait.incrementAndGet(); }
    public void dbQuery() { dbQuery.incrementAndGet(); }
    public void lockAcquired() { lockAcquired.incrementAndGet(); }
    public void lockTimeout() { lockTimeout.incrementAndGet(); }
    public void logicalExpired() { logicalExpired.incrementAndGet(); }
    public void asyncRebuild() { asyncRebuild.incrementAndGet(); }

    // ==================== 读取 ====================

    /** 快照, 用于接口返回与日志打印 */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("invalidFormat", invalidFormat.get());
        m.put("bloomRejected", bloomRejected.get());
        m.put("cacheHit", cacheHit.get());
        m.put("cacheMiss", cacheMiss.get());
        m.put("nullCacheHit", nullCacheHit.get());
        m.put("cacheHitAfterWait", cacheHitAfterWait.get());
        m.put("dbQuery", dbQuery.get());
        m.put("lockAcquired", lockAcquired.get());
        m.put("lockTimeout", lockTimeout.get());
        m.put("logicalExpired", logicalExpired.get());
        m.put("asyncRebuild", asyncRebuild.get());
        return m;
    }

    /** 最关键的单一指标: 打到 DB 的次数 */
    public long dbQueryCount() {
        return dbQuery.get();
    }

    public void reset() {
        bloomRejected.set(0);
        invalidFormat.set(0);
        cacheHit.set(0);
        cacheMiss.set(0);
        nullCacheHit.set(0);
        cacheHitAfterWait.set(0);
        dbQuery.set(0);
        lockAcquired.set(0);
        lockTimeout.set(0);
        logicalExpired.set(0);
        asyncRebuild.set(0);
        log.info("缓存防护计数器已清零");
    }

    /** 打印一行摘要, 压测结束时肉眼可读 */
    public void logSummary(String label) {
        Map<String, Long> s = snapshot();
        log.info("[缓存防护统计] {} -> {}", label, s);
    }
}
