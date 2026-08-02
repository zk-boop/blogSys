package com.blogsys.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ArticleListItemVO {

    private Long id;
    private String title;
    private String summary;
    private String cover;
    private Integer status;
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private LocalDateTime createdAt;
    private UserBriefVO author;
    private List<String> tags;
}
