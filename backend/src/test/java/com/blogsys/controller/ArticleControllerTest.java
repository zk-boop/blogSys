package com.blogsys.controller;

import com.blogsys.service.ArticleService;
import com.blogsys.vo.ArticleDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
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
}
