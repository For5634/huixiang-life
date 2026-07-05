package com.hmdp.mq;

import com.alibaba.fastjson.JSON;
import com.hmdp.config.RabbitMQTopicConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 消息消费者（等价 Kafka Consumer）
 *
 * 异步处理秒杀下单：扣减 DB 库存 + 保存订单
 *   - 幂等性：用 Redis Set 记录已处理订单 ID，避免重复消费
 *   - 死信队列：消费失败转 DLQ，避免消息丢失
 */
@Slf4j
@Service
public class MQReceiver {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 幂等去重 Redis Key 前缀 */
    private static final String ORDER_PROCESSED_KEY = "order:processed:";

    /**
     * 接收秒杀消息并下单
     */
    @Transactional
    @RabbitListener(queues = RabbitMQTopicConfig.QUEUE)
    public void receiveSeckillMessage(String msg) {
        log.info("接收到秒杀消息: {}", msg);
        VoucherOrder voucherOrder = JSON.parseObject(msg, VoucherOrder.class);
        Long orderId = voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        // 1. 幂等性校验：订单已处理过则直接返回
        Long added = stringRedisTemplate.opsForSet().add(ORDER_PROCESSED_KEY + orderId, "1");
        if (added == null || added == 0) {
            log.warn("订单已处理过，跳过: orderId={}", orderId);
            return;
        }
        // 幂等 key 设 24 小时 TTL，避免 Redis 内存无限增长
        stringRedisTemplate.expire(ORDER_PROCESSED_KEY + orderId, 24, TimeUnit.HOURS);

        // 2. 一人一单二次校验
        long count = voucherOrderService.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("该用户已购买过: userId={}, voucherId={}", userId, voucherId);
            stringRedisTemplate.opsForSet().remove(ORDER_PROCESSED_KEY + orderId, "1");
            return;
        }

        // 3. 扣减 DB 库存（CAS 乐观锁 stock > 0）
        log.info("扣减库存: voucherId={}", voucherId);
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足: voucherId={}", voucherId);
            stringRedisTemplate.opsForSet().remove(ORDER_PROCESSED_KEY + orderId, "1");
            return;
        }

        // 4. 保存订单
        voucherOrderService.save(voucherOrder);
        log.info("订单创建成功: orderId={}", orderId);
    }

    /**
     * 缓存删除补偿：重试删除 Redis 缓存
     */
    @RabbitListener(queues = RabbitMQTopicConfig.CACHE_DELETE_QUEUE)
    public void receiveCacheDeleteMessage(String redisKey) {
        log.info("接收到缓存删除补偿消息: key={}", redisKey);
        try {
            stringRedisTemplate.delete(redisKey);
            log.info("缓存删除成功: key={}", redisKey);
        } catch (Exception e) {
            log.error("缓存删除失败: key={}", redisKey, e);
            throw e; // 触发重试 / 进死信
        }
    }

    /**
     * 死信队列消费者：处理消费失败的消息
     */
    @RabbitListener(queues = RabbitMQTopicConfig.SECKILL_DEAD_QUEUE)
    public void receiveDeadMessage(String msg) {
        log.error("【死信队列】秒杀消息消费失败，进入死信: {}", msg);
        // 可扩展：告警 / 人工补偿 / 落库待处理
    }
}
