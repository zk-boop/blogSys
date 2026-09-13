package com.blogsys.visibility;

import com.blogsys.entity.Comment;

import java.util.List;

/**
 * 可见性模块的 interface。
 *
 * <p>它回答一个问题:<b>这份内容此刻对这个 viewer 可见吗?</b> 两种用法共用同一个答案 ——
 * 用在单份内容上得到「存在 / 不存在」,用在列表上得到过滤。
 *
 * <p>只有两个入口,因为「取当前 viewer」这件事不需要调用方参与
 * ({@link ViewerSource} 在模块内部完成),而模块的其余能力都挂在查询对象上。
 *
 * <p><b>没有「不过滤」的变体</b> —— 这就是查询拥有者的全部含义。调用方拿到
 * {@link ArticleQuery} 时谓词已经在了,忘不掉。
 */
public interface Visibility {

    /**
     * 文章查询。默认是<b>公开语料</b>:草稿被排除,连作者本人也看不到 ——
     * 需要草稿的地方显式 {@link ArticleQuery#includingOwnDrafts()} 登记意向。
     */
    ArticleQuery articles();

    /**
     * 一篇文章的评论,按时间正序。
     *
     * <p>参数是{@link VisibleArticle 权限令牌}而不是一个 id:拿不到令牌就调不了这个方法,
     * 于是「文章可见」与「它的评论可见」不可能在同一处分歧。
     *
     * <p>评论自己的规则只有一条:作者被封禁的评论对非管理员隐藏。
     * 评论没有独立的状态字段 —— 它可见 ⟺ 所属文章可见 ∧ 评论作者未被封禁。
     */
    List<Comment> commentsOf(VisibleArticle article);

    /**
     * 绑定到一个显式 viewer。
     *
     * <p>生产路径用不到它(它们要的就是「当前请求的 viewer」)。它存在是为了测试,
     * 以及将来跑在非请求线程上的后台任务。
     */
    Visibility as(Viewer viewer);
}
