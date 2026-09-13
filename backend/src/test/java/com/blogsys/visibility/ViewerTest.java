package com.blogsys.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerTest {

    @Test
    @DisplayName("匿名是正常取值:不是 null,不是异常,也不是缺失")
    void anonymous_shouldBeAValueNotAnError() {
        Viewer viewer = Viewer.anonymous();

        assertTrue(viewer.isAnonymous());
        assertTrue(viewer.id().isEmpty());
        assertFalse(viewer.isAdmin());
        assertFalse(viewer.owns(1L));
        assertFalse(viewer.owns(null));
    }

    @Test
    void of_shouldCarryIdentity() {
        Viewer viewer = Viewer.of(7L, false);

        assertFalse(viewer.isAnonymous());
        assertEquals(7L, viewer.id().orElseThrow());
        assertFalse(viewer.isAdmin());
    }

    @Test
    void owns_shouldCompareByIdAndRejectNull() {
        Viewer viewer = Viewer.of(7L, false);

        assertTrue(viewer.owns(7L));
        assertFalse(viewer.owns(8L));
        assertFalse(viewer.owns(null), "null 不是一个主体,不能因为 viewer 恰好也是 null 就认为相等");
    }

    @Test
    void admin_shouldBeIndependentOfOwnership() {
        Viewer admin = Viewer.of(3L, true);

        assertTrue(admin.isAdmin());
        assertFalse(admin.owns(7L), "管理员对别人的内容并不因此成为作者");
    }

    @Test
    @DisplayName("不变量不靠工厂方法守:直接构造 record 也拦得住 null userId")
    void directConstruction_shouldStillEnforceTheInvariant() {
        assertThrows(IllegalArgumentException.class, () -> Viewer.of(null, false));
        assertThrows(IllegalArgumentException.class, () -> new Viewer.Member(null, false));
        assertThrows(IllegalArgumentException.class, () -> new Viewer.Member(null, true));
    }

    @Test
    void equals_shouldCompareByValue() {
        assertEquals(Viewer.of(7L, false), Viewer.of(7L, false));
        assertNotEquals(Viewer.of(7L, false), Viewer.of(7L, true));
        assertNotEquals(Viewer.of(7L, false), Viewer.of(8L, false));
        assertNotEquals(Viewer.of(7L, false), Viewer.anonymous());
        assertEquals(Viewer.anonymous(), Viewer.anonymous());
    }

    @Test
    void toString_shouldBeReadableInTestFailures() {
        assertEquals("Viewer.anonymous", Viewer.anonymous().toString());
        assertEquals("Viewer(7)", Viewer.of(7L, false).toString());
        assertEquals("Viewer(7,admin)", Viewer.of(7L, true).toString());
    }
}
