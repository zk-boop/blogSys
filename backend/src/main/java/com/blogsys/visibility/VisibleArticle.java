package com.blogsys.visibility;

import com.blogsys.entity.Article;

/**
 * 一篇<b>已经通过可见性判断</b>的文章,连同做出该判断时的 viewer —— 一个权限令牌,不是一个 DTO。
 *
 * <p>构造器是包私有的,模块之外无法凭空造出一个。拿到它就等于拿到了
 * 「这篇内容对这个 viewer 可见」这个事实。
 *
 * <p>令牌<b>带上 viewer</b>,而不是让下游再读一次环境:这样「读这篇的评论」用的必然是同一次
 * 判断的 viewer,文章规则与评论规则不可能对同一处产生分歧 ——
 * 而它们此前确实分歧过(详情页 404,评论区却 200)。
 */
public final class VisibleArticle {

    private final Article article;
    private final Viewer viewer;

    /** 包私有:只有可见性模块能在验证之后签发。 */
    VisibleArticle(Article article, Viewer viewer) {
        this.article = article;
        this.viewer = viewer;
    }

    public Article article() {
        return article;
    }

    public long id() {
        return article.getId();
    }

    /** 做出这次判断时的 viewer。包私有:下游不需要知道它是谁,只需要沿用同一次判断。 */
    Viewer viewer() {
        return viewer;
    }

    @Override
    public String toString() {
        return "VisibleArticle(" + article.getId() + " for " + viewer + ")";
    }
}
