package com.hmdp.aop;

/**
 * 限流类型
 */
public enum LimitType {
    /** 全局限流：所有用户共享一个桶 */
    GLOBAL,
    /** 按 IP 限流：每个 IP 独立桶 */
    IP,
    /** 按用户限流：每个登录用户独立桶 */
    USER
}
