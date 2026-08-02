package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.BizException;
import com.blogsys.common.PageResult;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.CommentMapper;
import com.blogsys.mapper.LikeMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.mapper.UserMapper;
import com.blogsys.security.SecurityUtil;
import com.blogsys.vo.ArticleListItemVO;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final ArticleMapper articleMapper;
    private final CommentMapper commentMapper;
    private final TagMapper tagMapper;
    private final LikeMapper likeMapper;
    private final ArticleService articleService;

    public Map<String, Object> stats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> stats = new HashMap<>();
        stats.put("userCount", userMapper.selectCount(null));
        stats.put("articleCount", articleMapper.selectCount(null));
        stats.put("commentCount", commentMapper.selectCount(null));
        stats.put("tagCount", tagMapper.selectCount(null));
        stats.put("likeCount", likeMapper.selectCount(null));
        stats.put("todayUsers", userMapper.selectCount(
                Wrappers.<User>lambdaQuery().ge(User::getCreatedAt, todayStart)));
        stats.put("todayArticles", articleMapper.selectCount(
                Wrappers.<com.blogsys.entity.Article>lambdaQuery()
                        .ge(com.blogsys.entity.Article::getCreatedAt, todayStart)));
        stats.put("hotArticles", articleService.hot(5));
        return stats;
    }

    public PageResult<UserVO> users(long page, long size, String keyword) {
        Page<User> result = userMapper.selectPage(new Page<>(page, size),
                Wrappers.<User>lambdaQuery()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(User::getUsername, keyword)
                                .or().like(User::getNickname, keyword))
                        .orderByDesc(User::getCreatedAt));
        List<UserVO> records = result.getRecords().stream().map(user -> {
            UserVO vo = AuthService.toVO(user);
            vo.setArticleCount(articleMapper.selectCount(
                    Wrappers.<com.blogsys.entity.Article>lambdaQuery()
                            .eq(com.blogsys.entity.Article::getUserId, user.getId())));
            return vo;
        }).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    public void updateUserStatus(Long id, Integer status) {
        if (id.equals(SecurityUtil.currentUserId())) {
            throw new BizException("不能封禁或解封自己");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        if (Integer.valueOf(1).equals(status) && "ADMIN".equals(user.getRole())) {
            throw new BizException("不能封禁管理员账号");
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    public void updateUserRole(Long id, String role) {
        if (id.equals(SecurityUtil.currentUserId())) {
            throw new BizException("不能修改自己的角色");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        user.setRole("ADMIN".equals(role) ? "ADMIN" : "USER");
        userMapper.updateById(user);
    }
}
