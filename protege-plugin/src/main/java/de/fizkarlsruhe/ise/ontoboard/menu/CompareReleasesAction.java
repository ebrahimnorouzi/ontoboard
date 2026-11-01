package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.robot.AxiomDiff;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Compare releases - what changed between two versions, by term.
 *
 * <p>Every one of five expert panels asked for this independently, in its own words: at the
 * entailment level, as {@code robot diff}, as "what changed since I last looked, in sentences", by
 * term, and as <em>added, obsoleted, redefined, moved</em>. One question, four audiences.
 *
 * <p>An axiom diff was never the answer on its own. {@code robot diff} produces one, and a release
 * that renamed a single term runs to dozens of lines of it without ever using the word "renamed".
 * What a curator needs before publishing is which terms came, went, changed meaning or moved - and
 * above all whether anything was <b>removed</b> rather than obsoleted, since that breaks every
 * consumer that imported it and no axiom diff distinguishes the two unless you already know.
 */
public class CompareReleasesAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_FROM = "from";
    private static final String OPTION_TO = "to";
    private static final String OPTION_NOTES = "notes";
    private static final String OPTION_AXIOMS = "axioms";
    private static final String OPTION_PUBLISHED = "published";
    private static final String OPTION_DIFF_FILE = "diffFile";

    /** Enough of an axiom diff to review; the whole of a large one is unreadable in a dialog. */
    private static final int MAX_AXIOM_LINES = 300;

    /** What the later side is when somebody wants to compare a release against their edits. */
    private static final String WORKING_COPY = "what I have open now";

    /** The From side when the project has no releases: what is given below takes its place. */
    private static final String GIVEN_BELOW = "a web address or file, given below";

    private volatile String from = "";
    private volatile String to = WORKING_COPY;
    private volatile boolean writeNotes;
    private volatile boolean showAxioms;
    private volatile String publishedUrl = "";
    private volatile File diffFile;

    @Override
    protected String operationName() {
        return "Compare releases";
    }

    @Override
    protected boolean configure() {
        File editFile = fileOf(getOWLModelManager().getActiveOntology());
        List<String> releases = releasesOf(editFile);
        // No releases of its own is no reason to refuse: a contributor's question is usually
        // "what have I changed against the published version", and the address of that was the
        // option this dialog offered - after the refusal that kept anybody from reaching it.
        List<String> fromChoices = releases.isEmpty()
                ? java.util.Collections.singletonList(GIVEN_BELOW) : releases;

        List<String> laterChoices = new ArrayList<String>();
        laterChoices.add(WORKING_COPY);
        laterChoices.addAll(releases);

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_FROM, "From", Parameter.Kind.CHOICE)
                        .choices(fromChoices.toArray(new String[0]))
                        .defaultValue(fromChoices.get(0))
                        .required()
                        .help("The earlier version. Releases are listed newest first, read from "
                                + "the dated copies under releases/ - which is why keeping those "
                                + "rather than overwriting one file matters.")
                        .build(),
                Parameter.of(OPTION_TO, "To", Parameter.Kind.CHOICE)
                        .choices(laterChoices.toArray(new String[0]))
                        .defaultValue(WORKING_COPY)
                        .required()
                        .help("The later version. '" + WORKING_COPY + "' compares a release "
                                + "against your unreleased edits, which is the question to ask "
                                + "before cutting the next one.")
                        .build(),
                Parameter.of(OPTION_PUBLISHED,
                        "Or compare against a published release (web address or file)",
                        Parameter.Kind.TEXT)
                        .help("Fill this in and it replaces the 'From' side: the release is "
                                + "downloaded from this address and compared against the later "
                                + "version you chose.\n\nThis is ODK's release_diff - it "
                                + "downloads the currently published release and diffs the new "
                                + "build against it, which answers the question a release PR has "
                                + "to answer: what will consumers see change?\n\nUsually the "
                                + "ontology's own PURL, which resolves to whatever is published "
                                + "now.")
                        .build(),
                Parameter.of(OPTION_DIFF_FILE, "Write the axiom diff to (optional)",
                        Parameter.Kind.FILE)
                        .help("Writes ROBOT's axiom diff as markdown, which is what ODK's "
                                + "release_diff target produces at reports/release-diff.md. Needs "
                                + "'Also list every axiom that changed' to be on - that is the "
                                + "diff being written.")
                        .build(),
                Parameter.of(OPTION_AXIOMS, "Also list every axiom that changed",
                        Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("The table above answers what a release note is written from - which "
                                + "terms were added, obsoleted, redefined or moved. This adds "
                                + "ROBOT's own axiom diff underneath it, which answers what "
                                + "exactly changed.\n\nThey are different questions: a term that "
                                + "gained a definition and a term that changed parents both look "
                                + "like 'some axioms went, some came' to an axiom diff, which is "
                                + "why the term-level table exists. Turn this on when reviewing a "
                                + "release rather than describing it.")
                        .build(),
                Parameter.of(OPTION_NOTES, "Write release notes beside the later release",
                        Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("Writes CHANGES.md next to the later release, generated from this "
                                + "comparison rather than remembered. Ignored when the later side "
                                + "is your working copy, which is not a release and has nowhere "
                                + "to put them.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Compare releases",
                "What changed between two versions of this ontology, term by term.", parameters);
        if (chosen == null) {
            return false;
        }
        from = chosen.get(OPTION_FROM);
        to = chosen.get(OPTION_TO);
        writeNotes = "true".equalsIgnoreCase(chosen.get(OPTION_NOTES));
        showAxioms = "true".equalsIgnoreCase(chosen.get(OPTION_AXIOMS));
        publishedUrl = chosen.get(OPTION_PUBLISHED) == null
                ? "" : chosen.get(OPTION_PUBLISHED).trim();
        String diffPath = chosen.get(OPTION_DIFF_FILE);
        diffFile = diffPath == null || diffPath.trim().isEmpty()
                ? null : new File(diffPath.trim());
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        File editFile = fileOf(ontology);
        File projectRoot = ReleaseAction.projectRootOf(editFile);
        String id = Release.ontologyIdFrom(editFile);

        // "From" must be the earlier of the two. Both dropdowns are filled newest-first and the
        // From default is the newest release, so picking the top of each - the natural way to
        // "compare the last two releases" - handed them over backwards. ReleaseDiff.between is
        // asymmetric, so every term added since the older release came back as REMOVED, and the
        // deliberate data-loss warning fired: "N terms were removed rather than obsoleted.
        // Anything that imported them now references nothing." A false alarm of exactly the kind
        // that teaches people to ignore real ones. Compared as versions, not as text: as text
        // 0.10.0 sorts before 0.9.0, so a semantic project's tenth minor release was refused as
        // "earlier" than its ninth.
        if (GIVEN_BELOW.equals(from) && publishedUrl.isEmpty()) {
            return result.failed("This project has no releases of its own yet. Give the web "
                    + "address or the file of the version to compare against, under 'Or compare "
                    + "against a published release'. OntoBoard > Project > Release keeps a dated "
                    + "copy of each release from now on.").build();
        }
        if (!GIVEN_BELOW.equals(from) && !WORKING_COPY.equals(to) && !isEarlier(from, to)) {
            return result.failed("'From' must be an earlier release than 'To'. You picked From="
                    + from + " and To=" + to + ", which would report everything added since "
                    + to + " as having been removed. Both lists are newest-first, so the earlier "
                    + "release is further down.").build();
        }

        OWLOntology earlier;
        OWLOntology later;
        try {
            earlier = publishedUrl.isEmpty()
                    ? load(Release.releaseFile(projectRoot, id, from))
                    : loadPublished(publishedUrl, result);
            later = WORKING_COPY.equals(to) ? ontology
                    : load(Release.releaseFile(projectRoot, id, to));
        } catch (RobotException cannotLoad) {
            return result.failed(cannotLoad.getMessage()).build();
        }
        result.note("From: " + from);
        result.note("To: " + to);

        ReleaseDiff diff = ReleaseDiff.between(earlier, later);
        result.columns("Change", "Term", "Was", "Now");
        for (ReleaseDiff.TermChange change : diff.getChanges()) {
            result.row(change.getChange().getLabel(),
                    ReleaseDiff.shortForm(change.getIri()),
                    change.getBefore(), change.getAfter());
        }

        // Said as a warning, not a row, because it is the one outcome nobody downstream can undo.
        if (!diff.removals().isEmpty()) {
            result.warn(diff.removals().size() + " term"
                    + (diff.removals().size() == 1 ? " was" : "s were")
                    + " removed rather than obsoleted. Anything that imported them now references "
                    + "nothing, and no consumer is told. Obsoleting keeps the term and the "
                    + "reference; removing it does not.");
        }

        if (writeNotes && !WORKING_COPY.equals(to)) {
            File notes = new File(Release.releaseFile(projectRoot, id, to).getParentFile(),
                    "CHANGES.md");
            try {
                java.nio.file.Files.write(notes.toPath(),
                        diff.asReleaseNotes(to, later).getBytes("UTF-8"));
                result.wrote(notes);
            } catch (java.io.IOException cannotWrite) {
                result.warn("Could not write " + notes.getAbsolutePath() + ": "
                        + cannotWrite.getMessage());
            }
        } else if (writeNotes) {
            result.note("No notes written: '" + WORKING_COPY + "' is not a release, so there is "
                    + "nowhere to put them.");
        }

        if (showAxioms) {
            appendAxiomDiff(result, earlier, later);
        }

        return result.summary(diff.summary()).build();
    }

    /**
     * ROBOT's axiom diff, as notes under the term-level table.
     *
     * <p>Notes rather than rows: the table's columns are term-shaped - change, term, before, after -
     * and an axiom diff has no such shape. Forcing it into those columns would misrepresent it.
     *
     * <p>A failure here is a warning, not a failure of the whole comparison: the term-level answer
     * is the one the user asked for, and it has already been computed.
     */
    private void appendAxiomDiff(OperationResult.Builder result, OWLOntology earlier,
            OWLOntology later) {
        try {
            AxiomDiff.Result axioms = AxiomDiff.between(earlier, later, AxiomDiff.defaultOptions());
            if (axioms.isIdentical()) {
                result.note("Axiom diff: the two are identical axiom for axiom.");
                if (diffFile != null) {
                    result.note("Nothing written to " + diffFile.getName()
                            + ": there is no difference to write.");
                }
                return;
            }
            if (diffFile != null) {
                try {
                    java.nio.file.Files.write(diffFile.toPath(),
                            axioms.getText().getBytes("UTF-8"));
                    result.wrote(diffFile);
                } catch (java.io.IOException cannotWrite) {
                    result.warn("Could not write " + diffFile.getAbsolutePath() + ": "
                            + cannotWrite.getMessage());
                }
            }
            java.util.List<String> lines = axioms.getLines();
            result.note("Axiom diff (" + lines.size() + " lines):");
            int shown = 0;
            for (String line : lines) {
                if (shown >= MAX_AXIOM_LINES) {
                    result.note("  ... " + (lines.size() - MAX_AXIOM_LINES) + " more lines.");
                    break;
                }
                result.note("  " + line);
                shown++;
            }
        } catch (RobotException cannotDiff) {
            result.warn("The axiom diff could not be produced, so only the term-level comparison "
                    + "above is available: " + cannotDiff.getMessage());
        }
    }

    /**
     * A release, loaded into a manager of its own.
     *
     * <p>Its own manager because a release declares the same ontology IRI as the edit file, and
     * loading it into Protege's would either collide with what is open or silently return the
     * open one - which would compare a thing with itself and report that nothing had changed.
     */
    /**
     * The published release, downloaded.
     *
     * <p>ODK's release_diff fetches the currently published artefact and diffs the new build
     * against it, which is the question a release pull request has to answer: what will consumers
     * see change? A local release cannot answer that - it is what this project believes it
     * published, not what is actually there.
     */
    private OWLOntology loadPublished(String url, OperationResult.Builder result) {
        result.note("From: " + url + " (downloaded)");
        try {
            // toIri, so a file path works as well as a web address.
            return OntologySource.load(OWLManager.createOWLOntologyManager(),
                    OntologySource.toIri(url));
        } catch (RuntimeException cannotLoad) {
            throw new RobotException("Could not download the published release from " + url
                    + ": " + cannotLoad.getMessage() + ". A PURL that has never been published "
                    + "resolves to nothing, which is the usual reason.", cannotLoad);
        }
    }

    private OWLOntology load(File release) {
        if (!release.isFile()) {
            throw new RobotException("There is no release at " + release.getAbsolutePath());
        }
        return OntologySource.load(OWLManager.createOWLOntologyManager(),
                org.semanticweb.owlapi.model.IRI.create(release.toURI()));
    }

    /** Whether release {@code one} came before {@code other}: by version, or else by name. */
    static boolean isEarlier(String one, String other) {
        de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion first =
                de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion.parseOrNull(one);
        de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion second =
                de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion.parseOrNull(other);
        return first != null && second != null ? first.compareTo(second) < 0
                : one.compareTo(other) < 0;
    }

    /** The dated releases this project has kept, newest first. */
    static List<String> releasesOf(File editFile) {
        List<String> dates = new ArrayList<String>();
        if (editFile == null) {
            return dates;
        }
        File releases = new File(ReleaseAction.projectRootOf(editFile), "releases");
        File[] directories = releases.listFiles();
        if (directories == null) {
            return dates;
        }
        java.util.List<de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion> found =
                new ArrayList<de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion>();
        for (File directory : directories) {
            // Either convention - releases/2026-10-12 or releases/3.0.2. Listing only dated
            // directories made a project on semantic versions look as though it had never
            // released anything, so "compare with the last release" offered nothing at all.
            de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion version = directory.isDirectory()
                    ? de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion
                            .parseOrNull(directory.getName())
                    : null;
            if (version != null) {
                found.add(version);
            }
        }
        // Newest first: the comparison people want is nearly always against the last release.
        // By number, not as text, or 3.9.0 would be offered ahead of 3.10.0.
        for (de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion version
                : de.fizkarlsruhe.ise.ontoboard.odk.OntologyVersion.newestFirst(found)) {
            dates.add(version.getText());
        }
        return dates;
    }

    /** The ontology's own file, or null when it has never been saved. */
}
