package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.IRI;

/**
 * The term lists that make an import module rebuildable.
 *
 * <p>An ODK project does not commit an import module as a finished artefact. It commits the
 * <em>list of terms</em> it wants - {@code imports/iao_terms.txt} - and the build extracts the
 * module from an upstream ontology every time. That is what makes {@code make refresh-imports}
 * meaningful, and it is the difference between a repository somebody else can build and one
 * carrying a binary blob nobody dares regenerate.
 *
 * <p>Until now this plugin wrote the module and not the list. {@code ROBOT > Import terms...} would
 * extract {@code imports/iao_import.owl}, add the import statement and the catalog entry - and the
 * next person to open the repository had a module they could not reproduce, could not extend, and
 * could not tell had gone stale against its source. Writing the list beside the module costs one
 * file and restores the property the whole ODK layout is built around.
 *
 * <p>The format is ODK's: one term per line, {@code #} for comments, blank lines ignored. Written
 * sorted, because a list whose order changes with the extraction produces a diff on every refresh
 * and nobody can see what really moved.
 *
 * <p>No Protege types and no Swing.
 */
public final class ImportModules {

    /** Where an ODK project keeps its import modules and their term lists. */
    public static final String IMPORTS_DIRECTORY = "src/ontology/imports";

    /** Where an ODK project keeps downloaded copies of the ontologies it imports from. */
    public static final String MIRROR_DIRECTORY = "src/ontology/mirror";

    /** What ODK calls the list for an import named {@code x}. */
    private static final String TERMS_SUFFIX = "_terms.txt";

    /** What ODK calls the module for an import named {@code x}. */
    private static final String MODULE_SUFFIX = "_import.owl";

    private ImportModules() {
    }

    /** One import: its name, its module, and the term list that rebuilds it. */
    public static final class Module {
        private final String name;
        private final File moduleFile;
        private final File termsFile;

        Module(String name, File moduleFile, File termsFile) {
            this.name = name;
            this.moduleFile = moduleFile;
            this.termsFile = termsFile;
        }

        /** The short name ODK uses - {@code iao} for {@code iao_import.owl}. */
        public String getName() {
            return name;
        }

        public File getModuleFile() {
            return moduleFile;
        }

        public File getTermsFile() {
            return termsFile;
        }

        public boolean hasModule() {
            return moduleFile.isFile();
        }

        /**
         * Whether this module can be rebuilt from what the repository contains.
         *
         * <p>The question that matters for a clone: a module with no term list is one nobody can
         * regenerate, extend or check for staleness.
         */
        public boolean isRebuildable() {
            return termsFile.isFile();
        }
    }

    /**
     * Where a project keeps downloaded copies of the ontologies it imports from.
     *
     * <p>ODK's {@code mirror/}. The point is that a refresh does not have to go back to the
     * network: the upstream ontology is downloaded once, and every extraction after that reads the
     * local copy. It also pins what a module was built from, which is the difference between "we
     * extracted these terms from CHEBI" and "we extracted these terms from whatever CHEBI was that
     * afternoon".
     */
    public static File mirrorDirectoryIn(File projectRoot) {
        return new File(projectRoot, MIRROR_DIRECTORY);
    }

    /** Whether this project has anything mirrored, and so can refresh without the network. */
    public static boolean hasMirror(File projectRoot) {
        if (projectRoot == null) {
            return false;
        }
        File[] mirrored = mirrorDirectoryIn(projectRoot).listFiles();
        if (mirrored == null) {
            return false;
        }
        for (File file : mirrored) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".owl")) {
                return true;
            }
        }
        return false;
    }

    /** The imports directory of a project, whether or not it exists yet. */
    public static File importsDirectoryIn(File projectRoot) {
        return new File(projectRoot, IMPORTS_DIRECTORY);
    }

    /** Where the term list for {@code name} belongs. */
    public static File termsFileFor(File projectRoot, String name) {
        return new File(importsDirectoryIn(projectRoot), name + TERMS_SUFFIX);
    }

    /** Where the module for {@code name} belongs. */
    public static File moduleFileFor(File projectRoot, String name) {
        return new File(importsDirectoryIn(projectRoot), name + MODULE_SUFFIX);
    }

    /**
     * Every import this project has, by name, sorted.
     *
     * <p>Found from both the modules and the term lists, so an import whose module has not been
     * built yet - a fresh clone, before {@code make} has run - is still listed. That is the state
     * this class exists to make possible, so it must not be the state that makes it invisible.
     */
    public static List<Module> modulesIn(File projectRoot) {
        List<Module> modules = new ArrayList<Module>();
        if (projectRoot == null) {
            return modules;
        }
        File directory = importsDirectoryIn(projectRoot);
        File[] children = directory.listFiles();
        if (children == null) {
            return modules;
        }
        TreeSet<String> names = new TreeSet<String>();
        for (File child : children) {
            String file = child.getName();
            if (file.endsWith(MODULE_SUFFIX)) {
                names.add(file.substring(0, file.length() - MODULE_SUFFIX.length()));
            } else if (file.endsWith(TERMS_SUFFIX)) {
                names.add(file.substring(0, file.length() - TERMS_SUFFIX.length()));
            }
        }
        for (String name : names) {
            modules.add(new Module(name, moduleFileFor(projectRoot, name),
                    termsFileFor(projectRoot, name)));
        }
        return Collections.unmodifiableList(modules);
    }

    /**
     * Writes the term list for an import.
     *
     * <p>Sorted and deduplicated. A list whose order follows whatever the extraction happened to
     * produce makes a diff on every refresh, and then nobody reads the diffs.
     *
     * @return the file written
     * @throws IOException if the list cannot be written
     */
    public static File writeTerms(File projectRoot, String name, List<IRI> terms, String source)
            throws IOException {
        File file = termsFileFor(projectRoot, name);
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory.getAbsolutePath());
        }

        StringBuilder text = new StringBuilder();
        text.append("# Terms extracted into ").append(name).append(MODULE_SUFFIX).append('\n');
        if (source != null && !source.trim().isEmpty()) {
            text.append("# Source: ").append(source.trim()).append('\n');
        }
        text.append("# One term per line. This file is what makes the module rebuildable:\n");
        text.append("# edit it and re-run the import to change what the module contains.\n");
        for (String iri : sortedUnique(terms)) {
            text.append(iri).append('\n');
        }
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Reads a term list.
     *
     * <p>Lines that are not IRIs are skipped rather than fatal - the file is one a human edits, and
     * losing the other forty terms over one bad line would be the wrong trade. {@link #malformedIn}
     * reports them, so nothing is silently dropped.
     */
    public static List<IRI> readTerms(File termsFile) throws IOException {
        List<IRI> terms = new ArrayList<IRI>();
        for (String line : linesOf(termsFile)) {
            if (looksLikeIri(line)) {
                terms.add(IRI.create(line));
            }
        }
        return terms;
    }

    /** Lines of a term list that are neither blank, comments, nor usable IRIs. */
    public static List<String> malformedIn(File termsFile) throws IOException {
        List<String> malformed = new ArrayList<String>();
        for (String line : linesOf(termsFile)) {
            if (!looksLikeIri(line)) {
                malformed.add(line);
            }
        }
        return malformed;
    }

    /**
     * The upstream ontology a term list was extracted from, or null.
     *
     * <p>Recorded as a {@code # Source:} comment when the list is written, because a term list
     * without it is only half a recipe: it says which terms to take and not where from, and the
     * next person has to guess which of forty OBO ontologies owns them.
     */
    public static String sourceIn(File termsFile) throws IOException {
        if (termsFile == null || !termsFile.isFile()) {
            return null;
        }
        String text = new String(Files.readAllBytes(termsFile.toPath()), StandardCharsets.UTF_8);
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# Source:")) {
                String source = trimmed.substring("# Source:".length()).trim();
                return source.isEmpty() ? null : source;
            }
        }
        return null;
    }

    private static List<String> linesOf(File termsFile) throws IOException {
        List<String> lines = new ArrayList<String>();
        if (termsFile == null || !termsFile.isFile()) {
            return lines;
        }
        String text = new String(Files.readAllBytes(termsFile.toPath()), StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Whether a line is a term IRI.
     *
     * <p>Deliberately loose: any absolute IRI with a scheme. OBO terms are http, but a project may
     * legitimately use another scheme, and rejecting one on a guess would be worse than accepting a
     * line that then extracts nothing - which the extraction itself reports.
     */
    private static boolean looksLikeIri(String line) {
        int colon = line.indexOf(':');
        return colon > 0 && !line.contains(" ") && colon < line.length() - 1;
    }

    private static List<String> sortedUnique(List<IRI> terms) {
        TreeSet<String> sorted = new TreeSet<String>();
        if (terms != null) {
            for (IRI term : terms) {
                if (term != null) {
                    sorted.add(term.toString());
                }
            }
        }
        return new ArrayList<String>(sorted);
    }
}
