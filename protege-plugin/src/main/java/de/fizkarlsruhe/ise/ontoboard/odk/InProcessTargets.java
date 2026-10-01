package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Running a scaffolded project's build without make, without robot, and without a container.
 *
 * <p>The Makefile this plugin generates shells out to nine programs - make, sh, robot, date, echo,
 * rm, cat, mkdir, cp - and every one of its ROBOT invocations already has an in-process
 * implementation in this bundle, because robot-core is embedded as a library. So for a project
 * OntoBoard scaffolded, the whole build can run inside Protege with nothing installed at all. That
 * is the point of this class.
 *
 * <p><b>Only for Makefiles this plugin wrote, and the check is exact.</b> {@link #whyNotEligible}
 * re-renders the Makefile from the project's own settings and compares it to the one on disk. A
 * real ODK Makefile - 767 lines of {@code ifeq}, {@code foreach}, {@code define}, recursive make
 * and a {@code SHELL} override, calling owltools, wget, rsync and an {@code odk.py} this bundle
 * cannot contain - is refused, and so is a generated one the user has edited. Guessing at a
 * Makefile's meaning would produce a build that silently differs from the one their CI runs, which
 * is worse than not building.
 *
 * <p><b>The user's open ontology is never modified.</b> Every target that reasons or stamps works
 * on a copy. This is not a precaution: {@code reason} writes the reasoner's conclusions in as
 * asserted axioms, and doing that to the ontology someone has open in Protege would silently
 * rewrite their work.
 */
public final class InProcessTargets {

    /** The targets of a generated Makefile, in the order the Makefile declares them. */
    public static final List<String> TARGETS = Collections.unmodifiableList(Arrays.asList(
            "all", "test", "reason", "report", "sparql_test", "validate_profile", "clean"));

    /**
     * {@code prepare_release} is deliberately not here.
     *
     * <p>It exists in the generated Makefile, and the plugin can already do it - but through
     * {@code Project > Release}, which asks about the date, refuses to overwrite an existing
     * release, writes the dated and published copies together and reports what it wrote. A second
     * implementation of that behind a target name would be the same operation with fewer guards.
     */
    public static final String RELEASE_INSTEAD =
            "prepare_release is not run here. Use OntoBoard > Project > Release..., which does "
                    + "the same work and additionally refuses to overwrite a release that "
                    + "already exists.";

    /** What a run produced. */
    public static final class Outcome {
        private final boolean ok;
        private final String failure;
        private final List<String> transcript;
        private final List<File> written;

        Outcome(boolean ok, String failure, List<String> transcript, List<File> written) {
            this.ok = ok;
            this.failure = failure;
            this.transcript = transcript;
            this.written = written;
        }

        public boolean isOk() {
            return ok;
        }

        /** Why it failed, or null. */
        public String getFailure() {
            return failure;
        }

        public List<String> getTranscript() {
            return transcript;
        }

        public List<File> getWritten() {
            return written;
        }
    }

    private InProcessTargets() {
    }

    /** The project root, two levels above the edit file, or null. */
    public static File projectRoot(File editFile) {
        File ontology = editFile == null ? null : editFile.getParentFile();
        File src = ontology == null ? null : ontology.getParentFile();
        return src == null ? null : src.getParentFile();
    }

    /**
     * Why this project's build cannot run in-process, or null when it can.
     *
     * <p>The test is byte equality against a freshly rendered Makefile, through
     * {@link OdkRegenerator}, which already renders and compares with the right line endings.
     * Anything else - a foreign Makefile, an edited one, a project with no settings file - is
     * refused by name rather than attempted.
     */
    public static String whyNotEligible(File editFile) {
        File root = projectRoot(editFile);
        if (root == null || !root.isDirectory()) {
            return "This ontology is not inside a project, so there is no build to run.";
        }
        File makefile = new File(new File(new File(root, "src"), "ontology"), "Makefile");
        if (!makefile.isFile()) {
            return "There is no src/ontology/Makefile in " + root.getAbsolutePath() + ".";
        }
        OdkRegenerator.Plan plan;
        try {
            plan = OdkRegenerator.plan(root);
        } catch (RuntimeException cannotRead) {
            return "This looks like a project OntoBoard did not create: " + cannotRead.getMessage()
                    + " Its build has to be run the way that project expects, which for an ODK "
                    + "repository means Docker or a native ODK environment.";
        }
        for (OdkRegenerator.Change change : plan.getChanges()) {
            if (!"src/ontology/Makefile".equals(change.getPath())) {
                continue;
            }
            if (change.getAfter().equals(change.getBefore())) {
                return null;
            }
            return "This project's Makefile is not the one OntoBoard generates - it has been "
                    + "edited, or was written by a different version. Running it here would run "
                    + "something other than what it says, so it is left to make.";
        }
        return "This project has no Makefile OntoBoard recognises.";
    }

    /**
     * Runs one target.
     *
     * @param editFile the project's edit file, which locates everything else
     * @param ontology the ontology as Protege has it; never modified
     * @param reasonerFactory the reasoner for targets that need one
     * @param target one of {@link #TARGETS}
     */
    public static Outcome run(File editFile, OWLOntology ontology,
            OWLReasonerFactory reasonerFactory, String target) {
        List<String> log = new ArrayList<String>();
        List<File> written = new ArrayList<File>();
        String name = target == null ? "" : target.trim();

        if ("prepare_release".equals(name)) {
            return new Outcome(false, RELEASE_INSTEAD, log, written);
        }
        if (!TARGETS.contains(name)) {
            return new Outcome(false, "No target called '" + name + "' in this Makefile.",
                    log, written);
        }

        File root = projectRoot(editFile);
        List<String> steps = stepsFor(name);
        log.add("Running " + name + " inside Protege - no make, no robot, no container.");
        log.add("Steps: " + steps);

        for (String step : steps) {
            String failure = runStep(step, editFile, root, ontology, reasonerFactory, log, written);
            if (failure != null) {
                return new Outcome(false, failure, log, written);
            }
        }
        return new Outcome(true, null, log, written);
    }

    /** What a composite target expands to, matching the generated Makefile's dependencies. */
    static List<String> stepsFor(String target) {
        if ("all".equals(target)) {
            return Arrays.asList("reason", "report");
        }
        if ("test".equals(target)) {
            return Arrays.asList("reason", "report", "sparql_test", "validate_profile");
        }
        return Collections.singletonList(target);
    }

    private static String runStep(String step, File editFile, File root, OWLOntology ontology,
            OWLReasonerFactory reasonerFactory, List<String> log, List<File> written) {
        if ("reason".equals(step)) {
            return reason(editFile, ontology, reasonerFactory, log, written);
        }
        if ("report".equals(step)) {
            return report(editFile, ontology, log, written);
        }
        if ("sparql_test".equals(step)) {
            return sparqlTest(root, ontology, log);
        }
        if ("validate_profile".equals(step)) {
            return validateProfile(ontology, log);
        }
        if ("clean".equals(step)) {
            return clean(editFile, log);
        }
        return "Unknown step '" + step + "'.";
    }

    /**
     * {@code robot reason} into {@code <id>.owl}, on a copy.
     *
     * <p>The two flags the generated Makefile passes are not spelled out there either: it
     * interpolates {@link RobotTransform}'s own constants, so the CLI recipe and this path cannot
     * drift apart. {@code RobotTransform.preview} returns changes aimed at the ontology it was
     * given, so it is given the copy.
     */
    private static String reason(File editFile, OWLOntology ontology,
            OWLReasonerFactory reasonerFactory, List<String> log, List<File> written) {
        OWLOntology copy;
        try {
            copy = copyOf(ontology);
        } catch (OWLOntologyCreationException cannotCopy) {
            return "Could not take a working copy of the ontology: " + cannotCopy.getMessage();
        }
        try {
            RobotTransform.Diff inferred =
                    RobotTransform.preview(copy, RobotTransform.Kind.REASON, reasonerFactory);
            copy.getOWLOntologyManager().applyChanges(inferred.getChanges());
            log.add("reason: " + inferred.getAdded() + " inferred axioms written in");
        } catch (RuntimeException cannotReason) {
            return "The reasoner could not finish, so nothing was written: "
                    + cannotReason.getMessage();
        }
        File output = new File(editFile.getParentFile(), Release.ontologyIdFrom(editFile) + ".owl");
        try {
            copy.getOWLOntologyManager().saveOntology(copy, IRI.create(output.toURI()));
        } catch (Exception cannotWrite) {
            return "Could not write " + output.getName() + ": " + cannotWrite.getMessage();
        }
        log.add("reason: wrote " + output.getAbsolutePath());
        written.add(output);
        return null;
    }

    /** {@code robot report}, with the project's own profile.txt, exactly as the recipe does. */
    private static String report(File editFile, OWLOntology ontology, List<String> log,
            List<File> written) {
        List<QualityFinding> findings;
        try {
            findings = QualityReport.run(ontology, QualityReport.optionsFor(editFile));
        } catch (RuntimeException cannotReport) {
            return "The quality report could not run: " + cannotReport.getMessage();
        }
        int errors = 0;
        for (QualityFinding finding : findings) {
            if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                errors++;
            }
        }
        File output = new File(editFile.getParentFile(), "report.tsv");
        try {
            // ROBOT's own report.tsv columns, minus the two this in-process path does not
            // carry: QualityFinding keeps the rendered message rather than the raw property and
            // value, because that is what every other caller in the plugin shows.
            StringBuilder tsv = new StringBuilder("Level\tRule Name\tSubject\tMessage\n");
            for (QualityFinding finding : findings) {
                tsv.append(finding.getSeverity()).append('\t').append(finding.getRule())
                        .append('\t').append(finding.getSubject()).append('\t')
                        .append(finding.getMessage()).append('\n');
            }
            java.nio.file.Files.write(output.toPath(), tsv.toString().getBytes("UTF-8"));
        } catch (Exception cannotWrite) {
            return "Could not write report.tsv: " + cannotWrite.getMessage();
        }
        written.add(output);
        log.add("report: " + findings.size() + " findings, " + errors + " of them errors; wrote "
                + output.getName());
        // The recipe passes --fail-on ERROR, so this does too.
        return errors == 0 ? null
                : "report found " + errors + " error-level violations (--fail-on ERROR). "
                        + "They are listed in " + output.getName() + ".";
    }

    /** {@code robot verify} over the project's own {@code src/sparql/*.rq}. */
    private static String sparqlTest(File root, OWLOntology ontology, List<String> log) {
        List<File> checks = SparqlQuery.checksIn(root);
        if (checks.isEmpty()) {
            log.add("sparql_test: no .rq files under " + SparqlQuery.CHECKS_DIRECTORY
                    + ", nothing to check");
            return null;
        }
        int violations = 0;
        for (SparqlQuery.Check check : SparqlQuery.verify(ontology, checks)) {
            log.add("sparql_test: " + (check.isPassed() ? "PASS " : "FAIL ") + check.getName()
                    + ": " + check.getViolations() + " violation(s)");
            violations += check.getViolations();
        }
        return violations == 0 ? null
                : "sparql_test found " + violations + " violation(s) across "
                        + checks.size() + " check(s).";
    }

    /** {@code robot merge | convert | validate-profile --profile DL}, read from the model. */
    private static String validateProfile(OWLOntology ontology, List<String> log) {
        List<ProfileCheck.Violation> violations =
                ProfileCheck.violations(ontology, ProfileCheck.Target.DL);
        if (violations.isEmpty()) {
            log.add("validate_profile: in OWL 2 DL");
            return null;
        }
        int shown = 0;
        for (ProfileCheck.Violation violation : violations) {
            if (shown++ >= 20) {
                log.add("validate_profile: ... and " + (violations.size() - 20) + " more");
                break;
            }
            log.add("validate_profile: " + violation.getMessage());
        }
        return "validate_profile found " + violations.size() + " OWL 2 DL violation(s).";
    }

    /** What {@code make clean} removes, and nothing else. */
    private static String clean(File editFile, List<String> log) {
        File directory = editFile.getParentFile();
        int removed = 0;
        List<String> names = Arrays.asList(Release.ontologyIdFrom(editFile) + ".owl", "report.tsv",
                "validate-profile.txt", "tmp_validate.ofn");
        for (String name : names) {
            File victim = new File(directory, name);
            if (victim.isFile() && victim.delete()) {
                removed++;
                log.add("clean: removed " + name);
            }
        }
        log.add("clean: " + removed + " file(s) removed");
        return null;
    }

    /**
     * A working copy, so nothing here can touch what Protege has open.
     *
     * <p>A fresh manager rather than the ontology's own: adding a second copy of the same
     * ontology IRI to the manager Protege is using would collide with the original.
     */
    private static OWLOntology copyOf(OWLOntology ontology) throws OWLOntologyCreationException {
        OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        // Built by hand from the axiom set rather than with copyOntology, which is the shape
        // ReleaseAction already uses and which is known to behave the same on both supported OWL
        // API versions - 4.5.9 under Protege 5.5.0 and 4.5.29 under 5.6.9. The imports are kept
        // as declarations rather than merged, because the generated `reason` recipe does not
        // merge either; the header annotations come across so the written file still identifies
        // itself.
        IRI iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get()
                : null;
        OWLOntology copy = iri == null
                ? manager.createOntology(new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>(
                        ontology.getAxioms(org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)))
                : manager.createOntology(new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>(
                        ontology.getAxioms(org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)),
                        iri);
        for (org.semanticweb.owlapi.model.OWLImportsDeclaration declaration
                : ontology.getImportsDeclarations()) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddImport(copy, declaration));
        }
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : ontology.getAnnotations()) {
            manager.applyChange(
                    new org.semanticweb.owlapi.model.AddOntologyAnnotation(copy, annotation));
        }
        return copy;
    }
}
