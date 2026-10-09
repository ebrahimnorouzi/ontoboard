package de.fizkarlsruhe.ise.ontoboard.sheet;

import java.io.File;

/**
 * Where a project's templates live, so nobody has to go and find them.
 *
 * <p>Opening the sheet editor used to mean a file chooser and knowing the answer already.
 * That is the wrong way round: a project has one obvious place for its templates, and the
 * editor should be looking at it before anybody clicks anything.
 *
 * <h2>Where that is</h2>
 *
 * <p>An ODK project keeps its editable ontology at {@code <root>/src/ontology/<id>-edit.owl},
 * so the templates belong beside it at {@code <root>/src/templates} - which is where ODK's own
 * generated Makefile looks for them, and where PMDco and ECTO both keep theirs. For an
 * ontology that is not in an ODK layout there is no such convention, so the answer is a
 * {@code templates} folder beside the ontology file itself: still predictable, still in the
 * repository, and not somewhere a build will be surprised by.
 *
 * <p>Nothing here creates anything unless asked. Opening an editor should not put a directory
 * into somebody's repository as a side effect.
 */
public final class SheetLocation {

    /** What ODK calls it, and what its Makefile reads. */
    public static final String TEMPLATES = "templates";

    private SheetLocation() {
    }

    /**
     * The templates folder for an ontology file, whether or not it exists yet.
     *
     * @param ontologyFile the edit file, or any file the ontology was loaded from; may be null
     * @return the folder, or null when the file says nothing about where it lives
     */
    public static File forOntologyFile(File ontologyFile) {
        if (ontologyFile == null) {
            return null;
        }
        File holding = ontologyFile.isDirectory() ? ontologyFile : ontologyFile.getParentFile();
        if (holding == null) {
            return null;
        }
        File odk = odkTemplatesFor(holding);
        return odk != null ? odk : new File(holding, TEMPLATES);
    }

    /**
     * {@code <root>/src/templates} when the file sits in an ODK layout, otherwise null.
     *
     * <p>Recognised by the shape {@code .../src/ontology/}, not by looking for a Makefile or a
     * yaml: those are generated and a project part-way through being set up has the directories
     * before it has them.
     */
    private static File odkTemplatesFor(File holdingOntology) {
        if (!"ontology".equals(holdingOntology.getName())) {
            return null;
        }
        File src = holdingOntology.getParentFile();
        if (src == null || !"src".equals(src.getName())) {
            return null;
        }
        return new File(src, TEMPLATES);
    }

    /** Whether this folder is the ODK one rather than a fallback beside the ontology. */
    public static boolean isOdkLayout(File ontologyFile) {
        if (ontologyFile == null) {
            return false;
        }
        File holding = ontologyFile.isDirectory() ? ontologyFile : ontologyFile.getParentFile();
        return holding != null && odkTemplatesFor(holding) != null;
    }

    /** How many {@code .tsv} and {@code .csv} files are in a folder. Zero for one that is not. */
    public static int sheetsIn(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return 0;
        }
        File[] files = folder.listFiles();
        if (files == null) {
            return 0;
        }
        int found = 0;
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (file.isFile() && (name.endsWith(".tsv") || name.endsWith(".csv"))) {
                found++;
            }
        }
        return found;
    }

    /**
     * Creates the folder if it is not there.
     *
     * @return the folder when it exists afterwards, or null when it could not be made
     */
    public static File create(File folder) {
        if (folder == null) {
            return null;
        }
        if (folder.isDirectory()) {
            return folder;
        }
        return folder.mkdirs() ? folder : null;
    }

    /**
     * A sensible name for a new sheet in a folder, never one that is already taken.
     *
     * @param stem what to call it, without an extension
     */
    public static File freeNameIn(File folder, String stem) {
        if (folder == null) {
            return null;
        }
        String base = stem == null || stem.trim().isEmpty() ? "terms" : stem.trim();
        File first = new File(folder, base + ".tsv");
        if (!first.exists()) {
            return first;
        }
        for (int at = 2; at < 1000; at++) {
            File next = new File(folder, base + "-" + at + ".tsv");
            if (!next.exists()) {
                return next;
            }
        }
        return null;
    }
}
