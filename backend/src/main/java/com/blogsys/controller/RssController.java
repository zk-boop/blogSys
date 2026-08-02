package com.blogsys.controller;

import com.blogsys.entity.Article;
import com.blogsys.entity.User;
import com.blogsys.mapper.ArticleMapper;
import com.blogsys.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class RssController {

    private static final String SITE_URL = "http://localhost:8080";
    private static final DateTimeFormatter RFC822 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z");

    private final ArticleMapper articleMapper;
    private final UserMapper userMapper;

    @GetMapping(value = "/rss", produces = MediaType.APPLICATION_RSS_XML_VALUE)
    public String rss() {
        List<Article> articles = articleMapper.selectList(
                Wrappers.<Article>lambdaQuery()
                        .eq(Article::getStatus, 1)
                        .orderByDesc(Article::getCreatedAt)
                        .last("LIMIT 20"));
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<rss version=\"2.0\"><channel>\n");
        xml.append("<title>blogSys</title>\n");
        xml.append("<link>").append(SITE_URL).append("</link>\n");
        xml.append("<description>blogSys 多人博客平台</description>\n");
        for (Article article : articles) {
            User author = article.getUserId() == null ? null : userMapper.selectById(article.getUserId());
            String authorName = author == null ? "unknown" : author.getNickname() != null ? author.getNickname() : author.getUsername();
            xml.append("<item>\n");
            xml.append("<title><![CDATA[").append(article.getTitle()).append("]]></title>\n");
            xml.append("<link>").append(SITE_URL).append("/article/").append(article.getId()).append("</link>\n");
            xml.append("<guid>").append(SITE_URL).append("/article/").append(article.getId()).append("</guid>\n");
            xml.append("<description><![CDATA[").append(article.getSummary() == null ? "" : article.getSummary())
                    .append("]]></description>\n");
            xml.append("<author><![CDATA[").append(authorName).append("]]></author>\n");
            if (article.getCreatedAt() != null) {
                xml.append("<pubDate>").append(RFC822.format(article.getCreatedAt())).append("</pubDate>\n");
            }
            xml.append("</item>\n");
        }
        xml.append("</channel></rss>");
        return xml.toString();
    }
}
