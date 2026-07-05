package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时自动关闭定时任务
 *
 * 业务场景：用户秒杀成功后未在 15 分钟内支付，订单自动关闭，回滚 Redis 库存与下单资格。
 * 并发控制：用乐观锁更新 status: 1 -> 4，避免覆盖并发支付成功。
 */
@Slf4j
@Component
public class OrderCloseTask {

    /** 订单超时时间（分钟） */
    private static final int TIMEOUT_MINUTES = 15;

    /** 一次性扫描的订单数 */
    private static final int BATCH_SIZE = 1000;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每 20 秒扫描一次未支付超时订单
     */
    @Scheduled(fixedRate = 20000)
    public void closeTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);

        // 1. 查询超时未支付订单（status=1 且 createTime < now - 15min）
        List<VoucherOrder> orders = voucherOrderService.query()
                .eq("status", 1)
                .lt("create_time", deadline)
                .last("limit " + BATCH_SIZE)
                .list();

        if (orders == null || orders.isEmpty()) {
            return;
        }

        log.info("扫描到 {} 笔超时未支付订单，开始关闭", orders.size());

        int closed = 0;
        for (VoucherOrder order : orders) {
            try {
                // 2. 乐观锁更新 status: 1 -> 4（已取消），并记录关单时间
                //    条件 status=1 防止并发覆盖已支付的订单
                boolean success = voucherOrderService.update()
                        .set("status", 4)
                        .set("cancel_time", LocalDateTime.now())
                        .eq("id", order.getId())
                        .eq("status", 1)
                        .update();

                if (success) {
                    closed++;
                    // 3. 回滚 Redis 库存 + 移除下单资格（恢复用户可再次抢购）
                    String stockKey = RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId();
                    String orderKey = "seckill:order:" + order.getVoucherId();
                    stringRedisTemplate.opsForValue().increment(stockKey);
                    stringRedisTemplate.opsForSet().remove(orderKey, order.getUserId().toString());
                    log.info("订单已关闭: orderId={}, voucherId={}", order.getId(), order.getVoucherId());
                }
                // success=false 说明该订单已被支付或已被关单，跳过
            } catch (Exception e) {
                log.error("关单异常: orderId={}", order.getId(), e);
            }
        }
        log.info("本轮关闭 {} 笔订单", closed);
    }
}
