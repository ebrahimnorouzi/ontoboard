package de.fizkarlsruhe.ise.ontoboard.sheet;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which folder of templates belongs to the ontology in front of the sheet editor.
 *
 * <p>{@link SheetLocation} answered this with one folder: {@code src/templates} for an ODK
 * edit file, {@code templates/} beside anything else. A project that keeps a knowledge graph
 * beside its ontology - {@code pizza-bfo} keeps nine sheets in {@code kg/templates} - therefore
 * got "No templates yet" whenever it was opened from its edit file, with every sheet one folder
 * away. This looks in a short, fixed list of places instead, and says which ones.
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li>{@code templates/} beside the opened file - except for an edit file in
 *       {@code src/ontology/}, where ODK's own {@code src/templates/} comes first;</li>
 *   <li>{@code src/templates/} of the ODK project the file belongs to;</li>
 *   <li>{@code kg/templates/}, a knowledge graph kept beside the ontology;</li>
 *   <li>{@code templates/} at the project root.</li>
 * </ol>
 *
 * <p>The first of these that holds a {@code .tsv} or {@code .csv} is opened, and the others that
 * hold one are named so they can be chosen instead. The project is the nearest folder holding
 * {@code .git} or {@code src/ontology/}, at most {@value #MOST_LEVELS_UP} levels above the file:
 * the nearest repository wins, so a project nested inside another one - as the test projects are
 * inside this repository - never picks up its host's templates, and an ontology somewhere with
 * no project around it is only ever given the folder beside it. Nothing searches subfolders, and
 * nothing here creates anything.
 */
public final class TemplateDiscovery {

    /** How far above the ontology file a project root may be. */
    static final int MOST_LEVELS_UP = 4;

    /** Why a folder is on the list, in words for the status line. */
    public enum Reason {
        ODK("the ODK project's templates"),
        BESIDE("beside the ontology file"),
        KNOWLEDGE_GRAPH("the project's knowledge graph"),
        PROJECT("the project folder");

        private final String says;

        Reason(String says) {
            this.says = says;
        }

        public String says() {
            return says;
        }
    }

    /** What the sheet editor should do when the active ontology changes. */
    public enum Reaction {
        /** Leave the open sheets as they are. */
        KEEP,
        /** Open the new ontology's templates. */
        OPEN,
        /** Leave them, and say where the new ontology's templates are. */
        HOLD
    }

    /** One place looked in. */
    public static final class Candidate {
        private final File folder;
        private final Reason reason;
        private final boolean exists;
        private final int sheets;

        Candidate(File folder, Reason reason) {
            this.folder = folder;
            this.reason = reason;
            this.exists = folder.isDirectory();
            this.sheets = SheetLocation.sheetsIn(folder);
        }

        public File getFolder() {
            return folder;
        }

        public Reason getReason() {
            return reason;
        }

        public int getSheets() {
            return sheets;
        }

        public boolean exists() {
            return exists;
        }
    }

    private final File ontologyFile;
    private final File projectRoot;
    private final File key;
    private final List<Candidate> candidates;

    private TemplateDiscovery(File ontologyFile, File projectRoot, File key,
            List<Candidate> candidates) {
        this.ontologyFile = ontologyFile;
        this.projectRoot = projectRoot;
        this.key = key;
        this.candidates = Collections.unmodifiableList(candidates);
    }

    /**
     * Where the templates are for the ontology loaded from this file.
     *
     * @param ontologyFile the file the ontology was loaded from; null when it has none
     */
    public static TemplateDiscovery of(File ontologyFile) {
        return of(ontologyFile, null);
    }

    /**
     * The same, never looking above {@code ceiling} for the project. A test's temporary folder
     * is a ceiling, so a {@code .git} above it on the machine running the test changes nothing.
     */
    static TemplateDiscovery of(File ontologyFile, File ceiling) {
        if (ontologyFile == null) {
            return new TemplateDiscovery(null, null, null, new ArrayList<Candidate>());
        }
        File file = ontologyFile.getAbsoluteFile();
        File holding = file.isDirectory() ? file : file.getParentFile();
        if (holding == null) {
            return new TemplateDiscovery(file, null, null, new ArrayList<Candidate>());
        }
        File root = projectRootOf(holding, ceiling);
        List<Candidate> found = new ArrayList<Candidate>();
        if (isSrcOntology(holding)) {
            add(found, new File(holding.getParentFile(), SheetLocation.TEMPLATES), Reason.ODK);
        } else {
            add(found, new File(holding, SheetLocation.TEMPLATES), Reason.BESIDE);
        }
        if (root != null) {
            File src = childIgnoringCase(root, "src");
            if (new File(src, "ontology").isDirectory()
                    || childIgnoringCase(src, "ontology").isDirectory()) {
                add(found, new File(src, SheetLocation.TEMPLATES), Reason.ODK);
            }
            add(found, new File(childIgnoringCase(root, "kg"), SheetLocation.TEMPLATES),
                    Reason.KNOWLEDGE_GRAPH);
            add(found, new File(root, SheetLocation.TEMPLATES), Reason.PROJECT);
        }
        return new TemplateDiscovery(file, root, root != null ? root : holding, found);
    }

    /** The file this was worked out for, or null. */
    public File getOntologyFile() {
        return ontologyFile;
    }

    /** The project the file belongs to, or null when there is none to be found. */
    public File getProjectRoot() {
        return projectRoot;
    }

    /**
     * What identifies "the same place" when the active ontology changes: the project root, or
     * the folder holding the file when there is no project. Null when there is no file.
     */
    public File getKey() {
        return key;
    }

    /** Every place looked in, in the order they are tried. */
    public List<Candidate> getCandidates() {
        return candidates;
    }

    /** The first place holding a sheet, or null when none does. */
    public Candidate getChosen() {
        for (Candidate each : candidates) {
            if (each.getSheets() > 0) {
                return each;
            }
        }
        return null;
    }

    /** The places holding sheets that were not chosen. */
    public List<Candidate> getAlternatives() {
        Candidate chosen = getChosen();
        List<Candidate> others = new ArrayList<Candidate>();
        for (Candidate each : candidates) {
            if (each != chosen && each.getSheets() > 0) {
                others.add(each);
            }
        }
        return others;
    }

    /** Whether more than one place holds sheets. */
    public boolean isAmbiguous() {
        return !getAlternatives().isEmpty();
    }

    /**
     * Where a new sheet goes: the chosen folder, or else the first place on the list - which is
     * the one this project's layout says templates belong in. Null when there is no file.
     */
    public File getFolderForNewSheets() {
        Candidate chosen = getChosen();
        if (chosen != null) {
            return chosen.getFolder();
        }
        return candidates.isEmpty() ? null : candidates.get(0).getFolder();
    }

    /** One paragraph for the status line: what was opened, or where it looked and what next. */
    public String describe() {
        if (candidates.isEmpty()) {
            return "The active ontology is not saved in a file, so there is no project folder to "
                    + "look in. Open folder... reads any folder of .tsv or .csv templates.";
        }
        Candidate chosen = getChosen();
        if (chosen == null) {
            List<String> places = new ArrayList<String>();
            for (Candidate each : candidates) {
                places.add(shortName(each.getFolder(), projectRoot)
                        + (each.exists() ? " (empty)" : " (not there)"));
            }
            return "No templates found. Looked in " + join(places)
                    + (projectRoot == null ? "" : " of " + projectRoot.getAbsolutePath())
                    + ". New sheet... starts one in "
                    + shortName(getFolderForNewSheets(), projectRoot)
                    + "; Open folder... reads any other folder.";
        }
        StringBuilder said = new StringBuilder();
        said.append(shortName(chosen.getFolder(), projectRoot)).append(": ")
                .append(sheets(chosen.getSheets())).append(", ")
                .append(chosen.getReason().says()).append('.');
        List<String> before = new ArrayList<String>();
        for (Candidate each : candidates) {
            if (each == chosen) {
                break;
            }
            before.add(shortName(each.getFolder(), projectRoot)
                    + (each.exists() ? " (empty)" : " (not there)"));
        }
        if (!before.isEmpty()) {
            said.append(" Looked first in ").append(join(before)).append('.');
        }
        List<String> others = new ArrayList<String>();
        for (Candidate each : getAlternatives()) {
            others.add(shortName(each.getFolder(), projectRoot) + " (" + sheets(each.getSheets())
                    + ")");
        }
        if (!others.isEmpty()) {
            said.append(" Also holding sheets: ").append(join(others))
                    .append(" - Project templates switches.");
        }
        return said.toString();
    }

    /**
     * What the sheet editor does when the active ontology changes.
     *
     * <p>Another file of the same project changes nothing - an import, a release, the graph
     * beside the ontology. A different project opens its own templates, unless that would throw
     * away unsaved edits, which an event never does.
     */
    public static Reaction onActiveOntologyChanged(TemplateDiscovery now, File shownKey,
            boolean unsaved) {
        return onActiveOntologyChanged(now, shownKey, unsaved, false);
    }

    /**
     * The same, for a folder somebody chose by hand: that is kept, and the new ontology's
     * templates are named rather than opened.
     */
    public static Reaction onActiveOntologyChanged(TemplateDiscovery now, File shownKey,
            boolean unsaved, boolean chosenByHand) {
        if (now == null || now.getKey() == null) {
            return Reaction.KEEP;
        }
        if (shownKey != null && same(now.getKey(), shownKey)) {
            return Reaction.KEEP;
        }
        return unsaved || chosenByHand ? Reaction.HOLD : Reaction.OPEN;
    }

    /**
     * The file a document IRI names, or null when it names something else.
     *
     * <p>{@code Paths.get(URI)} rather than {@code new File(URI)}: the second refuses a URI with
     * an authority, which is how a Windows network share arrives, and both decode {@code %20}.
     */
    public static File fileOf(URI document) {
        if (document == null || document.getScheme() == null
                || !"file".equalsIgnoreCase(document.getScheme())) {
            return null;
        }
        try {
            return Paths.get(document).toFile();
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException no) {
            try {
                return new File(document);
            } catch (IllegalArgumentException notEither) {
                return null;
            }
        }
    }

    /**
     * A folder as the status line shows it: relative to the project with forward slashes, or
     * in full when there is no project or it lies outside it.
     */
    public static String shortName(File folder, File root) {
        if (folder == null) {
            return "";
        }
        if (root == null) {
            return folder.getAbsolutePath();
        }
        try {
            Path relative = normal(root).relativize(normal(folder));
            String text = relative.toString().replace('\\', '/');
            return text.isEmpty() || text.startsWith("..") ? folder.getAbsolutePath() : text;
        } catch (IllegalArgumentException otherDrive) {
            return folder.getAbsolutePath();
        }
    }

    // ------------------------------------------------------------------ the project

    private static File projectRootOf(File holding, File ceiling) {
        if (isSrcOntology(holding)) {
            File root = holding.getParentFile().getParentFile();
            if (root != null) {
                return root;
            }
        }
        File at = holding;
        for (int up = 0; at != null && up <= MOST_LEVELS_UP; up++) {
            if (isProjectRoot(at)) {
                return at;
            }
            if (ceiling != null && same(at, ceiling)) {
                return null;
            }
            at = at.getParentFile();
        }
        return null;
    }

    private static boolean isProjectRoot(File folder) {
        if (new File(folder, ".git").exists()) {
            return true;
        }
        File src = childIgnoringCase(folder, "src");
        return childIgnoringCase(src, "ontology").isDirectory();
    }

    /** {@code .../src/ontology}, in any case - the ODK shape, and Windows does not mind case. */
    private static boolean isSrcOntology(File folder) {
        File parent = folder.getParentFile();
        return "ontology".equalsIgnoreCase(folder.getName()) && parent != null
                && "src".equalsIgnoreCase(parent.getName());
    }

    /**
     * The child of that name, matching its case on disk when it exists in another one. One
     * directory listing, never a search.
     */
    private static File childIgnoringCase(File parent, String name) {
        File exact = new File(parent, name);
        if (exact.exists()) {
            return exact;
        }
        String[] names = parent.list();
        if (names != null) {
            for (String each : names) {
                if (each.equalsIgnoreCase(name)) {
                    return new File(parent, each);
                }
            }
        }
        return exact;
    }

    private static void add(List<Candidate> found, File folder, Reason reason) {
        for (Candidate each : found) {
            if (same(each.getFolder(), folder)) {
                return;
            }
        }
        found.add(new Candidate(folder, reason));
    }

    private static boolean same(File one, File other) {
        return normal(one).equals(normal(other));
    }

    private static Path normal(File file) {
        return file.getAbsoluteFile().toPath().normalize();
    }

    private static String sheets(int count) {
        return count == 1 ? "1 sheet" : count + " sheets";
    }

    private static String join(List<String> items) {
        if (items.size() <= 1) {
            return items.isEmpty() ? "" : items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and "
                + items.get(items.size() - 1);
    }
}
