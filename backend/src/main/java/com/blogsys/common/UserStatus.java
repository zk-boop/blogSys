package com.blogsys.common;

/**
 * 用户状态,对应 {@code users.status}。
 *
 * <p>存在的意义是消灭裸数字。这里有一个真实的数值陷阱:
 * {@code ArticleStatus.PUBLISHED.getValue() == 1},而「已封禁」也是 {@code 1} ——
 * 同一个数字,相反的语义。封禁这条规则原本在全库 7 个文件里写作
 * {@code Integer.valueOf(1).equals(user.getStatus())},读代码时无法一眼分辨它在问哪件事。
 *
 * <p>两个取值入口语义不同,不要混用:
 * <ul>
 *   <li>{@link #of(Integer)} —— 读一个<b>已经存在的事实</b>(数据库列)。未知值按 {@code BANNED} 处理,
 *       即 fail closed:状态不认识时应当隐藏内容,而不是把内容放出去。</li>
 *   <li>{@link #require(Integer)} —— 校验一个<b>不可信输入</b>(管理端请求体)。未知值直接拒绝,
 *       而不是默默把用户改成一个谁也没定义过的状态。</li>
 * </ul>
 */
public enum UserStatus {

    ACTIVE(0),
    BANNED(1);

    private final int value;

    UserStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public boolean isBanned() {
        return this == BANNED;
    }

    /**
     * 读取一个已经存在的用户状态。
     *
     * <p>{@code null} 与任何未定义的值都视为 {@link #BANNED}。这是刻意的:
     * 「不认识」应当收敛到「看不见」,否则将来多出一个状态值就是一次静默的内容泄漏。
     */
    public static UserStatus of(Integer raw) {
        if (raw != null) {
            for (UserStatus status : values()) {
                if (status.value == raw) {
                    return status;
                }
            }
        }
        return BANNED;
    }

    /**
     * 校验一个不可信的用户状态输入。
     *
     * <p>{@code null} 与任何未定义的值都抛 400。用于写入路径 —— 那里需要的是「拒绝」,
     * 不是「猜一个安全的默认值」。
     */
    public static UserStatus require(Integer raw) {
        if (raw == null) {
            throw new BizException("用户状态不能为空");
        }
        for (UserStatus status : values()) {
            if (status.value == raw) {
                return status;
            }
        }
        throw new BizException("不支持的用户状态: " + raw);
    }
}
