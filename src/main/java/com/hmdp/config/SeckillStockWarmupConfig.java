package com.hmdp.config;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 启动时把所有秒杀券库存预热到 Redis（避免 Lua 脚本 get 不到 key 报"库存不足"）
 *
 * 适用场景：
 *   - 全新导入种子数据后，秒杀券库存只在 MySQL，Redis 没缓存
 *   - 重启时确保库存与 DB 同步（以 DB 为准，覆盖 Redis 中可能残留的旧值）
 *
 * 注：以 DB 中 tb_seckill_voucher.stock 为准，覆盖式的 SET，幂等安全。
 *     只对当前时间在 [begin_time, end_time] 区间内的券预热（避免结束的券也预热占用内存）。
 */
@Slf4j
@Configuration
public class SeckillStockWarmupConfig {

    @Bean
    public ApplicationRunner seckillStockWarmupRunner(
            IVoucherService voucherService,
            ISeckillVoucherService seckillVoucherService,
            StringRedisTemplate stringRedisTemplate) {
        return args -> {
            try {
                // 查所有秒杀券（type=1, status=1）
                List<Voucher> vouchers = voucherService.query().eq("type", 1).eq("status", 1).list();
                if (vouchers == null || vouchers.isEmpty()) {
                    log.info("秒杀券库存预热: 暂无秒杀券，跳过");
                    return;
                }
                int warmed = 0;
                for (Voucher v : vouchers) {
                    SeckillVoucher sv = seckillVoucherService.getById(v.getId());
                    if (sv == null) continue;
                    // 只预热未结束的券
                    if (sv.getEndTime() != null && sv.getEndTime().isBefore(java.time.LocalDateTime.now())) {
                        continue;
                    }
                    String stockKey = RedisConstants.SECKILL_STOCK_KEY + v.getId();
                    // 以 DB 为准，覆盖式 SET
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(sv.getStock()));
                    warmed++;
                }
                log.info("秒杀券库存预热完成: 共 {} 张券已写入 Redis", warmed);
            } catch (Exception e) {
                log.error("秒杀券库存预热失败（不影响启动，但秒杀会报库存不足）", e);
            }
        };
    }
}
