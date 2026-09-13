package com.blogsys.controller;

import com.blogsys.common.BizException;
import com.blogsys.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传资产的命名约定。它不是实现细节,而是**跨 module 的契约**:
 * 写方产出文件名,读方({@code ArticleListItems.coverThumb})从文件名反推缩略图。
 *
 * <p>这条约定此前只对 {@code type=cover} 成立 —— {@code saveOriginal} 不写缩略图,
 * 而封面是一个自由文本 URL 字段,作者可以把正文图片的 URL 粘进去。那时列表页会去请求一个
 * 从未写入的 {@code -thumb.jpg},得到 404。
 *
 * <p>所以这里的断言不是「有没有写文件」,而是「**读取方反推出来的那个路径是否存在**」。
 */
class UploadControllerTest {

    @TempDir
    Path uploadDir;

    private UploadController controller() {
        return new UploadController(uploadDir.toString());
    }

    private byte[] png(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    /** 读取方从 url 反推缩略图的那条规则(与 {@code ArticleListItems.coverThumb} 一致)。 */
    private String thumbOf(String url) {
        return url.replaceAll("\\.\\w+$", "-thumb.jpg");
    }

    private Path onDisk(String url) {
        return uploadDir.resolve(url.replace("/uploads/", ""));
    }

    @Test
    @DisplayName("正文图片(type=content)也写缩略图 —— 作者会把它粘进封面")
    void contentUpload_shouldAlsoWriteTheThumb() throws Exception {
        Result<Map<String, String>> result = controller().upload(
                file("pic.png", "image/png", png(40, 30)), "content");

        String url = result.getData().get("url");
        assertTrue(Files.exists(onDisk(url)), "原图要在");
        assertTrue(Files.exists(onDisk(thumbOf(url))),
                "读取方照着 url 反推出的缩略图必须真的存在,否则列表页 404。反推结果: " + thumbOf(url));
    }

    @Test
    @DisplayName("封面上传:原图与缩略图都在,响应里也带 thumbUrl")
    void coverUpload_shouldWriteBothAndReportTheThumb() throws Exception {
        Result<Map<String, String>> result = controller().upload(
                file("cover.png", "image/png", png(1600, 900)), "cover");

        String url = result.getData().get("url");
        assertEquals(thumbOf(url), result.getData().get("thumbUrl"),
                "响应说出的文件名要与读取方反推出来的一致");
        assertTrue(Files.exists(onDisk(url)) && Files.exists(onDisk(thumbOf(url))));
    }

    @Test
    @DisplayName("解不开的图不声称有缩略图 —— 那时确实没有")
    void undecodableImage_shouldNotClaimAThumb() throws Exception {
        byte[] broken = new byte[64];
        broken[0] = (byte) 0x89;
        broken[1] = 0x50;
        broken[2] = 0x4E;
        broken[3] = 0x47;

        Result<Map<String, String>> result = controller().upload(
                file("x.png", "image/png", broken), "cover");

        assertTrue(Files.exists(onDisk(result.getData().get("url"))), "原图照旧落盘");
        assertFalse(result.getData().containsKey("thumbUrl"), "没有缩略图就不该报一个");
    }

    @Test
    @DisplayName("拼错的上传类型报错,而不是静默按正文图片存下去")
    void unknownType_shouldBeRejected() throws Exception {
        BizException e = assertThrows(BizException.class, () ->
                controller().upload(file("pic.png", "image/png", png(40, 30)), "covers"));

        assertTrue(e.getMessage().contains("未知的上传类型"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("头像不产出 -thumb.jpg —— 它有自己的命名,读取方也不会去反推")
    void avatarUpload_shouldNotWriteAThumb() throws Exception {
        Result<Map<String, String>> result = controller().upload(
                file("me.png", "image/png", png(300, 300)), "avatar");

        assertTrue(result.getData().get("url").endsWith("-avatar.jpg"));
        assertFalse(result.getData().containsKey("thumbUrl"));
    }
}
