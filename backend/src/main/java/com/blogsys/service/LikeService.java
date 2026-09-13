package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.Article;
import com.blogsys.entity.Like;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.security.SecurityUtil;
import com.blogsys.visibility.Visibility;
import com.blogsys.vo.LikeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeMapper likeMapper;
    private final ArticleMapper articleMapper;
    private final Visibility visibility;

    @Transactional
    public LikeVO toggle(Long articleId) {
        // 不可见 = 不存在,写操作也一样(见 ADR-0001)。
        // 迁移前这里只查 status,于是「详情页 404 的文章」照样能被点赞。
        visibility.articles().require(articleId);
        Long userId = SecurityUtil.currentUserId();
        Like existing = likeMapper.selectOne(Wrappers.<Like>lambdaQuery()
                .eq(Like::getArticleId, articleId)
                .eq(Like::getUserId, userId));
        boolean liked;
        if (existing != null) {
            likeMapper.deleteById(existing.getId());
            articleMapper.incrLikeCount(articleId, -1);
            liked = false;
        } else {
            Like like = new Like();
            like.setArticleId(articleId);
            like.setUserId(userId);
            likeMapper.insert(like);
            articleMapper.incrLikeCount(articleId, 1);
            liked = true;
        }
        Article fresh = articleMapper.selectById(articleId);
        return new LikeVO(liked, fresh.getLikeCount());
    }
}
