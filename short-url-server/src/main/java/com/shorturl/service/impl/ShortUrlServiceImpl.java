package com.shorturl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.po.ShortUrl;
import com.shorturl.entity.vo.ShortUrlVO;
import com.shorturl.mapper.ShortUrlMapper;
import com.shorturl.service.IShortUrlService;
import com.shorturl.utils.Base62Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    @Value("${short-url.domain}")
    private String domain;

    @Value("${short-url.cache-prefix}")
    private String cachePrefix;

    @Value("${short-url.cache-ttl-days}")
    private int cacheTtlDays;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ShortUrlVO createShortUrl(ShortUrlCreateDTO dto) {
        String originalUrl = dto.getOriginalUrl();

        // 1. 先查 DB 是否已存在该长链,避免重复
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
        ShortUrl shortUrl = new ShortUrl();
        shortUrl.setOriginalUrl(originalUrl);
        shortUrl.setClickCount(0L);
        if (dto.getExpireSeconds() != null && dto.getExpireSeconds() > 0) {
            shortUrl.setExpireTime(LocalDateTime.now().plusSeconds(dto.getExpireSeconds()));
        }
        shortUrlMapper.insert(shortUrl);

        // 3. ID 转 62 进制得到 shortCode,更新回 DB
        String shortCode = Base62Utils.encode(shortUrl.getId());
        shortUrl.setShortCode(shortCode);
        shortUrlMapper.updateById(shortUrl);

        // 4. 写入 Redis 缓存
        redisTemplate.opsForValue().set(
                cachePrefix + shortCode,
                originalUrl,
                cacheTtlDays,
                TimeUnit.DAYS
        );
        log.info("生成短链成功: {} -> {}", shortCode, originalUrl);
        return toVO(shortUrl);
    }

    @Override
    public String getOriginalUrl(String shortCode) {
        String cacheKey = cachePrefix + shortCode;

        // 1. 先查 Redis 缓存
        String originalUrl = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(originalUrl)) {
            // 缓存命中 -> 统计点击并返回 (MVP 阶段统计用同步 UPDATE,后续阶段3 用 MQ 优化)
            incrementClickCount(shortCode);
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

        // 4. 回写缓存
        redisTemplate.opsForValue().set(
                cacheKey,
                shortUrl.getOriginalUrl(),
                cacheTtlDays,
                TimeUnit.DAYS
        );

        // 5. 统计点击
        incrementClickCount(shortCode);
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
     * 点击次数 +1
     * TODO (阶段3): 改为发送消息到 RabbitMQ 异步处理,减轻 DB 压力
     */
    private void incrementClickCount(String shortCode) {
        shortUrlMapper.update(null,
                new LambdaUpdateWrapper<ShortUrl>()
                        .eq(ShortUrl::getShortCode, shortCode)
                        .setSql("click_count = click_count + 1")
        );
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
