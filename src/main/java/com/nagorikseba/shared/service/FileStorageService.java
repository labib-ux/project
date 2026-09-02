package com.nagorikseba.shared.service;

import com.nagorikseba.shared.config.StorageProperties;
import com.nagorikseba.shared.exception.FileStorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StorageProperties storageProperties;
    private Path uploadRoot;
    private Path tempRoot;

    @PostConstruct
    void initializeStorage() {
        uploadRoot = Path.of(storageProperties.getUploadDir()).toAbsolutePath().normalize();
        tempRoot = Path.of(storageProperties.getUploadDir(), "temp").toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
            Files.createDirectories(tempRoot);
        } catch (IOException exception) {
            throw new FileStorageException("Could not create the upload directories", exception);
        }
    }

    public StoredFile storeIssuePhoto(MultipartFile file) {
        ImageFormat imageFormat = detectImageFormat(file);

        LocalDate today = LocalDate.now();
        String relativePath = "%d/%02d/%s.%s".formatted(
                today.getYear(), today.getMonthValue(), UUID.randomUUID(), imageFormat.extension());
        Path destination = uploadRoot.resolve(relativePath).normalize();
        if (!destination.startsWith(uploadRoot)) {
            throw new FileStorageException("Invalid file location");
        }

        try {
            Files.createDirectories(destination.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredFile("/uploads/" + relativePath.replace('\\', '/'), destination);
        } catch (IOException exception) {
            throw new FileStorageException("Could not save the uploaded image", exception);
        }
    }

    public void storeTemp(String storageKey, Path tempPath) throws IOException {
        Path tempDestination = tempRoot.resolve(storageKey).normalize();
        if (!tempDestination.startsWith(tempRoot)) {
            throw new FileStorageException("Invalid temp file location");
        }
        Files.createDirectories(tempDestination.getParent());
        Files.move(tempPath, tempDestination, StandardCopyOption.REPLACE_EXISTING);
    }

    public void moveFromTemp(String storageKey) throws IOException {
        Path tempSource = tempRoot.resolve(storageKey).normalize();
        if (!tempSource.startsWith(tempRoot)) {
            throw new FileStorageException("Invalid temp file location");
        }
        if (!Files.exists(tempSource)) {
            return;
        }
        Path finalDestination = uploadRoot.resolve(storageKey).normalize();
        if (!finalDestination.startsWith(uploadRoot)) {
            throw new FileStorageException("Invalid final file location");
        }
        Files.createDirectories(finalDestination.getParent());
        Files.move(tempSource, finalDestination, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<Path> listTempFilesOlderThan(Instant cutoff) throws IOException {
        List<Path> result = new java.util.ArrayList<>();
        if (!Files.exists(tempRoot)) {
            return result;
        }
        try (var stream = Files.walk(tempRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                            return lastModified.isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(result::add);
        }
        return result;
    }

    public void deleteAll(List<StoredFile> storedFiles) {
        storedFiles.forEach(file -> {
            try {
                Files.deleteIfExists(file.path());
            } catch (IOException ignored) {
                // The database transaction is the source of truth. Orphaned development files are safe to clean later.
            }
        });
    }

    private ImageFormat detectImageFormat(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileStorageException("Each photo must contain image data");
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(12);
            if (isJpeg(header)) {
                return new ImageFormat("jpg");
            }
            if (isPng(header)) {
                return new ImageFormat("png");
            }
            if (isWebp(header)) {
                return new ImageFormat("webp");
            }
        } catch (IOException exception) {
            throw new FileStorageException("Could not read the uploaded image", exception);
        }
        throw new FileStorageException("Only valid JPEG, PNG, and WebP images are allowed");
    }

    private boolean isJpeg(byte[] header) {
        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header) {
        return header.length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A;
    }

    private boolean isWebp(byte[] header) {
        return header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
    }

    public record StoredFile(String publicUrl, Path path) {
    }

    private record ImageFormat(String extension) {
    }
}