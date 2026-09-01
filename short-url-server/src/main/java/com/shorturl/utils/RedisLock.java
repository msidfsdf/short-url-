package com.shorturl.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁 (阶段4 缓存击穿治理)
 *
 * 加锁: SET key value NX EX seconds    -- 原子操作, value 用 UUID 标识加锁方
 * 解锁: Lua 脚本先比对 value 再 DEL     -- 原子操作, 防止误删别人的锁
 *
 * 为什么解锁必须校验 value (经典考点):
 *  线程 A 拿到锁, 业务执行时间超过了锁的过期时间 -> 锁自动释放
 *  线程 B 随后拿到锁
 *  此时 A 执行完了, 如果 A 直接 DEL, 删掉的是 B 的锁 -> 锁失效, C 也能进来
 *  用 Lua 把"比对 + 删除"合成一个原子操作, A 只能删自己的锁
 *
 * 为什么不自己实现看门狗/自动续期:
 *  本项目锁住的临界区只是一次 DB 查询 + 一次 Redis 写, 耗时在毫秒级,
 *  给 10s 过期时间绰绰有余。引入续期线程会让代码复杂度和故障面都变大,
 *  收益不匹配。真需要长时间持锁时, 应该用 Redisson 的 RLock(自带看门狗)。
 *
 * 使用姿势:
 *  String token = UUID.randomUUID().toString();
 *  if (lock.tryLock(key, token, 10)) {
 *      try { ... } finally { lock.unlock(key, token); }
 *  }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_PREFIX = "short:url:lock:";

    /**
     * 解锁脚本: 比对 value 一致才删除, 保证"谁加的锁谁解"
     * KEYS[1]=锁key  ARGV[1]=加锁时的 value
     */
    private static final String UNLOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('DEL', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final RedisScript<Long> unlockScript = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    /**
     * 尝试加锁
     *
     * @param bizKey        业务 key(如短码), 方法内会加统一前缀
     * @param token         调用方生成的唯一标识, 解锁时必须传同一个值
     * @param expireSeconds 锁过期时间, 兜底防止持有者崩溃导致死锁
     * @return true=抢到锁
     */
    public boolean tryLock(String bizKey, String token, long expireSeconds) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + bizKey, token, Duration.ofSeconds(expireSeconds));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放锁。只有 value 匹配才真正删除。
     *
     * @return true=成功释放(锁确实是自己持有的); false=锁已过期或属于别人
     */
    public boolean unlock(String bizKey, String token) {
        Long r = redisTemplate.execute(unlockScript,
                Collections.singletonList(LOCK_PREFIX + bizKey), token);
        return r != null && r == 1L;
    }

    /** 生成一个锁标识 */
    public static String newToken() {
        return UUID.randomUUID().toString();
    }
}
