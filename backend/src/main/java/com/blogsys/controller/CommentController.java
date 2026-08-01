package com.blogsys.controller;

import com.blogsys.common.Result;
import com.blogsys.dto.CommentRequest;
import com.blogsys.service.CommentService;
import com.blogsys.vo.CommentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/articles/{articleId}/comments")
    public Result<List<CommentVO>> list(@PathVariable Long articleId) {
        return Result.ok(commentService.listByArticle(articleId));
    }

    @PostMapping("/articles/{articleId}/comments")
    public Result<CommentVO> create(@PathVariable Long articleId,
                                    @Valid @RequestBody CommentRequest request) {
        return Result.ok(commentService.create(articleId, request));
    }

    @DeleteMapping("/comments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.ok();
    }
}
