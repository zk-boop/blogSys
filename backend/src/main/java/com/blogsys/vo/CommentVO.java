package com.blogsys.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentVO {

    private Long id;
    private Long articleId;
    private String content;
    private LocalDateTime createdAt;
    private UserBriefVO user;
}
