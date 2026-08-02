package com.blogsys.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.common.BizException;
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

    public void rename(Long id, String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("标签名不能为空");
        }
        String trimmed = name.trim();
        Tag existing = tagMapper.selectOne(Wrappers.<Tag>lambdaQuery().eq(Tag::getName, trimmed));
        if (existing != null && !existing.getId().equals(id)) {
            throw new BizException("标签名已存在");
        }
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BizException(404, "标签不存在");
        }
        tag.setName(trimmed);
        tagMapper.updateById(tag);
    }

    public void remove(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BizException(404, "标签不存在");
        }
        tagMapper.deleteById(id);
        articleTagMapper.delete(Wrappers.<ArticleTag>lambdaQuery().eq(ArticleTag::getTagId, id));
    }
}
