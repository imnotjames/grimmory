package org.booklore.service.book;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.KoboSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.kobo.CbxConversionService;
import org.booklore.service.kobo.KepubConversionService;
import org.booklore.util.FileUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@AllArgsConstructor
@Service
public class BookDownloadService {

    private static final Pattern NON_ASCII_PATTERN = Pattern.compile("[^\\x00-\\x7F]");

    private final BookRepository bookRepository;
    private final BookFileRepository bookFileRepository;
    private final KepubConversionService kepubConversionService;
    private final CbxConversionService cbxConversionService;
    private final AppSettingService appSettingService;

    public ResponseEntity<Resource> downloadBook(Long bookId) {
        try {
            BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

            BookFileEntity primaryFile = bookEntity.getPrimaryBookFile();
            if (primaryFile == null) {
                throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
            }
            Path libraryRoot = Path.of(bookEntity.getLibraryPath().getPath());
            Path file = FileUtils.requirePathWithinBase(primaryFile.getFullFilePath(), libraryRoot);

            if (!Files.exists(file)) {
                throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
            }

            // Handle folder-based audiobooks - create ZIP
            if (primaryFile.isFolderBased() && Files.isDirectory(file)) {
                return downloadFolderAsZip(file, primaryFile.getFileName());
            }

            return downloadSingleFile(file);
        } catch (Exception e) {
            log.error("Failed to download book {}: {}", bookId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        }
    }

    public ResponseEntity<Resource> downloadBookFile(Long bookId, Long fileId) {
        try {
            BookFileEntity bookFileEntity = bookFileRepository.findByIdWithBookAndLibraryPath(fileId)
                    .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(fileId));

            // Verify the file belongs to the specified book
            if (!bookFileEntity.getBook().getId().equals(bookId)) {
                throw ApiError.FILE_NOT_FOUND.createException(fileId);
            }

            Path libraryRoot = Path.of(bookFileEntity.getBook().getLibraryPath().getPath());
            Path file = FileUtils.requirePathWithinBase(bookFileEntity.getFullFilePath(), libraryRoot);

            return downloadSingleFile(file);
        } catch (Exception e) {
            log.error("Failed to download book file {}: {}", fileId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(fileId);
        }
    }

    public ResponseEntity<Resource> downloadAllBookFiles(Long bookId) {
        try {
            BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

            List<BookFileEntity> allBookFiles = bookEntity.getBookFiles();
            if (allBookFiles == null || allBookFiles.isEmpty()) {
                throw ApiError.FILE_NOT_FOUND.createException(bookId);
            }

            Path libraryRoot = Path.of(bookEntity.getLibraryPath().getPath());

            // If only one file and it's not folder-based, download it directly
            if (allBookFiles.size() == 1) {
                BookFileEntity singleFile = allBookFiles.getFirst();
                Path filePath = FileUtils.requirePathWithinBase(singleFile.getFullFilePath(), libraryRoot);

                if (!Files.exists(filePath)) {
                    throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
                }

                // For folder-based audiobooks, let it fall through to ZIP creation
                if (!singleFile.isFolderBased() || !Files.isDirectory(filePath)) {
                    return downloadSingleFile(filePath);
                }
            }

            // Turn allFiles into a single list of all relevant files.
            Map<String, Path> contents = new HashMap<>();

            for (BookFileEntity bookFile : allBookFiles) {
                Path filePath = FileUtils.requirePathWithinBase(bookFile.getFullFilePath(), libraryRoot);

                if (!Files.exists(filePath)) {
                    log.warn("Skipping missing file during ZIP creation: {}", filePath);
                    continue;
                }

                // Handle folder-based audiobooks - add all files from the folder
                if (bookFile.isFolderBased() && Files.isDirectory(filePath)) {
                    String folderPrefix = bookFile.getFileName() + "/";
                    try (var folderBaseFiles = Files.list(filePath)) {
                        for (Path audioFile : folderBaseFiles.filter(Files::isRegularFile).toList()) {
                            String entryName = folderPrefix + audioFile.getFileName().toString();
                            contents.put(entryName, audioFile);
                        }
                    }
                } else {
                    // Regular file
                    contents.put(bookFile.getFileName(), filePath);
                }
            }

            // Create ZIP with all files
            String bookTitle = bookEntity.getMetadata() != null && bookEntity.getMetadata().getTitle() != null
                    ? bookEntity.getMetadata().getTitle()
                    : "book-" + bookId;

            return downloadFilesAsZip(contents, bookTitle);
        } catch (Exception e) {
            log.error("Failed to download book {}: {}", bookId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        }
    }

    public ResponseEntity<Resource> downloadKoboBook(Long bookId) {
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        var primaryFile = bookEntity.getPrimaryBookFile();
        if (primaryFile == null) {
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        }
        boolean isEpub = primaryFile.getBookType() == BookFileType.EPUB;
        boolean isCbx = primaryFile.getBookType() == BookFileType.CBX;

        if (!isEpub && !isCbx) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("The requested book is not an EPUB or CBX file.");
        }

        KoboSettings koboSettings = appSettingService.getAppSettings().getKoboSettings();
        if (koboSettings == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Kobo settings not found.");
        }

        boolean convertEpubToKepub = isEpub && koboSettings.isConvertToKepub() && primaryFile.getFileSizeKb() <= (long) koboSettings.getConversionLimitInMb() * 1024;
        boolean convertCbxToEpub = isCbx && koboSettings.isConvertCbxToEpub() && primaryFile.getFileSizeKb() <= (long) koboSettings.getConversionLimitInMbForCbx() * 1024;

        int compressionPercentage = koboSettings.getConversionImageCompressionPercentage();
        Path tempDir = null;
        try {
            Path libraryRoot = Path.of(bookEntity.getLibraryPath().getPath());
            Path pathToSend = FileUtils.requirePathWithinBase(primaryFile.getFullFilePath(), libraryRoot);

            if (convertCbxToEpub || convertEpubToKepub) {
                tempDir = Files.createTempDirectory("kobo-conversion");
            }

            if (convertCbxToEpub) {
                pathToSend = cbxConversionService.convertCbxToEpub(
                    pathToSend.toFile(),
                    tempDir.toFile(),
                    bookEntity,
                    compressionPercentage
                ).toPath();
            }

            if (convertEpubToKepub) {
                pathToSend = kepubConversionService.convertEpubToKepub(
                    pathToSend.toFile(),
                    tempDir.toFile(),
                    koboSettings.isForceEnableHyphenation()
                ).toPath();
            }

            return downloadSingleFile(pathToSend);
        } catch (Exception e) {
            log.error("Failed to download kobo book {}: {}", bookId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    private void cleanupTempDirectory(Path tempDir) {
        if (tempDir != null) {
            try {
                FileSystemUtils.deleteRecursively(tempDir);
                log.debug("Deleted temporary directory {}", tempDir);
            } catch (Exception e) {
                log.warn("Failed to delete temporary directory {}: {}", tempDir, e.getMessage());
            }
        }
    }

    private ResponseEntity<Resource> downloadSingleFile(Path filePath) throws IOException{
        String filename = filePath.getFileName().toString();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        String fallbackFilename = NON_ASCII_PATTERN.matcher(filename).replaceAll("_");
        String contentDisposition = String.format(
                "attachment; filename=\"%s\"; filename*=UTF-8''%s",
                fallbackFilename,
                encodedFilename
        );

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
            .header(HttpHeaders.CONTENT_LENGTH, Long.toString(Files.size(filePath)))
            .body(new FileSystemResource(filePath));
    }

    private ResponseEntity<Resource> downloadFolderAsZip(Path folderPath, String folderName) throws IOException {
        try (var files = Files.list(folderPath)) {
            Map<String, Path> contents = files
                .filter(Files::isRegularFile)
                .collect(
                    Collectors.<Path, String, Path>toMap(
                        (p) -> p.getFileName().toString(),
                        (p) -> p
                    )
                );

            return this.downloadFilesAsZip(
                    contents,
                    folderName
            );
        }
    }

    private ResponseEntity<Resource> downloadFilesAsZip(Map<String, Path> contents, String name) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        // Create a consistent sort order for files we write to the zip
        var entryNamesAndPaths = contents.entrySet()
            .stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .toList();

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (var entryNameAndPath : entryNamesAndPaths) {
                ZipEntry entry = new ZipEntry(entryNameAndPath.getKey());
                zos.putNextEntry(entry);
                Files.copy(entryNameAndPath.getValue(), zos);
                zos.closeEntry();
            }
        }

        byte[] zipBytes = baos.toByteArray();
        Resource resource = new org.springframework.core.io.ByteArrayResource(zipBytes);

        String zipFileName = name + ".zip";
        String encodedFilename = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8).replace("+", "%20");
        String fallbackFilename = NON_ASCII_PATTERN.matcher(zipFileName).replaceAll("_");
        String contentDisposition = String.format(
            "attachment; filename=\"%s\"; filename*=UTF-8''%s",
            fallbackFilename,
                encodedFilename
        );

        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
            .body(resource);
    }
}
