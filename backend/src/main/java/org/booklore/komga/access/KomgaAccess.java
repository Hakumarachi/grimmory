package org.booklore.komga.access;

import org.booklore.model.entity.UserContentRestrictionEntity;

import java.util.List;
import java.util.Set;

/**
 * What the authenticated user may see through the Komga API: admins see everything,
 * other users only their assigned libraries, minus the books hidden by their content restrictions.
 */
public record KomgaAccess(Long userId, boolean admin, Set<Long> libraryIds,
                          List<UserContentRestrictionEntity> restrictions) {

    public boolean canSeeLibrary(Long libraryId) {
        return admin || (libraryId != null && libraryIds.contains(libraryId));
    }

    /** Whether some books may be hidden by content restrictions. */
    public boolean restricted() {
        return !admin && !restrictions.isEmpty();
    }
}
