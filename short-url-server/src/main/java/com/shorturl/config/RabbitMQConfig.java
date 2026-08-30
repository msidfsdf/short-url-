package com.shorturl.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 (阶段3 异步点击统计)
 *
 * 拓扑:
 *   正常链路: click.exchange(Direct) ──routing──> click.queue (消费者异步 +1)
 *   死信链路: click.queue 消费失败(拒绝且不重入队) ──> dlx.exchange ──> dlx.queue (人工排查/重放)
 *
 * 消息体统一用 JSON (Jackson2JsonMessageConverter),
 * Spring Boot 会自动把该 MessageConverter Bean 装配到 RabbitTemplate 与监听容器工厂。
 */
@Configuration
public class RabbitMQConfig {

    @Value("${short-url.mq.click-exchange}")    private String clickExchange;
    @Value("${short-url.mq.click-queue}")        private String clickQueue;
    @Value("${short-url.mq.click-routing-key}")  private String clickRoutingKey;
    @Value("${short-url.mq.dlx-exchange}")        private String dlxExchange;
    @Value("${short-url.mq.dlx-queue}")           private String dlxQueue;
    @Value("${short-url.mq.dlx-routing-key}")     private String dlxRoutingKey;

    /** 正常交换机 (Direct) */
    @Bean
    public DirectExchange clickExchange() {
        return ExchangeBuilder.directExchange(clickExchange).durable(true).build();
    }

    /** 死信交换机 (Direct) */
    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(dlxExchange).durable(true).build();
    }

    /** 正常队列: 绑定死信参数, 消费失败(拒绝且不重入队)时进入死信队列 */
    @Bean
    public Queue clickQueue() {
        return QueueBuilder.durable(clickQueue)
                .withArgument("x-dead-letter-exchange", dlxExchange)
                .withArgument("x-dead-letter-routing-key", dlxRoutingKey)
                .build();
    }

    /** 死信队列 */
    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(dlxQueue).build();
    }

    @Bean
    public Binding clickBinding() {
        return BindingBuilder.bind(clickQueue()).to(clickExchange()).with(clickRoutingKey);
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(dlxRoutingKey);
    }

    /** JSON 消息转换器: 生产者与消费者共用, 保证消息体可读、可跨语言 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
