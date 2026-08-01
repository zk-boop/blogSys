package com.blogsys.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @Size(max = 20, message = "昵称最长20个字符")
    private String nickname;

    @Size(max = 255, message = "头像 URL 最长255个字符")
    private String avatar;
}
