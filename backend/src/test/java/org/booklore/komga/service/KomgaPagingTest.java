package org.booklore.komga.service;

import org.booklore.komga.dto.KomgaPageableDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class KomgaPagingTest {

    private static final List<Integer> ITEMS = List.of(1, 2, 3, 4, 5);

    @Test
    void cutsTheRequestedPage() {
        KomgaPageableDto<Integer> page = KomgaPaging.page(ITEMS, 1, 2, false, Function.identity());
        assertThat(page.getContent()).containsExactly(3, 4);
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getFirst()).isFalse();
        assertThat(page.getLast()).isFalse();
    }

    @Test
    void pageAfterTheEndIsEmptyAndLast() {
        KomgaPageableDto<Integer> page = KomgaPaging.page(ITEMS, 7, 2, false, Function.identity());
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getEmpty()).isTrue();
        assertThat(page.getLast()).isTrue();
    }

    @Test
    void unpagedReturnsEverythingAsPageZero() {
        KomgaPageableDto<Integer> page = KomgaPaging.page(ITEMS, 3, 2, true, Function.identity());
        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(KomgaPaging.page(List.of(), 0, 20, true, Function.identity()).getTotalPages()).isZero();
    }

    @Test
    void onlyThePageIsMappedAndDroppedItemsAreNotCounted() {
        KomgaPageableDto<String> page = KomgaPaging.page(ITEMS, 0, 3, false,
                items -> items.stream().filter(i -> i != 2).map(String::valueOf).toList());
        assertThat(page.getContent()).containsExactly("1", "3");
        assertThat(page.getNumberOfElements()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
    }
}
