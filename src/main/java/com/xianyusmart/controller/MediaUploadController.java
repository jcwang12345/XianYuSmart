package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.ImageUploadService;
import com.xianyusmart.service.LocalMediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/** 用于素材库和发布页面的统一图片/视频上传入口。 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaUploadController {
    private static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 500L * 1024 * 1024;
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "webm", "m4v", "ogg");

    private final ImageUploadService imageUploadService;
    private final LocalMediaStorageService localMediaStorageService;

    @PostMapping("/upload")
    public ResultObject<MediaUploadResponse> upload(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(required = false) Long accountId) {
        try {
            if (file.isEmpty()) return ResultObject.failed("文件不能为空");
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
            boolean image = contentType.startsWith("image/");
            boolean video = contentType.startsWith("video/") || VIDEO_EXTENSIONS.contains(extensionOf(file.getOriginalFilename()));
            if (!image && !video) return ResultObject.failed("仅支持图片或视频文件");
            long maxSize = image ? MAX_IMAGE_SIZE : MAX_VIDEO_SIZE;
            if (file.getSize() > maxSize) {
                return ResultObject.failed((image ? "图片" : "视频") + "不能超过" + (maxSize / 1024 / 1024) + "MB");
            }
            String filename = file.getOriginalFilename() == null ? (image ? "image.jpg" : "video.mp4") : file.getOriginalFilename();
            if (image && accountId != null) {
                ResultObject<String> uploaded = imageUploadService.uploadImage(accountId, file.getBytes(), filename);
                if (uploaded.getCode() == 200 && uploaded.getData() != null) {
                    boolean local = localMediaStorageService.isLocalMediaUrl(uploaded.getData());
                    return ResultObject.success(new MediaUploadResponse(uploaded.getData(), "IMAGE", local ? "LOCAL" : "GOOFISH"),
                            local ? "闲鱼图床暂不可用，已保存到本地素材库" : "已上传到闲鱼图片服务");
                }
                return ResultObject.failed(uploaded.getMsg());
            }
            String url = localMediaStorageService.store(file.getBytes(), filename);
            return ResultObject.success(new MediaUploadResponse(url, image ? "IMAGE" : "VIDEO", "LOCAL"),
                    image ? "图片已保存到本地素材库" : "视频已保存到本地素材库");
        } catch (Exception e) {
            log.error("保存本地素材失败", e);
            return ResultObject.failed("上传失败: " + e.getMessage());
        }
    }

    public record MediaUploadResponse(String url, String mediaType, String storage) {}

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
