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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    private final Path uploadDir;

    public UploadController(@Value("${blogsys.upload.dir:uploads}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostMapping
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
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
            byte[] head = file.getBytes();
            if (!matchesMagic(head, ext)) {
                throw new BizException("文件内容与扩展名不符");
            }
            Files.createDirectories(uploadDir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(uploadDir.resolve(filename).toFile());
            return Result.ok(Map.of("url", "/uploads/" + filename));
        } catch (IOException e) {
            log.error("Upload failed", e);
            throw new BizException(500, "图片保存失败");
        }
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
