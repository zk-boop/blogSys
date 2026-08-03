package com.blogsys.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ArticleListItemVO {

    private Long id;
    private String title;
    private String summary;
    private String cover;
    private String coverThumb;
    private Integer status;
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private LocalDateTime createdAt;
    private UserBriefVO author;
    private List<String> tags;

    /** 仅推荐接口使用,普通列表不输出。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double recommendScore;
}
