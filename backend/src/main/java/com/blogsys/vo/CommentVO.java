package com.blogsys.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentVO {

    private Long id;
    private Long articleId;
    private Long parentId;
    private UserBriefVO replyTo;
    private String content;
    private LocalDateTime createdAt;
    private UserBriefVO user;
    private List<CommentVO> replies;
}
