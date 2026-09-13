package com.blogsys.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LIKE 转义的规则。
 *
 * <p>配着 {@code ArticleServiceTest} 里那两条「公开搜索与后台搜索绑定的是同一个模式串」看 ——
 * 规则本身很简单,出错的是**各写各的**:这条规则此前只长在 {@code ArticleService} 的一个
 * 私有方法里,于是公开搜索转义了、后台三处没有(D12)。
 */
class LikePatternTest {

    @Test
    @DisplayName("百分号与下划线被转义 —— 否则搜「%」命中全部内容,搜「_」命中任意单字符")
    void shouldEscapeWildcards() {
        assertEquals("100\\%", LikePattern.of("100%"));
        assertEquals("a\\_b", LikePattern.of("a_b"));
        assertEquals("\\%\\_", LikePattern.of("%_"));
    }

    @Test
    @DisplayName("反斜杠必须先转,否则它会把新加的转义符再转一遍")
    void shouldEscapeBackslashFirst() {
        // 输入两个字符:「\」与「%」
        // 期望四个字符:「\」「\」「\」「%」—— 前两个是转义后的原反斜杠,后两个是转义后的百分号
        assertEquals("\\\\\\%", LikePattern.of("\\%"));
    }

    @Test
    @DisplayName("普通关键词原样返回")
    void shouldLeaveOrdinaryKeywordsAlone() {
        assertEquals("java 微服务", LikePattern.of("java 微服务"));
        assertEquals("", LikePattern.of(""));
    }
}
