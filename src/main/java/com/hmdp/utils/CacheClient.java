package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 多级缓存客户端：Caffeine 本地缓存（一级） + Redis 分布式缓存（二级）
 *
 * 读链路：L1 Caffeine -> L2 Redis -> DB -> 回写 L1+L2
 * 写链路：更新 DB -> 删 L2 -> 删 L1（删除失败走 MQ 补偿 + TTL 兜底）
 *
 *   - Caffeine 解决：热点数据极速访问，降低 Redis 网络开销
 *   - Redis 解决：分布式共享缓存，避免多实例缓存不一致
 *   - 缓存穿透：缓存空值
 *   - 缓存击穿：逻辑过期 + 互斥锁
 */
@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private com.hmdp.mq.MQSender mqSender;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /** 一级缓存：Caffeine 本地缓存 */
    private static final Cache<String, String> LOCAL_CACHE = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    /**
     * 方法1：将任意 Java 对象序列化为 JSON，存入 Redis（String Key），设置 TTL
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 方法2：逻辑过期（无 TTL，靠 expireTime 字段判断），用于解决缓存击穿
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 方法3：缓存穿透防护（缓存空值）+ 多级缓存（Caffeine + Redis）
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;

        // 1. L1 Caffeine 命中
        String l1 = LOCAL_CACHE.getIfPresent(key);
        if (StrUtil.isNotBlank(l1)) {
            return JSONUtil.toBean(l1, type);
        }
        if (l1 != null) {
            // 空字符串 "" 表示缓存空值
            return null;
        }

        // 2. L2 Redis 查询
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 回写 L1
            LOCAL_CACHE.put(key, json);
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 空值缓存，回写 L1 空值
            LOCAL_CACHE.put(key, "");
            return null;
        }

        // 3. DB 查询
        R r = dbFallback.apply(id);

        if (r == null) {
            // 缓存空值（穿透防护）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            LOCAL_CACHE.put(key, "");
            return null;
        }

        // 4. 回写 L2 + L1
        String jsonStr = JSONUtil.toJsonStr(r);
        stringRedisTemplate.opsForValue().set(key, jsonStr, time, timeUnit);
        LOCAL_CACHE.put(key, jsonStr);
        return r;
    }

    /**
     * 方法4：逻辑过期解决缓存击穿（热点 Key）+ 多级缓存
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;

        // 1. L1 Caffeine 命中（逻辑过期数据也可能在本地缓存）
        String l1 = LOCAL_CACHE.getIfPresent(key);
        String json = (l1 != null) ? l1 : stringRedisTemplate.opsForValue().get(key);
        if (l1 != null && StrUtil.isBlank(l1)) {
            return null;
        }

        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // data 可能是 JSONObject/Map/bean，统一转 JSON 再解析，避免 ClassCastException
        Object data = redisData.getData();
        R r;
        if (data instanceof JSONObject) {
            r = JSONUtil.toBean((JSONObject) data, type);
        } else {
            r = JSONUtil.toBean(JSONUtil.parseObj(JSONUtil.toJsonStr(data)), type);
        }
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return r;
        }

        // 过期，尝试获取互斥锁，异步重建
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R tmp = dbFallback.apply(id);
                    this.setWithLogicExpire(key, tmp, time, timeUnit);
                    LOCAL_CACHE.put(key, JSONUtil.toJsonStr(tmp));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            LOCAL_CACHE.put(key, json);
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        R r = null;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean flag = tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, timeUnit);
            LOCAL_CACHE.put(key, JSONUtil.toJsonStr(r));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return r;
    }

    /**
     * 删除缓存（数据一致性：更新数据库后调用）
     * 先删 L2 Redis，再删 L1 Caffeine；L2 删除失败走 MQ 补偿 + TTL 兜底。
     */
    public void evict(String keyPrefix, Object id) {
        String key = keyPrefix + id;
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.error("删除 Redis 缓存失败，发送 MQ 补偿消息: key={}", key, e);
            // 删缓存失败 → 发 MQ 补偿重试，保证最终一致
            try {
                mqSender.sendCacheDeleteMessage(key);
            } catch (Exception ex) {
                log.error("发送缓存删除补偿消息失败: key={}", key, ex);
                // TTL 兜底：Redis key 自带 TTL，最终会过期
            }
        }
        LOCAL_CACHE.invalidate(key);
    }

    /** 打印 Caffeine 命中率（调试/监控用） */
    public void printStats() {
        log.info("Caffeine 缓存统计: {}", LOCAL_CACHE.stats());
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

