package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 订单 & 支付 Controller
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    /**
     * 查询我的订单
     * @param status 订单状态（可选）：1未支付/2已支付/3已核销/4已取消/5退款中/6已退款
     */
    @GetMapping("/my")
    public Result myOrders(@RequestParam(value = "status", required = false) Integer status) {
        return voucherOrderService.queryMyOrders(status);
    }

    /**
     * 模拟支付订单
     */
    @PostMapping("/pay/{id}")
    public Result pay(@PathVariable("id") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }
}
