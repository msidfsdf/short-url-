package com.shorturl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.po.ShortUrl;
import com.shorturl.entity.vo.ShortUrlVO;
import com.shorturl.mapper.ShortUrlMapper;
import com.shorturl.mq.ClickProducer;
import com.shorturl.service.IShortUrlService;
import com.shorturl.utils.Base62Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 短链 Service 实现
 *
 * 核心流程:
 *  1. 生成短链: 长链 -> 入库拿自增ID -> 62进制编码 -> 写 Redis
 *  2. 跳转:     短码 -> 查 Redis -> 未命中查 DB -> 回写缓存 -> 302 跳转
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlServiceImpl implements IShortUrlService {

    private final ShortUrlMapper shortUrlMapper;
    private final StringRedisTemplate redisTemplate;
    private final ClickProducer clickProducer;

    @Value("${short-url.domain}")
    private String domain;

    @Value("${short-url.cache-prefix}")
    private String cachePrefix;

    @Value("${short-url.cache-ttl-days}")
    private int cacheTtlDays;

    @Value("${short-url.click.async-enabled:true}")
    private boolean clickAsyncEnabled;

    @Value("${short-url.click.simulate-db-latency-ms:0}")
    private long simulateDbLatencyMs;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ShortUrlVO createShortUrl(ShortUrlCreateDTO dto) {
        String originalUrl = dto.getOriginalUrl();

        // 1. 先查 DB 是否已存在该长链,快速路径避免无谓 INSERT
        ShortUrl exist = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>()
                        .eq(ShortUrl::getOriginalUrl, originalUrl)
                        .last("LIMIT 1")
        );
        if (exist != null) {
            log.debug("长链已存在,直接返回已有短链: {}", exist.getShortCode());
            return toVO(exist);
        }

        // 2. 插入 DB 拿自增 ID (shortCode 先留空,稍后更新)
        //    并发时两个请求同时查不到、同时 INSERT → DB 唯一索引拦截 DuplicateKeyException
        //    catch 后回查已有记录返回,不会产生重复短码
        ShortUrl shortUrl;
        try {
            shortUrl = new ShortUrl();
            shortUrl.setOriginalUrl(originalUrl);
            shortUrl.setClickCount(0L);
            if (dto.getExpireSeconds() != null && dto.getExpireSeconds() > 0) {
                shortUrl.setExpireTime(LocalDateTime.now().plusSeconds(dto.getExpireSeconds()));
            }
            shortUrlMapper.insert(shortUrl);
        } catch (DuplicateKeyException e) {
            // 并发查重竞争: 另一个请求先 INSERT 成功了, 回查已有记录
            log.debug("并发创建冲突, 回查已有短链: {}", originalUrl);
            exist = shortUrlMapper.selectOne(
                    new LambdaQueryWrapper<ShortUrl>()
                            .eq(ShortUrl::getOriginalUrl, originalUrl)
                            .last("LIMIT 1")
            );
            if (exist != null) {
                return toVO(exist);
            }
            // 唯一索引拦截但查不到(极端时序), 抛异常让调用方感知
            throw e;
        }

        // 3. ID 转 62 进制得到 shortCode,更新回 DB
        String shortCode = Base62Utils.encode(shortUrl.getId());
        shortUrl.setShortCode(shortCode);
        shortUrlMapper.updateById(shortUrl);

        // 4. 写入 Redis 缓存 (TTL 取配置值与短链过期时间的较小者, 避免过期短链缓存仍存活)
        long cacheTtlSeconds = calcCacheTtlSeconds(shortUrl.getExpireTime());
        redisTemplate.opsForValue().set(
                cachePrefix + shortCode,
                originalUrl,
                cacheTtlSeconds,
                TimeUnit.SECONDS
        );
        log.info("生成短链成功: {} -> {}", shortCode, originalUrl);
        return toVO(shortUrl);
    }

    /**
     * 计算缓存 TTL (秒): 取配置的 cacheTtlDays 与短链 expireTime 剩余时长的较小值。
     * 修复 bug: 过期短链在缓存 TTL 内仍能跳转(缓存命中路径未检查 expireTime)。
     * 缓存随短链同步死亡, 无需在缓存命中时再做过期判断。
     */
    private long calcCacheTtlSeconds(LocalDateTime expireTime) {
        long configTtlSeconds = (long) cacheTtlDays * 24 * 3600L;
        if (expireTime == null) {
            return configTtlSeconds;
        }
        long remainingSeconds = Duration.between(LocalDateTime.now(), expireTime).getSeconds();
        if (remainingSeconds <= 0) {
            // 已过期, 给个极短 TTL 让缓存尽快淘汰
            return 1L;
        }
        return Math.min(configTtlSeconds, remainingSeconds);
    }

    @Override
    public String getOriginalUrl(String shortCode) {
        String cacheKey = cachePrefix + shortCode;

        // 1. 先查 Redis 缓存
        String originalUrl = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(originalUrl)) {
            // 缓存命中 -> 记录点击事件(按 async-enabled 决定 MQ 或同步), 立即返回原 URL
            recordClickEvent(shortCode);
            return originalUrl;
        }

        // 2. 缓存未命中 -> 查 DB
        ShortUrl shortUrl = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>()
                        .eq(ShortUrl::getShortCode, shortCode)
                        .last("LIMIT 1")
        );
        if (shortUrl == null) {
            return null;
        }

        // 3. 检查是否过期
        if (shortUrl.getExpireTime() != null
                && shortUrl.getExpireTime().isBefore(LocalDateTime.now())) {
            return null;
        }

        // 4. 回写缓存 (TTL 取配置值与短链过期时间的较小者, 与 createShortUrl 保持一致)
        long cacheTtlSeconds = calcCacheTtlSeconds(shortUrl.getExpireTime());
        redisTemplate.opsForValue().set(
                cacheKey,
                shortUrl.getOriginalUrl(),
                cacheTtlSeconds,
                TimeUnit.SECONDS
        );

        // 5. 记录点击事件(按 async-enabled 决定 MQ 或同步), 由消费者异步或本线程累加 click_count
        recordClickEvent(shortCode);
        return shortUrl.getOriginalUrl();
    }

    @Override
    public Page<ShortUrlVO> page(int current, int size) {
        Page<ShortUrl> page = new Page<>(current, size);
        Page<ShortUrl> result = shortUrlMapper.selectPage(
                page,
                new LambdaQueryWrapper<ShortUrl>().orderByDesc(ShortUrl::getId)
        );
        Page<ShortUrlVO> voPage = new Page<>();
        voPage.setTotal(result.getTotal());
        voPage.setCurrent(result.getCurrent());
        voPage.setSize(result.getSize());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 异步累加点击次数 (阶段3: 由 MQ 消费者调用)
     * 执行 click_count = click_count + 1 的原子更新, 与跳转请求路径解耦
     */
    @Override
    public void recordClick(String shortCode) {
        // 压测专用: 模拟下游 DB 写入/网络耗时, 用于演示 MQ 把"慢下游"从请求关键路径解耦。
        // 同步模式: 该耗时阻塞在请求线程内 -> 请求 p99 飙升;
        // MQ 模式:   该耗时只阻塞消费者线程 -> 请求线程早已返回, 跳转不受影响。
        if (simulateDbLatencyMs > 0) {
            try {
                Thread.sleep(simulateDbLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        shortUrlMapper.update(null,
                new LambdaUpdateWrapper<ShortUrl>()
                        .eq(ShortUrl::getShortCode, shortCode)
                        .setSql("click_count = click_count + 1")
        );
    }

    /**
     * 点击事件统一入口: 按 async-enabled 开关决定统计方式
     *  - true (默认, 阶段2): 发送到 RabbitMQ, 由消费者异步 click_count+1, 请求线程不阻塞
     *  - false (阶段1 退化): 本线程同步 UPDATE click_count+1, 与请求路径耦合
     * 用途: ① 压测 A/B 对比; ② 生产环境降级开关(MQ 不可用时退化为同步, 不丢统计)
     */
    private void recordClickEvent(String shortCode) {
        if (clickAsyncEnabled) {
            clickProducer.sendClick(shortCode);
        } else {
            recordClick(shortCode);
        }
    }

    private ShortUrlVO toVO(ShortUrl shortUrl) {
        ShortUrlVO vo = new ShortUrlVO();
        vo.setId(shortUrl.getId());
        vo.setShortCode(shortUrl.getShortCode());
        vo.setShortUrl(domain + shortUrl.getShortCode());
        vo.setOriginalUrl(shortUrl.getOriginalUrl());
        vo.setClickCount(shortUrl.getClickCount());
        if (shortUrl.getCreateTime() != null) {
            vo.setCreateTime(shortUrl.getCreateTime().format(FMT));
        }
        return vo;
    }
}
