package com.blogsys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ArticleRequest {

    @Size(max = 200, message = "标题最长200个字符")
    private String title;

    private String content;

    @Size(max = 500, message = "摘要最长500个字符")
    private String summary;

    @Size(max = 255, message = "封面 URL 最长255个字符")
    private String cover;

    private Boolean draft;

    private List<String> tagNames;
}
