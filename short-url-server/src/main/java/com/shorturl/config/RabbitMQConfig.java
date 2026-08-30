package com.shorturl.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置 (阶段2 异步点击统计 + 批量聚合)
 *
 * 拓扑:
 *   正常链路: click.exchange(Direct) ──routing──> click.queue (消费者异步 +1)
 *   死信链路: click.queue 消费失败(拒绝且不重入队) ──> dlx.exchange ──> dlx.queue (人工排查/重放)
 *
 * 消息体统一用 JSON (Jackson2JsonMessageConverter),
 * Spring Boot 会自动把该 MessageConverter Bean 装配到 RabbitTemplate 与监听容器工厂。
 *
 * 批量消费:
 *   consumerBatchEnabled=true 开启 Spring AMQP 原生批量模式,
 *   监听容器按 consumerBatchSize(满N条) / receiveTimeout(超时) 两个条件之一触发交付整批 List<Message>。
 *   消费者端对整批做 HashMap 合并后一次性写 DB, 把 N 条消息的 UPDATE 降为 M 次(M=唯一短码数)。
 */
@Configuration
public class RabbitMQConfig {

    @Value("${short-url.mq.click-exchange}")    private String clickExchange;
    @Value("${short-url.mq.click-queue}")        private String clickQueue;
    @Value("${short-url.mq.click-routing-key}")  private String clickRoutingKey;
    @Value("${short-url.mq.dlx-exchange}")        private String dlxExchange;
    @Value("${short-url.mq.dlx-queue}")           private String dlxQueue;
    @Value("${short-url.mq.dlx-routing-key}")     private String dlxRoutingKey;

    // ===== 批量消费参数 =====
    @Value("${short-url.mq.batch-size:100}")                 private int batchSize;
    @Value("${short-url.mq.batch-timeout-ms:200}")           private long batchTimeoutMs;

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

    /**
     * 自定义 RabbitListener 容器工厂: 开启批量消费。
     *
     * Bean 名默认就是 "rabbitListenerContainerFactory",
     * 会被 @RabbitListener 自动装配使用, 无需在注解里显式指定。
     *
     * 批量交付两个触发条件:
     *   ① consumerBatchSize = 攒满 N 条 (默认 100) → 立刻整批交付
     *   ② receiveTimeout    = 等 N ms 还没攒够 → 也交付 (避免消息少的时候无限攒)
     *
     * 可靠性保持:
     *   batchAckMode = MANUAL, 整批消息全部成功才挨个 basicAck,
     *   整批失败则循环 basicNack 让每条都进死信 (仍保持 at-least-once 语义)。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);

        // ===== 批量消费配置 (始终开启, 已移除 batch-enabled=false 分支) =====
        // 说明: 批量模式与单条模式的监听器方法签名不兼容, 无法用一份代码兼得。
        //       需要"逐条"基线对比时, 请直接用阶段2 的原始代码打包运行。
        factory.setConsumerBatchEnabled(true);

        // 每批最大消息数 (攒满或超时二选一触发交付)
        factory.setBatchSize(batchSize);
        // 拉不到更多消息时最多等多久就把攒的那批交付 (ms)
        // receiveTimeout 要比 batchTimeoutMs 稍大, 保证批内消息先被接收够再交付
        factory.setReceiveTimeout(batchTimeoutMs + 50);

        // ===== 预取窗口: 消费者手头最多保留多少条未 ACK 消息 =====
        // prefetch >= batchSize, 否则凑不齐整批
        factory.setPrefetchCount(Math.max(batchSize * 2, 250));

        return factory;
    }
}
