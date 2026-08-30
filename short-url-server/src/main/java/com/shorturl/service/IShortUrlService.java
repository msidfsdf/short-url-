package com.shorturl.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.vo.ShortUrlVO;

/**
 * 短链 Service 接口
 */
public interface IShortUrlService {

    /**
     * 生成短链
     * - 同一长链不重复生成,直接返回已有短链
     * - 长链入库 -> 自增ID -> 62进制编码 -> 写缓存
     */
    ShortUrlVO createShortUrl(ShortUrlCreateDTO dto);

    /**
     * 根据短链码获取原始URL (用于跳转)
     * - 先查 Redis 缓存,未命中查 DB 并回写
     * - 检查是否过期
     * - 异步统计点击次数 (MVP 暂用同步 UPDATE,后续用 MQ 优化)
     */
    String getOriginalUrl(String shortCode);

    /**
     * 分页查询
     */
    Page<ShortUrlVO> page(int current, int size);

    /**
     * 异步累加点击次数 (由 MQ 消费者调用)
     * 执行 click_count = click_count + 1 的原子更新, 与跳转请求路径解耦
     */
    void recordClick(String shortCode);
}
