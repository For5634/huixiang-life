package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Autowired
    private IUserInfoService userInfoService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFellow) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //不能关注自己
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己");
        }
        //判断是否关注
        if (isFellow) {
            //关注，则将信息保存到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean successed = save(follow);
            //则将数据也写入Redis
            if (successed) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                // 同步 tb_user_info 计数：当前用户 followee+1，被关注用户 fans+1
                incrUserInfoField(userId, "followee", 1);
                incrUserInfoField(followUserId, "fans", 1);
            }
        } else {
            //取关，则将数据从数据库中移除
//            delete from tb_follow where user_id = ?  and follow_user_id = ?
            boolean successed=remove(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id",followUserId));
            //则将数据也从Redis中移除
            if (successed){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
                // 同步 tb_user_info 计数：当前用户 followee-1，被关注用户 fans-1
                incrUserInfoField(userId, "followee", -1);
                incrUserInfoField(followUserId, "fans", -1);
            }
        }
        return Result.ok();
    }

    /**
     * 同步 tb_user_info 的 fans/followee 计数。
     * 若该用户的 UserInfo 行还不存在（老用户、首次进入），则自动建行并设置初始值，
     * 避免计数丢失。使用 setSql 确保 SQL 列名与 entity 字段映射正确。
     */
    private void incrUserInfoField(Long userId, String column, int delta) {
        if (userId == null || delta == 0) return;
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 第一次访问，先建一条空记录（fans/followee 默认 0）
            info = new UserInfo()
                    .setUserId(userId)
                    .setFans(0)
                    .setFollowee(0)
                    .setCredits(0)
                    .setLevel(false);
            userInfoService.save(info);
        }
        // 用 setSql 做原子加减，避免读-改-写竞态
        userInfoService.update()
                .setSql(column + " = " + column + " + " + delta)
                .eq("user_id", userId)
                .update();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取当前登录的userId
        Long userId = UserHolder.getUser().getId();
//        select count(*) from tb_follow where user_id = ?  and follow_user_id = ?
        Long count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        //只想知道有没有，所以用count(*)即可
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + id;
        String key2 = "follows:" + userId;
        //对当前用户和博主用户的关注列表取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            //无交集就返回个空集合
            return Result.ok(Collections.emptyList());
        }
        //将结果转为list
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //之后根据ids去查询共同关注的用户，封装成UserDto再返回
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result followCount() {
        Long userId = UserHolder.getUser().getId();
        // 当前用户关注了多少人（tb_follow 中 user_id = 当前用户）
        long count = query().eq("user_id", userId).count();
        return Result.ok(count);
    }

    @Override
    public Result fansCount() {
        Long userId = UserHolder.getUser().getId();
        // 当前用户有多少粉丝（tb_follow 中 follow_user_id = 当前用户）
        long count = query().eq("follow_user_id", userId).count();
        return Result.ok(count);
    }

    @Override
    public Result followList() {
        Long userId = UserHolder.getUser().getId();
        // tb_follow: user_id = 当前用户，表示"我关注了谁"
        List<Follow> follows = query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = follows.stream().map(Follow::getFollowUserId).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result fansList() {
        Long userId = UserHolder.getUser().getId();
        // tb_follow: follow_user_id = 当前用户，表示"谁关注了我"
        List<Follow> fans = query().eq("follow_user_id", userId).list();
        if (fans == null || fans.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = fans.stream().map(Follow::getUserId).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
