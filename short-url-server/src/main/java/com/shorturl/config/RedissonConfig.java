package com.shorturl.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置 (阶段4, 只给布隆过滤器 RBloomFilter 使用)
 *
 * 为什么不用 redisson-spring-boot-starter:
 *  starter 会接管 Spring Data Redis 的 ConnectionFactory,
 *  导致 StringRedisTemplate 底层也被换成 Redisson 连接, 影响面过大
 *  (RedisLock / CacheValue 等都在用 StringRedisTemplate)。
 *  这里手动建独立客户端, 与 Lettuce (StringRedisTemplate) 并存互不影响。
 *
 * 为什么单独一个配置类而不是放进 CacheGuardConfig:
 *  CacheGuardConfig 构造器注入了 RedisBloomFilter, 而 RedisBloomFilter
 *  又依赖 RedissonClient —— 放一起会形成循环依赖, 容器启动直接失败。
 *
 * 单机模式, 地址读 spring.data.redis.host/port, 与现有 Redis 配置保持一致。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
