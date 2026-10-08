package de.fizkarlsruhe.ise.ontoboard.widoco;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The command line that generates an ontology's documentation, and what its output means.
 *
 * <p>Pure: nothing here starts a process. {@code WidocoAction} does, through the same
 * {@code ProcessRunner} a build goes through, so the thing worth testing - what gets run and
 * what the result means - is testable without a 39 MB jar or a JVM.
 *
 * <p><b>Every constant here was measured by running it</b>, on MWO's 520 KB {@code mwo.owl},
 * with the flags below. Two things that an unverified plan had wrong, and that would each have
 * shipped a broken feature:
 *
 * <ul>
 *   <li><b>There is no {@code doc/} subfolder.</b> With {@code -outFolder} given, the page is
 *       written to {@code <out>/index-<lang>.html} directly - measured at 854,425 bytes. Looking
 *       for {@code <out>/doc/index-en.html} would have reported failure on every successful run.
 *   <li><b>The log is not full of errors.</b> A successful run printed 27 lines, of which the
 *       only warnings were a missing {@code config/config.properties} and two SAX parser
 *       properties the bundled Xerces does not recognise. Treating warnings as failure would
 *       reject every run there is.
 * </ul>
 *
 * <p><b>The exit code is still not the test.</b> It was 0 here, and a tool that writes a page by
 * template can finish cleanly having written nothing useful; the page existing is the fact worth
 * checking, so {@link #outcome} checks that. The warnings are reported rather than hidden,
 * because the first of them is actionable.
 *
 * <p><b>Its configuration is found beside the jar, not beside the working directory.</b> This
 * was worth running twice to establish: with the process started in one directory and the jar in
 * another, Widoco still looked for {@code config/config.properties} next to the <em>jar</em>.
 * So moving the jar moves where a {@code config/} would have to live, and setting the working
 * directory does not help a user who wrote one. The warning it prints names the path it tried,
 * which is why {@link #worthReading} keeps that line and drops the two Xerces ones.
 *
 * <p><b>A failure can be reported as a warning and still matter.</b> The same run printed
 * {@code ERROR widoco.WidocoUtils - Failed to download vocabulary} and
 * {@code ERROR widoco.CreateResources - Could not generate changelog} while exiting 0 and
 * writing a complete page: Widoco tried to fetch the previous release to diff against, could
 * not, and carried on without the changelog section. That is the shape of thing the log filter
 * exists to surface - the page is fine and a section of it is quietly missing.
 */
public final class Widoco {

    private Widoco() {
    }

    /** The default language, and the suffix the page is written under. */
    public static final String DEFAULT_LANGUAGE = "en";

    /**
     * The flags, as the real projects run them.
     *
     * <p>{@code -uniteSections} puts everything on one page, which is what a reader of a
     * generated ontology page wants; {@code -includeAnnotationProperties} documents the
     * annotation properties an OBO-style ontology leans on; {@code -getOntologyMetadata} reads
     * the title, licence and creators out of the ontology rather than asking;
     * {@code -noPlaceHolderText} leaves a gap where a missing annotation would otherwise be
     * filled with "[Add a description]", which is what makes the page usable as a check on the
     * metadata; {@code -rewriteAll} overwrites a previous run instead of asking.
     */
    public static List<String> flags() {
        return Collections.unmodifiableList(Arrays.asList(
                "-uniteSections",
                "-includeAnnotationProperties",
                "-getOntologyMetadata",
                "-noPlaceHolderText",
                "-rewriteAll"));
    }

    /**
     * The whole command.
     *
     * @param java the JVM to run it with - Prot&eacute;g&eacute;'s own when new enough
     * @param jar the Widoco jar
     * @param ontology the file to document
     * @param outFolder where the page goes
     * @param language a language tag, or null for {@link #DEFAULT_LANGUAGE}
     * @param webVowl whether to include the WebVowl diagram, which costs time
     */
    public static List<String> command(File java, File jar, File ontology, File outFolder,
            String language, boolean webVowl) {
        if (jar == null) {
            throw new IllegalArgumentException("no Widoco jar");
        }
        if (ontology == null) {
            throw new IllegalArgumentException("nothing to document");
        }
        if (outFolder == null) {
            throw new IllegalArgumentException("nowhere to write the documentation");
        }
        List<String> command = new ArrayList<String>();
        command.add(java == null ? "java" : java.getAbsolutePath());
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        command.add("-ontFile");
        command.add(ontology.getAbsolutePath());
        command.add("-outFolder");
        command.add(outFolder.getAbsolutePath());
        command.addAll(flags());
        String tag = language == null || language.trim().isEmpty()
                ? DEFAULT_LANGUAGE : language.trim();
        if (!DEFAULT_LANGUAGE.equals(tag)) {
            command.add("-lang");
            command.add(tag);
        }
        if (webVowl) {
            command.add("-webVowl");
        }
        return Collections.unmodifiableList(command);
    }

    /**
     * Where the page lands.
     *
     * <p>{@code <out>/index-<lang>.html}, with no intervening directory. Measured rather than
     * assumed, because the obvious guess - a {@code doc/} subfolder, which Widoco does create
     * when no output folder is given - is wrong here and would make a working run look broken.
     */
    public static File indexIn(File outFolder, String language) {
        String tag = language == null || language.trim().isEmpty()
                ? DEFAULT_LANGUAGE : language.trim();
        return new File(outFolder, "index-" + tag + ".html");
    }

    /** What a run produced. */
    public static final class Outcome {
        private final boolean wrotePage;
        private final File page;
        private final List<String> worthReading;

        Outcome(boolean wrotePage, File page, List<String> worthReading) {
            this.wrotePage = wrotePage;
            this.page = page;
            this.worthReading = Collections.unmodifiableList(worthReading);
        }

        /** Whether the page exists. This, not the exit code, is the test. */
        public boolean wrotePage() {
            return wrotePage;
        }

        public File getPage() {
            return page;
        }

        /** The log lines a user would want to see, which is not all of them. */
        public List<String> getWorthReading() {
            return worthReading;
        }
    }

    /**
     * What to make of a finished run.
     *
     * <p>Success is the page existing and being big enough to be a page. A template-driven
     * generator can exit 0 having written a stub, and an empty file on disk is the failure mode
     * worth catching - the real one measured 854,425 bytes.
     */
    public static Outcome outcome(int exitCode, List<String> log, File page) {
        boolean wrote = page != null && page.isFile() && page.length() > MINIMUM_PAGE;
        return new Outcome(wrote, page, worthReading(log));
    }

    /** Below this a page is a stub, not documentation. A real one measured 854,425 bytes. */
    static final long MINIMUM_PAGE = 2048;

    /**
     * The log lines a user should see.
     *
     * <p>Not all of them, and not none. A successful run prints 27 lines of which most are
     * progress; the two SAX parser warnings are noise from the bundled Xerces and say nothing
     * anybody can act on, while the missing {@code config/config.properties} is worth knowing
     * because it means a configuration the user may have written was not read.
     */
    static List<String> worthReading(List<String> log) {
        List<String> keep = new ArrayList<String>();
        if (log == null) {
            return keep;
        }
        for (String line : log) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("entityexpansionlimit") || lower.contains("saxparsers")) {
                // Xerces does not recognise two properties the OWL API sets. It is printed on
                // every run of every ontology and means nothing to the person reading.
                continue;
            }
            if (lower.contains("error") || lower.contains("exception")
                    || lower.contains("severe") || lower.contains("warn")) {
                keep.add(line.trim());
            }
        }
        return keep;
    }

    /**
     * Which file to document.
     *
     * <p>The release product rather than the edit file, when there is one. An edit file imports
     * its modules rather than containing them, so documenting it produces a page about the few
     * hundred terms the project wrote and none of the ones it reuses - and with
     * {@code -getOntologyMetadata}, the version IRI and the citation that <i>Release…</i> stamps
     * are not in it yet either.
     *
     * @param projectRoot the repository root, or null
     * @param id the ontology id
     * @param editFile what is open, used when there is no release product
     */
    public static File inputFor(File projectRoot, String id, File editFile) {
        if (projectRoot != null && id != null && !id.trim().isEmpty()) {
            for (String extension : new String[] {".owl", ".ttl", ".ofn", ".owx"}) {
                File product = new File(projectRoot, id.trim() + extension);
                if (product.isFile()) {
                    return product;
                }
            }
        }
        return editFile;
    }

    /** Whether the chosen input is the edit file rather than a release product. */
    public static boolean isEditFile(File chosen) {
        return chosen != null && chosen.getName().contains("-edit.");
    }
}
