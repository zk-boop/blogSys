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
        return Result.ok(articleService.pageByUser(page, size, SecurityUtil.currentUserId()));
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
        // publicProfile 是这里的前置条件:用户不存在或已被封禁时抛 404。
        //
        // 迁移前这一行是「调一次、把返回值丢掉」—— 那次调用的副作用就是「主页过滤」的
        // 全部实现,而读代码的人一眼看不出它在做校验。现在用它的结果:意图写在代码里,
        // 而且后面那次查询也确实建立在这个已确认可见的用户之上。
        UserVO profile = userService.publicProfile(id);
        return Result.ok(articleService.pageByUser(page, size, profile.getId()));
    }
}
