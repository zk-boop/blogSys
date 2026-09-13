package com.blogsys.visibility;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.blogsys.entity.Article;
import com.blogsys.entity.User;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把可见性模块要依赖的三个 MyBatis-Plus 机制钉死在测试里,而不是靠记忆或库文档。
 *
 * <p>版本敏感:本仓库锁定 {@code mybatis-plus.version = 3.5.7}(见 pom.xml)。
 * 任何一条若在升级后失效,这里会先红,而不是等到线上多出一批看不见的内容泄漏。
 *
 * <p>不启动 Spring:{@code LambdaQueryWrapper} 解析方法引用需要 {@code TableInfo},
 * 用 {@code TableInfoHelper} 手工初始化一次即可,不需要数据库、不需要容器。
 */
class WrapperMechanicsTest {

    /** 绑定参数渲染后的样子:status = #{ew.paramNameValuePairs.MPGENVALn} */
    private static final Pattern BOUND_PARAM = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.MPGENVAL\\d+}");

    /** 一个被括号整体包住的 OR 组 —— 这是「调用方改不动模块谓词」的形式化表述。 */
    private static final Pattern PARENTHESIZED_OR_GROUP =
            Pattern.compile("\\(\\s*status = #\\{[^}]+} OR user_id = #\\{[^}]+}\\s*\\)");

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @Test
    @DisplayName("apply 的 {0} 是绑定参数,不是把值拼进 SQL 文本")
    void apply_shouldBindValues_ratherThanSpliceThem() {
        LambdaQueryWrapper<Article> wrapper = Wrappers.<Article>lambdaQuery()
                .apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})", 0);

        String sql = wrapper.getSqlSegment();

        assertTrue(BOUND_PARAM.matcher(sql).find(), "应渲染成绑定参数,实际片段: " + sql);
        assertFalse(sql.contains("status = 0"), "值不该出现在 SQL 文本里,实际片段: " + sql);
        assertEquals(0, wrapper.getParamNameValuePairs().values().iterator().next(),
                "值应进入参数表,实际: " + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("参数表是惰性填充的:必须先渲染 SQL 段才读得到 —— 测试里踩过一次")
    void paramTable_isPopulatedLazily_bySqlSegmentRendering() {
        LambdaQueryWrapper<Article> wrapper = Wrappers.<Article>lambdaQuery()
                .apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})", 0);

        assertTrue(wrapper.getParamNameValuePairs().isEmpty(),
                "只 add 不渲染时参数表还是空的 —— 断言参数前必须先调 getSqlSegment()");

        wrapper.getSqlSegment();

        assertFalse(wrapper.getParamNameValuePairs().isEmpty(),
                "渲染之后参数才出现");
    }

    @Test
    @DisplayName("inSql 无法绑定参数 —— 这正是可见性模块不用它的原因")
    void inSql_cannotBindValues() {
        LambdaQueryWrapper<Article> spliced = Wrappers.<Article>lambdaQuery()
                .inSql(Article::getUserId, "SELECT id FROM users WHERE status = 0");
        String splicedSql = spliced.getSqlSegment();

        assertTrue(splicedSql.contains("status = 0"), "inSql 会把字符串原样拼进 SQL,实际: " + splicedSql);
        assertFalse(BOUND_PARAM.matcher(splicedSql).find(), "inSql 不产生绑定参数,实际: " + splicedSql);
        assertTrue(spliced.getParamNameValuePairs().isEmpty(),
                "inSql 的参数表恒为空,实际: " + spliced.getParamNameValuePairs());
    }

    @Test
    @DisplayName("and(Consumer) 会把嵌套条件括起来,所以同级的条件改不动它的含义")
    void and_shouldParenthesizeTheNestedCondition() {
        LambdaQueryWrapper<Article> wrapper = Wrappers.<Article>lambdaQuery()
                .apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})", 0)
                .and(w -> w.eq(Article::getStatus, 1).or().eq(Article::getUserId, 7));

        assertSqlContainsParenthesizedOrGroup(wrapper);
    }

    @Test
    @DisplayName("模块自身的多分支谓词同样必须经 and(Consumer) 落地")
    void modulePredicate_shouldAlsoBeWrappedInParentheses() {
        // 正常 viewer 的谓词形状:(已发布 OR 是我自己的) AND 作者未封禁
        LambdaQueryWrapper<Article> wrapper = Wrappers.<Article>lambdaQuery()
                .and(w -> w.eq(Article::getStatus, 1).or().eq(Article::getUserId, 7))
                .apply(true, "user_id IN (SELECT id FROM users WHERE status = {0})", 0);

        assertSqlContainsParenthesizedOrGroup(wrapper);
    }

    @Test
    @DisplayName("管理员:谓词完全省略,而不是恒真 —— 省略才拿得到执行计划复用")
    void adminPredicate_shouldBeOmittedEntirely() {
        // 调用方自己的条件(这里 status = 1)在两种 viewer 下完全一样。
        // 注意:MP 连 eq 的值也会绑定,所以「有绑定参数」不等于「有可见性谓词」,
        // 判断标准只能是「有没有出现封禁子查询 / 有没有出现那个 OR 组」。
        LambdaQueryWrapper<Article> admin = Wrappers.<Article>lambdaQuery()
                .eq(Article::getStatus, 1);
        String adminSql = admin.getSqlSegment();

        assertFalse(adminSql.contains("FROM users"),
                "管理员路径不该出现封禁子查询,实际: " + adminSql);
        assertFalse(PARENTHESIZED_OR_GROUP.matcher(adminSql).find(),
                "管理员路径不该出现 OR 组,实际: " + adminSql);
        assertEquals("(status = #{ew.paramNameValuePairs.MPGENVAL1})", adminSql,
                "管理员拿到的应当是「只有调用方条件」的那条 SQL,实际: " + adminSql);
    }

    private static void assertSqlContainsParenthesizedOrGroup(LambdaQueryWrapper<Article> wrapper) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("OR"), "前提:条件里确实含 OR,实际片段: " + sql);
        assertTrue(PARENTHESIZED_OR_GROUP.matcher(sql).find(),
                "OR 组必须整体被括号包住,否则同级的 AND 会改变它的意义,实际片段: " + sql);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(7L) || params.containsValue(7),
                "viewer 的 id 应当作为绑定参数出现,实际参数表: " + params);
    }
}
