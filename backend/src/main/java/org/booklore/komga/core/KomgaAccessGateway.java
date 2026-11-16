package org.booklore.komga.core;

import lombok.RequiredArgsConstructor;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.UserContentRestrictionEntity;
import org.booklore.repository.UserContentRestrictionRepository;
import org.booklore.security.policy.ContentRestrictionSpecification;
import org.booklore.service.restriction.ContentRestrictionService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Core Grimmory calls needed to know what the current user may see: authenticated user,
 * assigned libraries and content restrictions.
 */
@ConditionalOnKomgaEnabled
@Component
@RequiredArgsConstructor
public class KomgaAccessGateway {

    private final AuthenticationService authenticationService;
    private final UserContentRestrictionRepository restrictionRepository;
    private final ContentRestrictionService contentRestrictionService;

    /** Grimmory user behind the authenticated OPDS account, or {@code null}. */
    public BookLoreUser currentUser() {
        return authenticationService.getAuthenticatedUser();
    }

    public List<UserContentRestrictionEntity> restrictions(Long userId) {
        return restrictionRepository.findByUserId(userId);
    }

    /** Removes the books hidden by the user's content restrictions. */
    public List<BookEntity> applyRestrictions(List<BookEntity> books, Long userId) {
        return contentRestrictionService.applyRestrictions(books, userId);
    }

    /** Same filter as {@link #applyRestrictions}, as a SQL specification. */
    public Specification<BookEntity> restrictionSpecification(List<UserContentRestrictionEntity> restrictions) {
        return ContentRestrictionSpecification.from(restrictions);
    }
}
