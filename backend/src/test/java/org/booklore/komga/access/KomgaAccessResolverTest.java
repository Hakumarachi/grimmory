package org.booklore.komga.access;

import org.booklore.exception.APIException;
import org.booklore.komga.core.KomgaAccessGateway;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Access to a single book through the Komga API (pages, file, thumbnail, details).
 */
@ExtendWith(MockitoExtension.class)
class KomgaAccessResolverTest {

    @Mock
    private KomgaAccessGateway accessGateway;

    @Mock
    private KomgaBookRepository komgaBookRepository;

    private KomgaAccessResolver resolver;

    private final BookEntity book = BookEntity.builder().id(1L).library(LibraryEntity.builder().id(1L).build()).build();

    @BeforeEach
    void setUp() {
        resolver = new KomgaAccessResolver(accessGateway, komgaBookRepository);
    }

    @Test
    void bookOfAnAssignedLibraryIsVisible() {
        login(user(false, 1L));
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        assertThat(resolver.requireVisibleBook(1L)).isSameAs(book);
        verify(accessGateway, never()).applyRestrictions(any(), any());
    }

    @Test
    void userWithoutPermissionsIsNotAdmin() {
        login(BookLoreUser.builder().id(10L).assignedLibraries(List.of(Library.builder().id(1L).build())).build());
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        assertThat(resolver.requireVisibleBook(1L)).isSameAs(book);
    }

    @Test
    void userWithoutAssignedLibrariesSeesNothing() {
        login(BookLoreUser.builder().id(10L).build());
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        assertNotFound(1L);
        verify(accessGateway, never()).applyRestrictions(any(), any());
    }

    @Test
    void bookOfAnotherLibraryIsNotFound() {
        login(user(false, 2L));
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        assertNotFound(1L);
        verify(accessGateway, never()).applyRestrictions(any(), any());
    }

    @Test
    void bookHiddenByContentRestrictionsIsNotFound() {
        login(user(false, 1L));
        when(accessGateway.restrictions(10L)).thenReturn(List.of(new UserContentRestrictionEntity()));
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));
        when(accessGateway.applyRestrictions(List.of(book), 10L)).thenReturn(List.of());

        assertNotFound(1L);
    }

    @Test
    void missingBookIsNotFound() {
        login(user(false, 1L));

        assertNotFound(1L);
    }

    @Test
    void adminSeesEveryBookWithoutRestrictionCheck() {
        login(user(true));
        when(komgaBookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(book));

        assertThat(resolver.requireVisibleBook(1L)).isSameAs(book);
        verify(accessGateway, never()).restrictions(any());
        verify(accessGateway, never()).applyRestrictions(any(), any());
    }

    @Test
    void noAuthenticatedUserIsForbidden() {
        assertThatThrownBy(() -> resolver.current())
                .isInstanceOf(APIException.class)
                .extracting(e -> ((APIException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void login(BookLoreUser user) {
        when(accessGateway.currentUser()).thenReturn(user);
    }

    private static BookLoreUser user(boolean admin, Long... libraryIds) {
        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        return BookLoreUser.builder()
                .id(10L)
                .permissions(permissions)
                .assignedLibraries(java.util.Arrays.stream(libraryIds).map(id -> Library.builder().id(id).build()).toList())
                .build();
    }

    private void assertNotFound(Long bookId) {
        assertThatThrownBy(() -> resolver.requireVisibleBook(bookId))
                .isInstanceOf(APIException.class)
                .extracting(e -> ((APIException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
