package com.blogsys.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentVO {

    private Long id;
    private Long articleId;
    private String articleTitle;
    private String content;
    private LocalDateTime createdAt;
    private UserBriefVO user;
}
