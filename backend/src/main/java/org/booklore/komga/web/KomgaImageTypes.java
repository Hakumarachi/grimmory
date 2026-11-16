package org.booklore.komga.web;

/** Image media type from the first bytes of an image (pages are served as they are stored in the archive). */
final class KomgaImageTypes {

    static final String JPEG = "image/jpeg";

    private KomgaImageTypes() {
    }

    /** The media type of the image, JPEG when it cannot be recognized. */
    static String detect(byte[] data) {
        if (data == null || data.length < 12) {
            return JPEG;
        }
        if (startsWith(data, 0, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (startsWith(data, 0, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (startsWith(data, 4, 'f', 't', 'y', 'p')) {
            if (startsWith(data, 8, 'a', 'v', 'i', 'f') || startsWith(data, 8, 'a', 'v', 'i', 's')) {
                return "image/avif";
            }
            if (startsWith(data, 8, 'h', 'e', 'i', 'c') || startsWith(data, 8, 'h', 'e', 'i', 'x') || startsWith(data, 8, 'm', 'i', 'f', '1')) {
                return "image/heif";
            }
        }
        if (startsWith(data, 0, 0xFF, 0x0A) || startsWith(data, 4, 'J', 'X', 'L', ' ')) {
            return "image/jxl";
        }
        if (startsWith(data, 0, 'B', 'M')) {
            return "image/bmp";
        }
        return JPEG;
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
