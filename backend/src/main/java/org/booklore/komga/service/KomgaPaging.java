package org.booklore.komga.service;

import org.booklore.komga.dto.KomgaPageableDto;

import java.util.List;
import java.util.function.Function;

/** In-memory paging of a full result list into a Komga page. */
final class KomgaPaging {

    private KomgaPaging() {
    }

    /**
     * Cuts {@code all} into the requested page and maps only that page. {@code unpaged} returns everything
     * as page 0. The mapped content may be shorter than the page (items that failed to map are dropped).
     */
    static <T, R> KomgaPageableDto<R> page(List<T> all, int page, int size, boolean unpaged,
                                           Function<List<T>, List<R>> mapPage) {
        int totalElements = all.size();
        List<T> pageItems;
        int actualPage;
        int actualSize;
        int totalPages;

        if (unpaged) {
            pageItems = all;
            actualPage = 0;
            actualSize = totalElements;
            totalPages = totalElements > 0 ? 1 : 0;
        } else {
            totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);
            pageItems = all.subList(fromIndex, toIndex);
            actualPage = page;
            actualSize = size;
        }

        List<R> content = mapPage.apply(pageItems);
        return KomgaPageableDto.<R>builder()
                .content(content)
                .number(actualPage)
                .size(actualSize)
                .numberOfElements(content.size())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .first(actualPage == 0)
                .last(totalElements == 0 || actualPage >= totalPages - 1)
                .empty(content.isEmpty())
                .build();
    }
}
