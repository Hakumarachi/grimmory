package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
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

/**
 * Book content served by the core: thumbnails, file download and page images.
 * Callers must check the user's access to the book first.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaContentGateway {

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
}
