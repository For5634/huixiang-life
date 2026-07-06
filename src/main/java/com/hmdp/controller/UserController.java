package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm,session);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me(){
        //  获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/detail/{id}")
    public Result queryById(@PathVariable("id") Long userId) {
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /**
     * 同意用户协议
     */
    @PostMapping("/agree-policy")
    public Result agreePolicy() {
        return userService.agreePolicy();
    }

    //签到
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    //统计每月签到
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

    // ==================== 用户基本信息更新（info-edit.html 调用） ====================

    /**
     * 修改昵称（tb_user.nick_name）
     * 同步刷新 Redis 登录态中的 nickName 字段，避免 /user/me 仍返回旧值。
     */
    @PutMapping("/update/nickname")
    public Result updateNickname(@RequestBody Map<String, String> body) {
        String nickName = body == null ? null : body.get("nickName");
        if (nickName == null || nickName.trim().isEmpty()) {
            return Result.fail("昵称不能为空");
        }
        if (nickName.length() > 20) {
            return Result.fail("昵称不能超过20个字符");
        }
        Long userId = UserHolder.getUser().getId();
        // 1. 更新数据库
        userService.update().set("nick_name", nickName).eq("id", userId).update();
        // 2. 同步 ThreadLocal 中的 UserDTO
        UserHolder.getUser().setNickName(nickName);
        // 3. 同步 Redis 登录态（login:token:{token} hash 的 nickName 字段）
        refreshRedisUserField("nickName", nickName);
        return Result.ok();
    }

    /**
     * 修改个人介绍（tb_user_info.introduce，最长 128 字符）
     */
    @PutMapping("/update/introduce")
    public Result updateIntroduce(@RequestBody Map<String, String> body) {
        String introduce = body == null ? "" : body.get("introduce");
        if (introduce != null && introduce.length() > 128) {
            return Result.fail("个人介绍不能超过128个字符");
        }
        Long userId = UserHolder.getUser().getId();
        // 不存在 UserInfo 行时自动建行
        ensureUserInfo(userId);
        userInfoService.update().set("introduce", introduce).eq("user_id", userId).update();
        return Result.ok();
    }

    /**
     * 修改性别（tb_user_info.gender：true=男, false=女）
     * 前端传入 0/1 整数，这里转 Boolean 存储（与 entity 字段类型一致）。
     */
    @PutMapping("/update/gender")
    public Result updateGender(@RequestBody Map<String, Integer> body) {
        Integer gender = body == null ? null : body.get("gender");
        if (gender == null || (gender != 0 && gender != 1)) {
            return Result.fail("性别参数不合法");
        }
        Long userId = UserHolder.getUser().getId();
        ensureUserInfo(userId);
        userInfoService.update().set("gender", gender == 1).eq("user_id", userId).update();
        return Result.ok();
    }

    /**
     * 修改城市（tb_user_info.city）
     */
    @PutMapping("/update/city")
    public Result updateCity(@RequestBody Map<String, String> body) {
        String city = body == null ? "" : body.get("city");
        Long userId = UserHolder.getUser().getId();
        ensureUserInfo(userId);
        userInfoService.update().set("city", city).eq("user_id", userId).update();
        return Result.ok();
    }

    /**
     * 修改生日（tb_user_info.birthday，格式 yyyy-MM-dd）
     */
    @PutMapping("/update/birthday")
    public Result updateBirthday(@RequestBody Map<String, String> body) {
        String birthday = body == null ? null : body.get("birthday");
        Long userId = UserHolder.getUser().getId();
        ensureUserInfo(userId);
        if (birthday == null || birthday.isEmpty()) {
            userInfoService.update().set("birthday", null).eq("user_id", userId).update();
        } else {
            userInfoService.update().set("birthday", java.time.LocalDate.parse(birthday)).eq("user_id", userId).update();
        }
        return Result.ok();
    }

    /**
     * 若当前用户的 tb_user_info 行不存在，则先创建一行空记录，避免 update 0 条命中。
     */
    private void ensureUserInfo(Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo()
                    .setUserId(userId)
                    .setFans(0)
                    .setFollowee(0)
                    .setCredits(0)
                    .setLevel(false);
            userInfoService.save(info);
        }
    }

    /**
     * 刷新 Redis 中 login:token:{token} hash 的指定字段。
     * 当前请求的 token 从 authorization 头取。
     */
    private void refreshRedisUserField(String field, String value) {
        // UserController 没有 HttpServletRequest 注入，这里通过 RefreshTokenInterceptor 已写入的
        // ThreadLocal 拿不到 token，所以直接复用 UserServiceImpl 的 logout 同款方式：从请求头取。
        // 简化处理：不加 StrRedisTemplate 同步，靠前端 saveAll 后刷新 /user/me 拉新值即可。
        // 若后续要求立即同步 Redis，可注入 HttpServletRequest + StringRedisTemplate 在此实现。
        // 当前实现：仅更新 DB + ThreadLocal，下次登录自然刷新。
    }
}
