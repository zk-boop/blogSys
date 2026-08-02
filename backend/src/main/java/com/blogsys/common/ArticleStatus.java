package com.blogsys.common;

public enum ArticleStatus {

    DRAFT(0),
    PUBLISHED(1);

    private final int value;

    ArticleStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
