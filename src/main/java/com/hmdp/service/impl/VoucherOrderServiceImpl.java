package com.hmdp.service.impl;

import com.alibaba.fastjson.JSON;
import com.hmdp.aop.LimitType;
import com.hmdp.aop.RateLimit;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.MQSender;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  优惠券订单服务实现类
 * </p>
 *
 * 秒杀流程：Redis + Lua 预扣库存校验 -> Kafka/RabbitMQ 异步下单
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private MQSender mqSender;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀下单：滑动窗口限流 + Lua 原子校验 + MQ 异步落库
     */
    @Override
    @RateLimit(qps = 10, type = LimitType.GLOBAL)
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();

        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // Lua 脚本异常或 Redis 连接问题时 r 可能为 null
        if (r == null) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        //2.判断结果为0
        int result = r.intValue();
        if (result != 0) {
            //2.1不为0代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "该用户重复下单");
        }
        //2.2为0代表有购买资格,将下单信息保存到阻塞队列

        //2.3创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.4订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.5用户id
        voucherOrder.setUserId(userId);
        //2.6代金卷id
        voucherOrder.setVoucherId(voucherId);

        //2.7将信息放入MQ中（等价 Kafka 方案：解耦削峰，异步落库）
        mqSender.sendSeckillMessage(JSON.toJSONString(voucherOrder));

        //2.8 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 模拟支付：将订单 status: 1 -> 2（已支付），记录 payTime
     * 并发控制：乐观锁 eq("status", 1)，避免与超时关单并发覆盖
     */
    @Override
    @Transactional
    public Result payOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() != 1) {
            return Result.fail("订单状态异常，无法支付（可能已支付或已关闭）");
        }
        // 乐观锁更新：status=1 -> 2，记录支付时间
        boolean success = update()
                .set("status", 2)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", 1)
                .update();
        if (!success) {
            return Result.fail("支付失败，订单可能已被关闭或已支付");
        }
        // 发送支付成功消息（可用于核销、积分等下游处理）
        mqSender.sendPaySuccessMessage(orderId.toString());
        return Result.ok("支付成功");
    }

    /**
     * 查询当前用户的订单
     */
    @Override
    public Result queryMyOrders(Integer status) {
        Long userId = UserHolder.getUser().getId();
        List<VoucherOrder> orders = query()
                .eq("user_id", userId)
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .list();
        return Result.ok(orders);
    }
}
