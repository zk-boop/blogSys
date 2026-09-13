package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blogsys.common.BizException;
import com.blogsys.common.PageResult;
import com.blogsys.common.UserStatus;
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

    /**
     * 全站统计。
     *
     * <p><b>这里的计数刻意不过可见性:</b>它们是「平台上有多少东西」的<b>汇报</b>事实,
     * 不是「这个 viewer 看得见多少」的可见性事实。把 articleCount 改成可见性计数,
     * 反而会让管理员失去信息(看不见「有 12 篇、其中 5 篇是草稿」这个更有用的数字)。
     *
     * <p>但同一张 map 里混着两种语义确实容易被误读,所以写明:
     * <ul>
     *   <li>{@code *Count} / {@code today*} —— <b>原始汇报值</b>,包含草稿与封禁作者的内容</li>
     *   <li>{@code hotArticles} —— <b>内容列表</b>,因此必须过可见性(它是要展示的文章)</li>
     * </ul>
     * 于是「articleCount 说 12、hotArticles 只给 5 篇」不是矛盾,而是两种语义各说各的话。
     */
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
            // 注意:这里的 articleCount 是「全部文章数(含草稿)」;
            // UserService.publicProfile 里同名字段是「已发布文章数」。同一个字段名两种含义
            // 是既有的命名冲突,改动它会动到前端契约,所以本次只在两处写明,不静默改名。
            vo.setArticleCount(articleMapper.selectCount(
                    Wrappers.<com.blogsys.entity.Article>lambdaQuery()
                            .eq(com.blogsys.entity.Article::getUserId, user.getId())));
            return vo;
        }).toList();
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    public void updateUserStatus(Long id, Integer status) {
        // 先校验输入。迁移前这里把请求体里的 status 未校验直接写库:
        // 管理端传一个 2 就能让数据库里出现一个谁也没定义过的状态值,
        // 而当时所有读取点都会把它当成「正常用户」。
        // 现在未知值与 null 一律 400 拒绝,写入路径不接受「猜一个默认值」。
        UserStatus target = UserStatus.require(status);

        if (id.equals(SecurityUtil.currentUserId())) {
            throw new BizException("不能封禁或解封自己");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        if (target.isBanned() && "ADMIN".equals(user.getRole())) {
            throw new BizException("不能封禁管理员账号");
        }
        user.setStatus(target.getValue());
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
