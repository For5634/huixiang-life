package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SmsUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private SmsUtils smsUtils;
    @Value("${aliyun.sms.dev-mode:false}")
    private boolean devMode;
    //发短信
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2. 判断是否在一级限制条件内
        Boolean oneLevelLimit = stringRedisTemplate.opsForSet().isMember(ONE_LEVERLIMIT_KEY + phone, "1");
        if (oneLevelLimit != null && oneLevelLimit) {
            // 在一级限制条件内，不能发送验证码
            return Result.fail("您需要等5分钟后再请求");
        }

// 2. 判断是否在二级限制条件内
        Boolean twoLevelLimit = stringRedisTemplate.opsForSet().isMember(TWO_LEVERLIMIT_KEY + phone, "1");
        if (twoLevelLimit != null && twoLevelLimit) {
            // 在二级限制条件内，不能发送验证码
            return Result.fail("您需要等20分钟后再请求");
        }

// 3. 检查过去1分钟内发送验证码的次数
        long oneMinuteAgo = System.currentTimeMillis() - 60 * 1000;
        long count_oneminute = stringRedisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + phone, oneMinuteAgo, System.currentTimeMillis());
        if (count_oneminute >= 1) {
            // 过去1分钟内已经发送了1次，不能再发送验证码
            return Result.fail("距离上次发送时间不足1分钟，请1分钟后重试");
        }

        // 4. 检查发送验证码的次数
        long fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000;
        long count_fiveminute = stringRedisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + phone, fiveMinutesAgo, System.currentTimeMillis());
        if (count_fiveminute % 3 == 2 && count_fiveminute > 5) {
            // 发送了8, 11, 14, ...次，进入二级限制
            stringRedisTemplate.opsForSet().add(TWO_LEVERLIMIT_KEY + phone, "1");
            stringRedisTemplate.expire(TWO_LEVERLIMIT_KEY + phone, 20, TimeUnit.MINUTES);
            return Result.fail("接下来如需再发送，请等20分钟后再请求");
        } else if (count_fiveminute == 5) {
            // 过去5分钟内已经发送了5次，进入一级限制
            stringRedisTemplate.opsForSet().add(ONE_LEVERLIMIT_KEY + phone, "1");
            stringRedisTemplate.expire(ONE_LEVERLIMIT_KEY + phone, 5, TimeUnit.MINUTES);
            return Result.fail("5分钟内已经发送了5次，接下来如需再发送请等待5分钟后重试");
        }

        //6. 生成验证码
        String code = SmsUtils.generateCode();

        //7. 发送短信验证码
        //   开发模式下若配置了 dev-fixed-code，SmsUtils 会返回 dev-fixed-code（"123456"），
        //   而不是这里随机生成的 code。为了保证"前端拿到的码 = Redis 里保存的码"，
        //   这里以 SmsUtils 的返回值为准，覆盖随机 code。
        String sendResult = smsUtils.sendSms(phone, code);
        if (sendResult == null) {
            return Result.fail("短信发送失败，请稍后重试");
        }
        // 开发模式：sendResult 是要用的验证码（可能是 dev-fixed-code，也可能是随机码）
        if (devMode) {
            code = sendResult;
        }

        //8. 将验证码保存到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送登录验证码：手机号={}, 验证码={}", phone, code);

        //9. 更新发送时间和次数
        stringRedisTemplate.opsForZSet().add(SENDCODE_SENDTIME_KEY + phone, String.valueOf(System.currentTimeMillis()), System.currentTimeMillis());

        //10. 返回结果（开发模式返回验证码，生产模式返回成功）
        if ("success".equals(sendResult)) {
            return Result.ok();
        } else {
            return Result.ok(sendResult);
        }
}

    /**
     * 登录/注册
     * 支持两种模式：
     *   1. 验证码登录：提交 phone + code（未注册自动注册）
     *   2. 密码登录：提交 phone + password（必须已注册）
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();

        // 1. 校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }

        User user;
        boolean isNewUser = false;

        // 2. 分支：密码登录 or 验证码登录
        if (loginForm.getPassword() != null && !loginForm.getPassword().isEmpty()) {
            // —— 密码登录 ——
            user = query().eq("phone", phone).one();
            if (user == null) {
                return Result.fail("账号不存在，请先使用验证码登录注册");
            }
            String encodedPassword = user.getPassword();
            // 数据库密码为空或未设置（验证码注册的用户），提示用户先设置密码
            if (encodedPassword == null || encodedPassword.isEmpty()) {
                return Result.fail("该账号尚未设置密码，请使用验证码登录");
            }
            if (!PasswordEncoder.matches(encodedPassword, loginForm.getPassword())) {
                return Result.fail("手机号或密码错误");
            }
        } else {
            // —— 验证码登录 ——
            String code = loginForm.getCode();
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (cacheCode == null || code == null || !cacheCode.equals(code)) {
                return Result.fail("验证码无效或已过期");
            }
            // 查询用户，不存在则自动注册
            user = query().eq("phone", phone).one();
            if (user == null) {
                user = createUser(phone, loginForm.getAgreedPolicy());
                isNewUser = true;
            }
        }

        // 3. 记录协议同意状态
        Boolean agreedPolicy = user.getAgreedPolicy();
        if (agreedPolicy == null) {
            agreedPolicy = false;
        }
        if (Boolean.TRUE.equals(loginForm.getAgreedPolicy()) && !agreedPolicy) {
            update().set("agreed_policy", true).eq("id", user.getId()).update();
            agreedPolicy = true;
            user.setAgreedPolicy(true);
        }

        // 4. 生成token并保存用户信息到Redis
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        HashMap<String, String> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(userDTO.getId()));
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());
        userMap.put("agreedPolicy", String.valueOf(agreedPolicy));

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5. 验证码登录成功后删除验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        // 6. 返回登录结果
        LoginResultDTO result = new LoginResultDTO();
        result.setToken(token);
        result.setIsNewUser(isNewUser);
        result.setAgreedPolicy(agreedPolicy);
        return Result.ok(result);
    }

    /**
     * 创建新用户
     */
    private User createUser(String phone, Boolean agreedPolicy) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setAgreedPolicy(agreedPolicy != null ? agreedPolicy : false);
        save(user);
        return user;
    }

    @Override
    public Result agreePolicy() {
        Long userId = UserHolder.getUser().getId();
        update().set("agreed_policy", true).eq("id", userId).update();
        UserDTO userDTO = UserHolder.getUser();
        userDTO.setAgreedPolicy(true);
        return Result.ok();
    }

    @Override
    public Result logout(String token) {
        // 删除Redis中的登录token
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(tokenKey);
        // 清除ThreadLocal中的用户信息
        UserHolder.removeUser();
        log.info("用户已退出登录，token={}", token);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis  BITSET key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();


        //5. 获取截止至今日的签到记录  BITFIELD key GET uDay 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //6. 循环遍历
        int count = 0;
        Long num = result.get(0);
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else
                count++;
            //数字右移，抛弃最后一位
            num = num>>>1;
        }
        return Result.ok(count);
    }
}
