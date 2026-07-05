package com.hmdp.mq;

import com.hmdp.config.RabbitMQTopicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 消息发送者（等价 Kafka Producer）
 *
 * 注：本项目使用 RabbitMQ 实现消息中间件能力，与 Kafka 在职责上等价：
 *   - Exchange/Queue ≈ Kafka Topic
 *   - Routing Key ≈ Kafka Message Key（用于分区/路由）
 *   - Consumer Group 由 Listener 自动集群消费
 *   - 死信队列 (DLX) ≈ Kafka DLQ Topic
 */
@Slf4j
@Service
public class MQSender {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String ROUTINGKEY = "seckill.message";

    /**
     * 发送秒杀下单消息
     */
    public void sendSeckillMessage(String msg) {
        log.info("发送秒杀消息: {}", msg);
        rabbitTemplate.convertAndSend(RabbitMQTopicConfig.EXCHANGE, ROUTINGKEY, msg);
    }

    /**
     * 发送缓存删除补偿消息（数据一致性：删缓存失败时重试）
     */
    public void sendCacheDeleteMessage(String redisKey) {
        log.info("发送缓存删除补偿消息: key={}", redisKey);
        rabbitTemplate.convertAndSend(RabbitMQTopicConfig.EXCHANGE, "cache.delete", redisKey);
    }

    /**
     * 发送支付成功消息
     */
    public void sendPaySuccessMessage(String orderId) {
        log.info("发送支付成功消息: orderId={}", orderId);
        rabbitTemplate.convertAndSend(RabbitMQTopicConfig.EXCHANGE, "pay.success", orderId);
    }
}
