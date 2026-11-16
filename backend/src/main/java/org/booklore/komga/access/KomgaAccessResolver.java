package org.booklore.komga.access;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.komga.core.KomgaAccessGateway;
import org.booklore.komga.repository.KomgaBookRepository;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.Library;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the {@link KomgaAccess} of the current user and applies it to books.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaAccessResolver {

    private final KomgaAccessGateway accessGateway;
    private final KomgaBookRepository komgaBookRepository;

    /** Access of the authenticated user; 403 when nobody is authenticated. */
    public KomgaAccess current() {
        BookLoreUser user = accessGateway.currentUser();
        if (user == null) {
            throw ApiError.FORBIDDEN.createException("Authentication required");
        }
        if (user.getPermissions() != null && user.getPermissions().isAdmin()) {
            return new KomgaAccess(user.getId(), true, Set.of(), List.of());
        }
        Set<Long> libraryIds = new TreeSet<>();
        if (user.getAssignedLibraries() != null) {
            user.getAssignedLibraries().stream().map(Library::getId).forEach(libraryIds::add);
        }
        return new KomgaAccess(user.getId(), false, libraryIds, accessGateway.restrictions(user.getId()));
    }

    /** Removes the books hidden from a non-admin user by their content restrictions. */
    public List<BookEntity> visibleBooks(List<BookEntity> books, KomgaAccess access) {
        if (!access.restricted() || books.isEmpty()) {
            return books;
        }
        return accessGateway.applyRestrictions(books, access.userId());
    }

    /** Non-deleted books of the given libraries that pass the user's content restrictions. */
    public Specification<BookEntity> visibleBooksSpec(Collection<Long> libraryIds, KomgaAccess access) {
        return booksInLibraries(libraryIds).and(accessGateway.restrictionSpecification(access.restrictions()));
    }

    /**
     * Non-deleted books of the given libraries hidden by the user's content restrictions: the exact
     * complement of {@link #visibleBooksSpec}, as every restriction is an {@code EXISTS} subquery.
     */
    public Specification<BookEntity> hiddenBooksSpec(Collection<Long> libraryIds, KomgaAccess access) {
        return booksInLibraries(libraryIds).and(Specification.not(accessGateway.restrictionSpecification(access.restrictions())));
    }

    /** Loads a book the current user may see, or answers 404 (the book's existence is not disclosed). */
    public BookEntity requireVisibleBook(Long bookId) {
        return requireVisibleBook(bookId, current());
    }

    /** Same as {@link #requireVisibleBook(Long)}, for an access already resolved. */
    public BookEntity requireVisibleBook(Long bookId, KomgaAccess access) {
        return komgaBookRepository.findByIdWithBookFiles(bookId)
                .filter(book -> access.admin()
                        || (book.getLibrary() != null && access.canSeeLibrary(book.getLibrary().getId())))
                .filter(book -> !visibleBooks(List.of(book), access).isEmpty())
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
    }

    /** Non-deleted books of the given libraries. */
    private static Specification<BookEntity> booksInLibraries(Collection<Long> libraryIds) {
        Specification<BookEntity> notDeleted = (root, query, cb) ->
                cb.or(cb.isNull(root.get("deleted")), cb.equal(root.get("deleted"), false));
        Specification<BookEntity> inLibraries = (root, query, cb) ->
                libraryIds.isEmpty() ? cb.disjunction() : root.get("library").get("id").in(libraryIds);
        return notDeleted.and(inLibraries);
    }
}
