package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * A .tgz for a test: its entries by name, with what each holds - written as POSIX ustar, as the
 * dosdp-tools release is (commons-compress cannot write tar with the bundle's commons-io).
 */
final class TarGz {

    private TarGz() {
    }

    /** One entry. */
    static File of(File folder, String name, String text) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put(name, text.getBytes(StandardCharsets.UTF_8));
        return of(folder, entries);
    }

    /** Several entries, in this order; a name ending in / is a folder. */
    static File of(File folder, Map<String, byte[]> entries) throws IOException {
        File tgz = new File(folder, "made-" + System.nanoTime() + ".tgz");
        try (OutputStream tar = new GZIPOutputStream(new FileOutputStream(tgz))) {
            for (Map.Entry<String, byte[]> one : entries.entrySet()) {
                boolean folderEntry = one.getKey().endsWith("/");
                byte[] data = folderEntry ? new byte[0] : one.getValue();
                tar.write(header(one.getKey(), data.length, folderEntry ? '5' : '0'));
                tar.write(data);
                int rest = data.length % 512;
                if (rest != 0) {
                    tar.write(new byte[512 - rest]);
                }
            }
            tar.write(new byte[1024]);
        }
        return tgz;
    }

    private static byte[] header(String name, long size, char type) {
        byte[] block = new byte[512];
        put(block, 0, name, 100);
        put(block, 100, "0000644", 8);
        put(block, 108, "0000000", 8);
        put(block, 116, "0000000", 8);
        put(block, 124, String.format("%011o", size), 12);
        put(block, 136, String.format("%011o", 0L), 12);
        block[156] = (byte) type;
        put(block, 257, "ustar", 6);
        put(block, 263, "00", 2);
        for (int i = 148; i < 156; i++) {
            block[i] = ' ';
        }
        long sum = 0;
        for (byte b : block) {
            sum += b & 0xff;
        }
        put(block, 148, String.format("%06o", sum), 7);
        block[155] = ' ';
        return block;
    }

    private static void put(byte[] block, int at, String text, int length) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            throw new IllegalArgumentException("'" + text + "' is longer than " + length);
        }
        System.arraycopy(bytes, 0, block, at, bytes.length);
    }
}
