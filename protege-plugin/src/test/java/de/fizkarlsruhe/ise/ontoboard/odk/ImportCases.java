package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The project ODK's import targets were recorded on ({@code equivalence/loim}, made by
 * {@code tools/odk-goldens/imports_goldens.py}), put together from the goldens: an ontology
 * that uses a term of each of its three imports, and the small ontologies it mirrors, which
 * are given to make as files so that nothing is fetched from the network. Every file has
 * the age the recording gave it.
 */
public final class ImportCases {

    private ImportCases() {
    }

    /**
     * The project as ODK's seed left it, with the edit file and the two term lists of the
     * recording over it: under {@code top/project}; the ontologies to mirror under
     * {@code top/upstream}.
     *
     * @return the edit file
     */
    public static File fresh(File top) throws IOException {
        File project = new File(top, "project");
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>(
                Goldens.seedTree("localimports"));
        files.putAll(Goldens.tree(new File(Goldens.root(), "equivalence/loim/overlay")));
        write(project, files);
        write(new File(top, "upstream"),
                Goldens.tree(new File(Goldens.root(), "equivalence/loim/upstream")));
        // ODK's seed has the folder; a tree of files cannot hold an empty one.
        Files.createDirectories(new File(project, "src/ontology/tmp").toPath());
        // Every file of the same age, as in the recording: make decides by file times, and
        // files written one after the other here would look newer and older to it.
        RunComparisons.allOfTheSameAge(project);
        return new File(project, "src/ontology/loim-edit.owl");
    }

    /**
     * Where ODK's own refresh left that project, with one more term asked for in a term list:
     * the start of somebody who has just added a term. The recording checked that its own
     * start was exactly this.
     *
     * @return the edit file
     */
    public static File refreshedAndOneMoreTermAskedFor(File top) throws IOException {
        File edit = fresh(top);
        lay(new File(top, "project"), "equivalence/loim/refresh-imports/files");
        lay(new File(top, "project"), "equivalence/loim/more");
        RunComparisons.allOfTheSameAge(new File(top, "project"));
        return edit;
    }

    /** The files of a folder of the goldens, written over the project's. */
    public static void lay(File project, String golden) throws IOException {
        write(project, Goldens.tree(new File(Goldens.root(), golden)));
    }

    /** The folder of the ontologies to mirror, as an address: what make is given as OBOBASE. */
    public static String upstream(File top) {
        String address = new File(top, "upstream").toURI().toString();
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    /** The recording's command line, with this machine's folder of the ontologies to mirror. */
    public static Map<String, String> commandLine(File top) {
        Map<String, String> line = new LinkedHashMap<String, String>();
        line.put("OBOBASE", upstream(top));
        line.put("VERSION", "1.0.0");
        line.put("TODAY", "2026-11-11");
        line.put("OBODATE", "11:11:2026 12:00");
        return line;
    }

    private static void write(File folder, Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            File to = new File(folder, file.getKey());
            Files.createDirectories(to.getParentFile().toPath());
            Files.write(to.toPath(), file.getValue());
        }
    }
}
