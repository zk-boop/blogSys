package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
     * 把一个<b>以 article_id 引用文章</b>的外层查询,收窄到该 viewer 可见的文章上。
     *
     * <p>用于语料不是 {@code articles} 本身的场景(收藏列表、标签计数):那些查询的排序与分页
     * 属于各自的概念,模块不该把它们整个吃掉,但「哪些文章可见」必须由模块说了算。
     *
     * <p><b>约定:</b>外层表的文章外键列名是 {@code article_id}。
     * 本 schema 里四张引用文章的表(article_tags / comments / likes / favorites)都遵循它 ——
     * 这也是唯一一处列名以字面量出现的地方,因为 MyBatis-Plus 的 {@code apply} / {@code exists}
     * 不接受列引用,只能拼接;值仍然全部走绑定。有测试钉住这一点。
     *
     * <p><b>诚实说明这是本模块唯一可被忘记的一环。</b>它是个 wrapper 组合器,调用方不调它
     * 就会拿到未过滤的行。之所以仍然这么做:把模块变成「所有读路径的拥有者」会让它
     * interface 巨大而 leverage 很薄。真实的防线是调用点只有两处,且都被测试覆盖。
     */
    <R> LambdaQueryWrapper<R> restrictToVisibleArticles(LambdaQueryWrapper<R> wrapper);

    /**
     * 绑定到一个显式 viewer。
     *
     * <p>生产路径用不到它(它们要的就是「当前请求的 viewer」)。它存在是为了测试,
     * 以及将来跑在非请求线程上的后台任务。
     */
    Visibility as(Viewer viewer);
}
