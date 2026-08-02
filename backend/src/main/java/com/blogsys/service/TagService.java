package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.ArticleTag;
import com.blogsys.entity.Tag;
import com.blogsys.mapper.ArticleTagMapper;
import com.blogsys.mapper.TagMapper;
import com.blogsys.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final ArticleTagMapper articleTagMapper;

    public List<TagVO> listAll() {
        List<Tag> tags = tagMapper.selectList(Wrappers.<Tag>lambdaQuery().orderByAsc(Tag::getName));
        if (tags.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = articleTagMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(ArticleTag::getTagId, Collectors.counting()));
        return tags.stream()
                .map(tag -> new TagVO(tag.getId(), tag.getName(), counts.getOrDefault(tag.getId(), 0L)))
                .toList();
    }
}
