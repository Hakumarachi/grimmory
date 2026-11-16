package org.booklore.komga.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.service.KomgaBookService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = KomgaApiDoc.TAG, description = KomgaApiDoc.TAG_DESCRIPTION)
@Slf4j
@ConditionalOnKomgaEnabled
@RestController
@RequestMapping(value = "/komga/api", produces = "application/json")
@RequiredArgsConstructor
public class KomgaBookController {

    private final KomgaBookService bookService;
    private final KomgaJsonResponses json;

    @Operation(summary = "List books")
    @GetMapping("/v1/books")
    public ResponseEntity<String> getAllBooks(
            @Parameter(description = "Library ID filter") @RequestParam(required = false, name = "library_id") Long libraryId,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        return json.ok(bookService.getAllBooks(libraryId, page, size));
    }

    @Operation(summary = "Get book details")
    @GetMapping("/v1/books/{bookId}")
    public ResponseEntity<String> getBook(
            @Parameter(description = "Book ID") @PathVariable Long bookId) {
        return json.ok(bookService.getBookById(bookId));
    }

    @Operation(summary = "Get book pages metadata")
    @GetMapping("/v1/books/{bookId}/pages")
    public ResponseEntity<String> getBookPages(
            @Parameter(description = "Book ID") @PathVariable Long bookId) {
        return json.ok(bookService.getBookPages(bookId));
    }

    @Operation(summary = "Get book page image")
    @GetMapping("/v1/books/{bookId}/pages/{pageNumber}")
    public ResponseEntity<Resource> getBookPage(
            @Parameter(description = "Book ID") @PathVariable Long bookId,
            @Parameter(description = "Page number") @PathVariable Integer pageNumber,
            @Parameter(description = "Convert image format (e.g., 'png')") @RequestParam(required = false) String convert) {
        // Outside the try: a hidden or unknown book answers the API's 404, not an empty one
        bookService.requireBookAccess(bookId);

        try {
            boolean convertToPng = "png".equalsIgnoreCase(convert);
            byte[] pageImage = bookService.getBookPageImage(bookId, pageNumber, convertToPng);
            // Pages are served as stored in the archive (JPEG, PNG, WebP...): tell the real type
            String contentType = convertToPng ? "image/png" : KomgaImageTypes.detect(pageImage);
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .body(new ByteArrayResource(pageImage));
        } catch (Exception e) {
            log.error("Failed to get page {} from book {}", pageNumber, bookId, e);
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Download book file")
    @GetMapping("/v1/books/{bookId}/file")
    public ResponseEntity<StreamingResponseBody> downloadBook(
            @Parameter(description = "Book ID") @PathVariable Long bookId) {
        return bookService.downloadBook(bookId);
    }

    @Operation(summary = "Get book thumbnail")
    @GetMapping("/v1/books/{bookId}/thumbnail")
    public ResponseEntity<Resource> getBookThumbnail(
            @Parameter(description = "Book ID") @PathVariable Long bookId) {
        Resource coverImage = bookService.getBookThumbnail(bookId);
        if (coverImage == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(coverImage);
    }
}
