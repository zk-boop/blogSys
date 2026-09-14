package com.blogsys.controller;

import com.blogsys.common.Result;
import com.blogsys.service.ArticleService;
import com.blogsys.vo.ArticleBriefVO;
import com.blogsys.vo.ArticleDetailVO;
import com.blogsys.vo.ArticleNeighborsVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/articles/{id}} 的接线:先记一次浏览,再读详情。
 *
 * <p>顺序与位置都是有意的,它承着三件事:
 * <ol>
 *   <li>响应里的 {@code viewCount} 含本次 —— 先写后读,与拆分前的行为逐字一致</li>
 *   <li>不可见/不存在的文章在<b>写之前</b>就被 404 挡住,不会留下一次没人看过的浏览</li>
 *   <li>「有人打开了页面」留在 HTTP 层,于是 {@code detail} 成了纯读 ——
 *       AI 工具路径同样调 {@code detail},却因此不可能改动任何数据</li>
 * </ol>
 *
 * <p>{@code /{id}/neighbors} 的接线在同一层,断言的是同一件事的另一面:<b>它什么都不记</b>。
 * 浏览量的显式记法(而不是藏在读方法里)正是让这条断言写得出来的原因。
 */
@ExtendWith(MockitoExtension.class)
class ArticleControllerTest {

    @Mock
    private ArticleService articleService;

    @Test
    @DisplayName("先 recordView 再 detail —— 顺序反了,响应里的浏览量就会少一次")
    void detail_shouldRecordTheViewBeforeReading() {
        ArticleDetailVO vo = new ArticleDetailVO();
        when(articleService.detail(3L)).thenReturn(vo);

        ArticleDetailVO returned = new ArticleController(articleService).detail(3L).getData();

        assertSame(vo, returned);
        InOrder order = inOrder(articleService);
        order.verify(articleService).recordView(3L);
        order.verify(articleService).detail(3L);
    }

    @Test
    @DisplayName("邻居接口是一次纯读:不该顺手记一次浏览 —— 浏览量正是热门榜的排序依据")
    void neighbors_shouldNotRecordAView() {
        when(articleService.neighbors(18L)).thenReturn(new ArticleNeighborsVO());

        Result<ArticleNeighborsVO> result = new ArticleController(articleService).neighbors(18L);

        assertEquals(200, result.getCode());
        assertEquals("ok", result.getMessage());
        verify(articleService, never()).recordView(any());
    }

    @Test
    @DisplayName("邻居的响应形状:两侧的键始终存在,每项只有 id 与 title")
    void neighbors_shouldKeepNullSides_andOnlyTwoFieldsPerSide() throws Exception {
        when(articleService.neighbors(18L))
                .thenReturn(new ArticleNeighborsVO(new ArticleBriefVO(17L, "上一篇"), null));

        String json = new ObjectMapper().writeValueAsString(
                new ArticleController(articleService).neighbors(18L).getData());

        // 前端按 neighbors.prev?.id 判断,所以「键在、值为 null」与「每项只有两个字段」
        // 都是契约的一部分:给 VO 加 @JsonInclude(NON_NULL) 或改用 ArticleListItemVO
        // 都会破坏它 —— 而这个断言是唯一会发现那种改动的测试。
        assertEquals("{\"prev\":{\"id\":17,\"title\":\"上一篇\"},\"next\":null}", json);
    }
}
