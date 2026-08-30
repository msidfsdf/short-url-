package com.shorturl.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 点击统计消息生产者
 *
 * 跳转流程只负责"发消息", 不等待入库结果 -> 把点击统计从请求关键路径中解耦。
 * MQ 不可用时(异常)只记录日志, 绝不阻塞 302 跳转 (统计是副作用, 可丢不可挡)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${short-url.mq.click-exchange}")    private String exchange;
    @Value("${short-url.mq.click-routing-key}") private String routingKey;

    public void sendClick(String shortCode) {
        try {
            ClickMessage message = new ClickMessage(shortCode, System.currentTimeMillis());
            rabbitTemplate.convertAndSend(exchange, routingKey, message);
        } catch (Exception e) {
            // 异步统计是副作用: 即使 MQ 写入失败, 也不能影响用户跳转
            log.error("发送点击统计消息失败, shortCode={}", shortCode, e);
        }
    }
}
