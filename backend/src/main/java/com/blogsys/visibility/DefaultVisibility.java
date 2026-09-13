package com.blogsys.visibility;

import com.blogsys.mapper.ArticleMapper;
import org.springframework.stereotype.Service;

/**
 * {@link Visibility} 的默认实现。
 *
 * <p>viewer 在<b>构造查询对象的那一刻</b>被解析并冻结进查询对象里。所以一个
 * {@link ArticleQuery} 是单 viewer、短命的:不要把它存进字段、静态变量或缓存。
 *
 * <p>刻意不在这里做任何写操作、计数或缓存 —— 这个模块只回答「看得见吗」。
 */
@Service
public class DefaultVisibility implements Visibility {

    private final ArticleMapper articleMapper;
    private final ViewerSource viewerSource;

    public DefaultVisibility(ArticleMapper articleMapper, ViewerSource viewerSource) {
        this.articleMapper = articleMapper;
        this.viewerSource = viewerSource;
    }

    @Override
    public ArticleQuery articles() {
        return new ArticleQuery(viewerSource.current(), articleMapper);
    }

    @Override
    public Visibility as(Viewer viewer) {
        return new DefaultVisibility(articleMapper, ViewerSource.fixed(viewer));
    }
}
