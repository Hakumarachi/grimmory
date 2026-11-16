package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
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

/**
 * Book content served by the core: thumbnails, file download and page images.
 * Callers must check the user's access to the book first.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaContentGateway {

    /** A page of a comic or PDF: 1-based number and file name in the archive. */
    public record PageInfo(int number, String fileName) {}

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
     * Pages of the primary file of a comic (file names from the archive listing, cached by the core CBX reader
     * and needed anyway to read a page) or a PDF (numbered pages); empty for other formats. Dimensions are not
     * read: that scans the whole archive, seconds on network storage, and Komga clients do not need them.
     */
    public List<PageInfo> pages(Long bookId, BookFileType bookType) {
        if (bookType == BookFileType.PDF) {
            return pdfReaderService.getAvailablePages(bookId, null).stream()
                    .map(number -> new PageInfo(number, "page-" + number))
                    .toList();
        }
        if (bookType != BookFileType.CBX) {
            return List.of();
        }
        return cbxReaderService.getPageInfo(bookId, null).stream()
                .map(page -> new PageInfo(page.getPageNumber(), page.getDisplayName()))
                .toList();
    }
}
