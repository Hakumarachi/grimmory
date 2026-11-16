package org.booklore.komga.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.komga.access.KomgaAccess;
import org.booklore.komga.access.KomgaAccessResolver;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaContentGateway;
import org.booklore.komga.dto.KomgaBookDto;
import org.booklore.komga.dto.KomgaPageDto;
import org.booklore.komga.dto.KomgaPageableDto;
import org.booklore.komga.mapper.KomgaMapper;
import org.booklore.komga.progress.KomgaProgressReader;
import org.booklore.komga.progress.ProgressSnapshot;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Komga books: listing, details, pages, thumbnail and file. Every book answers 404 unless visible. */
@Slf4j
@ConditionalOnKomgaEnabled
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KomgaBookService {

    private final KomgaBookRepository komgaBookRepository;
    private final KomgaMapper komgaMapper;
    private final KomgaAccessResolver accessResolver;
    private final KomgaContentGateway contentGateway;
    private final KomgaProgressReader progressReader;

    public KomgaPageableDto<KomgaBookDto> getAllBooks(Long libraryId, int requestedPage, int requestedSize) {
        int page = KomgaPaging.pageNumber(requestedPage);
        int size = KomgaPaging.pageSize(requestedSize);
        KomgaAccess access = accessResolver.current();
        Pageable pageable = PageRequest.of(page, size);
        Page<BookEntity> bookPage;

        if (!access.admin()) {
            // Only accessible libraries, minus books hidden by content restrictions (filtered in SQL so counts stay right)
            Set<Long> libraryIds = libraryId == null ? access.libraryIds()
                    : access.canSeeLibrary(libraryId) ? Set.of(libraryId) : Set.of();
            bookPage = komgaBookRepository.findAll(accessResolver.visibleBooksSpec(libraryIds, access), pageable);
        } else if (libraryId != null) {
            bookPage = komgaBookRepository.findAllWithMetadataByLibraryIdPaged(libraryId, pageable);
        } else {
            bookPage = komgaBookRepository.findAllWithMetadataPaged(pageable);
        }

        ProgressSnapshot progress = progressReader.load(access, bookPage.getContent());
        List<KomgaBookDto> content = bookPage.getContent().stream()
                .map(book -> komgaMapper.toKomgaBookDto(book, progress.of(book)))
                .toList();

        return KomgaPageableDto.<KomgaBookDto>builder()
                .content(content)
                .number(page)
                .size(size)
                .numberOfElements(content.size())
                .totalElements((int) bookPage.getTotalElements())
                .totalPages(bookPage.getTotalPages())
                .first(page == 0)
                .last(page >= bookPage.getTotalPages() - 1)
                .empty(content.isEmpty())
                .build();
    }

    public KomgaBookDto getBookById(Long bookId) {
        KomgaAccess access = accessResolver.current();
        BookEntity book = accessResolver.requireVisibleBook(bookId, access);
        return komgaMapper.toKomgaBookDto(book, progressReader.load(access, List.of(book)).of(book));
    }

    public List<KomgaPageDto> getBookPages(Long bookId) {
        BookEntity book = accessResolver.requireVisibleBook(bookId);

        BookMetadataEntity metadata = book.getMetadata();
        Integer pageCount = metadata != null && metadata.getPageCount() != null ? metadata.getPageCount() : 0;

        List<KomgaPageDto> pages = new ArrayList<>();
        for (int i = 1; i <= pageCount; i++) {
            pages.add(KomgaPageDto.builder()
                    .number(i)
                    .fileName("page-" + i)
                    .mediaType("image/jpeg")
                    .build());
        }
        return pages;
    }

    /** 404 unless the current user may see the book. */
    public void requireBookAccess(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
    }

    public Resource getBookPageImage(Long bookId, Integer pageNumber, boolean convertToPng) throws IOException {
        log.debug("Getting page {} from book {} (convert to PNG: {})", pageNumber, bookId, convertToPng);

        BookEntity book = accessResolver.requireVisibleBook(bookId);

        // Stream the page to a ByteArrayOutputStream; throws if the page does not exist
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        contentGateway.streamPage(bookId, book.getPrimaryBookFile().getBookType(), pageNumber, outputStream);

        byte[] imageData = outputStream.toByteArray();
        if (convertToPng) {
            imageData = convertImageToPng(imageData);
        }
        return new ByteArrayResource(imageData);
    }

    /** Thumbnail of a book the user may see; {@code null} when the book has none. */
    public Resource getBookThumbnail(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
        return contentGateway.thumbnail(bookId);
    }

    /** File of a book the user may see. */
    public ResponseEntity<StreamingResponseBody> downloadBook(Long bookId) {
        accessResolver.requireVisibleBook(bookId);
        return contentGateway.download(bookId);
    }

    private static byte[] convertImageToPng(byte[] imageData) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageData);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IOException("Failed to read image data");
            }
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}
