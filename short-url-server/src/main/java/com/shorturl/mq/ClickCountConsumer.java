package com.shorturl.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.shorturl.service.IShortUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点击统计消息消费者 (批量聚合模式)
 *
 * 工作模式:
 *  1. 监听容器按 批量配置 (batch-size + batch-timeout-ms)
 *     攒满 N 条 或 等够 200ms 未再收到 → 整批交付为 List&lt;Message&gt;
 *     (原始 AMQP Message, 由本类手动取 deliveryTag 并反序列化, 不用 @Header 注入)
 *  2. 本方法在内存用 HashMap 按 shortCode 合并出现次数:
 *        [4c9g, 4c9g, xyz1, 4c9g, ...]  →  {"4c9g":3, "xyz1":1}
 *  3. 调用 recordClickBatch 一次执行 M 次 UPDATE (M=批内唯一短码数, M<=N)
 *     原本 N 条消息 = N 次 DB 写 → 降为 M 次写, 典型压缩比 5~20 倍
 *  4. 整批全部成功 → 用该批最大 deliveryTag 调 basicAck(multiple=true) 一次签收整批
 *     整批任意失败 → 从第一条 deliveryTag 开始循环 basicNack(requeue=false) 逐条进死信
 *
 * 投递语义保持:
 *   手动 ACK + 死信兜底 = at-least-once (不丢, 但整批重投会放大重复消费面)
 *   对点击统计业务而言, at-least-once 的重复面扩大影响可接受 (click_count 误差仅为少量重复 +1)
 *
 * 只做批量, 不再保留 "退化成逐条" 的开关:
 *   曾经这里有过 `if (!batchEnabled) { 逐条处理 }` 的兼容分支, 它是死代码——
 *   容器在 consumerBatchEnabled=false 时走单条适配器, 会把单个 JSON Object 直接喂给
 *   List<ClickMessage> 参数, Jackson 反序列化在进方法体之前就失败, 整批进死信,
 *   一次 SQL 都执行不到 (实测 5.6 万条全部 conversion error, click_count 零增长)。
 *   单条与批量必须由两个签名不同的监听器承担, 无法用一份 List 签名兼得。
 *   需要"逐条"基线做 A/B 对比时, 请直接用阶段2 的原始 jar 打包运行, 不要靠开关切。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickCountConsumer {

    private final IShortUrlService shortUrlService;
    private final ObjectMapper objectMapper;

    // ================ 批量模式主入口 ================
    /**
     * 参数用原始 Message 而不是 ClickMessage, deliveryTag 也从 MessageProperties 手动取。
     * 原因: 之前用 `List<ClickMessage> + @Header(AmqpHeaders.DELIVERY_TAG) List<Long>`
     *       在 spring-amqp 3.1.7 下拿不到 header, 每批都抛
     *       "Missing header 'amqp_deliveryTag' for method parameter type [interface java.util.List]",
     *       整批拒收, 一条都没落库 (实测 B/D 两组 SQL 执行 0 次)。
     *       该版本也没有 setBatchAckMode 方法可开, 所以直接绕开 header 解析。
     */
    @RabbitListener(queues = "${short-url.mq.click-queue}", ackMode = "MANUAL")
    public void handleBatch(Channel channel, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Long> deliveryTags = new ArrayList<>(messages.size());
        List<ClickMessage> payloads = new ArrayList<>(messages.size());
        for (Message m : messages) {
            deliveryTags.add(m.getMessageProperties().getDeliveryTag());
            ClickMessage cm = deserialize(m);
            if (cm != null) {
                payloads.add(cm);
            }
        }
        handleBatchInternal(channel, payloads, deliveryTags);
    }

    /** 消息体是纯 JSON, 类型信息在 __TypeId__ header 里, 直接按目标类型反序列化即可 */
    private ClickMessage deserialize(Message message) {
        try {
            return objectMapper.readValue(message.getBody(), ClickMessage.class);
        } catch (IOException e) {
            log.error("点击事件反序列化失败, tag={}", message.getMessageProperties().getDeliveryTag(), e);
            return null;
        }
    }

    private void handleBatchInternal(Channel channel,
                                     List<ClickMessage> messages,
                                     List<Long> deliveryTags) {
        final int total = deliveryTags.size();
        final long firstTag = deliveryTags.get(0);
        final long lastTag = deliveryTags.get(total - 1);

        // 若有消息反序列化失败, payload 与 deliveryTag 数量会错位,
        // 此时无法安全签收"子集"(basicAck(multiple=true) 会签掉不该签的), 整批 NACK 进死信
        if (messages.size() != total) {
            log.error("批内消息解析不全: 收到 {} 条, 成功解析 {} 条, 整批进死信, tags=[{}..{}]",
                    total, messages.size(), firstTag, lastTag);
            nackAll(channel, deliveryTags);
            return;
        }

        try {
            // ---- 步骤1: HashMap 合并 (纯内存, 0 IO) ----
            Map<String, Long> shortCodeCounts = mergeByShortCode(messages);

            // ---- 步骤2: 批量写入 DB ----
            shortUrlService.recordClickBatch(shortCodeCounts);

            // ---- 步骤3: 批量 ACK ----
            // multiple=true: 签收 deliveryTag ≤ lastTag 的所有消息, 一次 RPC 搞定整批
            channel.basicAck(lastTag, true);

            if (log.isDebugEnabled()) {
                log.debug("批量点击统计完成: {} 条消息 → {} 个唯一短码, 压缩比 {}:1, tags=[{}..{}]",
                        total, shortCodeCounts.size(), total, shortCodeCounts.size(), firstTag, lastTag);
            }
        } catch (Exception e) {
            log.error("批量点击统计消费失败, 共 {} 条消息, tags=[{}..{}]", total, firstTag, lastTag, e);
            nackAll(channel, deliveryTags);
        }
    }

    /**
     * 整批逐条 basicNack (requeue=false) 进死信。
     * 逐条写法比 basicNack(lastTag, multiple=true) 更显式, 便于日志对账。
     */
    private void nackAll(Channel channel, List<Long> deliveryTags) {
        for (Long tag : deliveryTags) {
            try {
                channel.basicNack(tag, false, false);
            } catch (IOException io) {
                log.error("NACK 进死信失败, tag={}", tag, io);
            }
        }
    }

    /**
     * 按 shortCode 合并计数。
     * 例: [4c9g, 4c9g, xyz1, 4c9g] → {4c9g:3, xyz1:1}
     */
    private Map<String, Long> mergeByShortCode(List<ClickMessage> messages) {
        // capacity 预估: 典型长尾 15~30% 重复, 给个略宽松的初始容量避免 rehash
        Map<String, Long> counts = new HashMap<>((int) (messages.size() * 0.6) + 1);
        for (ClickMessage msg : messages) {
            if (msg == null || msg.getShortCode() == null) {
                continue;
            }
            counts.merge(msg.getShortCode(), 1L, Long::sum);
        }
        return counts;
    }
}
