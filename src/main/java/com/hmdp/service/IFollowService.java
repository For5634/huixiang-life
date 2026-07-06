package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result isFollow(Long followUserId);

    Result follow(Long followUserId, Boolean isFellow);

    Result followCommons(Long id);

    /** 当前登录用户的关注数（关注了多少人） */
    Result followCount();

    /** 当前登录用户的粉丝数（被多少人关注） */
    Result fansCount();

    /** 当前登录用户关注的用户列表 */
    Result followList();

    /** 当前登录用户的粉丝列表 */
    Result fansList();
}
