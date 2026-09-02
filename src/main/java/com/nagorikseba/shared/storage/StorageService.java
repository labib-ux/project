package com.nagorikseba.shared.storage;

import com.nagorikseba.shared.exception.FileStorageException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public interface StorageService {
    void storeTemp(String storageKey, Path tempPath) throws IOException;
    void moveFromTemp(String storageKey) throws IOException;
    List<Path> listTempFilesOlderThan(Instant cutoff) throws IOException;
}