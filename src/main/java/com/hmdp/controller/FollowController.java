package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    //判断当前用户是否关注了该博主,加载页面的时候就会发起请求
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    //实现取关/关注
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFellow) {
        return followService.follow(followUserId,isFellow);
    }

//    共同关注代码
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id){
        return followService.followCommons(id);
    }

    // 当前用户的关注数（关注了多少人）
    @GetMapping("/count")
    public Result followCount() {
        return followService.followCount();
    }

    // 当前用户的粉丝数（被多少人关注）
    @GetMapping("/fans/count")
    public Result fansCount() {
        return followService.fansCount();
    }

    // 当前用户关注的用户列表
    @GetMapping("/list")
    public Result followList() {
        return followService.followList();
    }

    // 当前用户的粉丝列表
    @GetMapping("/fans/list")
    public Result fansList() {
        return followService.fansList();
    }
}
