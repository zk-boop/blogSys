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
        String ext = extensionOf(file.getOriginalFilename());
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
                default -> saveOriginal(base, ext, bytes);
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
        String filename = base + "." + ext;
        if (image == null) {
            Files.write(uploadDir.resolve(filename), bytes);
            return Result.ok(urls(filename, null));
        }
        Files.write(uploadDir.resolve(filename), toOriginalBytes(image, ext));
        String thumb = base + "-thumb.jpg";
        Files.write(uploadDir.resolve(thumb), toJpeg(coverFit(image, COVER_THUMB_W, COVER_THUMB_H)));
        return Result.ok(urls(filename, thumb));
    }

    private Result<Map<String, String>> saveOriginal(String base, String ext, byte[] bytes) throws IOException {
        String filename = base + "." + ext;
        Files.write(uploadDir.resolve(filename), bytes);
        return Result.ok(urls(filename, null));
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] toOriginalBytes(BufferedImage image, String ext) throws IOException {
        if (image == null) {
            throw new BizException(400, "图片内容无法识别");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String format = "jpeg".equals(ext) ? "jpg" : ext;
        if (!ImageIO.write(image, format, out)) {
            throw new BizException(400, "图片内容无法识别");
        }
        return out.toByteArray();
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

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
