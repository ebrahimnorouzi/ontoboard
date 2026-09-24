package de.fizkarlsruhe.ise.ontoboard.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff;
import de.fizkarlsruhe.ise.ontoboard.reason.InferredEdges;
import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologyMeasurements;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Writes the pizza project as a real ODK repository, one release at a time.
 *
 * <p>Driven from the command line so an entire release history can be produced and committed
 * without anything being edited by hand:
 *
 * <pre>
 * mvn -o test -Dtest=PizzaProjectTest -Dpizza.repo=/somewhere -Dpizza.version=v1
 * </pre>
 *
 * <p>Everything it writes comes from the plugin's own code - {@link OdkScaffold} for the project
 * layout, {@link OntologyProjection} for what the canvas would show, {@link InferredEdges} for what
 * the reasoner concludes, {@link ReleaseDiff} for the evolution. Nothing here reimplements a
 * feature in order to demonstrate it, which would prove only that this file works.
 */
class PizzaProjectTest {

    /** Newline for the Markdown this writes, spelled once. */
    private static final String LF = "\n";

    private static final String ID = "pizza";

    private File repo;
    private String version;

    /** The ORCID recorded as the curator, fixed so the output is reproducible. */
    private static final String CURATOR = "https://orcid.org/0000-0002-1825-0097";

    /** The curation date, fixed for the same reason - nothing here reads a clock. */
    private static final String CURATED_ON = "2026-09-23";

    private OWLOntology ontologyFor(String which) throws Exception {
        if ("v1".equals(which)) {
            return PizzaOntology.v1();
        }
        if ("v2".equals(which)) {
            return PizzaOntology.v2();
        }
        if ("v3".equals(which)) {
            return PizzaOntology.v3();
        }
        return PizzaOntology.v4(CURATOR, CURATED_ON);
    }

    @Test
    void buildsThePublishableProject() throws Exception {
        repo = new File(System.getProperty("pizza.repo", "target/pizza-repo"));
        version = System.getProperty("pizza.version", "v3");
        OWLOntology pizza = ontologyFor(version);

        // --- the ODK project, scaffolded exactly as the plugin's wizard scaffolds one ----
        //
        // The scaffold puts the project in a directory named after the ontology id, under the
        // target directory it is given - so the project root is not the directory handed to it.
        // Taking getProjectRoot() rather than assuming means this keeps working if that changes.
        OdkProjectConfig config = new OdkProjectConfig(ID, "Pizza Ontology",
                "A pizza ontology built end to end with OntoBoard, to exercise the plugin "
                        + "against ODK and ROBOT.",
                PizzaOntology.IRI_BASE,
                "https://creativecommons.org/publicdomain/zero/1.0/", repo.getParentFile());
        repo = config.getProjectRoot();
        File ontologyDir = new File(repo, "src/ontology");
        if (!new File(ontologyDir, ID + "-odk.yaml").isFile()) {
            // validate() refuses to write into a directory that already exists, which is right -
            // scaffolding over somebody's project would destroy it. So it is asked only on the
            // run that actually scaffolds; later releases edit a project that is already there.
            config.validate();
            List<File> written = OdkScaffold.create(config);
            assertFalse(written.isEmpty(), "the scaffold wrote nothing");
        }
        assertTrue(new File(ontologyDir, "Makefile").isFile(), "no Makefile - ODK cannot build");
        assertTrue(new File(ontologyDir, "catalog-v001.xml").isFile(), "no catalog");
        assertTrue(new File(ontologyDir, ID + "-idranges.owl").isFile(), "no ID ranges");

        // --- this release's edit file ---------------------------------------------------
        File editFile = new File(ontologyDir, ID + "-edit.owl");
        pizza.getOWLOntologyManager().saveOntology(pizza, IRI.create(editFile.toURI()));
        assertTrue(editFile.length() > 0);

        File reports = new File(repo, "reports/" + version);
        if (!reports.isDirectory() && !reports.mkdirs()) {
            throw new IOException("could not create " + reports);
        }

        // The import first. It adds an owl:imports to the ontology and re-saves the edit file,
        // so measuring before it ran described an ontology with no imports - every "... incl"
        // measure equal to its own non-incl twin, in a report sitting beside imports.md, which
        // documented the module it could not see.
        if ("v4".equals(version)) {
            writeImportReport(pizza, ontologyDir, reports);
        }

        writeCanvasReport(pizza, reports);
        writeReasonerReport(pizza, reports);
        writeRobotReport(pizza, reports);
        writeProfileReport(pizza, reports);
        writeIdRangeReport(ontologyDir, reports);
        writeTemplateReport(pizza, repo, reports);
        if (!"v1".equals(version)) {
            writeEvolutionReport(reports);
        }
        if ("v4".equals(version)) {
            writeCurationReport(pizza, reports);
        }
        writeMakeTargetsReport(editFile, reports);
    }

    // ------------------------------------------------------------------ the reports

    /** What the canvas would draw. The closest this machine can get to a screenshot. */
    private void writeCanvasReport(OWLOntology pizza, File reports) throws IOException {
        Set<String> onCanvas = OntologyProjection.everythingWorthShowing(pizza);
        Projection projection = OntologyProjection.project(pizza, onCanvas);

        StringBuilder text = new StringBuilder("# Canvas - " + version + "\n\n");
        text.append("What OntoBoard's board shows after pressing **Add all**. ");
        text.append("No Protege was involved in producing this: it is the output of the same\n");
        text.append("projection the canvas renders, so it is what would be drawn, not a picture\n");
        text.append("of what was drawn.\n\n");
        text.append("`Add all` offers **").append(onCanvas.size()).append("** terms.\n\n");

        for (NodeKind kind : NodeKind.values()) {
            List<String> labels = new ArrayList<String>();
            for (CanvasNode node : projection.getNodes()) {
                if (node.getKind() == kind) {
                    labels.add(node.getLabel());
                }
            }
            if (labels.isEmpty()) {
                continue;
            }
            java.util.Collections.sort(labels);
            text.append("## ").append(kind).append(" (").append(labels.size()).append(")\n\n");
            for (String label : labels) {
                text.append("- ").append(label).append('\n');
            }
            text.append('\n');
        }

        text.append("## Edges\n\n| Kind | Count |\n|---|---|\n");
        java.util.TreeMap<CanvasEdge.Kind, Integer> counts =
                new java.util.TreeMap<CanvasEdge.Kind, Integer>();
        for (CanvasEdge edge : projection.getEdges()) {
            counts.put(edge.getKind(),
                    counts.containsKey(edge.getKind()) ? counts.get(edge.getKind()) + 1 : 1);
        }
        for (CanvasEdge.Kind kind : counts.keySet()) {
            text.append("| ").append(kind).append(" | ").append(counts.get(kind)).append(" |\n");
        }
        write(new File(reports, "canvas.md"), text.toString());
    }

    /** Consistency, coherence, and what each reasoner concludes. */
    private void writeReasonerReport(OWLOntology pizza, File reports) throws IOException {
        Set<String> onCanvas = OntologyProjection.everythingWorthShowing(pizza);
        Projection asserted = OntologyProjection.project(pizza, onCanvas);

        StringBuilder text = new StringBuilder("# Reasoning - " + version + "\n\n");
        for (Reasoners.Choice choice : new Reasoners.Choice[] {
            Reasoners.Choice.ELK, Reasoners.Choice.HERMIT}) {
            OWLReasoner reasoner = choice.newFactory().createReasoner(pizza);
            text.append("## ").append(choice.getLabel()).append("\n\n");
            text.append("- consistent: **").append(reasoner.isConsistent()).append("**\n");
            Set<String> unsatisfiable =
                    InferredEdges.unsatisfiableClasses(reasoner, onCanvas);
            text.append("- unsatisfiable classes: **").append(unsatisfiable.size())
                    .append("**").append(unsatisfiable.isEmpty() ? "" : " " + unsatisfiable)
                    .append('\n');

            List<CanvasEdge> subclasses = InferredEdges.subClassEdges(reasoner, onCanvas,
                    asserted.getEdges(), pizza.getOWLOntologyManager().getOWLDataFactory());
            List<CanvasEdge> types = InferredEdges.typeEdges(reasoner, onCanvas,
                    asserted.getEdges(), pizza.getOWLOntologyManager().getOWLDataFactory());
            text.append("\n### Inferred subclasses (").append(subclasses.size()).append(")\n\n");
            for (CanvasEdge edge : subclasses) {
                text.append("- `").append(shortName(edge.getSourceId())).append("` -> `")
                        .append(shortName(edge.getTargetId())).append("`\n");
            }
            text.append("\n### Inferred types (").append(types.size()).append(")\n\n");
            for (CanvasEdge edge : types) {
                text.append("- `").append(shortName(edge.getSourceId())).append("` is a `")
                        .append(shortName(edge.getTargetId())).append("`\n");
            }
            text.append('\n');
            reasoner.dispose();
        }
        write(new File(reports, "reasoning.md"), text.toString());
    }

    private void writeRobotReport(OWLOntology pizza, File reports) throws IOException {
        StringBuilder text = new StringBuilder("# ROBOT - " + version + "\n\n## measure\n\n");
        text.append("| Group | Measure | Value |\n|---|---|---|\n");
        for (OntologyMeasurements.Measurement m
                : OntologyMeasurements.run(pizza, OntologyMeasurements.Depth.EXTENDED)) {
            text.append("| ").append(m.getGroup()).append(" | ").append(m.getLabel())
                    .append(" | ").append(m.getValue()).append(" |\n");
        }
        text.append(LF).append("## report").append(LF).append(LF);
        java.util.List<QualityFinding> findings = QualityReport.run(pizza);
        text.append(findings.size()).append(" findings, by rule:").append(LF).append(LF);
        text.append("| Level | Rule | Subject |").append(LF).append("|---|---|---|").append(LF);
        for (QualityFinding finding : findings) {
            text.append("| ").append(finding.getSeverity()).append(" | ")
                    .append(finding.getRule()).append(" | ")
                    .append(finding.getSubject()).append(" |").append(LF);
        }
        write(new File(reports, "robot.md"), text.toString());
    }

    private void writeProfileReport(OWLOntology pizza, File reports) throws IOException {
        ProfileCheck.Target tightest = ProfileCheck.tightestProfile(pizza);
        List<ProfileCheck.Violation> outsideEl =
                ProfileCheck.violations(pizza, ProfileCheck.Target.EL);
        StringBuilder text = new StringBuilder("# OWL 2 profile - " + version + "\n\n");
        text.append("Tightest profile: **")
                .append(tightest == null ? "outside OWL 2 DL" : "OWL 2 " + tightest.getLabel())
                .append("**\n\n");
        text.append(outsideEl.size()).append(" axioms outside OWL 2 EL.");
        if (outsideEl.isEmpty()) {
            // Said unconditionally, this read "0 axioms outside OWL 2 EL. ELK ignores each of
            // these silently" - a warning about nothing, on the one release with no ELK blind spot.
            text.append(" Everything here is inside the profile the ODK build reasons with, so ");
            text.append("ELK sees all of it.\n\n");
        } else {
            text.append(" ELK ignores each of these silently, and ELK is what the ODK build ");
            text.append("runs.\n\n");
        }
        for (ProfileCheck.Violation violation : outsideEl) {
            text.append("- ").append(violation.getMessage()).append('\n');
        }
        write(new File(reports, "profile.md"), text.toString());
    }

    /** The ID range file the scaffold wrote, read back through the plugin's own parser. */
    private void writeIdRangeReport(File ontologyDir, File reports) throws IOException {
        File rangesFile = new File(ontologyDir, ID + "-idranges.owl");
        IdRanges ranges = IdRanges.parse(new String(
                Files.readAllBytes(rangesFile.toPath()), Charset.forName("UTF-8")));
        StringBuilder text = new StringBuilder("# ID ranges - " + version + "\n\n");
        text.append("Read back with the plugin's own parser, so this is what OntoBoard would\n");
        text.append("mint from - not a copy of the file.\n\n");
        text.append("| # | Allocated to | Lower | Upper |\n|---|---|---|---|\n");
        for (IdRanges.Range range : ranges.getRanges()) {
            text.append("| ").append(range.getNumber())
                    .append(" | ").append(range.getAllocatedTo())
                    .append(" | ").append(range.getLower())
                    .append(" | ").append(range.getUpper()).append(" |\n");
        }
        write(new File(reports, "id-ranges.md"), text.toString());
    }

    /** A ROBOT template, run through the plugin, so the spreadsheet path is exercised too. */
    private void writeTemplateReport(OWLOntology pizza, File repo, File reports)
            throws IOException {
        File templates = new File(repo, "src/templates");
        templates.mkdirs();
        File sheet = new File(templates, "extra-toppings.tsv");
        if (!sheet.isFile()) {
            StringBuilder tsv = new StringBuilder();
            tsv.append("ID\tLabel\tParent\tDefinition\n");
            tsv.append("ID\tLABEL\tSC %\tA IAO:0000115\n");
            tsv.append(PizzaOntology.NS).append("BasilTopping\tbasil topping\t")
                    .append(PizzaOntology.NS).append("VegetableTopping\t")
                    .append("The leaf of Ocimum basilicum, used fresh.\n");
            tsv.append(PizzaOntology.NS).append("RocketTopping\trocket topping\t")
                    .append(PizzaOntology.NS).append("VegetableTopping\t")
                    .append("The leaf of Eruca vesicaria, added after baking.\n");
            write(sheet, tsv.toString());
        }

        TemplateSheet.Result result = TemplateSheet.run(sheet.getName(),
                TemplateSheet.read(sheet), pizza, null,
                IRI.create(PizzaOntology.IRI_BASE + "/templates/extra-toppings.owl"));

        StringBuilder text = new StringBuilder("# ROBOT template - " + version + "\n\n");
        text.append("Source: `src/templates/extra-toppings.tsv`\n\n");
        text.append("- rows of terms: **").append(result.getDataRows()).append("**\n");
        text.append("- axioms generated: **").append(result.getAxiomCount()).append("**\n");
        text.append("- problems: **").append(result.getProblems().size()).append("**\n\n");
        for (TemplateSheet.Problem problem : result.getProblems()) {
            text.append("- ").append(problem.where()).append(": ")
                    .append(problem.getMessage()).append('\n');
        }
        write(new File(reports, "template.md"), text.toString());
        assertTrue(result.getProblems().isEmpty(),
                "the published template should be a clean one: " + result.getProblems());
    }

    /** What changed since the previous release, term by term. */
    private void writeEvolutionReport(File reports) throws Exception {
        // The release before this one, from the ordered list. A two-way ternary meant v4 was
        // compared with v2, so its notes reprinted v3's changes under a v4 heading and said
        // nothing about what v4 actually did.
        List<String> order = java.util.Arrays.asList("v1", "v2", "v3", "v4");
        int index = order.indexOf(version);
        if (index <= 0) {
            throw new IllegalStateException("no release before " + version);
        }
        String previous = order.get(index - 1);
        ReleaseDiff diff = ReleaseDiff.between(ontologyFor(previous), ontologyFor(version));

        StringBuilder text = new StringBuilder("# Evolution " + previous + " -> " + version
                + "\n\n");
        text.append(diff.summary()).append("\n\n");
        if (diff.getChanges().isEmpty()) {
            // A release can change a great deal without touching a term. Saying so beats an empty
            // page, and beats the previous behaviour of quietly reprinting the last release.
            text.append("This release changed no term. What it did change - provenance, notes, ");
            text.append("imports, ontology metadata - is not a term change; see the other ");
            text.append("reports in this directory.\n");
        }
        for (ReleaseDiff.Change change : ReleaseDiff.Change.values()) {
            List<ReleaseDiff.TermChange> of = diff.of(change);
            if (of.isEmpty()) {
                continue;
            }
            text.append("## ").append(change.getLabel()).append(" (").append(of.size())
                    .append(")\n\n");
            for (ReleaseDiff.TermChange one : of) {
                text.append("- `").append(ReleaseDiff.shortForm(one.getIri())).append("` ")
                        .append(one.getAfter() == null || one.getAfter().isEmpty() ? ""
                                : one.getAfter())
                        .append('\n');
            }
            text.append('\n');
        }
        write(new File(reports, "evolution.md"), text.toString());
        write(new File(reports, "release-notes.md"),
                diff.asReleaseNotes(version, ontologyFor(version)));
    }

    /**
     * Who did what, and what they said about it.
     *
     * <p>The editorial surface a curator spends their time in, and the half of the plugin that
     * nothing had exercised end to end: provenance on terms, attributed notes, and a link to
     * where the argument actually happened.
     */
    private void writeCurationReport(OWLOntology pizza, File reports) throws IOException {
        StringBuilder text = new StringBuilder("# Curation - " + version + "\n\n");

        text.append("## Who added what\n\n| Term | Contributor | Created |\n|---|---|---|\n");
        int stamped = 0;
        for (org.semanticweb.owlapi.model.OWLClass cls : pizza.getClassesInSignature()) {
            List<String> contributors =
                    de.fizkarlsruhe.ise.ontoboard.prov.Provenance.contributorsOf(
                            pizza, cls.getIRI());
            if (contributors.isEmpty()) {
                continue;
            }
            stamped++;
            text.append("| `").append(shortName(cls.getIRI().toString())).append("` | ")
                    .append(String.join(", ", contributors)).append(" | ")
                    .append(de.fizkarlsruhe.ise.ontoboard.prov.Provenance.createdOn(
                            pizza, cls.getIRI()))
                    .append(" |\n");
        }
        text.append("\n").append(stamped).append(" terms carry provenance. The rest predate the ");
        text.append("convention, which is why they carry none - OntoBoard does not backfill a\n");
        text.append("claim about who made something it was not there for.\n\n");

        text.append("## Notes\n\n");
        List<de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Note> notes =
                de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.allIn(pizza);
        for (de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Note note : notes) {
            text.append("- **").append(note.getKind().getLabel()).append("** on `")
                    .append(shortName(note.getSubject().toString())).append("`");
            String by = note.describeAttribution();
            text.append(by.isEmpty() ? "" : " (" + by + ")").append("  \n  ")
                    .append(note.getText()).append('\n');
        }
        text.append("\n## Discussion links\n\n");
        for (org.semanticweb.owlapi.model.OWLClass cls : pizza.getClassesInSignature()) {
            for (String item : de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.trackerItemsOn(
                    pizza, cls.getIRI())) {
                text.append("- `").append(shortName(cls.getIRI().toString())).append("` -> ")
                        .append(item).append('\n');
            }
        }
        write(new File(reports, "curation.md"), text.toString());

        assertFalse(notes.isEmpty(), "v4 is the curation pass; it must carry notes");
    }

    /**
     * Borrowing terms from somebody else's ontology, with the whole apparatus that makes it
     * resolve for anyone but the author: a module, a catalog entry, an import declaration, and a
     * record of which upstream release it came from.
     */
    private void writeImportReport(OWLOntology pizza, File ontologyDir, File reports)
            throws Exception {
        OWLOntology upstream = PizzaOntology.upstreamFoodOntology();
        IRI moduleIri = IRI.create(PizzaOntology.IRI_BASE + "/imports/food_import.owl");

        de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.Result extracted =
                de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.run(upstream,
                        PizzaOntology.borrowedTerms(),
                        de.fizkarlsruhe.ise.ontoboard.robot.TermExtract.Method.BOT, moduleIri);

        // Which release it came from, recorded in the module itself.
        extracted.getModule().getOWLOntologyManager().applyChanges(
                de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.stamp(
                        extracted.getModule(), upstream, CURATED_ON));

        File imports = new File(ontologyDir, "imports");
        imports.mkdirs();
        File moduleFile = new File(imports, "food_import.owl");
        extracted.getModule().getOWLOntologyManager().saveOntology(
                extracted.getModule(), IRI.create(moduleFile.toURI()));

        // The catalog entry is the part that makes it resolve for anybody else.
        de.fizkarlsruhe.ise.ontoboard.odk.Catalog.addEntry(
                new File(ontologyDir, "catalog-v001.xml"), moduleIri.toString(),
                "imports/food_import.owl");

        // The import declaration itself. Without it the module is a file nobody reads: the three
        // pieces - module, catalog entry, import statement - only work together, which is the
        // whole reason ImportTermsAction does all three rather than leaving two to the user.
        pizza.getOWLOntologyManager().applyChange(new org.semanticweb.owlapi.model.AddImport(
                pizza, pizza.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLImportsDeclaration(moduleIri)));
        pizza.getOWLOntologyManager().saveOntology(pizza,
                IRI.create(new File(ontologyDir, ID + "-edit.owl").toURI()));

        de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.Report provenance =
                de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.check(
                        extracted.getModule(), upstream.getOWLOntologyManager());

        StringBuilder text = new StringBuilder("# Borrowed terms - " + version + "\n\n");
        text.append("Extracted from `").append(upstream.getOntologyID().getOntologyIRI().get())
                .append("` with ROBOT's BOT module method.\n\n");
        text.append("- terms requested: **").append(extracted.getRequested()).append("**\n");
        text.append("- terms missing upstream: **").append(extracted.getMissing().size())
                .append("**\n");
        text.append("- axioms in the module: **").append(extracted.getAxiomCount())
                .append("**\n");
        text.append("- module IRI: `").append(moduleIri).append("`\n");
        text.append("- cut from: `").append(provenance.getCutFrom()).append("`\n");
        text.append("- extracted on: ").append(provenance.getExtractedOn()).append("\n\n");
        text.append("The module is written to `src/ontology/imports/food_import.owl` and mapped\n");
        text.append("in `catalog-v001.xml`. Both are committed: an import module that is\n");
        text.append("gitignored resolves for the person who made it and for nobody else, which\n");
        text.append("is a defect this run found in OntoBoard's own scaffold.\n");
        write(new File(reports, "imports.md"), text.toString());

        assertTrue(extracted.getMissing().isEmpty(),
                "the borrowed terms should all exist upstream: " + extracted.getMissing());
        assertTrue(extracted.getAxiomCount() > 0, "an empty module is not an import");
        assertNotNull(provenance.getCutFrom(), "the module must record which release it came from");
    }

    /** What the generated Makefile actually offers, read by the plugin's own parser. */
    private void writeMakeTargetsReport(File editFile, File reports) throws IOException {
        List<String> targets = de.fizkarlsruhe.ise.ontoboard.odk.MakeTargets.of(editFile);
        StringBuilder text = new StringBuilder("# Make targets - " + version + "\n\n");
        text.append("Parsed from the generated Makefile by the plugin's own parser, which is\n");
        text.append("what OntoBoard's Build... dialog offers.\n\n");
        for (String target : targets) {
            text.append("- `make ").append(target).append("`\n");
        }
        write(new File(reports, "make-targets.md"), text.toString());
        assertFalse(targets.isEmpty(), "the Build dialog would have nothing to offer");
    }

    private static void write(File target, String body) throws IOException {
        Files.write(target.toPath(), body.getBytes(Charset.forName("UTF-8")));
    }

    private static String shortName(String iri) {
        int hash = iri.lastIndexOf('#');
        return hash < 0 ? iri : iri.substring(hash + 1);
    }
}
