package com.blogsys.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一篇文章在列表里的左右邻居。
 *
 * <p><b>两侧缺失是 null,但键始终存在</b> —— 所以这里不用
 * {@code @JsonInclude(NON_NULL)}(本仓库其它 VO 用它来藏起「这个功能没有的字段」,
 * 而这里的 `null` 正是答案本身:第一篇没有上一篇,这个事实必须能被表达)。
 * 前端按 {@code neighbors.prev?.id} 判断,键不见了就会变成 undefined 与 null 两种写法。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleNeighborsVO {

    /** 比当前文章更早发布的最近一篇。 */
    private ArticleBriefVO prev;

    /** 比当前文章更晚发布的最近一篇。 */
    private ArticleBriefVO next;
}
