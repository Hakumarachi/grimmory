package org.booklore.komga.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KomgaImageTypesTest {

    @Test
    void recognizesCommonComicImageFormats() {
        assertThat(KomgaImageTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(KomgaImageTypes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A))).isEqualTo("image/png");
        assertThat(KomgaImageTypes.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))).isEqualTo("image/webp");
        assertThat(KomgaImageTypes.detect(bytes('G', 'I', 'F', '8', '9', 'a'))).isEqualTo("image/gif");
        assertThat(KomgaImageTypes.detect(bytes(0, 0, 0, 0x1C, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'))).isEqualTo("image/avif");
        assertThat(KomgaImageTypes.detect(bytes(0xFF, 0x0A))).isEqualTo("image/jxl");
    }

    @Test
    void unknownOrTooShortDataIsJpeg() {
        assertThat(KomgaImageTypes.detect(null)).isEqualTo("image/jpeg");
        assertThat(KomgaImageTypes.detect(new byte[3])).isEqualTo("image/jpeg");
        assertThat(KomgaImageTypes.detect(bytes('x', 'y', 'z'))).isEqualTo("image/jpeg");
    }

    /** The given leading bytes, padded to 16 bytes. */
    private static byte[] bytes(int... values) {
        byte[] data = new byte[16];
        for (int i = 0; i < values.length; i++) {
            data[i] = (byte) values[i];
        }
        return data;
    }
}
