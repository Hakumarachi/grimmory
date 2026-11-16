package org.booklore.komga.repository;

import org.booklore.model.entity.BookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Read-only queries of the Komga API on the core book entities. Kept out of the core
 * {@code BookRepository} so the Komga module stays self-contained.
 */
public interface KomgaBookRepository extends Repository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity> {

    /** Non-deleted book with its metadata and files (same query as the core {@code BookRepository}). */
    @EntityGraph(attributePaths = { "metadata", "metadata.comicMetadata", "shelves", "libraryPath", "library", "bookFiles" })
    @Query("SELECT b FROM BookEntity b WHERE b.id = :id AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByIdWithBookFiles(@Param("id") Long id);

    /**
     * Get distinct series names for a library when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId 
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGroupedByLibraryId(
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names across all libraries when groupUnknown=true.
     * Books without series name are grouped as "Unknown Series".
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    ELSE :unknownSeriesName
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesGrouped(@Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Get distinct series names for a library when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE (
                        SELECT bf2.fileName FROM BookFileEntity bf2
                        WHERE bf2.book = b
                          AND bf2.isBookFormat = true
                          AND bf2.id = (
                              SELECT MIN(bf3.id) FROM BookFileEntity bf3
                              WHERE bf3.book = b AND bf3.isBookFormat = true
                          )
                    )
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId 
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesUngroupedByLibraryId(@Param("libraryId") Long libraryId);

    /**
     * Get distinct series names across all libraries when groupUnknown=false.
     * Each book without series gets its own entry (title or filename).
     */
    @Query("""
            SELECT DISTINCT 
                CASE 
                    WHEN m.seriesName IS NOT NULL THEN m.seriesName
                    WHEN m.title IS NOT NULL THEN m.title
                    ELSE (
                        SELECT bf2.fileName FROM BookFileEntity bf2
                        WHERE bf2.book = b
                          AND bf2.isBookFormat = true
                          AND bf2.id = (
                              SELECT MIN(bf3.id) FROM BookFileEntity bf3
                              WHERE bf3.book = b AND bf3.isBookFormat = true
                          )
                    )
                END as seriesName
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY seriesName
            """)
    List<String> findDistinctSeriesNamesUngrouped();

    /**
     * Created / last modified dates (oldest / newest book added) of every Komga series
     * that has a series name, per library. Used to sort Komga series by date.
     */
    @Query("""
            SELECT b.library.id as libraryId,
                   m.seriesName as seriesName,
                   MIN(b.addedOn) as createdDate,
                   MAX(b.addedOn) as lastModifiedDate
            FROM BookEntity b
            JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND m.seriesName IS NOT NULL
            GROUP BY b.library.id, m.seriesName
            """)
    List<KomgaSeriesDatesProjection> findKomgaSeriesDates();

    /**
     * Non-deleted books without series name, with the fields needed to derive their Komga
     * series name (title, first book-format file name) and their added date.
     * Used to sort Komga series by date.
     */
    @Query("""
            SELECT b.library.id as libraryId,
                   m.title as title,
                   (
                       SELECT bf2.fileName FROM BookFileEntity bf2
                       WHERE bf2.book = b
                         AND bf2.isBookFormat = true
                         AND bf2.id = (
                             SELECT MIN(bf3.id) FROM BookFileEntity bf3
                             WHERE bf3.book = b AND bf3.isBookFormat = true
                         )
                   ) as fileName,
                   b.addedOn as addedOn
            FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND m.seriesName IS NULL
            """)
    List<KomgaBookWithoutSeriesProjection> findKomgaBooksWithoutSeries();

    /**
     * Find books by series name for a library when groupUnknown=true.
     * Uses the first bookFile.fileName as fallback when metadata.seriesName is null.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND :seriesName = :unknownSeriesName)
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameGroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Find books by series name for a library when groupUnknown=false.
     * Matches by series name, or by title/filename for books without series.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE b.library.id = :libraryId
            AND (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND m.title = :seriesName)
                OR (
                    m.seriesName IS NULL AND m.title IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameUngroupedByLibraryId(
            @Param("seriesName") String seriesName,
            @Param("libraryId") Long libraryId);

    /**
     * Find books by series name across all libraries when groupUnknown=true.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT DISTINCT b FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (
                (m.seriesName = :seriesName)
                OR (
                    m.seriesName IS NULL
                    AND :seriesName = :unknownSeriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameGrouped(
            @Param("seriesName") String seriesName,
            @Param("unknownSeriesName") String unknownSeriesName);

    /**
     * Find books by series name across all libraries when groupUnknown=false.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "shelves", "libraryPath", "bookFiles"})
    @Query("""
            SELECT b FROM BookEntity b
            LEFT JOIN b.metadata m
            LEFT JOIN b.bookFiles bf
            WHERE (
                (m.seriesName = :seriesName)
                OR (m.seriesName IS NULL AND m.title = :seriesName)
                OR (
                    m.seriesName IS NULL AND m.title IS NULL
                    AND bf.isBookFormat = true
                    AND bf.id = (
                        SELECT MIN(bf2.id) FROM BookFileEntity bf2
                        WHERE bf2.book = b AND bf2.isBookFormat = true
                    )
                    AND bf.fileName = :seriesName
                )
            )
            AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 0)
            """)
    List<BookEntity> findBooksBySeriesNameUngrouped(
            @Param("seriesName") String seriesName);

    /**
     * Paginated query for all non-deleted books with Komga-relevant metadata.
     * Only ToOne paths in EntityGraph to avoid Cartesian product; collections loaded via @BatchSize.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "libraryPath", "library"})
    @Query("SELECT b FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false)")
    Page<BookEntity> findAllWithMetadataPaged(Pageable pageable);

    /**
     * Paginated query for non-deleted books by library with Komga-relevant metadata.
     * Only ToOne paths in EntityGraph to avoid Cartesian product; collections loaded via @BatchSize.
     */
    @EntityGraph(attributePaths = {"metadata", "metadata.comicMetadata", "libraryPath", "library"})
    @Query("SELECT b FROM BookEntity b WHERE b.library.id = :libraryId AND (b.deleted IS NULL OR b.deleted = false)")
    Page<BookEntity> findAllWithMetadataByLibraryIdPaged(@Param("libraryId") Long libraryId, Pageable pageable);
}
