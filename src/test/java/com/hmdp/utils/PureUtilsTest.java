package com.hmdp.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单元测试（无需 Spring 上下文，无需 Redis/MySQL）
 *
 * 这部分测试专注于纯逻辑工具类，CI 环境也能跑通。
 * 集成测试在 HuiXiangApplicationTests，需要 Redis + MySQL 启动。
 */
class PureUtilsTest {

    @Test
    @DisplayName("正则工具 - 手机号格式校验")
    void testPhoneRegex() {
        // isPhoneInvalid: true 表示"无效"
        assertFalse(RegexUtils.isPhoneInvalid("13912345678"));
        assertFalse(RegexUtils.isPhoneInvalid("17012345678"));
        assertTrue(RegexUtils.isPhoneInvalid("12345"));
        assertTrue(RegexUtils.isPhoneInvalid("12345678901")); // 不以 1 开头第二位 3-9
        assertTrue(RegexUtils.isPhoneInvalid(""));
        assertTrue(RegexUtils.isPhoneInvalid(null));
    }

    @Test
    @DisplayName("正则工具 - 邮箱格式校验")
    void testEmailRegex() {
        assertFalse(RegexUtils.isEmailInvalid("abc@example.com"));
        assertTrue(RegexUtils.isEmailInvalid("abc"));
        assertTrue(RegexUtils.isEmailInvalid(""));
        assertTrue(RegexUtils.isEmailInvalid(null));
    }

    @Test
    @DisplayName("正则工具 - 验证码格式校验（6位数字或字母）")
    void testCodeRegex() {
        assertFalse(RegexUtils.isCodeInvalid("123456"));
        assertFalse(RegexUtils.isCodeInvalid("abcDEF"));
        assertTrue(RegexUtils.isCodeInvalid("12345")); // 5 位
        assertTrue(RegexUtils.isCodeInvalid("1234567")); // 7 位
        assertTrue(RegexUtils.isCodeInvalid(""));
        assertTrue(RegexUtils.isCodeInvalid(null));
    }

    @Test
    @DisplayName("密码编码 - 双盐 MD5 匹配")
    void testPasswordEncoder() {
        String rawPassword = "123456";
        String encoded = PasswordEncoder.encode(rawPassword);
        assertNotNull(encoded);
        assertTrue(encoded.contains("@"),
                "加密后应包含 @ 形如 salt@md5(salt+raw)");

        boolean matches = PasswordEncoder.matches(encoded, rawPassword);
        assertTrue(matches, "正确密码应匹配");

        boolean wrongMatch = PasswordEncoder.matches(encoded, "wrong");
        assertFalse(wrongMatch, "错误密码不应匹配");
    }

    @Test
    @DisplayName("密码编码 - 不同盐生成不同密文")
    void testPasswordEncoderDifferentSalt() {
        String rawPassword = "abc123";
        String encoded1 = PasswordEncoder.encode(rawPassword);
        String encoded2 = PasswordEncoder.encode(rawPassword);
        assertNotEquals(encoded1, encoded2,
                "两次随机盐加密结果应不同");
    }

    @Test
    @DisplayName("RedisIdWorker - 常量定义")
    void testRedisIdWorkerConstants() {
        assertTrue(RedisIdWorker.BEGIN_TIMESTAMP > 0,
                "起始时间戳应为正");
        assertTrue(RedisIdWorker.COUNT_BIT > 0,
                "序列号位长度应为正");
    }

    @Test
    @DisplayName("RedisConstants - 常量非空")
    void testRedisConstants() {
        assertNotNull(RedisConstants.LOGIN_CODE_KEY);
        assertNotNull(RedisConstants.LOGIN_USER_KEY);
        assertTrue(RedisConstants.LOGIN_CODE_TTL > 0);
        assertTrue(RedisConstants.LOGIN_USER_TTL > 0);
    }

    @Test
    @DisplayName("RegexPatterns - 正则常量定义")
    void testRegexPatterns() {
        assertNotNull(RegexPatterns.PHONE_REGEX);
        assertNotNull(RegexPatterns.EMAIL_REGEX);
        assertNotNull(RegexPatterns.PASSWORD_REGEX);
        assertNotNull(RegexPatterns.VERIFY_CODE_REGEX);
        // 简单校验一个手机号正则
        assertTrue("13912345678".matches(RegexPatterns.PHONE_REGEX));
        assertFalse("12345".matches(RegexPatterns.PHONE_REGEX));
    }

    @Test
    @DisplayName("SystemConstants - 默认值")
    void testSystemConstants() {
        assertEquals("user_", SystemConstants.USER_NICK_NAME_PREFIX);
        assertEquals(5, SystemConstants.DEFAULT_PAGE_SIZE);
        assertTrue(SystemConstants.MAX_PAGE_SIZE >= SystemConstants.DEFAULT_PAGE_SIZE);
    }
}
