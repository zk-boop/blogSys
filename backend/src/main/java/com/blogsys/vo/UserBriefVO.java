package com.blogsys.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBriefVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
}
