package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The files saved in Protege - an ontology, a sheet - and when, so that a release that went on
 * behind while the person worked (Keep working) can say what was saved meanwhile before it
 * commits: the release may hold some of it or none, as make reads the sources more than once,
 * and {@code git add -u} takes in each file as it is at the end (the 1.155.0 review's I3).
 *
 * <p>Fed by Protege's own saves (an IOListener on each window's model manager) and by the sheet
 * editor's. A file saved by another program is not known here.
 */
public final class SavedMeanwhile {

    /** Each file saved this session, by its absolute path, with when it was last saved. */
    private static final Map<String, Long> SAVED = new ConcurrentHashMap<String, Long>();

    private SavedMeanwhile() {
    }

    /** That {@code file} was saved now. */
    public static void saved(File file) {
        saved(file, System.currentTimeMillis());
    }

    /** That {@code file} was saved at {@code when}. */
    static void saved(File file, long when) {
        if (file != null) {
            SAVED.merge(pathOf(file).toString(), when, Math::max);
        }
    }

    /**
     * The file's path as the disk names it: a short name such as RUNNER~1, which GitHub's
     * Windows runners have in TEMP, and its long name are one path.
     */
    private static java.nio.file.Path pathOf(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (java.io.IOException | SecurityException notCanonical) {
            return file.getAbsoluteFile().toPath().normalize();
        }
    }

    /**
     * The files under {@code root} saved at {@code since} or later, as the project names them -
     * forward slashes, from the root - in name order; empty when none was.
     */
    public static List<String> under(File root, long since) {
        List<String> found = new ArrayList<String>();
        if (root == null) {
            return found;
        }
        java.nio.file.Path top = pathOf(root);
        for (Map.Entry<String, Long> each : SAVED.entrySet()) {
            java.nio.file.Path path = java.nio.file.Paths.get(each.getKey());
            if (each.getValue() >= since && path.startsWith(top) && !path.equals(top)) {
                found.add(top.relativize(path).toString().replace('\\', '/'));
            }
        }
        Collections.sort(found);
        return found;
    }
}
