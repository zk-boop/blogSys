package com.blogsys.controller;

import com.blogsys.common.BizException;
import com.blogsys.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final long MAX_SIZE = 5 * 1024 * 1024L;
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final int AVATAR_SIZE = 256;
    private static final int COVER_W = 1280;
    private static final int COVER_H = 720;
    private static final int COVER_THUMB_W = 640;
    private static final int COVER_THUMB_H = 360;

    private final Path uploadDir;

    public UploadController(@Value("${blogsys.upload.dir:uploads}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue = "content") String type) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("图片大小不能超过 5MB");
        }
        String ext = extensionOf(file);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException("仅支持 jpg/png/gif/webp 图片");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!matchesMagic(bytes, ext)) {
                throw new BizException("文件内容与扩展名不符");
            }
            Files.createDirectories(uploadDir);
            String base = UUID.randomUUID().toString().replace("-", "");
            BufferedImage image = readImage(bytes);

            return switch (type) {
                case "avatar" -> saveAvatar(base, image);
                case "cover" -> saveCover(base, ext, bytes, image);
                case "content" -> saveOriginal(base, ext, bytes, image);
                // 此前是 default -> saveOriginal:type 是个未校验的魔法字符串,
                // 拼错一个字母就静默按「正文图片」存下去,调用方还以为自己传的是封面。
                default -> throw new BizException("未知的上传类型: " + type);
            };
        } catch (IOException e) {
            log.error("Upload failed", e);
            throw new BizException(500, "图片保存失败");
        }
    }

    private Result<Map<String, String>> saveAvatar(String base, BufferedImage image) throws IOException {
        if (image == null) {
            throw new BizException(400, "图片内容无法识别,头像请使用 jpg/png/gif");
        }
        int size = Math.min(image.getWidth(), image.getHeight());
        BufferedImage square = image.getSubimage(
                (image.getWidth() - size) / 2, (image.getHeight() - size) / 2, size, size);
        byte[] jpeg = toJpeg(scaleTo(square, AVATAR_SIZE, AVATAR_SIZE));
        String filename = base + "-avatar.jpg";
        Files.write(uploadDir.resolve(filename), jpeg);
        return Result.ok(urls(filename, null));
    }

    private Result<Map<String, String>> saveCover(String base, String ext, byte[] bytes, BufferedImage image)
            throws IOException {
        if (image == null) {
            String filename = base + "." + ext;
            Files.write(uploadDir.resolve(filename), bytes);
            return Result.ok(urls(filename, null));
        }
        String filename = base + ".jpg";
        Files.write(uploadDir.resolve(filename), toJpeg(coverFit(image, COVER_W, COVER_H)));
        return Result.ok(urls(filename, writeThumb(base, image)));
    }

    /**
     * 原图 + 缩略图。
     *
     * <p><b>为什么「正文里的图片」也要写缩略图:</b>它不是给正文用的,而是因为**读取方是从
     * 文件名反推缩略图的**(见 {@code ArticleListItems.coverThumb}:把 {@code /uploads/X.ext}
     * 换成 {@code /uploads/X-thumb.jpg}),而封面是一个自由文本 URL 字段 ——
     * 作者完全可以把正文图片的 URL 粘进封面。那时列表页会去请求一个从未写入的文件,得到 404。
     *
     * <p>这条约定此前只对 {@code type=cover} 成立,于是「粘贴正文图片 URL 当封面」必然裂开。
     * 现在凡是能解码的图,一律在同一个目录里留下它的缩略图 —— 命名约定成为**事实**,
     * 而不再是一个只在一半情况下成立的猜测。
     */
    private Result<Map<String, String>> saveOriginal(String base, String ext, byte[] bytes, BufferedImage image)
            throws IOException {
        String filename = base + "." + ext;
        Files.write(uploadDir.resolve(filename), bytes);
        return Result.ok(urls(filename, writeThumb(base, image)));
    }

    /**
     * 写缩略图,返回它的文件名;图片解码不了时返回 {@code null} —— 那时确实没有缩略图,
     * 调用方也不该声称有(上传响应里的 {@code thumbUrl} 因此缺席)。
     */
    private String writeThumb(String base, BufferedImage image) throws IOException {
        if (image == null) {
            return null;
        }
        String thumb = base + "-thumb.jpg";
        Files.write(uploadDir.resolve(thumb), toJpeg(coverFit(image, COVER_THUMB_W, COVER_THUMB_H)));
        return thumb;
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] toJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private BufferedImage coverFit(BufferedImage src, int tw, int th) {
        double srcRatio = (double) src.getWidth() / src.getHeight();
        double targetRatio = (double) tw / th;
        int cw;
        int ch;
        if (srcRatio > targetRatio) {
            ch = src.getHeight();
            cw = (int) (ch * targetRatio);
        } else {
            cw = src.getWidth();
            ch = (int) (cw / targetRatio);
        }
        BufferedImage cropped = src.getSubimage(
                (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
        return scaleTo(cropped, tw, th);
    }

    private Map<String, String> urls(String url, String thumbUrl) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("url", "/uploads/" + url);
        if (thumbUrl != null) {
            result.put("thumbUrl", "/uploads/" + thumbUrl);
        }
        return result;
    }

    private boolean matchesMagic(byte[] head, String ext) {
        if (head.length < 12) {
            return false;
        }
        return switch (ext) {
            case "png" -> (head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47;
            case "jpg", "jpeg" -> (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "gif" -> head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x38;
            case "webp" -> head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                    && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50;
            default -> false;
        };
    }

    private String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                default -> "";
            };
        }
        return "";
    }
}
