package com.blogsys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("article_tags")
public class ArticleTag {

    private Long articleId;

    private Long tagId;
}
