package com.blogsys.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleDetailVO extends ArticleListItemVO {

    private String content;
    private boolean liked;
}
