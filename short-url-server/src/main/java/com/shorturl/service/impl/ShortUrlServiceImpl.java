package com.shorturl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.common.CacheGuardMetrics;
import com.shorturl.common.CacheValue;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.po.ShortUrl;
import com.shorturl.entity.vo.ShortUrlVO;
import com.shorturl.mapper.ShortUrlMapper;
import com.shorturl.mq.ClickProducer;
import com.shorturl.service.IShortUrlService;
import com.shorturl.utils.Base62Utils;
import com.shorturl.utils.FeistelUtils;
import com.shorturl.utils.RedisBloomFilter;
import com.shorturl.utils.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 短链 Service 实现
 *
 * 核心流程:
 *  1. 生成短链: 长链 -> 入库拿自增ID -> 62进制编码 -> 写 Redis -> 加入布隆过滤器
 *  2. 跳转:     短码 -> 三层防护 -> 302 跳转
 *
 * 阶段4 跳转链路的三种模式 (short-url.cache-guard.mode):
 *  none           阶段1~3 原逻辑, 无任何防护。作为 A/B 压测的基线对照组。
 *                 存在缓存穿透(不存在的短码每次打 DB)与缓存击穿(热 key 过期瞬间
 *                 并发回源)两个问题, 压测就是要把它量化出来。
 *
 *  mutex          互斥锁重建。缓存未命中时抢分布式锁, 只有抢到锁的线程回源查 DB,
 *                 其余线程短暂等待后双重检查缓存。强一致, 不会返回脏数据,
 *                 代价是热 key 重建瞬间未抢到锁的请求有等待延迟。
 *
 *  logical-expiry 逻辑过期。缓存不靠 Redis TTL 过期, 而是把过期时间存在 value 里
 *                 由应用判断。过期后抢到锁的请求提交异步重建任务并立即返回,
 *                 其余请求继续拿旧值走人 —— 全链路零等待。
 *                 适合本项目的理由: original_url 创建后几乎不变, "旧值"与"新值"
 *                 实际上永远是同一个, 所以返回旧值没有业务风险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlServiceImpl implements IShortUrlService {

    private final ShortUrlMapper shortUrlMapper;
    private final StringRedisTemplate redisTemplate;
    private final ClickProducer clickProducer;
    private final RedisBloomFilter bloomFilter;
    private final RedisLock redisLock;
    private final CacheGuardMetrics metrics;

    /** 逻辑过期模式的异步重建线程池 (仅 logical-expiry 模式使用) */
    @Qualifier("cacheRebuildExecutor")
    private final ThreadPoolTaskExecutor rebuildExecutor;

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

    // ==================== 阶段4 缓存防护配置 ====================

    @Value("${short-url.cache-guard.mode:mutex}")
    private String cacheGuardMode;

    @Value("${short-url.cache-guard.bloom.enabled:true}")
    private boolean bloomEnabled;

    @Value("${short-url.cache-guard.null-enabled:true}")
    private boolean nullCacheEnabled;

    @Value("${short-url.cache-guard.null-ttl-seconds:120}")
    private long nullTtlSeconds;

    @Value("${short-url.cache-guard.lock-wait-ms:3000}")
    private long lockWaitMs;

    @Value("${short-url.cache-guard.lock-expire-seconds:10}")
    private long lockExpireSeconds;

    @Value("${short-url.cache-guard.lock-retry-interval-ms:20}")
    private long lockRetryIntervalMs;

    @Value("${short-url.cache-guard.logical-expire-seconds:300}")
    private long logicalExpireSeconds;

    /**
     * 空值标记: 缓存里存这个串表示"该短码确实不存在或已过期"。
     * 作用: 布隆过滤器有约 1% 误判率, 漏过来的非法短码查完 DB 后也写一份空值缓存,
     *       这样同一个非法短码被反复刷时也只查一次 DB。
     */
    private static final String NULL_MARK = "__NULL__";

    /** 合法短码格式: 62 进制字符集, 长度 1~10 (与 short_code VARCHAR(10) 对齐) */
    private static final Pattern SHORT_CODE_PATTERN = Pattern.compile("^[0-9a-zA-Z]{1,10}$");

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== 生成短链 ====================

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

        // 3. ID 经 Feistel 双射置换打乱后再转 62 进制得到 shortCode,更新回 DB
        //    Feistel 保证: ① 不碰撞(双射,一一对应) ② 连号 id → 混淆值完全不可预测
        //    从而防止攻击者用 4c92/4c93/4c94 这种连号短码遍历爬光全部短链。
        String shortCode = Base62Utils.encode(FeistelUtils.encrypt(shortUrl.getId()));
        shortUrl.setShortCode(shortCode);
        shortUrlMapper.updateById(shortUrl);

        // 3.5 关键: 新短码必须立刻加入布隆过滤器
        //     否则刚创建的短链会被布隆判为"一定不存在"而直接 404 —— 这是布隆过滤器
        //     最经典的坑: 它只认预热时和显式 add 过的元素, 不会自己感知 DB 的新数据。
        if (bloomEnabled && bloomFilter.isReady()) {
            bloomFilter.add(shortCode);
        }

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

    // ==================== 跳转 ====================

    @Override
    public String getOriginalUrl(String shortCode) {
        // none 模式直接走阶段1~3 的未防护逻辑, 保证 A/B 基线的纯净
        if ("none".equals(cacheGuardMode)) {
            return getOriginalUrlUnguarded(shortCode);
        }
        return getOriginalUrlGuarded(shortCode);
    }

    /**
     * 无防护路径 (mode=none, 阶段1~3 原逻辑)
     *
     * 故意保持原样不做任何改动, 作为压测基线:
     *  缓存未命中就查 DB, 不存在也不写空值 → 每个非法短码都是一次 DB 查询(穿透)
     *  热 key 过期的瞬间所有并发请求同时回源(击穿)
     */
    private String getOriginalUrlUnguarded(String shortCode) {
        String cacheKey = cachePrefix + shortCode;

        String originalUrl = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(originalUrl)) {
            metrics.cacheHit();
            recordClickEvent(shortCode);
            return originalUrl;
        }
        metrics.cacheMiss();

        String url = queryDbAndWriteBack(shortCode, cacheKey, false);
        if (url != null) {
            recordClickEvent(shortCode);
        }
        return url;
    }

    /**
     * 带防护路径 (mode=mutex / logical-expiry)
     *
     * 三层:
     *  ① 格式校验 —— 连布隆都不用问, 直接挡掉超长/含非法字符的输入
     *  ② 布隆过滤器 —— 判定"一定不存在"直接 404, 零 DB 查询 (挡穿透)
     *  ③ 缓存策略 —— 互斥锁重建 或 逻辑过期 (挡击穿)
     */
    private String getOriginalUrlGuarded(String shortCode) {
        // ① 格式校验
        if (!isValidShortCode(shortCode)) {
            metrics.invalidFormat();
            return null;
        }

        // ② 布隆过滤器
        if (bloomEnabled && bloomFilter.isReady() && !bloomFilter.mightContain(shortCode)) {
            metrics.bloomRejected();
            return null;
        }

        // ③ 缓存策略
        String cacheKey = cachePrefix + shortCode;
        return "logical-expiry".equals(cacheGuardMode)
                ? getWithLogicalExpiry(shortCode, cacheKey)
                : getWithMutex(shortCode, cacheKey);
    }

    /**
     * 互斥锁重建 (mode=mutex)
     *
     * 流程: 读缓存 → 未命中则抢锁 → 抢到锁的线程双重检查后回源
     *       没抢到的线程短暂 sleep 后双重检查, 大概率能拿到别人重建好的值
     *
     * 两个必须做的双重检查:
     *  1. 等待锁期间——别人可能已经重建完了, 没必要再抢
     *  2. 拿到锁之后——等待期间别人可能已经重建完了, 没必要重复回源
     *
     * 可用性兜底: 抢锁超时后不抛异常, 而是降级直查 DB。
     *            宁可多一次 DB 查询, 也不能让跳转失败。
     */
    private String getWithMutex(String shortCode, String cacheKey) {
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (NULL_MARK.equals(cached)) {
            metrics.nullCacheHit();
            return null;
        }
        if (StringUtils.hasText(cached)) {
            metrics.cacheHit();
            recordClickEvent(shortCode);
            return cached;
        }
        metrics.cacheMiss();

        String token = RedisLock.newToken();
        boolean locked = false;
        try {
            long deadline = System.currentTimeMillis() + lockWaitMs;
            while (true) {
                if (redisLock.tryLock(shortCode, token, lockExpireSeconds)) {
                    metrics.lockAcquired();
                    locked = true;
                    break;
                }
                // 没抢到: 等一小会儿再双重检查
                sleepQuietly(lockRetryIntervalMs);
                String again = redisTemplate.opsForValue().get(cacheKey);
                if (NULL_MARK.equals(again)) {
                    metrics.nullCacheHit();
                    return null;
                }
                if (StringUtils.hasText(again)) {
                    metrics.cacheHitAfterWait();
                    recordClickEvent(shortCode);
                    return again;
                }
                if (System.currentTimeMillis() >= deadline) {
                    metrics.lockTimeout();
                    break;
                }
            }

            // 拿到锁后再确认一次 (等待期间可能已被重建)
            if (locked) {
                String again = redisTemplate.opsForValue().get(cacheKey);
                if (NULL_MARK.equals(again)) {
                    metrics.nullCacheHit();
                    return null;
                }
                if (StringUtils.hasText(again)) {
                    metrics.cacheHitAfterWait();
                    recordClickEvent(shortCode);
                    return again;
                }
            }

            // 回源: 抢到锁的正常回源; 抢锁超时的降级直查, 保证跳转可用
            String url = queryDbAndWriteBack(shortCode, cacheKey, nullCacheEnabled);
            if (url != null) {
                recordClickEvent(shortCode);
            }
            return url;
        } finally {
            if (locked) {
                redisLock.unlock(shortCode, token);
            }
        }
    }

    /**
     * 逻辑过期 (mode=logical-expiry)
     *
     * 与互斥锁的区别: 缓存过期时**不阻塞任何请求**。
     *  抢到锁的请求: 提交异步重建任务, 自己拿旧值立即返回
     *  没抢到锁的请求: 直接拿旧值返回
     *  → 热 key 过期瞬间, DB 只会被打 1 次, 且所有请求 RT 不受影响
     *
     * 为什么本项目适合: original_url 创建后几乎不变, "旧值"与"新值"实际是同一个,
     *                  返回旧值没有业务风险。换成库存/余额这类强一致场景就不能这么干。
     */
    private String getWithLogicalExpiry(String shortCode, String cacheKey) {
        String raw = redisTemplate.opsForValue().get(cacheKey);
        CacheValue cv = CacheValue.parse(raw);

        // 从未缓存过(或遇到阶段1~3 遗留的裸 URL 字符串) → 走互斥重建做冷启动
        if (cv == null) {
            // 只在"缓存里确实什么都没有"时记 miss。
            // 遗留裸 URL 属于"有缓存但格式旧", 交给 getWithMutex 自己判命中,
            // 否则冷启动会被记两次 miss, 报告里的数字对不上。
            if (raw == null) {
                metrics.cacheMiss();
            }
            return getWithMutex(shortCode, cacheKey);
        }

        // 逻辑未过期 → 直接返回, 零等待
        if (!cv.isExpired()) {
            metrics.cacheHit();
            return fireClick(shortCode, cv.isN() ? null : cv.getU());
        }

        // 逻辑已过期 → 抢到锁的负责异步重建, 本次请求先拿旧值走人
        metrics.logicalExpired();
        String token = RedisLock.newToken();
        if (redisLock.tryLock(shortCode, token, lockExpireSeconds)) {
            metrics.asyncRebuild();
            try {
                rebuildExecutor.execute(() -> {
                    try {
                        queryDbAndWriteBack(shortCode, cacheKey, true);
                    } finally {
                        redisLock.unlock(shortCode, token);
                    }
                });
            } catch (RuntimeException e) {
                // 线程池拒绝等异常: 锁必须还回去, 否则这个 key 会被锁死到过期
                redisLock.unlock(shortCode, token);
                log.warn("异步重建任务提交失败, 已释放锁: {}", shortCode, e);
            }
        }
        return fireClick(shortCode, cv.isN() ? null : cv.getU());
    }

    /**
     * 回源查 DB 并写回缓存
     *
     * @param cacheNull 是否缓存空值。布隆误判漏过来的非法短码查完 DB 后写一份空值,
     *                  避免同一个非法短码被反复刷时重复打 DB。
     * @return 原始 URL; 短码不存在或已过期返回 null
     */
    private String queryDbAndWriteBack(String shortCode, String cacheKey, boolean cacheNull) {
        metrics.dbQuery();
        ShortUrl shortUrl = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>()
                        .eq(ShortUrl::getShortCode, shortCode)
                        .last("LIMIT 1")
        );
        boolean expired = shortUrl != null
                && shortUrl.getExpireTime() != null
                && shortUrl.getExpireTime().isBefore(LocalDateTime.now());

        if (shortUrl == null || expired) {
            if (cacheNull) {
                writeCacheValue(cacheKey, null, null);
            }
            return null;
        }

        String url = shortUrl.getOriginalUrl();
        writeCacheValue(cacheKey, url, shortUrl.getExpireTime());
        return url;
    }

    /**
     * 按当前模式选择缓存存储格式。
     *
     * 这里有个必须注意的坑:
     *  logical-expiry 模式下**必须**写 CacheValue JSON, 不能图省事写裸 URL。
     *  否则后续请求解析不出 CacheValue 就一直走冷启动分支,
     *  逻辑过期机制永远不会生效(实测踩过, 表现为 logicalExpired 恒为 0)。
     *
     * @param url        原始 URL, null 表示写空值标记
     * @param expireTime 短链过期时间, 用于计算物理 TTL
     */
    private void writeCacheValue(String cacheKey, String url, LocalDateTime expireTime) {
        if (isLogicalExpiry()) {
            CacheValue cv = (url == null)
                    ? CacheValue.nullValue(System.currentTimeMillis() + nullTtlSeconds * 1000)
                    : CacheValue.of(url, System.currentTimeMillis() + logicalExpireSeconds * 1000);
            // 物理 TTL 仍然要设: 逻辑过期意味着 value 不会自己消失,
            // 不设 TTL 则每个被查过的短码(含大量非法短码)都会永久占内存
            long physicalTtlSeconds = (url == null)
                    ? Math.max(nullTtlSeconds, 60L)
                    : Math.max(calcCacheTtlSeconds(expireTime), logicalExpireSeconds * 2);
            String json = cv.toJson();
            if (json != null) {
                redisTemplate.opsForValue().set(cacheKey, json, physicalTtlSeconds, TimeUnit.SECONDS);
            }
        } else if (url == null) {
            redisTemplate.opsForValue().set(cacheKey, NULL_MARK, nullTtlSeconds, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().set(cacheKey, url, calcCacheTtlSeconds(expireTime), TimeUnit.SECONDS);
        }
    }

    private boolean isLogicalExpiry() {
        return "logical-expiry".equals(cacheGuardMode);
    }

    // ==================== 分页 ====================

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

    // ==================== 点击统计 ====================

    /**
     * 单条累加点击次数 (同步模式 / MQ 逐条消费模式)
     * 执行 click_count = click_count + 1 的原子更新, 与跳转请求路径解耦
     */
    @Override
    public void recordClick(String shortCode) {
        simulateDbLatencyOnce();
        shortUrlMapper.update(null,
                new LambdaUpdateWrapper<ShortUrl>()
                        .eq(ShortUrl::getShortCode, shortCode)
                        .setSql("click_count = click_count + 1")
        );
    }

    /**
     * 批量聚合累加点击次数 (MQ 批量消费模式)
     *
     * 典型场景: 一批 100 条 ClickMessage 经 HashMap 合并后只剩 15 个唯一短码
     *          → DB 写次数从 100 降为 15, 典型压缩比 5~20 倍
     *          → 热门短码越多, 批内重复度越高, 压缩比越好 (80/20 定律天然利好)
     *
     * 语义保持: 加法可合并且顺序无关, 合并前后最终 click_count 数学上完全等价,
     *          不存在"攒批导致统计错误"的风险。
     */
    @Override
    public void recordClickBatch(Map<String, Long> shortCodeCounts) {
        if (shortCodeCounts == null || shortCodeCounts.isEmpty()) {
            return;
        }
        // 按短码逐条执行 UPDATE (click_count = click_count + N)
        // 注: 极端场景下可用 CASE WHEN 合成 1 条 SQL, 但分开写简单直观, MP 拼接安全,
        //     且批内唯一短码数通常不大, 多条 UPDATE 的总开销仍然远低于逐条消息 UPDATE
        for (Map.Entry<String, Long> entry : shortCodeCounts.entrySet()) {
            String shortCode = entry.getKey();
            Long step = entry.getValue();
            if (!StringUtils.hasText(shortCode) || step == null || step <= 0) {
                continue;
            }
            simulateDbLatencyOnce();
            shortUrlMapper.update(null,
                    new LambdaUpdateWrapper<ShortUrl>()
                            .eq(ShortUrl::getShortCode, shortCode)
                            .setSql("click_count = click_count + " + step)
            );
        }
    }

    /**
     * 压测专用: 模拟下游 DB 写入/网络耗时。
     * 每次 UPDATE 算 1 次慢下游 (按调用次数模拟, 不是按消息条数)
     * 批量场景下每批调用次数 = 批内唯一短码数, 正好模拟"少调 DB, 总延迟下降"的效果。
     */
    private void simulateDbLatencyOnce() {
        if (simulateDbLatencyMs > 0) {
            try {
                Thread.sleep(simulateDbLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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

    // ==================== 工具方法 ====================

    /** 只在 URL 非空时记点击, 返回 URL 本身以便链式返回 */
    private String fireClick(String shortCode, String url) {
        if (url != null) {
            recordClickEvent(shortCode);
        }
        return url;
    }

    /** 短码格式校验: 长度 1~10 且只含 62 进制字符集 */
    private boolean isValidShortCode(String shortCode) {
        return shortCode != null && SHORT_CODE_PATTERN.matcher(shortCode).matches();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
