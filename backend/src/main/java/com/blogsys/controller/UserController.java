package com.blogsys.controller;

import com.blogsys.common.Result;
import com.blogsys.service.AuthService;
import com.blogsys.service.UserService;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me());
    }

    @PutMapping("/me")
    public Result<UserVO> updateProfile(@RequestBody Map<String, String> body) {
        return Result.ok(userService.updateProfile(body.get("nickname"), body.get("avatar")));
    }
}
