package com.xianyusmart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地素材存储。Docker 环境中默认位于 /app/data，因此随 app-data 数据卷持久化。
 */
@Service
public class LocalMediaStorageService {

    private final Path storageDirectory;

    public LocalMediaStorageService(@Value("${app.media.storage-dir:/app/data/media}") String storageDir) {
        this.storageDirectory = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    public String store(byte[] data, String originalFilename) throws IOException {
        Files.createDirectories(storageDirectory);
        String extension = extensionOf(originalFilename);
        String filename = UUID.randomUUID() + extension;
        Files.write(storageDirectory.resolve(filename), data);
        return "/media/" + filename;
    }

    public byte[] read(String mediaUrl) throws IOException {
        if (!isLocalMediaUrl(mediaUrl)) {
            throw new IllegalArgumentException("不是本地素材地址");
        }
        String filename = mediaUrl.substring("/media/".length());
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("本地素材地址无效");
        }
        Path file = storageDirectory.resolve(filename).normalize();
        if (!file.startsWith(storageDirectory) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("本地素材不存在或已被删除");
        }
        return Files.readAllBytes(file);
    }

    public boolean isLocalMediaUrl(String value) {
        return value != null && value.startsWith("/media/");
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }
}
