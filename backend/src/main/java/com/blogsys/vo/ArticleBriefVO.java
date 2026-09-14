package com.blogsys.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一篇文章的最小投影:只够画一行链接。
 *
 * <p>刻意不复用 {@link ArticleListItemVO},尽管后者也带 {@code id} 与 {@code title}:
 * 列表项会顺带输出摘要、封面、作者、标签 —— 前端拿到就会开始依赖它们,而这个投影
 * 并不承诺这些字段(邻居接口只为「上一页/下一页」而存在)。形状更小也更难漂移。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleBriefVO {

    private Long id;
    private String title;
}
