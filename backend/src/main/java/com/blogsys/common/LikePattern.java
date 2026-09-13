package com.blogsys.common;

/**
 * LIKE 模式串:把用户输入里的通配符转义掉。
 *
 * <p>不转义的后果不是注入(值始终走参数绑定),而是**通配符被当成字面量**:
 * 搜「%」会命中全部内容,搜「_」会命中任意单字符 —— 而用户以为自己搜的就是这两个符号。
 *
 * <p>这条规则此前只长在 {@code ArticleService} 的一个私有方法里,于是出现了一种很难察觉的
 * 不一致:**公开搜索转义了,后台三处没有**(D12)。同一个搜索框,前台与后台对 `%` `_`
 * 的行为不同 —— 而没有一处是「错的」,只是各写各的。
 *
 * <p>现在四个调用点共用这一个实现。它依赖 MySQL 的默认转义符 {@code \}
 * (`LIKE` 的默认 {@code ESCAPE` 就是它),所以不需要额外的 ESCAPE 子句。
 */
public final class LikePattern {

    private LikePattern() {
    }

    /**
     * 转义 {@code \}、{@code %}、{@code _}。
     *
     * <p>反斜杠必须先转,否则它会把后面新加的转义符再转一遍。
     */
    public static String of(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
