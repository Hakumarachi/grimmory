package org.booklore.komga.mapper;

import org.booklore.komga.config.ConditionalOnKomgaEnabled;

import org.booklore.komga.web.json.KomgaCleanContext;
import org.booklore.model.dto.MagicShelf;
import org.booklore.komga.dto.*;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.komga.core.KomgaSettingsGateway;
import org.booklore.komga.series.SeriesIdCodec;
import org.booklore.komga.series.SeriesNaming;
import org.booklore.komga.progress.BookProgress;
import org.booklore.komga.progress.ReadCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaMapper {

    private final KomgaSettingsGateway settings;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public KomgaLibraryDto toKomgaLibraryDto(LibraryEntity library) {
        return KomgaLibraryDto.builder()
                .id(library.getId().toString())
                .name(library.getName())
                .root(library.getLibraryPaths() != null && !library.getLibraryPaths().isEmpty() 
                      ? library.getLibraryPaths().getFirst().getPath()
                      : "")
                .unavailable(false)
                .build();
    }

    /** Komga book, with the current user's progress on it ({@link BookProgress#NONE} when unread). */
    public KomgaBookDto toKomgaBookDto(BookEntity book, BookProgress progress) {
        BookMetadataEntity metadata = book.getMetadata();
        BookFileEntity bookFile = book.getPrimaryBookFile();
        String seriesId = generateSeriesId(book);
        KomgaMediaDto media = toKomgaMediaDto(book, metadata);

        return KomgaBookDto.builder()
                .id(book.getId().toString())
                .seriesId(seriesId)
                .seriesTitle(getBookSeriesName(book))
                .libraryId(book.getLibrary().getId().toString())
                .name(metadata != null ? metadata.getTitle() : bookFile.getFileName())
                .url("/komga/api/v1/books/" + book.getId())
                .number(metadata != null && metadata.getSeriesNumber() != null 
                       ? metadata.getSeriesNumber().intValue() 
                       : 1)
                .created(book.getAddedOn())
                .lastModified(book.getAddedOn())
                .fileLastModified(book.getAddedOn())
                .sizeBytes(bookFile.getFileSizeKb() != null ? bookFile.getFileSizeKb() * 1024 : 0L)
                .size(formatFileSize(bookFile.getFileSizeKb()))
                .media(media)
                .metadata(toKomgaBookMetadataDto(metadata))
                .readProgress(progress.toDto(media.getPagesCount()))
                .deleted(book.getDeleted())
                .fileHash(bookFile.getCurrentHash())
                .oneshot(false)
                .build();
    }

    /** Komga series of the given books, with the current user's read counts. */
    public KomgaSeriesDto toKomgaSeriesDto(String seriesName, Long libraryId, List<BookEntity> books, ReadCounts counts) {
        if (books == null || books.isEmpty()) {
            return null;
        }
        
        BookEntity firstBook = books.getFirst();
        String seriesId = generateSeriesId(firstBook);
        
        // Aggregate metadata from all books
        KomgaSeriesMetadataDto metadata = aggregateSeriesMetadata(seriesName, books);
        KomgaBookMetadataAggregationDto booksMetadata = aggregateBooksMetadata(books);
        
        return KomgaSeriesDto.builder()
                .id(seriesId)
                .libraryId(libraryId.toString())
                .name(seriesName)
                .url("/komga/api/v1/series/" + seriesId)
                .created(firstBook.getAddedOn())
                .lastModified(firstBook.getAddedOn())
                .fileLastModified(firstBook.getAddedOn())
                .booksCount(books.size())
                .booksReadCount(counts.read())
                .booksUnreadCount(counts.unread())
                .booksInProgressCount(counts.inProgress())
                .metadata(metadata)
                .booksMetadata(booksMetadata)
                .deleted(false)
                .oneshot(books.size() == 1)
                .build();
    }

    private KomgaMediaDto toKomgaMediaDto(BookEntity book, BookMetadataEntity metadata) {
        BookFileEntity bookFile = book.getPrimaryBookFile();
        String mediaType = getMediaType(bookFile.getBookType());
        Integer pageCount = metadata != null && metadata.getPageCount() != null ? metadata.getPageCount() : 0;
        return KomgaMediaDto.builder()
                .status("READY")
                .mediaType(mediaType)
                .mediaProfile(getMediaProfile(bookFile.getBookType()))
                .pagesCount(pageCount)
                .build();
    }

    private KomgaBookMetadataDto toKomgaBookMetadataDto(BookMetadataEntity metadata) {
        if (metadata == null) {
            return KomgaBookMetadataDto.builder().build();
        }
        
        List<KomgaAuthorDto> authors = new ArrayList<>();
        if (metadata.getAuthors() != null) {
            authors = metadata.getAuthors().stream()
                    .map(author -> KomgaAuthorDto.builder()
                            .name(author.getName())
                            .role("writer")
                            .build())
                    .toList();
        }
        
        List<String> tags = new ArrayList<>();
        if (metadata.getTags() != null) {
            tags = metadata.getTags().stream()
                    .map(TagEntity::getName)
                    .toList();
        }
        
        return KomgaBookMetadataDto.builder()
                .title(nullIfEmptyInCleanMode(metadata.getTitle(), ""))
                .titleLock(metadata.getTitleLocked())
                .summary(nullIfEmptyInCleanMode(metadata.getDescription(), ""))
                .summaryLock(metadata.getDescriptionLocked())
                .number(nullIfEmptyInCleanMode(metadata.getSeriesNumber(), 1.0F).toString())
                .numberLock(metadata.getSeriesNumberLocked())
                .numberSort(nullIfEmptyInCleanMode(metadata.getSeriesNumber(), 1.0F))
                .numberSortLock(metadata.getSeriesNumberLocked())
                .releaseDate(metadata.getPublishedDate() != null 
                           ? metadata.getPublishedDate().format(DATE_FORMATTER) 
                           : null)
                .releaseDateLock(metadata.getPublishedDateLocked())
                .authors(authors)
                .authorsLock(metadata.getAuthorsLocked())
                .tags(tags)
                .tagsLock(metadata.getTagsLocked())
                .isbn(metadata.getIsbn13() != null ? metadata.getIsbn13() : metadata.getIsbn10())
                .isbnLock(metadata.getIsbn13Locked())
                .build();
    }

    private KomgaSeriesMetadataDto aggregateSeriesMetadata(String seriesName, List<BookEntity> books) {
        BookEntity firstBook = books.getFirst();
        BookMetadataEntity firstMetadata = firstBook.getMetadata();
        
        List<String> genres = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        
        if (firstMetadata != null) {
            if (firstMetadata.getCategories() != null) {
                genres = firstMetadata.getCategories().stream()
                        .map(CategoryEntity::getName)
                        .toList();
            }
            if (firstMetadata.getTags() != null) {
                tags = firstMetadata.getTags().stream()
                        .map(TagEntity::getName)
                        .toList();
            }
        }
        String language = firstMetadata != null ? firstMetadata.getLanguage() : null;
        String description = firstMetadata != null ? firstMetadata.getDescription() : null;
        String publisher = firstMetadata != null ? firstMetadata.getPublisher() : null;
        
        return KomgaSeriesMetadataDto.builder()
                .status("ONGOING")
                .statusLock(false)
                .title(seriesName)
                .titleLock(false)
                .titleSort(seriesName)
                .titleSortLock(false)
                .summary(nullIfEmptyInCleanMode(description, ""))
                .summaryLock(false)
                .publisher(nullIfEmptyInCleanMode(publisher, ""))
                .publisherLock(false)
                .language(nullIfEmptyInCleanMode(language, "en"))
                .languageLock(false)
                .genres(genres)
                .genresLock(false)
                .tags(tags)
                .tagsLock(false)
                .totalBookCount(books.size())
                .totalBookCountLock(false)
                // not used but required right now by Mihon/komga apps
                .ageRatingLock(false)
                .readingDirection("LEFT_TO_RIGHT")
                .readingDirectionLock(false)
                .build();
    }

    private KomgaBookMetadataAggregationDto aggregateBooksMetadata(List<BookEntity> books) {
        Set<String> authorNames = new HashSet<>();
        Set<String> allTags = new HashSet<>();
        String releaseDate = null;
        String summary = null;
        
        BookEntity firstBook = books.getFirst();
            for (BookEntity book : books) {
            BookMetadataEntity metadata = book.getMetadata();
            if (metadata != null) {
                if (metadata.getAuthors() != null) {
                    metadata.getAuthors().forEach(author -> authorNames.add(author.getName()));
                }
                
                if (metadata.getTags() != null) {
                    metadata.getTags().forEach(tag -> allTags.add(tag.getName()));
                }
                
                if (releaseDate == null && metadata.getPublishedDate() != null) {
                    releaseDate = metadata.getPublishedDate().format(DATE_FORMATTER);
                }
                
                if (summary == null && metadata.getDescription() != null) {
                    summary = metadata.getDescription();
                }
            }
        }
        
        List<KomgaAuthorDto> authors = authorNames.stream()
                .map(name -> KomgaAuthorDto.builder().name(name).role("writer").build())
                .toList();
        
        return KomgaBookMetadataAggregationDto.builder()
                .authors(authors)
                .tags(new ArrayList<>(allTags))
                .created(firstBook.getAddedOn())
                .lastModified(firstBook.getAddedOn())
                .releaseDate(releaseDate)
                .summary(nullIfEmptyInCleanMode(summary, ""))
                // summaryNumber is typically empty, but in clean mode should be null to be filtered
                .summaryNumber(nullIfEmptyInCleanMode(null, ""))
                .summaryLock(false)
                .build();
    }

    public String getBookSeriesName(BookEntity book) {
        return SeriesNaming.nameOf(book, settings.groupUnknown());
    }

    private String generateSeriesId(BookEntity book) {
        // Pseudo-ID based on library and series name
        return SeriesIdCodec.encode(book.getLibrary().getId(), getBookSeriesName(book));
    }

    private String getMediaType(BookFileType bookType) {
        if (bookType == null) {
            return "application/zip";
        }
        
        return switch (bookType) {
            case PDF -> "application/pdf";
            case EPUB -> "application/epub+zip";
            case CBX -> "application/x-cbz";
            case FB2 -> "application/fictionbook2+zip";
            case MOBI -> "application/x-mobipocket-ebook";
            case AZW3 -> "application/vnd.amazon.ebook";
            case AUDIOBOOK -> "audio/*";
        };
    }

    private String getMediaProfile(BookFileType bookType) {
        if (bookType == null) {
            return "UNKNOWN";
        }
        
        return switch (bookType) {
            case PDF -> "PDF";
            case MOBI -> "EPUB";
            case AZW3 -> "EPUB";
            case EPUB -> "EPUB";
            case CBX -> "DIVINA"; // DIVINA is for comic books
            case FB2 -> "DIVINA";
            case AUDIOBOOK -> "AUDIOBOOK";
        };
    }

    private String formatFileSize(Long fileSizeKb) {
        if (fileSizeKb == null || fileSizeKb == 0) {
            return "0 B";
        }
        
        long bytes = fileSizeKb * 1024;
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        } else if (bytes < 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        } else {
            return (bytes / (1024 * 1024 * 1024)) + " GB";
        }
    }
    
    /**
     * Helper method to return null for empty strings in clean mode.
     * In clean mode, we want to allow null values so they can be filtered out.
     */
    private String nullIfEmptyInCleanMode(String value, String defaultValue) {
        if (KomgaCleanContext.isCleanMode()) {
            return (value != null && !value.isEmpty()) ? value : null;
        }
        return value != null ? value : defaultValue;
    }
    /**
     * Helper method to return null for empty integer in clean mode.
     * In clean mode, we want to allow null values so they can be filtered out.
     */
    private Integer nullIfEmptyInCleanMode(Integer value, Integer defaultValue) {
        if (KomgaCleanContext.isCleanMode()) {
            return (value != null) ? value : null;
        }
        return value != null ? value : defaultValue;
    }

    /**
     * Helper method to return null for empty float in clean mode.
     * In clean mode, we want to allow null values so they can be filtered out.
     */
    private Float nullIfEmptyInCleanMode(Float value, Float defaultValue) {
        if (KomgaCleanContext.isCleanMode()) {
            return (value != null) ? value : null;
        }
        return value != null ? value : defaultValue;
    }

    public KomgaUserDto toKomgaUserDto(OpdsUserV2Entity opdsUser) {
        return KomgaUserDto.builder()
                .id(opdsUser.getId().toString())
                .email(opdsUser.getUsername() + "@grimmory.local")
                .roles(List.of("USER"))
                .sharedAllLibraries(true)
                .build();
    }
    
    public KomgaCollectionDto toKomgaCollectionDto(MagicShelf magicShelf, int seriesCount) {
        String now = Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        return KomgaCollectionDto.builder()
                .id(magicShelf.getId().toString())
                .name(magicShelf.getName())
                .ordered(false)
                .seriesCount(seriesCount)
                .createdDate(now)
                .lastModifiedDate(now)
                .build();
    }
}