package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Release;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotException;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import java.io.File;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Project &gt; Release - turn the edit file into a published version.
 *
 * <p>The ODK layout already gives a project two files: {@code mwo-edit.owl}, which people edit, and
 * {@code mwo.owl}, which the build produces. That is the "edit version and published version" half
 * of the request, and it is already there. What was missing is everything that makes the second one
 * a release rather than a copy.
 *
 * <p>A release without a version IRI is a file with the same name as last month's. Somebody who
 * imported it has no way to say which one their results came from, no way to pin it, and no way to
 * tell whether a disagreement with a colleague is about method or about which Tuesday they
 * downloaded it. So this stamps a dated version IRI, keeps a dated copy that will never change
 * again, and refuses to run over a report the project's own rules call failing.
 *
 * <p>The edit ontology is never touched. Everything happens on a copy in a manager of its own, and
 * what Protege has open is exactly as it was whether the release succeeds or fails.
 */
public class ReleaseAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_DATE = "date";
    private static final String OPTION_CHECK = "check";
    private static final String OPTION_REASON = "reason";
    private static final String OPTION_REASONER = "reasoner";
    private static final String OPTION_MERGE = "merge";
    private static final String OPTION_STRIP = "strip";

    private volatile String date = "";
    private volatile boolean check = true;
    private volatile boolean reason = true;
    private volatile Reasoners.Choice reasoner = Reasoners.DEFAULT;
    private volatile boolean merge;
    private volatile boolean strip;

    @Override
    protected String operationName() {
        return "Release";
    }

    @Override
    protected boolean configure() {
        String today = LocalDate.now().toString();
        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_DATE, "Release date", Parameter.Kind.TEXT)
                        .defaultValue(today)
                        .required()
                        .help("YYYY-MM-DD, and it is not decoration: it becomes the version IRI "
                                + "(.../releases/" + today + "/<id>.owl) and the name of the "
                                + "directory the copy is kept in, so it is what anybody uses to "
                                + "say which release they were looking at. Change it only to "
                                + "reproduce a release you meant to cut on an earlier day.")
                        .build(),
                Parameter.of(OPTION_CHECK, "Refuse to release on an error",
                        Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("Two checks. ROBOT's quality report, with this project's own "
                                + "profile.txt, stopping if anything comes back at error. And "
                                + "whether this release drops a term the last one published - "
                                + "which breaks every ontology that imported it, silently, and is "
                                + "almost always a mistake nobody noticed. A release is the one "
                                + "artefact you cannot take back once somebody has imported it, "
                                + "so it is the right place to be strict. The report needs "
                                + "Protege 5.6; on 5.5 it is skipped and the result says so "
                                + "rather than pretending it passed.")
                        .build(),
                Parameter.of(OPTION_REASON, "Write down the inferences", Parameter.Kind.FLAG)
                        .defaultValue("true")
                        .help("Runs the reasoner and records its conclusions as ordinary subclass "
                                + "axioms, so a consumer who never runs a reasoner still sees the "
                                + "whole hierarchy. This is what 'make reason' does and what most "
                                + "distinguishes a release from the edit file.")
                        .build(),
                Parameter.of(OPTION_REASONER, "Reasoner", Parameter.Kind.CHOICE)
                        .choices(Reasoners.labels().toArray(new String[0]))
                        .defaultValue(Reasoners.DEFAULT.getLabel())
                        .help(Reasoners.help())
                        .build(),
                Parameter.of(OPTION_MERGE, "Merge the imports in", Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("Copies every imported axiom into the release and drops the import "
                                + "statements, giving one self-contained file. OBO projects "
                                + "publish this as the '-full' artefact alongside the ordinary "
                                + "one, because it is convenient to consume and enormous to read. "
                                + "Off gives the release the same imports the edit file has.")
                        .build(),
                Parameter.of(OPTION_STRIP, "Leave the editorial notes out", Parameter.Kind.FLAG)
                        .defaultValue("false")
                        .help("Removes IAO:0000116 editor notes and IAO:0000232 curator notes "
                                + "from the release. Their own definition permits this - a note "
                                + "'may not be included in the publication version' - but it is "
                                + "permission, not instruction, and plenty of published OBO "
                                + "ontologies keep them because they explain the modelling. Off "
                                + "by default for that reason.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Release",
                "Produces the published version from the file you have open. Your edit file is "
                        + "not modified.",
                parameters);
        if (chosen == null) {
            return false;
        }
        date = chosen.get(OPTION_DATE).trim();
        check = "true".equalsIgnoreCase(chosen.get(OPTION_CHECK));
        reason = "true".equalsIgnoreCase(chosen.get(OPTION_REASON));
        reasoner = Reasoners.byLabel(chosen.get(OPTION_REASONER));
        merge = "true".equalsIgnoreCase(chosen.get(OPTION_MERGE));
        strip = "true".equalsIgnoreCase(chosen.get(OPTION_STRIP));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());

        File editFile = fileOf(ontology);
        if (editFile == null) {
            return result.failed("This ontology has not been saved, so there is no project to "
                    + "release it into. Save it first.").build();
        }
        String id = Release.ontologyIdFrom(editFile);
        File projectRoot = projectRootOf(editFile);
        result.note("Project: " + projectRoot.getAbsolutePath());
        result.note("Date: " + date);

        // Refuse before doing any work. A release is the one artefact that cannot be taken back
        // once somebody has imported it.
        if (check) {
            String refusal = whatTheReportSays(ontology, editFile, result);
            if (refusal != null) {
                return result.failed(refusal).build();
            }
        } else {
            result.warn("The quality report was not run, so nothing checked this release against "
                    + "the project's own rules.");
        }

        String overwriting = Release.wouldOverwrite(projectRoot, id, date);
        if (overwriting != null) {
            result.warn(overwriting);
        }

        OWLOntology release;
        try {
            release = copyForRelease(ontology);
        } catch (Exception cannotCopy) {
            return result.failed("Could not prepare the release: " + cannotCopy.getMessage())
                    .build();
        }

        if (reason) {
            try {
                RobotTransform.Diff inferred = RobotTransform.preview(release,
                        RobotTransform.Kind.REASON, reasoner.newFactory());
                release.getOWLOntologyManager().applyChanges(inferred.getChanges());
                result.note("Reasoner: " + reasoner.getLabel());
                result.note("Inferences written: " + inferred.getAdded());
            } catch (RobotException cannotReason) {
                return result.failed("The reasoner could not finish, so nothing was written: "
                        + cannotReason.getMessage()).build();
            }
        }
        if (strip) {
            List<org.semanticweb.owlapi.model.OWLOntologyChange> removed =
                    EditorNotes.strip(release);
            release.getOWLOntologyManager().applyChanges(removed);
            result.note("Editorial notes removed: " + removed.size());
        }

        try {
            release.getOWLOntologyManager().applyChanges(Release.stamp(release, date));
        } catch (IllegalArgumentException cannotStamp) {
            return result.failed(cannotStamp.getMessage()).build();
        }
        result.note("Version IRI: " + Release.versionIriOf(release));

        if (BackgroundRun.abandoned()) {
            return result.failed("Stopped before anything was written.").build();
        }

        // Against the last release, before anything is written. A term that is gone rather than
        // obsoleted breaks every ontology that imported it and tells nobody, so this is the last
        // moment it can be caught for free.
        ReleaseDiff diff = null;
        String previous = previousRelease(projectRoot, id, date);
        if (previous != null) {
            try {
                diff = ReleaseDiff.between(
                        loadRelease(Release.releaseFile(projectRoot, id, previous)), release);
                result.note("Compared with the " + previous + " release: " + diff.summary());
                if (!diff.removals().isEmpty()) {
                    for (ReleaseDiff.TermChange removed : diff.removals()) {
                        result.warn("Removed: " + ReleaseDiff.shortForm(removed.getIri())
                                + (removed.getBefore().isEmpty() ? ""
                                        : " (" + removed.getBefore() + ")"));
                    }
                    if (check) {
                        return result.failed(diff.removals().size() + " term"
                                + (diff.removals().size() == 1 ? " that the " : "s that the ")
                                + previous + " release published "
                                + (diff.removals().size() == 1 ? "is" : "are")
                                + " gone from this one, and nothing was written. Obsolete them "
                                + "instead - mark them owl:deprecated with a replacement - so "
                                + "everything that imported them still resolves. Turn the check "
                                + "off if you genuinely mean to drop them.").build();
                    }
                }
            } catch (RuntimeException cannotCompare) {
                result.warn("Could not compare with the " + previous + " release: "
                        + cannotCompare.getMessage());
            }
        }

        // The formats the project declares, which OntoBoard's own scaffold writes into every
        // project it creates and then, until 1.98.0, ignored: a release from this menu was one
        // RDF/XML file whatever export_formats said, so it disagreed with the build of the very
        // project it had generated. NFDIcore asks for owl and ttl.
        de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats.Wanted formats =
                de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats.wantedBy(
                        editFile.getAbsoluteFile().getParentFile());
        if (formats.wasDeclared()) {
            StringBuilder named = new StringBuilder();
            for (de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats.Format format
                    : formats.getWritable()) {
                named.append(named.length() == 0 ? "" : ", ").append(format.getKey());
            }
            result.note("Formats, from this project's export_formats: " + named);
        }

        File dated = Release.releaseFile(projectRoot, id, date);
        // The project root, which is where the generated Makefile's prepare_release copies it
        // (cp ../../releases/$(TODAY)/$(ONT).owl ../../$(ONT).owl) and where a PURL resolves. This
        // wrote src/ontology/<id>.owl instead - the file `make reason` overwrites and `make clean`
        // deletes, and which the scaffold gitignores for exactly that reason. So the menu and the
        // build published to two different places, and the menu's copy was one `git add -A` would
        // not even stage.
        File published = new File(projectRoot, id + ".owl");
        // Never the file the user has open. projectRootOf falls back to the ontology's own
        // directory for any layout that is not src/ontology/, and ontologyIdFrom only strips an
        // "-edit" suffix - so an ontology opened at C:/work/pizza.owl gave a project root of
        // C:/work and a published path of C:/work/pizza.owl, which is the edit file. The release
        // was then written over it: inferences materialised as asserted axioms, editorial notes
        // stripped if that box was ticked, a dated versionIRI stamped in - while the dialog said
        // the edit file would not be modified.
        try {
            if (published.getCanonicalFile().equals(editFile.getCanonicalFile())) {
                return result.failed("The release would be written over " + editFile.getName()
                        + ", which is the file you have open. That happens when the ontology is "
                        + "not in an ODK layout: the published copy is named after the ontology "
                        + "and lands beside it. Move the ontology into src/ontology/ (or rename "
                        + "it <id>-edit.owl) so the release has somewhere of its own to go.")
                        .build();
            }
        } catch (java.io.IOException cannotCompare) {
            return result.failed("Could not work out where the release would go relative to "
                    + editFile.getName() + ": " + cannotCompare.getMessage()).build();
        }
        try {
            if (dated.getParentFile() != null && !dated.getParentFile().isDirectory()
                    && !dated.getParentFile().mkdirs()) {
                return result.failed("Could not create " + dated.getParentFile().getAbsolutePath())
                        .build();
            }
            OWLOntologyManager manager = release.getOWLOntologyManager();
            for (de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats.Format format
                    : formats.getWritable()) {
                manager.saveOntology(release, format.newWriter(),
                        IRI.create(ExportFormats.named(dated, format).toURI()));
                manager.saveOntology(release, format.newWriter(),
                        IRI.create(ExportFormats.named(published, format).toURI()));
            }
        } catch (Exception cannotWrite) {
            return result.failed("Could not write the release: " + cannotWrite.getMessage())
                    .build();
        }
        for (de.fizkarlsruhe.ise.ontoboard.odk.ExportFormats.Format format
                : formats.getWritable()) {
            result.wrote(ExportFormats.named(dated, format));
            result.wrote(ExportFormats.named(published, format));
        }
        // What the project asked for and did not get. A release missing a file somebody's
        // pipeline consumes is worse when nothing said it was missing.
        if (!formats.getUnsupported().isEmpty()) {
            result.warn("This project's export_formats also asks for "
                    + String.join(", ", formats.getUnsupported())
                    + ". Those are not written here; the project's own build produces them.");
        }

        // Generated, not remembered. Release notes written from memory are written once, badly,
        // and then not at all.
        if (diff != null) {
            File notes = new File(dated.getParentFile(), "CHANGES.md");
            try {
                java.nio.file.Files.write(notes.toPath(),
                        diff.asReleaseNotes(date, release).getBytes("UTF-8"));
                result.wrote(notes);
            } catch (java.io.IOException cannotWrite) {
                result.warn("The release was written but its notes were not: "
                        + cannotWrite.getMessage());
            }
        }

        result.note("The dated copy is what the version IRI names, so leave it alone - it is the "
                + "only thing that makes the IRI mean anything.");
        result.note("The copy at the project root is the same file under the name a PURL resolves "
                + "to, which is where `make prepare_release` puts it as well.");
        if (!merge && !release.getImportsDeclarations().isEmpty()) {
            // Found by releasing a real project: the dated copy sits under releases/, outside the
            // directory catalog-v001.xml covers, so its imports resolve only over the network.
            // That is how OBO publishes - the PURL layer answers for them - but it means the file
            // is not self-contained, and somebody opening the dated copy offline gets an ontology
            // missing everything the imports hold.
            result.warn("This release keeps its " + release.getImportsDeclarations().size()
                    + " import statement"
                    + (release.getImportsDeclarations().size() == 1 ? "" : "s")
                    + ", and the dated copy sits outside the directory catalog-v001.xml covers - "
                    + "so opening it will try to fetch them over the network. That is what OBO "
                    + "publishes; tick 'Merge the imports in' if you want a file that stands on "
                    + "its own.");
        }
        return result.summary("Released " + id + " " + date + ": " + release.getAxiomCount()
                + " axioms, written to " + published.getName() + " and kept as "
                + relative(projectRoot, dated) + ".").build();
    }

    /**
     * Why the release should not go out, or null.
     *
     * <p>Against the project's own {@code profile.txt}, so this agrees with what its CI would say
     * rather than with ROBOT's defaults.
     */
    private String whatTheReportSays(OWLOntology ontology, File editFile,
            OperationResult.Builder result) {
        Map<String, String> options = QualityReport.optionsFor(editFile);
        options.put(QualityReport.OPTION_FAIL_ON, "error");
        try {
            List<QualityFinding> findings = QualityReport.run(ontology, options);
            int errors = 0;
            for (QualityFinding finding : findings) {
                if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                    errors++;
                    if (errors <= 10) {
                        result.warn(finding.getRule() + ": " + finding.getSubject() + " - "
                                + finding.getMessage());
                    }
                }
            }
            File profile = QualityReport.profileBeside(editFile);
            result.note("Checked against: "
                    + (profile == null ? "ROBOT's built-in profile" : profile.getAbsolutePath()));
            if (errors > 0) {
                return errors + " error" + (errors == 1 ? "" : "s")
                        + " in the quality report, so nothing was written. Fix them, or turn the "
                        + "check off if you know what you are publishing.";
            }
            return null;
        } catch (RobotException cannotReport) {
            // Not a silent pass. A release that skipped its own gate and did not say so is worse
            // than one that refused.
            result.warn("The quality report could not run here, so this release was not checked: "
                    + cannotReport.getMessage());
            return null;
        }
    }

    /**
     * The most recent release before {@code date}, or null when this is the first.
     *
     * <p>Dates sort lexically because they are ISO, which is most of why the format is insisted
     * on elsewhere.
     */
    static String previousRelease(File projectRoot, String ontologyId, String date) {
        java.util.List<String> dates = new java.util.ArrayList<String>();
        File releases = new File(projectRoot, "releases");
        File[] directories = releases.listFiles();
        if (directories == null) {
            return null;
        }
        for (File directory : directories) {
            if (directory.isDirectory()
                    && directory.getName().matches("\\d{4}-\\d{2}-\\d{2}")
                    && directory.getName().compareTo(date) < 0
                    && Release.releaseFile(projectRoot, ontologyId,
                            directory.getName()).isFile()) {
                dates.add(directory.getName());
            }
        }
        if (dates.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(dates);
        return dates.get(dates.size() - 1);
    }

    /**
     * A previous release, in a manager of its own.
     *
     * <p>Its own manager because it declares the same ontology IRI as the edit file: loading it
     * into Protege's would either collide with what is open or quietly hand back the open one,
     * and comparing a thing with itself reports that nothing changed.
     */
    private OWLOntology loadRelease(File file) {
        return de.fizkarlsruhe.ise.ontoboard.robot.OntologySource.load(
                OWLManager.createOWLOntologyManager(), IRI.create(file.toURI()));
    }

    /** The release ontology, in a manager of its own so the edit file is untouched. */
    private OWLOntology copyForRelease(OWLOntology ontology) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get()
                : null;
        if (iri == null) {
            throw new IllegalStateException("This ontology has no IRI of its own, so it cannot "
                    + "carry a version IRI. Give it one in the ontology header before releasing.");
        }
        OWLOntology release = manager.createOntology(
                new HashSet<OWLAxiom>(ontology.getAxioms(
                        merge ? Imports.INCLUDED : Imports.EXCLUDED)), iri);
        if (!merge) {
            // The imports travel with the release; only a merged one is self-contained.
            for (org.semanticweb.owlapi.model.OWLImportsDeclaration declaration
                    : ontology.getImportsDeclarations()) {
                manager.applyChange(new org.semanticweb.owlapi.model.AddImport(release,
                        declaration));
            }
        }
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : ontology.getAnnotations()) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(release,
                    annotation));
        }
        return release;
    }

    /**
     * The project root, which for an ODK layout is two directories above the edit file.
     *
     * <p>{@code src/ontology/mwo-edit.owl} means releases belong in {@code <project>/releases},
     * not in {@code src/ontology/releases}.
     */
    static File projectRootOf(File editFile) {
        File ontologyDir = editFile.getParentFile();
        if (ontologyDir == null) {
            return new File(".");
        }
        File src = ontologyDir.getParentFile();
        if ("ontology".equalsIgnoreCase(ontologyDir.getName()) && src != null
                && "src".equalsIgnoreCase(src.getName()) && src.getParentFile() != null) {
            return src.getParentFile();
        }
        return ontologyDir;
    }

    private static String relative(File root, File file) {
        try {
            return root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException elsewhere) {
            return file.getAbsolutePath();
        }
    }

    /** The ontology's own file, or null when it has never been saved. */
}
