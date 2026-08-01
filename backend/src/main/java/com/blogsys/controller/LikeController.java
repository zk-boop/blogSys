package com.blogsys.controller;

import com.blogsys.common.Result;
import com.blogsys.service.LikeService;
import com.blogsys.vo.LikeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/articles/{articleId}/like")
    public Result<LikeVO> toggle(@PathVariable Long articleId) {
        return Result.ok(likeService.toggle(articleId));
    }
}
