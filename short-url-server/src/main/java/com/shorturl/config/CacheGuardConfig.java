package com.shorturl.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shorturl.entity.po.ShortUrl;
import com.shorturl.mapper.ShortUrlMapper;
import com.shorturl.utils.RedisBloomFilter;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 缓存防护配置 (阶段4)
 *
 * 职责:
 *  1. 应用启动后预热布隆过滤器 (从 DB 全量加载 short_code)
 *  2. 提供逻辑过期模式用的异步重建线程池
 *  3. 启动时打印生效的防护模式, 防止"改了配置没生效"这种低级失误
 *
 * 为什么预热放在 ApplicationReadyEvent 而不是 @PostConstruct:
 *  @PostConstruct 在 @Configuration 类上会触发 Bean 的过早初始化,
 *  可能拿到还没准备好的 Mapper。ApplicationReadyEvent 保证整个容器已就绪。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheGuardConfig {

    private final ShortUrlMapper shortUrlMapper;
    private final RedisBloomFilter bloomFilter;

    @Value("${short-url.cache-guard.mode:mutex}")
    private String mode;

    @Value("${short-url.cache-guard.bloom.enabled:true}")
    private boolean bloomEnabled;

    @Value("${short-url.cache-guard.bloom.warm-up:true}")
    private boolean bloomWarmUp;

    /**
     * 逻辑过期模式的异步重建线程池
     *
     * 队列有界(10000): 防止极端流量下无限堆积把内存打爆
     * 拒绝策略 CallerRuns: 队列满时由请求线程自己重建, 宁可慢也不能不重建
     *                      (用 Discard 会导致缓存永远停留在旧值)
     * 关闭时等待任务完成: 避免重建到一半被打断, 留下半截状态
     */
    @Bean("cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(10000);
        ex.setThreadNamePrefix("cache-rebuild-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!isValidMode(mode)) {
            throw new IllegalArgumentException(
                    "short-url.cache-guard.mode 取值非法: " + mode + " (可选: none / mutex / logical-expiry)");
        }
        log.info("=================================================");
        log.info("阶段4 缓存防护已启动, 生效模式 = {}", mode);
        log.info("  none           = 阶段3 原逻辑, 无任何防护 (A/B 基线)");
        log.info("  mutex          = 互斥锁重建, 强一致, 未抢到锁的请求会等待");
        log.info("  logical-expiry = 逻辑过期, 请求零等待, 可能短暂返回旧值");
        log.info("=================================================");

        if (!bloomEnabled) {
            log.warn("布隆过滤器已关闭 (bloom.enabled=false), 穿透防护只剩空值缓存一层");
            return;
        }
        if (!bloomWarmUp) {
            log.info("跳过布隆预热 (bloom.warm-up=false)");
            return;
        }
        warmUpBloom();
    }

    /** 从 DB 全量加载短码写入布隆过滤器 */
    private void warmUpBloom() {
        long t0 = System.currentTimeMillis();
        List<Object> rows = shortUrlMapper.selectObjs(
                new LambdaQueryWrapper<ShortUrl>()
                        .select(ShortUrl::getShortCode)
                        .isNotNull(ShortUrl::getShortCode)
        );
        List<String> codes = rows.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        if (codes.isEmpty()) {
            log.info("DB 中没有短码, 跳过布隆预热");
            return;
        }
        bloomFilter.addBatch(codes);
        log.info("布隆预热完成: {} 条短码, 耗时 {} ms", codes.size(), System.currentTimeMillis() - t0);
    }

    private static boolean isValidMode(String m) {
        return "none".equals(m) || "mutex".equals(m) || "logical-expiry".equals(m);
    }

    @PreDestroy
    public void onShutdown() {
        log.info("缓存防护组件关闭");
    }
}
