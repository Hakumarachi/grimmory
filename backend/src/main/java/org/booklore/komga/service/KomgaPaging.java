package org.booklore.komga.service;

import org.booklore.komga.dto.KomgaPageableDto;

import java.util.List;
import java.util.function.Function;

/** Komga paging: request normalization and in-memory paging of a full result list. */
final class KomgaPaging {

    /** Spring Data defaults, which Komga uses: size 20 when missing or invalid, at most 2000. */
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 2000;

    private KomgaPaging() {
    }

    /** A negative page is the first page, like Komga. */
    static int pageNumber(int page) {
        return Math.max(page, 0);
    }

    /** A size below 1 is the default size and a size above {@link #MAX_SIZE} is capped, like Komga. */
    static int pageSize(int size) {
        return size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * Cuts {@code all} into the requested page and maps only that page. {@code unpaged} returns everything
     * as page 0. Page and size are normalized first ({@link #pageNumber}, {@link #pageSize}).
     * The mapped content may be shorter than the page (items that failed to map are dropped).
     */
    static <T, R> KomgaPageableDto<R> page(List<T> all, int requestedPage, int requestedSize, boolean unpaged,
                                           Function<List<T>, List<R>> mapPage) {
        int page = pageNumber(requestedPage);
        int size = pageSize(requestedSize);
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
