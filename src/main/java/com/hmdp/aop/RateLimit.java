package com.hmdp.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解
 * 基于 Redis + Lua 滑动窗口实现，支持全局 / IP / 用户多维度。
 *
 * 示例：
 *   @RateLimit(qps = 10)                       // 全局 10 QPS
 *   @RateLimit(qps = 5, type = LimitType.IP)   // 单 IP 5 QPS
 *   @RateLimit(qps = 2, type = LimitType.USER) // 单用户 2 QPS
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /** 每秒允许的请求数 */
    int qps() default 10;
    /** 限流维度 */
    LimitType type() default LimitType.GLOBAL;
}
