package com.shorturl.mq;

import com.rabbitmq.client.Channel;
import com.shorturl.service.IShortUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 点击统计消息消费者
 *
 * 手动 ACK:
 *   - 入库成功  -> basicAck
 *   - 入库失败  -> basicNack(requeue=false) 进入死信队列, 避免失败消息无限重入队导致队列阻塞
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickCountConsumer {

    private final IShortUrlService shortUrlService;

    @RabbitListener(queues = "${short-url.mq.click-queue}", ackMode = "MANUAL")
    public void handle(Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                       ClickMessage message) {
        try {
            shortUrlService.recordClick(message.getShortCode());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("点击统计消费失败, shortCode={}", message.getShortCode(), e);
            try {
                // 拒绝且不重入队 -> 进入死信队列, 等待人工排查/重放
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException io) {
                log.error("拒绝消息进入死信队列失败, shortCode={}", message.getShortCode(), io);
            }
        }
    }
}
