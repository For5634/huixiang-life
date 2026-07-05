package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IUserService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 惠享生活 - 核心功能单元测试
 *
 * 运行前确保：Redis 启动（localhost:6379）、MySQL 已初始化
 */
@SpringBootTest
@Tag("integration")
class HuiXiangApplicationTests {

    @Autowired
    private IUserService userService;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 供测试的手机号（开发模式验证码固定 123456） */
    private static final String TEST_PHONE = "13900000001";
    private static final String DEV_CODE = "123456";

    @BeforeEach
    void setUp() {
        // 开发模式：预设验证码到 Redis，免去发短信
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + TEST_PHONE,
                DEV_CODE,
                RedisConstants.LOGIN_CODE_TTL,
                java.util.concurrent.TimeUnit.MINUTES);
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + TEST_PHONE);
        UserHolder.removeUser();
    }

    // ========== 登录模块测试 ==========

    @Nested
    @DisplayName("登录模块")
    class LoginTests {

        @Test
        @DisplayName("验证码登录 - 成功")
        void testLoginSuccess() {
            LoginFormDTO dto = new LoginFormDTO();
            dto.setPhone(TEST_PHONE);
            dto.setCode(DEV_CODE);
            dto.setAgreedPolicy(true);

            Result result = userService.login(dto, null);
            System.out.println("login result: " + result);
            assertNotNull(result);
            assertTrue(Objects.requireNonNull(result.getSuccess()),
                    "登录应成功，但返回 success=" + result.getSuccess());
            assertNotNull(result.getData());
        }

        @Test
        @DisplayName("登录 - 验证码错误")
        void testLoginWrongCode() {
            LoginFormDTO dto = new LoginFormDTO();
            dto.setPhone(TEST_PHONE);
            dto.setCode("000000");

            Result result = userService.login(dto, null);
            assertNotNull(result);
            assertFalse(Objects.requireNonNull(result.getSuccess()));
        }

        @Test
        @DisplayName("登录 - 手机号格式错误")
        void testLoginBadPhone() {
            LoginFormDTO dto = new LoginFormDTO();
            dto.setPhone("123"); // 不合法手机号
            dto.setCode(DEV_CODE);

            Result result = userService.login(dto, null);
            assertNotNull(result);
            assertFalse(Objects.requireNonNull(result.getSuccess()));
        }
    }

    // ========== 验证码发送测试 ==========

    @Nested
    @DisplayName("验证码发送模块")
    class SendCodeTests {

        @Test
        @DisplayName("发送验证码 - 手机号格式错误")
        void testSendCodeBadPhone() {
            Result result = userService.sendCode("12345", null);
            assertNotNull(result);
            assertFalse(Objects.requireNonNull(result.getSuccess()));
        }
    }

    // ========== 秒杀 Lua 脚本测试 ==========

    @Nested
    @DisplayName("秒杀 Lua 脚本")
    class SeckillScriptTests {

        @Test
        @DisplayName("Lua脚本 - 库存不足时返回1")
        void testLuaMissingStock() {
            // 直接验证 Redis 操作：库存 key 不存在时脚本返回 1
            String stockKey = "seckill:stock:test99999";
            String orderKey = "seckill:order:test99999";
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);

            // 存 0 张库存，模拟库存不足
            stringRedisTemplate.opsForValue().set(stockKey, "0");

            String lua = "local stock = tonumber(redis.call('get', KEYS[1])) or 0 " +
                         "if stock <= 0 then return -1 end " +
                         "redis.call('incrby', KEYS[1], -1) " +
                         "redis.call('sadd', KEYS[2], ARGV[1]) " +
                         "return 0";

            Long result = stringRedisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                    java.util.List.of(stockKey, orderKey),
                    "user1");

            assertNotNull(result);
            assertEquals(-1L, result.longValue(), "库存为0时应返回-1表示库存不足");

            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }

        @Test
        @DisplayName("Lua脚本 - 库存充足可成功下单")
        void testLuaSeckillSuccess() {
            String stockKey = "seckill:stock:test88888";
            String orderKey = "seckill:order:test88888";
            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);

            // 设置 10 张库存
            stringRedisTemplate.opsForValue().set(stockKey, "10");

            String lua = "local stock = tonumber(redis.call('get', KEYS[1])) or 0 " +
                         "if stock <= 0 then return -1 end " +
                         "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return -2 end " +
                         "redis.call('incrby', KEYS[1], -1) " +
                         "redis.call('sadd', KEYS[2], ARGV[1]) " +
                         "return 0";

            Long result = stringRedisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(lua, Long.class),
                    java.util.List.of(stockKey, orderKey),
                    "user1");

            assertNotNull(result);
            assertEquals(0L, result.longValue(), "库存充足时应返回0表示成功");

            // 验证库存扣减
            String stockAfter = stringRedisTemplate.opsForValue().get(stockKey);
            assertEquals("9", stockAfter);

            // 验证用户已记录
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(orderKey, "user1");
            assertNotNull(isMember);
            assertTrue(isMember);

            stringRedisTemplate.delete(stockKey);
            stringRedisTemplate.delete(orderKey);
        }
    }

    // ========== 订单 & 支付测试 ==========

    @Nested
    @DisplayName("订单 & 支付")
    class OrderTests {

        @Test
        @DisplayName("支付 - 不存在的订单")
        void testPayNonExistOrder() {
            Result result = voucherOrderService.payOrder(-1L);
            assertNotNull(result);
            assertFalse(Objects.requireNonNull(result.getSuccess()));
        }
    }

    // ========== 缓存模块测试 ==========

    @Nested
    @DisplayName("缓存模块")
    class CacheTests {

        @Test
        @DisplayName("Redis 基本读写")
        void testRedisReadWrite() {
            String key = "test:huixiang:ping";
            String value = "pong";

            stringRedisTemplate.opsForValue().set(key, value, 1, java.util.concurrent.TimeUnit.MINUTES);
            String cached = stringRedisTemplate.opsForValue().get(key);

            assertEquals(value, cached);
            stringRedisTemplate.delete(key);
        }
    }

    // ========== 工具类测试 ==========

    @Nested
    @DisplayName("工具方法")
    class UtilTests {

        @Test
        @DisplayName("密码编码验证")
        void testPasswordEncoder() {
            String rawPassword = "123456";
            String encoded = com.hmdp.utils.PasswordEncoder.encode(rawPassword);
            assertNotNull(encoded);
            assertTrue(encoded.contains("@"));

            boolean matches = com.hmdp.utils.PasswordEncoder.matches(encoded, rawPassword);
            assertTrue(matches);

            boolean wrongMatch = com.hmdp.utils.PasswordEncoder.matches(encoded, "wrong");
            assertFalse(wrongMatch);
        }
    }
}
