package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.dto.response.CbxPageDimension;
import org.booklore.model.dto.response.CbxPageInfo;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.book.BookService;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.service.reader.PdfReaderService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Book content served by the core: thumbnails, file download and page images.
 * Callers must check the user's access to the book first.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaContentGateway {

    /** A page of a comic or PDF: 1-based number, file name in the archive, and its size when known. */
    public record PageInfo(int number, String fileName, Integer width, Integer height) {}

    private final BookService bookService;
    private final CbxReaderService cbxReaderService;
    private final PdfReaderService pdfReaderService;

    public Resource thumbnail(long bookId) {
        return bookService.getBookThumbnail(bookId);
    }

    public ResponseEntity<StreamingResponseBody> download(Long bookId) {
        return bookService.downloadBook(bookId);
    }

    /** Writes the image of a page; throws if the page does not exist. */
    public void streamPage(Long bookId, BookFileType bookType, int pageNumber, OutputStream out) throws IOException {
        // Make sure pages are cached before streaming one
        if (bookType == BookFileType.PDF) {
            pdfReaderService.getAvailablePages(bookId);
            pdfReaderService.streamPageImage(bookId, pageNumber, out);
        } else {
            cbxReaderService.getAvailablePages(bookId);
            cbxReaderService.streamPageImage(bookId, pageNumber, out);
        }
    }

    /**
     * Pages of the primary file of a comic (names and dimensions read by the core CBX reader, cached) or
     * a PDF (numbered pages); empty for other formats.
     */
    public List<PageInfo> pages(Long bookId, BookFileType bookType) {
        if (bookType == BookFileType.PDF) {
            return pdfReaderService.getAvailablePages(bookId, null).stream()
                    .map(number -> new PageInfo(number, "page-" + number, null, null))
                    .toList();
        }
        if (bookType != BookFileType.CBX) {
            return List.of();
        }
        List<CbxPageInfo> names = cbxReaderService.getPageInfo(bookId, null);
        Map<Integer, CbxPageDimension> dimensions;
        try {
            dimensions = cbxReaderService.getPageDimensions(bookId, null).stream()
                    .collect(Collectors.toMap(CbxPageDimension::getPageNumber, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            // Dimensions are a bonus: names alone are enough
            dimensions = Map.of();
        }
        Map<Integer, CbxPageDimension> sizes = dimensions;
        return names.stream()
                .map(page -> {
                    CbxPageDimension size = sizes.get(page.getPageNumber());
                    return new PageInfo(page.getPageNumber(), page.getDisplayName(),
                            size != null ? size.getWidth() : null, size != null ? size.getHeight() : null);
                })
                .toList();
    }
}
