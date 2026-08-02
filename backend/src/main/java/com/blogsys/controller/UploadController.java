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
            Set.of("jpg", "jpeg", "png", "gif", "webp", "svg");

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
            throw new BizException("仅支持 jpg/png/gif/webp/svg 图片");
        }
        try {
            Files.createDirectories(uploadDir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(uploadDir.resolve(filename).toFile());
            return Result.ok(Map.of("url", "/uploads/" + filename));
        } catch (IOException e) {
            log.error("Upload failed", e);
            throw new BizException(500, "图片保存失败");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
