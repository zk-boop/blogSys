package com.blogsys.visibility;

import com.blogsys.entity.Article;

/**
 * 一篇<b>已经通过可见性判断</b>的文章 —— 一个权限令牌,不是一个 DTO。
 *
 * <p>构造器是包私有的,模块之外无法凭空造出一个。拿到它就等于拿到了
 * 「这篇内容对这个 viewer 可见」这个事实。
 *
 * <p>需要「文章可见」作为前置条件的操作(读它的评论、评价它),应当要求这个令牌
 * 而不是要求一个 id:那样文章规则与评论规则就不可能对同一处产生分歧 ——
 * 而它们此前确实分歧过(详情页 404,评论区却 200)。
 */
public final class VisibleArticle {

    private final Article article;

    /** 包私有:只有可见性模块能在验证之后签发。 */
    VisibleArticle(Article article) {
        this.article = article;
    }

    public Article article() {
        return article;
    }

    public long id() {
        return article.getId();
    }

    @Override
    public String toString() {
        return "VisibleArticle(" + article.getId() + ")";
    }
}
