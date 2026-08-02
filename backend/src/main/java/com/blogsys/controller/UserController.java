package com.blogsys.controller;

import com.blogsys.common.PageResult;
import com.blogsys.common.Result;
import com.blogsys.dto.UpdateProfileRequest;
import com.blogsys.security.SecurityUtil;
import com.blogsys.service.ArticleService;
import com.blogsys.service.AuthService;
import com.blogsys.service.UserService;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;
    private final ArticleService articleService;

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me());
    }

    @PutMapping("/me")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(request));
    }

    @GetMapping("/me/articles")
    public Result<PageResult<ArticleListItemVO>> myArticles(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(articleService.pageByUser(page, size, SecurityUtil.currentUserId(), null));
    }

    @GetMapping("/me/favorites")
    public Result<PageResult<ArticleListItemVO>> myFavorites(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(articleService.favoritesPage(page, size, SecurityUtil.currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<UserVO> profile(@PathVariable Long id) {
        return Result.ok(userService.publicProfile(id));
    }

    @GetMapping("/{id}/articles")
    public Result<PageResult<ArticleListItemVO>> userArticles(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(articleService.pageByUser(page, size, id, com.blogsys.common.ArticleStatus.PUBLISHED.getValue()));
    }
}
