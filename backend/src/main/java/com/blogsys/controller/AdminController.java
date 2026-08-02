package com.blogsys.controller;

import com.blogsys.common.PageResult;
import com.blogsys.common.Result;
import com.blogsys.service.AdminService;
import com.blogsys.service.ArticleService;
import com.blogsys.service.CommentService;
import com.blogsys.service.TagService;
import com.blogsys.vo.AdminCommentVO;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.TagVO;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ArticleService articleService;
    private final CommentService commentService;
    private final TagService tagService;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(adminService.stats());
    }

    @GetMapping("/users")
    public Result<PageResult<UserVO>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.users(page, size, keyword));
    }

    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        adminService.updateUserStatus(id, body.get("status"));
        return Result.ok();
    }

    @PutMapping("/users/{id}/role")
    public Result<Void> updateUserRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        adminService.updateUserRole(id, body.get("role"));
        return Result.ok();
    }

    @GetMapping("/articles")
    public Result<PageResult<ArticleListItemVO>> articles(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId) {
        return Result.ok(articleService.adminPage(page, size, keyword, status, userId));
    }

    @DeleteMapping("/articles/{id}")
    public Result<Void> deleteArticle(@PathVariable Long id) {
        articleService.delete(id);
        return Result.ok();
    }

    @GetMapping("/comments")
    public Result<PageResult<AdminCommentVO>> comments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return Result.ok(commentService.adminPage(page, size, keyword));
    }

    @DeleteMapping("/comments/{id}")
    public Result<Void> deleteComment(@PathVariable Long id) {
        commentService.delete(id);
        return Result.ok();
    }

    @GetMapping("/tags")
    public Result<List<TagVO>> tags() {
        return Result.ok(tagService.listAll());
    }

    @PutMapping("/tags/{id}")
    public Result<Void> renameTag(@PathVariable Long id, @RequestBody Map<String, String> body) {
        tagService.rename(id, body.get("name"));
        return Result.ok();
    }

    @DeleteMapping("/tags/{id}")
    public Result<Void> deleteTag(@PathVariable Long id) {
        tagService.remove(id);
        return Result.ok();
    }
}
