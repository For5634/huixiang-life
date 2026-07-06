package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 模拟支付控制器
 * <p>
 * 个人用户不上线真实微信支付，所有支付走模拟流程：
 * POST /pay/order/{orderId}  →  直接修改订单状态 1→2（已支付）
 */
@RestController
@RequestMapping("/pay")
public class PayController {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    /**
     * 模拟支付
     */
    @PostMapping("/order/{orderId}")
    public Result payOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }
}
