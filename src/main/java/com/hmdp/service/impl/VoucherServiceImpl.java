package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IShopService shopService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券到普通优惠券voucher数据库
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        //把秒杀信息写入缓存，否则执行seckill.lua的时候找不到缓存，导致与空值比较从而报错
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
        seckillVoucherService.save(seckillVoucher);
    }

    @Override
    public Result queryAllSeckill() {
        LocalDateTime now = LocalDateTime.now();
        // 查询所有上架的秒杀券（type=1, status=1）
        List<Voucher> vouchers = query().eq("type", 1).eq("status", 1).list();
        if (vouchers == null || vouchers.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 聚合：关联店铺名 + 库存 + 时间窗口
        List<Map<String, Object>> result = new ArrayList<>();
        for (Voucher v : vouchers) {
            SeckillVoucher sv = seckillVoucherService.getById(v.getId());
            if (sv == null) continue;
            // 已结束的秒杀券不在专区展示
            if (sv.getEndTime() != null && sv.getEndTime().isBefore(now)) continue;
            Shop shop = shopService.getById(v.getShopId());
            String shopName = (shop != null) ? shop.getName() : "未知店铺";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", v.getId());
            item.put("shopId", v.getShopId());
            item.put("shopName", shopName);
            item.put("title", v.getTitle());
            item.put("subTitle", v.getSubTitle());
            item.put("payValue", v.getPayValue());
            item.put("actualValue", v.getActualValue());
            item.put("stock", sv.getStock());
            item.put("beginTime", sv.getBeginTime());
            item.put("endTime", sv.getEndTime());
            // 补充店铺主图，供秒杀列表展示
            item.put("shopImage", (shop != null && shop.getImages() != null) ? shop.getImages().split(",")[0] : null);
            // 状态：0=未开始, 1=进行中, 2=已结束（已结束不会进入这里）
            int state;
            if (sv.getBeginTime() != null && sv.getBeginTime().isAfter(now)) {
                state = 0;
            } else {
                state = 1;
            }
            item.put("state", state);
            result.add(item);
        }
        return Result.ok(result);
    }
}
