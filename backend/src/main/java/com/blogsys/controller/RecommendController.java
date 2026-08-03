package com.blogsys.controller;

import com.blogsys.common.Result;
import com.blogsys.service.RecommendService;
import com.blogsys.vo.ArticleListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    @GetMapping("/{id}/recommend")
    public Result<List<ArticleListItemVO>> recommend(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") int size) {
        return Result.ok(recommendService.recommend(id, size));
    }
}
