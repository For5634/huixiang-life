package com.hmdp.service.impl;

import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Override
    @Transactional
    public void updateBlogCommentCount(Long blogId) {
        // 重新统计当前 blog 的评论总数，回写到 tb_blog.comments
        Long count = query().eq("blog_id", blogId).count();
        // 使用 MyBatis-Plus update setSql 更新
        // 注意：这里用 mapper 直接 SQL 而非 service 的 update 链式
        baseMapper.updateCommentCount(blogId, count.intValue());
    }
}
