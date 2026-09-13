package com.blogsys.controller;

import com.blogsys.entity.Article;
import com.blogsys.entity.User;
import com.blogsys.mapper.UserMapper;
import com.blogsys.visibility.Visibility;
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

    private final UserMapper userMapper;
    private final Visibility visibility;

    @GetMapping(value = "/rss", produces = MediaType.APPLICATION_RSS_XML_VALUE)
    public String rss() {
        // 迁移前这里只过滤 status,从不检查作者是否被封禁 ——
        // 被封禁作者的文章仍在公开订阅源里,而订阅源是公开面最大的出口:
        // 读者一旦订阅过,内容就留在别人的阅读器里,事后封禁追不回来。
        // 订阅源不发送 Authorization,所以 viewer 自然是匿名的 —— 这正是想要的结果。
        List<Article> articles = visibility.articles()
                .orderByDesc(Article::getCreatedAt)
                .list(20);
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
                String pubDate = RFC822.format(article.getCreatedAt().atZone(java.time.ZoneId.systemDefault()));
                xml.append("<pubDate>").append(pubDate).append("</pubDate>\n");
            }
            xml.append("</item>\n");
        }
        xml.append("</channel></rss>");
        return xml.toString();
    }
}
