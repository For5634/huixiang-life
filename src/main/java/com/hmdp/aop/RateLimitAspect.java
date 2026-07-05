package com.hmdp.aop;

import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.UUID;

/**
 * 限流切面：基于 Redis + Lua 滑动窗口算法。
 * 用 ZSET 存储请求时间戳，删除窗口外的旧记录，统计窗口内请求数。
 *
 * 注：与 com.hmdp.aop.RateLimit 注解配套使用。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 滑动窗口 Lua 脚本：
     * 1. 移除窗口外的旧时间戳
     * 2. 添加当前时间戳
     * 3. 统计窗口内请求数
     * 4. 超过阈值则删除刚加入的时间戳并返回 0（拒绝），否则返回 1（放行）
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        // 滑动窗口：用 ZSET 存请求时间戳。member 必须唯一（同毫秒多请求会覆盖），
        // 因此用 INCR 计数器拼接保证唯一：score=now, member=now:counter
        RATE_LIMIT_SCRIPT.setScriptText(
            "local key = KEYS[1]\n" +
            "local now = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local limit = tonumber(ARGV[3])\n" +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n" +
            "local cnt = redis.call('ZCARD', key)\n" +
            "if cnt >= limit then\n" +
            "    return 0\n" +
            "end\n" +
            "local seq = redis.call('INCR', key .. ':seq')\n" +
            "redis.call('ZADD', key, now, now .. ':' .. seq)\n" +
            "redis.call('PEXPIRE', key, window)\n" +
            "redis.call('PEXPIRE', key .. ':seq', window)\n" +
            "return 1"
        );
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        int qps = rateLimit.qps();
        LimitType type = rateLimit.type();
        String key = buildKey(type, pjp);
        long now = System.currentTimeMillis();
        // 窗口 1 秒
        long window = 1000L;
        Long allowed = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(window),
                String.valueOf(qps)
        );
        if (allowed == null || allowed == 0L) {
            log.warn("接口限流拦截：key={}, qps={}, type={}", key, qps, type);
            return Result.fail("操作过于频繁，请稍后再试");
        }
        return pjp.proceed();
    }

    private String buildKey(LimitType type, ProceedingJoinPoint pjp) {
        String method = pjp.getSignature().toShortString();
        String base = "rate:limit:" + method;
        switch (type) {
            case IP:
                return base + ":ip:" + getClientIp();
            case USER:
                Long uid = (UserHolder.getUser() != null) ? UserHolder.getUser().getId() : null;
                return base + ":user:" + (uid != null ? uid : "anonymous:" + getClientIp());
            case GLOBAL:
            default:
                return base + ":global";
        }
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                String ip = req.getHeader("X-Forwarded-For");
                if (ip != null && !ip.isEmpty()) return ip.split(",")[0].trim();
                ip = req.getHeader("X-Real-IP");
                if (ip != null && !ip.isEmpty()) return ip;
                return req.getRemoteAddr();
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString();
    }
}
