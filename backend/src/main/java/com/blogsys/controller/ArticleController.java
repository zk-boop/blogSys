package com.blogsys.controller;

import com.blogsys.common.PageResult;
import com.blogsys.common.Result;
import com.blogsys.dto.ArticleRequest;
import com.blogsys.service.ArticleService;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.FavoriteVO;
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

import java.util.List;

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

    @GetMapping("/hot")
    public Result<List<ArticleListItemVO>> hot(@RequestParam(defaultValue = "5") int size) {
        return Result.ok(articleService.hot(size));
    }

    @GetMapping("/{id}")
    public Result<ArticleDetailVO> detail(@PathVariable Long id) {
        // 先记一次浏览,再读详情 —— 响应里的 viewCount 因此与旧行为逐字一致(含本次)。
        //
        // 顺序与位置都是有意的:「有人打开了页面」是 HTTP 层知道、而 AI 工具路径不知道的事。
        // 把它放在这里,detail 就成了一次纯读,AI 再怎么查也不会改动任何数据。
        articleService.recordView(id);
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

    @PostMapping("/{id}/favorite")
    public Result<FavoriteVO> toggleFavorite(@PathVariable Long id) {
        return Result.ok(articleService.toggleFavorite(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        articleService.delete(id);
        return Result.ok();
    }
}
