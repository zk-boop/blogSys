package com.blogsys.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArticleStatusTest {

    @Test
    void of_shouldMapKnownValues() {
        assertEquals(ArticleStatus.DRAFT, ArticleStatus.of(0));
        assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.of(1));
    }

    @Test
    @DisplayName("of 对未知值与 null 一律 fail closed 到 DRAFT(即对公众不可见)")
    void of_shouldFailClosed_whenValueIsUnknownOrNull() {
        assertEquals(ArticleStatus.DRAFT, ArticleStatus.of(2));
        assertEquals(ArticleStatus.DRAFT, ArticleStatus.of(-1));
        assertEquals(ArticleStatus.DRAFT, ArticleStatus.of(null));
    }

    @Test
    void getValue_shouldRoundTrip() {
        assertEquals(0, ArticleStatus.DRAFT.getValue());
        assertEquals(1, ArticleStatus.PUBLISHED.getValue());
    }

    @Test
    @DisplayName("枚举本身不接受解析之外的构造方式,所以取值集合固定为两个")
    void values_shouldBeExactlyTwo() {
        assertEquals(2, ArticleStatus.values().length);
        assertThrows(IllegalArgumentException.class, () -> ArticleStatus.valueOf("NOPE"));
    }
}
