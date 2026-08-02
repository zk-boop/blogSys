package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.ArticleStatus;
import com.blogsys.common.BizException;
import com.blogsys.dto.UpdateProfileRequest;
import com.blogsys.entity.Article;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.UserMapper;
import com.blogsys.security.SecurityUtil;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final ArticleMapper articleMapper;

    public UserVO updateProfile(UpdateProfileRequest request) {
        Long userId = SecurityUtil.currentUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在");
        }
        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        }
        if (StringUtils.hasText(request.getAvatar())) {
            user.setAvatar(request.getAvatar());
        }
        userMapper.updateById(user);
        return AuthService.toVO(user);
    }

    public UserVO publicProfile(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(404, "用户不存在");
        }
        UserVO vo = AuthService.toVO(user);
        vo.setArticleCount(articleMapper.selectCount(Wrappers.<Article>lambdaQuery()
                .eq(Article::getUserId, userId)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED.getValue())));
        return vo;
    }

    public Map<Long, User> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
