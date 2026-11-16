package org.booklore.komga.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Komga repository queries run against H2 with the full Spring context
 * (same setup as {@code HibernateRegressionTest}).
 */
@SpringBootTest(classes = BookloreApplication.class)
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:komga-repository-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(KomgaBookRepositoryTest.TestConfig.class)
class KomgaBookRepositoryTest {

    @Autowired
    private KomgaBookRepository komgaBookRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Test
    void findKomgaSeriesDates_aggregatesPerLibraryAndSeriesAndSkipsDeleted() {
        LibraryEntity library = persistLibrary("Komga Dates Library");
        LibraryPathEntity libraryPath = persistLibraryPath(library, "/komga");
        LibraryEntity otherLibrary = persistLibrary("Komga Dates Other Library");
        LibraryPathEntity otherPath = persistLibraryPath(otherLibrary, "/komga-other");
        entityManager.flush();

        persistBook(library, libraryPath, "Series A", "Tome 1", "a1.cbz", Instant.parse("2026-01-01T00:00:00Z"), false);
        persistBook(library, libraryPath, "Series A", "Tome 2", "a2.cbz", Instant.parse("2026-01-05T00:00:00Z"), false);
        persistBook(library, libraryPath, "Series A", "Tome 3", "a3.cbz", Instant.parse("2026-02-01T00:00:00Z"), true);
        persistBook(otherLibrary, otherPath, "Series A", "Tome 1", "o1.cbz", Instant.parse("2026-03-01T00:00:00Z"), false);
        persistBook(library, libraryPath, null, null, "loose.cbz", Instant.parse("2026-01-02T00:00:00Z"), false);
        entityManager.flush();
        entityManager.clear();

        var rows = komgaBookRepository.findKomgaSeriesDates().stream()
                .filter(r -> Set.of(library.getId(), otherLibrary.getId()).contains(r.getLibraryId()))
                .toList();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getLibraryId()).isEqualTo(library.getId());
            assertThat(r.getSeriesName()).isEqualTo("Series A");
            assertThat(r.getCreatedDate()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(r.getLastModifiedDate()).isEqualTo(Instant.parse("2026-01-05T00:00:00Z")); // deleted tome ignored
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getLibraryId()).isEqualTo(otherLibrary.getId());
            assertThat(r.getCreatedDate()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        });
    }

    @Test
    void findKomgaBooksWithoutSeries_returnsTitleOrFirstFileAndSkipsSeriesAndDeleted() {
        LibraryEntity library = persistLibrary("Komga No Series Library");
        LibraryPathEntity libraryPath = persistLibraryPath(library, "/komga-no-series");
        entityManager.flush();

        Instant standaloneAdded = Instant.parse("2026-01-01T00:00:00Z");
        Instant looseAdded = Instant.parse("2026-01-02T00:00:00Z");
        persistBook(library, libraryPath, "Series A", "Tome 1", "a1.cbz", Instant.now(), false);
        persistBook(library, libraryPath, null, "Standalone", "standalone.cbz", standaloneAdded, false);
        persistBook(library, libraryPath, null, null, "loose.cbz", looseAdded, false);
        persistBook(library, libraryPath, null, "Gone", "gone.cbz", Instant.now(), true);
        entityManager.flush();
        entityManager.clear();

        var rows = komgaBookRepository.findKomgaBooksWithoutSeries().stream()
                .filter(r -> library.getId().equals(r.getLibraryId()))
                .toList();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getTitle()).isEqualTo("Standalone");
            assertThat(r.getFileName()).isEqualTo("standalone.cbz");
            assertThat(r.getAddedOn()).isEqualTo(standaloneAdded);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getTitle()).isNull();
            assertThat(r.getFileName()).isEqualTo("loose.cbz");
            assertThat(r.getAddedOn()).isEqualTo(looseAdded);
        });
    }

    private LibraryEntity persistLibrary(String name) {
        LibraryEntity library = LibraryEntity.builder()
                .name(name)
                .icon("book")
                .watch(false)
                .build();
        entityManager.persist(library);
        return library;
    }

    private LibraryPathEntity persistLibraryPath(LibraryEntity library, String path) {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path(path)
                .build();
        entityManager.persist(libraryPath);
        return libraryPath;
    }

    private void persistBook(LibraryEntity library, LibraryPathEntity libraryPath, String seriesName,
                             String title, String fileName, Instant addedOn, boolean deleted) {
        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(addedOn)
                .deleted(deleted)
                .bookFiles(new HashSet<>())
                .build();

        BookFileEntity bookFile = BookFileEntity.builder()
                .book(book)
                .fileName(fileName)
                .fileSubPath("")
                .isBookFormat(true)
                .bookType(BookFileType.CBX)
                .fileSizeKb(100L)
                .initialHash("hash-" + fileName)
                .currentHash("hash-" + fileName)
                .addedOn(addedOn)
                .build();
        book.getBookFiles().add(bookFile);

        if (title != null) { // title is NOT NULL: "no title" means no metadata row at all
            book.setMetadata(BookMetadataEntity.builder()
                    .book(book)
                    .seriesName(seriesName)
                    .title(title)
                    .build());
        }

        entityManager.persist(book);
    }
}
