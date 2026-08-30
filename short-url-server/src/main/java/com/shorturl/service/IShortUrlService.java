package com.shorturl.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.vo.ShortUrlVO;

import java.util.Map;

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
     * 单条累加点击次数 (同步模式 / MQ 逐条消费模式)
     * 执行 click_count = click_count + 1 的原子更新, 与跳转请求路径解耦
     */
    void recordClick(String shortCode);

    /**
     * 批量聚合累加点击次数 (MQ 批量消费模式)
     *
     * @param shortCodeCounts key=短码, value=该短码在批内出现的次数(即累加步长)
     *                        例: {"4c9g":30, "xyz1":20} 表示 4c9g +30, xyz1 +20
     *
     * 说明:
     *  - 加法可合并不失真: 30 条 "+1" 合并为 1 条 "+30", 数学上完全等价
     *  - 把批次内 N 条消息从 N 次 UPDATE 降到 M 次 UPDATE (M=批内唯一短码数, M<=N)
     *  - 典型长尾点击分布下, N/M ≈ 5~20, DB 写次数直接降一个数量级
     */
    void recordClickBatch(Map<String, Long> shortCodeCounts);
}
