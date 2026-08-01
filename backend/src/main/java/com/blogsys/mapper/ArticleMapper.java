package com.blogsys.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blogsys.entity.Article;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    default int incrViewCount(Long id) {
        return update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .setSql("view_count = view_count + 1"));
    }

    default int incrLikeCount(Long id, int delta) {
        return update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .setSql("like_count = GREATEST(like_count + " + delta + ", 0)"));
    }

    default int incrCommentCount(Long id, int delta) {
        return update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .setSql("comment_count = GREATEST(comment_count + " + delta + ", 0)"));
    }
}
