package org.booklore.komga.mapper;

import org.booklore.komga.config.ConditionalOnKomgaEnabled;

import org.booklore.komga.web.json.KomgaCleanContext;
import org.booklore.model.dto.MagicShelf;
import org.booklore.komga.dto.*;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ComicCreatorRole;

import java.math.BigDecimal;
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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                .created(firstAdded(books))
                .lastModified(lastAdded(books))
                .fileLastModified(lastAdded(books))
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
                .status(mediaStatus(bookFile.getBookType(), pageCount))
                .mediaType(mediaType)
                .mediaProfile(getMediaProfile(bookFile.getBookType()))
                .pagesCount(pageCount)
                .build();
    }

    private KomgaBookMetadataDto toKomgaBookMetadataDto(BookMetadataEntity metadata) {
        if (metadata == null) {
            return KomgaBookMetadataDto.builder().build();
        }
        
        List<KomgaAuthorDto> authors = authorsOf(metadata);

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
                .number(formatNumber(nullIfEmptyInCleanMode(metadata.getSeriesNumber(), 1.0F)))
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

    /**
     * Series metadata aggregated over its books (sorted by number): genres and tags of every book, the most
     * common language and publisher, the first summary, the highest age rating, the reading direction of most
     * books. With a known total of volumes the series is {@code ENDED} (Mihon then shows it complete, or
     * "publishing finished" when volumes are missing), else {@code ONGOING}.
     */
    private KomgaSeriesMetadataDto aggregateSeriesMetadata(String seriesName, List<BookEntity> books) {
        List<BookMetadataEntity> metadata = books.stream().map(BookEntity::getMetadata).filter(Objects::nonNull).toList();

        Integer totalBookCount = metadata.stream()
                .map(BookMetadataEntity::getSeriesTotal)
                .filter(total -> total != null && total > 0)
                .max(Integer::compare)
                .orElse(null);
        Integer ageRating = metadata.stream()
                .map(BookMetadataEntity::getAgeRating)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(null);

        return KomgaSeriesMetadataDto.builder()
                .status(totalBookCount != null ? "ENDED" : "ONGOING")
                .statusLock(false)
                .title(seriesName)
                .titleLock(false)
                .titleSort(seriesName)
                .titleSortLock(false)
                .summary(nullIfEmptyInCleanMode(firstNonBlank(metadata, BookMetadataEntity::getDescription), ""))
                .summaryLock(false)
                .publisher(nullIfEmptyInCleanMode(mostCommon(metadata, BookMetadataEntity::getPublisher), ""))
                .publisherLock(false)
                .language(nullIfEmptyInCleanMode(mostCommon(metadata, BookMetadataEntity::getLanguage), ""))
                .languageLock(false)
                .genres(namesOf(metadata, BookMetadataEntity::getCategories, CategoryEntity::getName))
                .genresLock(false)
                .tags(namesOf(metadata, BookMetadataEntity::getTags, TagEntity::getName))
                .tagsLock(false)
                .totalBookCount(totalBookCount)
                .totalBookCountLock(false)
                .ageRating(ageRating)
                .ageRatingLock(false)
                .readingDirection(readingDirection(metadata))
                .readingDirectionLock(false)
                .created(firstAdded(books))
                .lastModified(lastAdded(books))
                .build();
    }

    private KomgaBookMetadataAggregationDto aggregateBooksMetadata(List<BookEntity> books) {
        List<BookMetadataEntity> metadata = books.stream().map(BookEntity::getMetadata).filter(Objects::nonNull).toList();

        // Every author of every book, once per name and role
        Map<String, KomgaAuthorDto> authors = new LinkedHashMap<>();
        metadata.forEach(m -> authorsOf(m).forEach(author -> authors.putIfAbsent(author.getRole() + "|" + author.getName(), author)));

        String releaseDate = metadata.stream()
                .map(BookMetadataEntity::getPublishedDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(date -> date.format(DATE_FORMATTER))
                .orElse(null);

        return KomgaBookMetadataAggregationDto.builder()
                .authors(new ArrayList<>(authors.values()))
                .tags(namesOf(metadata, BookMetadataEntity::getTags, TagEntity::getName))
                .created(firstAdded(books))
                .lastModified(lastAdded(books))
                .releaseDate(releaseDate)
                .summary(nullIfEmptyInCleanMode(firstNonBlank(metadata, BookMetadataEntity::getDescription), ""))
                // summaryNumber is typically empty, but in clean mode should be null to be filtered
                .summaryNumber(nullIfEmptyInCleanMode(null, ""))
                .summaryLock(false)
                .build();
    }

    /** Writers (the book authors), then the comic creators with their ComicInfo role, as Komga names them. */
    private static List<KomgaAuthorDto> authorsOf(BookMetadataEntity metadata) {
        List<KomgaAuthorDto> authors = new ArrayList<>();
        if (metadata.getAuthors() != null) {
            metadata.getAuthors().forEach(author -> authors.add(author(author.getName(), "writer")));
        }
        ComicMetadataEntity comic = metadata.getComicMetadata();
        if (comic != null && comic.getCreatorMappings() != null) {
            comic.getCreatorMappings().stream()
                    .filter(mapping -> mapping.getCreator() != null && mapping.getRole() != null)
                    .sorted(Comparator.comparing((ComicCreatorMappingEntity mapping) -> mapping.getRole().ordinal())
                            .thenComparing(mapping -> mapping.getCreator().getName(), Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(mapping -> authors.add(author(mapping.getCreator().getName(), komgaRole(mapping.getRole()))));
        }
        return authors;
    }

    private static KomgaAuthorDto author(String name, String role) {
        return KomgaAuthorDto.builder().name(name).role(role).build();
    }

    static String komgaRole(ComicCreatorRole role) {
        return switch (role) {
            case PENCILLER -> "penciller";
            case INKER -> "inker";
            case COLORIST -> "colorist";
            case LETTERER -> "letterer";
            case COVER_ARTIST -> "cover";
            case EDITOR -> "editor";
        };
    }

    /** Komga reading direction of most books with comic metadata; left to right by default. */
    static String readingDirection(List<BookMetadataEntity> metadata) {
        String direction = mostCommon(metadata, m -> m.getComicMetadata() != null ? m.getComicMetadata().getReadingDirection() : null);
        if (direction == null) {
            return "LEFT_TO_RIGHT";
        }
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "rtl" -> "RIGHT_TO_LEFT";
            case "ttb", "vertical" -> "VERTICAL";
            case "webtoon" -> "WEBTOON";
            default -> "LEFT_TO_RIGHT";
        };
    }

    /** Book number as Komga writes it: {@code 1}, {@code 10.5}. */
    static String formatNumber(Float number) {
        if (number == null) {
            return null;
        }
        return number == Math.rint(number) && !Float.isInfinite(number)
                ? String.valueOf(number.longValue())
                : BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    private static <T> List<String> namesOf(List<BookMetadataEntity> metadata,
                                            Function<BookMetadataEntity, Collection<T>> collection,
                                            Function<T, String> name) {
        return metadata.stream()
                .map(collection)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(name)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .toList();
    }

    /** Most frequent non-blank value; ties go to the first book. */
    static String mostCommon(List<BookMetadataEntity> metadata, Function<BookMetadataEntity, String> value) {
        Map<String, Long> counts = metadata.stream()
                .map(value)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String firstNonBlank(List<BookMetadataEntity> metadata, Function<BookMetadataEntity, String> value) {
        return metadata.stream().map(value).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static Instant firstAdded(List<BookEntity> books) {
        return books.stream().map(BookEntity::getAddedOn).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static Instant lastAdded(List<BookEntity> books) {
        return books.stream().map(BookEntity::getAddedOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    public String getBookSeriesName(BookEntity book) {
        return SeriesNaming.nameOf(book, settings.groupUnknown());
    }

    private String generateSeriesId(BookEntity book) {
        // Pseudo-ID based on library and series name
        return SeriesIdCodec.encode(book.getLibrary().getId(), getBookSeriesName(book));
    }

    /**
     * {@code READY}, or {@code UNSUPPORTED} for audiobooks and {@code UNKNOWN} for comics and PDFs whose page
     * count is not known yet (they cannot be read page by page).
     */
    static String mediaStatus(BookFileType bookType, int pageCount) {
        if (bookType == BookFileType.AUDIOBOOK) {
            return "UNSUPPORTED";
        }
        if ((bookType == BookFileType.CBX || bookType == BookFileType.PDF) && pageCount < 1) {
            return "UNKNOWN";
        }
        return "READY";
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
            case FB2 -> "EPUB"; // FB2 is text: Komga image readers cannot show it
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