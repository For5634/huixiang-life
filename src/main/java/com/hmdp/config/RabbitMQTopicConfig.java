package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置（等价 Kafka Topic/Partition/DLQ 概念）
 *
 *   - seckillExchange (Topic) ≈ Kafka Topic 路由
 *   - seckillQueue     ≈ Kafka Consumer 订阅
 *   - seckillDeadQueue ≈ Kafka DLQ（死信队列）
 *   - cacheDeleteQueue  缓存删除补偿队列
 */
@Configuration
public class RabbitMQTopicConfig {
    public static final String QUEUE = "seckillQueue";
    public static final String EXCHANGE = "seckillExchange";
    public static final String ROUTINGKEY = "seckill.#";

    /** 死信队列 */
    public static final String SECKILL_DEAD_EXCHANGE = "seckillDeadExchange";
    public static final String SECKILL_DEAD_QUEUE = "seckillDeadQueue";
    public static final String SECKILL_DEAD_ROUTING_KEY = "seckill.dead";

    /** 缓存删除补偿队列 */
    public static final String CACHE_DELETE_QUEUE = "cacheDeleteQueue";

    // ---------- 秒杀队列（绑定死信交换机）----------
    @Bean
    public Queue queue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", SECKILL_DEAD_EXCHANGE);
        args.put("x-dead-letter-routing-key", SECKILL_DEAD_ROUTING_KEY);
        return new Queue(QUEUE, true, false, false, args);
    }

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Binding binding() {
        return BindingBuilder.bind(queue()).to(topicExchange()).with("seckill.message");
    }

    @Bean
    public Binding cacheDeleteBinding() {
        return BindingBuilder.bind(cacheDeleteQueue()).to(topicExchange()).with("cache.delete");
    }

    // ---------- 死信队列 ----------
    @Bean
    public TopicExchange deadExchange() {
        return new TopicExchange(SECKILL_DEAD_EXCHANGE);
    }

    @Bean
    public Queue deadQueue() {
        return new Queue(SECKILL_DEAD_QUEUE, true);
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadExchange()).with(SECKILL_DEAD_ROUTING_KEY);
    }

    // ---------- 缓存删除补偿队列 ----------
    @Bean
    public Queue cacheDeleteQueue() {
        return new Queue(CACHE_DELETE_QUEUE, true);
    }
}
