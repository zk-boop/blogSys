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

    /**
     * 读取一个已经存在的文章状态。
     *
     * <p>与 {@link UserStatus#of(Integer)} 对称:{@code null} 与任何未定义的值都视为 {@link #DRAFT}。
     * 同样是 fail closed —— 状态不认识时应收敛到「只有作者和管理员看得见」,而不是「所有人都看得见」。
     *
     * <p>写入路径不走这里:{@code ArticleService.resolveStatus} 是从「是否草稿」的布尔值算出枚举的,
     * 不需要解析一个整数。
     */
    public static ArticleStatus of(Integer raw) {
        if (raw != null) {
            for (ArticleStatus status : values()) {
                if (status.value == raw) {
                    return status;
                }
            }
        }
        return DRAFT;
    }
}
