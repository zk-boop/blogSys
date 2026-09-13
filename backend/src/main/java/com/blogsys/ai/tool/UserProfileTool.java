package com.blogsys.ai.tool;

import com.blogsys.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.blogsys.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserProfileTool extends AbstractTool {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "getUserProfile";
    }

    @Override
    public String description() {
        return "按用户 ID 获取用户公开资料:昵称、简介、注册时间、文章数";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> userId = new LinkedHashMap<>();
        userId.put("type", "integer");
        userId.put("description", "用户 ID");
        schema.put("userId", userId);
        return schema;
    }

    @Override
    public List<String> requiredParameters() {
        return List.of("userId");
    }

    @Override
    public String execute(Map<String, Object> args) {
        long userId = requiredLong(args, "userId");
        UserVO vo = userService.publicProfile(userId);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", vo.getId());
        node.put("username", vo.getUsername());
        node.put("nickname", vo.getNickname());
        node.put("avatar", vo.getAvatar());
        node.put("createdAt", String.valueOf(vo.getCreatedAt()));
        if (vo.getArticleCount() != null) {
            node.put("articleCount", vo.getArticleCount());
        }
        return json(objectMapper, node);
    }
}
