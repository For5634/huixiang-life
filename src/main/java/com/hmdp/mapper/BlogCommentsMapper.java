package com.hmdp.mapper;

import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogCommentsMapper extends BaseMapper<BlogComments> {

    /**
     * 重新统计某篇博客的评论数并回写到 tb_blog.comments 字段
     */
    @Update("UPDATE tb_blog SET comments = #{count} WHERE id = #{blogId}")
    void updateCommentCount(Long blogId, int count);
}
