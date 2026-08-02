package com.blogsys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommentRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论最长1000个字符")
    private String content;

    private Long parentId;
}
