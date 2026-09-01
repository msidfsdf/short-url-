package com.shorturl;

import com.shorturl.common.CacheGuardMetrics;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.vo.ShortUrlVO;
import com.shorturl.service.IShortUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存防护行为测试 (阶段4)
 *
 * 用独立 Redis key 避免污染应用位图; warm-up 关掉由测试自己控制数据。
 * 需要本地 Redis + MySQL 可用。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "short-url.cache-guard.mode=mutex",
        "short-url.cache-guard.bloom.key=short:url:bloom:test2",
        "short-url.cache-guard.bloom.enabled=true",
        "short-url.cache-guard.bloom.warm-up=false"
})
class CacheGuardTest {

    @Autowired
    private IShortUrlService shortUrlService;

    @Autowired
    private CacheGuardMetrics metrics;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "short:url:";

    @BeforeEach
    void reset() {
        metrics.reset();
    }

    @Test
    @DisplayName("已存在的短码应能正常跳转")
    void existingCodeShouldRedirect() {
        ShortUrlVO vo = createOne("https://example.com/existing-" + System.nanoTime());

        // createShortUrl 会顺手写缓存, 先清掉才能验证"回源"这条路径
        redisTemplate.delete(CACHE_PREFIX + vo.getShortCode());

        String url = shortUrlService.getOriginalUrl(vo.getShortCode());

        assertThat(url).isEqualTo(vo.getOriginalUrl());
        assertThat(counter("dbQuery")).isEqualTo(1L);  // 冷启动回源一次
    }

    @Test
    @DisplayName("穿透防护: 大量不存在的短码, 打到 DB 的次数应远小于请求数")
    void penetrationShouldBeBlocked() {
        int total = 300;
        for (int i = 0; i < total; i++) {
            shortUrlService.getOriginalUrl("absent" + i);
        }

        long dbQuery = counter("dbQuery");
        long bloomRejected = counter("bloomRejected");

        System.out.printf("穿透测试: 请求 %d, 打到DB %d, 布隆拦截 %d%n",
                total, dbQuery, bloomRejected);

        // 理想情况 0 次; 布隆有约 1% 误判率, 漏过来的会被空值缓存兜住,
        // 所以这里卡一个宽松上界 (总量的 10%), 防止实现退化成"全部放行"
        assertThat(dbQuery).isLessThan(total / 10);
        assertThat(bloomRejected + counter("nullCacheHit")).isGreaterThan(total * 9 / 10);
    }

    @Test
    @DisplayName("格式校验: 超长/非法短码应被第一层挡下, 完全不查 DB")
    void invalidFormatShouldBeRejected() {
        String tooLong = "a".repeat(50);
        String withSymbol = "ab!@#$";

        assertThat(shortUrlService.getOriginalUrl(tooLong)).isNull();
        assertThat(shortUrlService.getOriginalUrl(withSymbol)).isNull();

        assertThat(counter("invalidFormat")).isEqualTo(2L);
        assertThat(counter("dbQuery")).isZero();
    }

    @Test
    @DisplayName("击穿防护: 缓存清空后高并发打同一个热 key, 只应回源 1 次")
    void hotKeyColdStartShouldQueryDbOnce() throws Exception {
        ShortUrlVO vo = createOne("https://example.com/hot-" + System.nanoTime());
        String code = vo.getShortCode();

        // 预热一次让布隆认识它, 然后清掉缓存模拟热 key 过期
        shortUrlService.getOriginalUrl(code);
        redisTemplate.delete(CACHE_PREFIX + code);
        metrics.reset();

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    shortUrlService.getOriginalUrl(code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        long dbQuery = counter("dbQuery");
        long lockAcquired = counter("lockAcquired");
        System.out.printf("击穿测试: %d 并发, 打到DB %d, 抢锁成功 %d, 缓存命中 %d%n",
                threads, dbQuery, lockAcquired, counter("cacheHit"));

        // 互斥锁生效时应该只有 1 次回源; 允许极小抖动(锁超时降级直查)
        assertThat(dbQuery).isLessThanOrEqualTo(3L);
        assertThat(lockAcquired).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("空值缓存: 同一个不存在的短码连打多次, 后续不应再查 DB")
    void nullValueShouldBeCached() {
        String absent = "nope123";

        // 第一次: 可能被布隆拦截(0 次 DB), 也可能误判放行(1 次 DB + 写空值)
        shortUrlService.getOriginalUrl(absent);
        long firstPassDb = counter("dbQuery");
        metrics.reset();

        // 后续 20 次: 无论走布隆还是空值缓存, 都不该再碰 DB
        for (int i = 0; i < 20; i++) {
            shortUrlService.getOriginalUrl(absent);
        }

        System.out.printf("空值缓存: 首次 DB 查询 %d, 后续 20 次 DB 查询 %d%n",
                firstPassDb, counter("dbQuery"));
        assertThat(counter("dbQuery")).isZero();
    }

    // ==================== 工具 ====================

    private long counter(String name) {
        Map<String, Long> snap = metrics.snapshot();
        return snap.getOrDefault(name, 0L);
    }

    private ShortUrlVO createOne(String url) {
        ShortUrlCreateDTO dto = new ShortUrlCreateDTO();
        dto.setOriginalUrl(url);
        return shortUrlService.createShortUrl(dto);
    }
}
