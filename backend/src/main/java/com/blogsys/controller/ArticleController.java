package com.blogsys.controller;

import com.blogsys.common.PageResult;
import com.blogsys.common.Result;
import com.blogsys.dto.ArticleRequest;
import com.blogsys.service.ArticleService;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleListItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @GetMapping
    public Result<PageResult<ArticleListItemVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String keyword) {
        return Result.ok(articleService.page(page, size, tagId, keyword));
    }

    @GetMapping("/{id}")
    public Result<ArticleDetailVO> detail(@PathVariable Long id) {
        return Result.ok(articleService.detail(id));
    }

    @GetMapping("/{id}/edit")
    public Result<ArticleDetailVO> editDetail(@PathVariable Long id) {
        return Result.ok(articleService.editDetail(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody ArticleRequest request) {
        return Result.ok(articleService.create(request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleRequest request) {
        articleService.update(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.ok();
    }
}
