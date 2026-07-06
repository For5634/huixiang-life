package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 用户评价（探店评论）控制器
 *
 *   - 对某篇笔记发表评价
 *   - 查询某篇笔记的评价列表
 *   - 评价发布时同步回写 tb_blog.comments 计数
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 获取某篇笔记的评论列表
     */
    @GetMapping("/of/{blogId}")
    public Result commentsOfBlog(@PathVariable("blogId") Long blogId) {
        return Result.ok(blogCommentsService.query()
                .eq("blog_id", blogId)
                .orderByDesc("create_time")
                .list());
    }

    /**
     * 发表评论
     */
    @PostMapping
    public Result addComment(@RequestBody BlogComments comment) {
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        comment.setCreateTime(LocalDateTime.now());
        comment.setStatus(false); // 正常
        blogCommentsService.save(comment);
        // 同步回写博客评论计数
        blogCommentsService.updateBlogCommentCount(comment.getBlogId());
        return Result.ok("评论成功");
    }
}
