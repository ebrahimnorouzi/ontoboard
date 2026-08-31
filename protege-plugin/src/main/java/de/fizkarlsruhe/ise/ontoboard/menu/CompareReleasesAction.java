package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
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

    /** What the later side is when somebody wants to compare a release against their edits. */
    private static final String WORKING_COPY = "what I have open now";

    private volatile String from = "";
    private volatile String to = WORKING_COPY;
    private volatile boolean writeNotes;

    @Override
    protected String operationName() {
        return "Compare releases";
    }

    @Override
    protected boolean configure() {
        File editFile = fileOf(getOWLModelManager().getActiveOntology());
        List<String> releases = releasesOf(editFile);
        if (releases.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This project has no releases to compare against yet.\n\nOntoBoard > Project "
                            + "> Release keeps a dated copy under releases/, and from the second "
                            + "one onwards this can tell you what changed.",
                    "Nothing to compare", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        List<String> laterChoices = new ArrayList<String>();
        laterChoices.add(WORKING_COPY);
        laterChoices.addAll(releases);

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_FROM, "From", Parameter.Kind.CHOICE)
                        .choices(releases.toArray(new String[0]))
                        .defaultValue(releases.get(0))
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
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        File editFile = fileOf(ontology);
        File projectRoot = ReleaseAction.projectRootOf(editFile);
        String id = Release.ontologyIdFrom(editFile);

        OWLOntology earlier;
        OWLOntology later;
        try {
            earlier = load(Release.releaseFile(projectRoot, id, from));
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

        return result.summary(diff.summary()).build();
    }

    /**
     * A release, loaded into a manager of its own.
     *
     * <p>Its own manager because a release declares the same ontology IRI as the edit file, and
     * loading it into Protege's would either collide with what is open or silently return the
     * open one - which would compare a thing with itself and report that nothing had changed.
     */
    private OWLOntology load(File release) {
        if (!release.isFile()) {
            throw new RobotException("There is no release at " + release.getAbsolutePath());
        }
        return OntologySource.load(OWLManager.createOWLOntologyManager(),
                org.semanticweb.owlapi.model.IRI.create(release.toURI()));
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
        for (File directory : directories) {
            if (directory.isDirectory() && directory.getName().matches("\\d{4}-\\d{2}-\\d{2}")) {
                dates.add(directory.getName());
            }
        }
        // Newest first: the comparison people want is nearly always against the last release.
        Collections.sort(dates, Collections.reverseOrder());
        return dates;
    }

    /** The ontology's own file, or null when it has never been saved. */
    private File fileOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        try {
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
