package com.blogsys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("articles")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    private String summary;

    private String cover;

    private Integer status;

    private Integer viewCount;

    private Integer likeCount;

    private Integer commentCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
