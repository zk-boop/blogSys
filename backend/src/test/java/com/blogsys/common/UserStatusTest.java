package com.blogsys.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserStatusTest {

    @Test
    void of_shouldMapKnownValues() {
        assertEquals(UserStatus.ACTIVE, UserStatus.of(0));
        assertEquals(UserStatus.BANNED, UserStatus.of(1));
    }

    @Test
    @DisplayName("of 对未知值与 null 一律 fail closed 到 BANNED")
    void of_shouldFailClosed_whenValueIsUnknownOrNull() {
        assertEquals(UserStatus.BANNED, UserStatus.of(2));
        assertEquals(UserStatus.BANNED, UserStatus.of(-1));
        assertEquals(UserStatus.BANNED, UserStatus.of(99));
        assertEquals(UserStatus.BANNED, UserStatus.of(null));
    }

    @Test
    @DisplayName("require 对未知值与 null 一律拒绝,而不是猜一个默认值")
    void require_shouldReject_whenValueIsUnknownOrNull() {
        assertEquals(UserStatus.ACTIVE, UserStatus.require(0));
        assertEquals(UserStatus.BANNED, UserStatus.require(1));
        assertEquals(400, assertThrows(BizException.class, () -> UserStatus.require(2)).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> UserStatus.require(null)).getCode());
    }

    @Test
    void getValue_shouldRoundTrip() {
        assertEquals(0, UserStatus.ACTIVE.getValue());
        assertEquals(1, UserStatus.BANNED.getValue());
    }

    @Test
    void isBanned_shouldBeTrueOnlyForBanned() {
        assertTrue(UserStatus.BANNED.isBanned());
        assertFalse(UserStatus.ACTIVE.isBanned());
    }

    @Test
    @DisplayName("数值陷阱:已发布与已封禁共用数字 1,靠类型区分而不是靠值")
    void publishedAndBanned_shareTheSameNumber() {
        assertEquals(ArticleStatus.PUBLISHED.getValue(), UserStatus.BANNED.getValue(),
                "数值相同是刻意记录下来的陷阱:一处是文章可读,一处是作者不可见");
        assertTrue(UserStatus.of(1).isBanned(), "数字 1 在用户语境下必须解读为已封禁");
        assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.of(1), "同一个数字在文章语境下解读为已发布");
    }

    @Test
    @DisplayName("fail closed 的后果:未定义状态值不会再被当成正常用户")
    void unknownStatus_isNotTreatedAsActive() {
        // status = 2 恰好是今天 updateUserStatus 能写进库、而所有读取点都会当成正常用户的情形
        assertTrue(UserStatus.of(2).isBanned());
        assertFalse(UserStatus.of(0).isBanned());
    }
}
