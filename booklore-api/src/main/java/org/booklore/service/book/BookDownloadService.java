package org.booklore.service.book;

import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;
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

            File bookFile = file.toFile();

            // Use FileSystemResource which properly handles file resources and closing
            Resource resource = new FileSystemResource(bookFile);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(bookFile.length())
                    .header(HttpHeaders.CONTENT_DISPOSITION, getContentDisposition(file))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .body(resource);
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

            if (!Files.exists(file)) {
                throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(fileId);
            }

            // Handle folder-based audiobooks - create ZIP
            if (bookFileEntity.isFolderBased() && Files.isDirectory(file)) {
                return downloadFolderAsZip(file, bookFileEntity.getFileName());
            }

            File bookFile = file.toFile();
            Resource resource = new FileSystemResource(bookFile);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(bookFile.length())
                    .header(HttpHeaders.CONTENT_DISPOSITION, getContentDisposition(file))
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to download book file {}: {}", fileId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(fileId);
        }
    }

    private String getContentDisposition(File file) {
        return getContentDisposition(file.getName());
    }

    private String getContentDisposition(Path path) {
        return getContentDisposition(path.getFileName().toString());
    }

    private String getContentDisposition(String filename) {
        String fallbackFilename = NON_ASCII_PATTERN.matcher(filename).replaceAll("_");

        return ContentDisposition.builder("attachment")
                .filename(fallbackFilename)
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    public void downloadAllBookFiles(Long bookId, HttpServletResponse response) {
        try{
            BookEntity bookEntity = bookRepository.findByIdWithBookFiles(bookId)
                    .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

            List<BookFileEntity> allBookFiles = bookEntity.getBookFiles();
            if (allBookFiles == null || allBookFiles.isEmpty()) {
                throw ApiError.FILE_NOT_FOUND.createException(bookId);
            }

            Path libraryRoot = Path.of(bookEntity.getLibraryPath().getPath());

            // Turn allFiles into a single list of all relevant files.
            Map<String, Path> contents = new HashMap<>();

            // Create ZIP with all files
            String bookTitle = bookEntity.getMetadata() != null && bookEntity.getMetadata().getTitle() != null
                    ? bookEntity.getMetadata().getTitle()
                    : "book-" + bookId;
            String safeTitle = bookTitle.replaceAll("[^a-zA-Z0-9\\-_]", "_");
            String zipFileName = safeTitle + ".zip";

            response.setContentType("application/zip");

            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, getContentDisposition(zipFileName));

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

            OutputStream outputStream = response.getOutputStream();

            writeZipToOutputStream(contents, outputStream);

            outputStream.flush();

            log.info("Successfully created and streamed ZIP for book {}", bookId);
        } catch (IOException e) {
            log.error("Failed to create ZIP for book {}: {}", bookId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        }
    }

    public void downloadKoboBook(Long bookId, HttpServletResponse response) {
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
            Path normalizedInputPath = FileUtils.requirePathWithinBase(primaryFile.getFullFilePath(), libraryRoot);
            File inputFile = normalizedInputPath.toFile();
            File fileToSend = inputFile;

            if (convertCbxToEpub || convertEpubToKepub) {
                tempDir = Files.createTempDirectory("kobo-conversion");
            }

            if (convertCbxToEpub) {
                fileToSend = cbxConversionService.convertCbxToEpub(inputFile, tempDir.toFile(), bookEntity,compressionPercentage);
            }

            if (convertEpubToKepub) {
                fileToSend = kepubConversionService.convertEpubToKepub(inputFile, tempDir.toFile(),
                    koboSettings.isForceEnableHyphenation());
            }

            setResponseHeaders(response, fileToSend);
            streamFileToResponse(fileToSend, response);

            log.info("Successfully streamed {} ({} bytes) to client", fileToSend.getName(), fileToSend.length());

        } catch (Exception e) {
            log.error("Failed to download kobo book {}: {}", bookId, e.getMessage(), e);
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(bookId);
        } finally {
            cleanupTempDirectory(tempDir);
        }
    }

    private void setResponseHeaders(HttpServletResponse response, File file) {
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLengthLong(file.length());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, getContentDisposition(file));
    }

    private void streamFileToResponse(File file, HttpServletResponse response) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            in.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stream file to response", e);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        this.writeZipToOutputStream(contents, baos);

        byte[] zipBytes = baos.toByteArray();

        String zipFileName = name + ".zip";

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, getContentDisposition(zipFileName))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(new ByteArrayResource(zipBytes));
    }

    private void writeZipToOutputStream(Map<String, Path> contents, OutputStream outputStream) throws IOException {
        // Create a consistent sort order for files we write to the zip
        var entryNamesAndPaths = contents.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (var entryNameAndPath : entryNamesAndPaths) {
                ZipEntry entry = new ZipEntry(entryNameAndPath.getKey());
                zos.putNextEntry(entry);
                Files.copy(entryNameAndPath.getValue(), zos);
                zos.closeEntry();
            }
        }
    }

}
