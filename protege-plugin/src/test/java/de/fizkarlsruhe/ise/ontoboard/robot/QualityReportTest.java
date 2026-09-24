package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The quality report, end to end against real ROBOT rules and a real ontology.
 *
 * <p>These tests run ROBOT's own SPARQL rather than a table this code invented, because the defect
 * they hold shut was invisible to a parser test: {@code ReportOperation} cannot reach its query
 * files inside an OSGi bundle, so for nine versions the report threw on both supported Protege
 * installs while the unit tests - which only ever exercised the TSV parsing - stayed green.
 */
class QualityReportTest {

    /** The subject IRIs of every finding for one rule. */
    private static List<String> subjectsFor(List<QualityFinding> findings, String rule) {
        List<String> subjects = new ArrayList<String>();
        for (QualityFinding finding : findings) {
            if (rule.equals(finding.getRule())) {
                subjects.add(finding.getSubject());
            }
        }
        return subjects;
    }

    private static OWLOntology tiny() throws Exception {
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
    }

    /**
     * The report runs. Not "runs or explains why it cannot" - that was the shape of this test
     * while the report was broken everywhere, and it passed throughout.
     */
    @Test
    void theReportRunsAndFindsRealViolations() throws Exception {
        List<QualityFinding> findings = QualityReport.run(tiny());

        assertFalse(findings.isEmpty(), "fixture-tiny has no labels and no ontology metadata, so "
                + "ROBOT's default profile must report violations");
        for (QualityFinding finding : findings) {
            assertTrue(finding.getSeverity() != null, "every finding needs a severity");
            assertTrue(finding.getRule() != null && !finding.getRule().isEmpty(),
                    "every finding needs the rule that produced it");
            assertTrue(finding.getSubject() != null && !finding.getSubject().isEmpty(),
                    "a finding with no subject is one a curator cannot act on");
        }
    }

    /**
     * Named rules against named subjects, so a change in the plumbing that returns plausible
     * nonsense fails here.
     *
     * <p>{@code missing_label} excludes an entity whose only triple is {@code rdf:type} - see the
     * {@code FILTER EXISTS} at the end of ROBOT's own query. In this fixture that leaves Person
     * (which has a superclass) and worksFor (which has a domain and range), and excludes Agent,
     * Organization and alice. Asserting the exclusions matters as much as the hits: it shows
     * ROBOT's filters are really being evaluated and not approximated here.
     */
    @Test
    void missingLabelFindsExactlyWhatRobotsOwnQuerySelects() throws Exception {
        List<String> subjects = subjectsFor(QualityReport.run(tiny()), "missing_label");

        assertTrue(subjects.contains("http://example.org/tiny#Person"), subjects.toString());
        assertTrue(subjects.contains("http://example.org/tiny#worksFor"), subjects.toString());
        assertFalse(subjects.contains("http://example.org/tiny#Agent"),
                "Agent's only triple is rdf:type, which ROBOT's query excludes: " + subjects);
        assertFalse(subjects.contains("http://example.org/tiny#alice"),
                "alice's only triples are rdf:type, which ROBOT's query excludes: " + subjects);
    }

    /** The ontology itself is a subject: fixture-tiny declares no title, description or licence. */
    @Test
    void ontologyLevelRulesReportTheOntology() throws Exception {
        List<QualityFinding> findings = QualityReport.run(tiny());

        for (String rule : new String[] {"missing_ontology_title", "missing_ontology_description",
                "missing_ontology_license"}) {
            assertEquals(java.util.Collections.singletonList("http://example.org/tiny"),
                    subjectsFor(findings, rule), rule + " should name the ontology");
        }
    }

    /**
     * Every rule in ROBOT's default profile actually runs.
     *
     * <p>The failure this guards against is not a crash but a quiet one: a report that checks 7 of
     * 32 rules and says nothing about the other 25 looks exactly like a clean ontology. ROBOT ships
     * a query for all 32 profile entries, so a single skip means a rule was renamed or moved by a
     * robot-core upgrade and a check has silently stopped running.
     */
    @Test
    void allThirtyTwoOfRobotsDefaultRulesRun() throws Exception {
        Map<String, String> defaults = ReportQueries.defaultSeverities();
        assertEquals(32, defaults.size(), "robot-core 1.9.8's profile lists 32 rules: " + defaults);

        RuleRunner.Outcome outcome = RuleRunner.runWithDetail(tiny(), defaults);
        assertEquals(new ArrayList<RuleRunner.Skipped>(), new ArrayList<RuleRunner.Skipped>(
                outcome.getSkipped()), "no rule in ROBOT's own profile may be skipped");
    }

    /** Most severe first, and stable, so working through the table one row at a time works. */
    @Test
    void findingsComeBackMostSevereFirst() throws Exception {
        List<QualityFinding> findings = QualityReport.run(tiny());

        QualityFinding.Severity previous = null;
        for (QualityFinding finding : findings) {
            if (previous != null) {
                assertTrue(previous.compareTo(finding.getSeverity()) <= 0,
                        "severity order broke at " + finding.getRule() + " (" + previous + " then "
                                + finding.getSeverity() + ")");
            }
            previous = finding.getSeverity();
        }
    }

    /**
     * A project's own profile.txt decides both which rules run and at what level.
     *
     * <p>This is the whole point of {@link QualityReport#optionsFor(File)}: the plugin and that
     * project's {@code make report} must agree about what counts as a violation. ROBOT does not
     * merge a given profile with its defaults, and neither does this.
     */
    @Test
    void aProjectsOwnProfileWinsOverRobotsDefaults(@TempDir File dir) throws Exception {
        File profile = new File(dir, "profile.txt");
        Files.write(profile.toPath(), "INFO\tmissing_label\n".getBytes(StandardCharsets.UTF_8));

        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put(QualityReport.OPTION_PROFILE, profile.getAbsolutePath());
        List<QualityFinding> findings = QualityReport.run(tiny(), options);

        assertEquals(new TreeSet<String>(java.util.Collections.singletonList("missing_label")),
                rulesIn(findings), "only the rule the project's profile names should have run");
        for (QualityFinding finding : findings) {
            assertEquals(QualityFinding.Severity.INFO, finding.getSeverity(),
                    "the project downgraded missing_label, so the plugin must report it as INFO");
        }
    }

    /** A rule this ROBOT does not ship is reported as unchecked, not thrown and not ignored. */
    @Test
    void aRuleWithNoQueryIsReportedAsSkipped(@TempDir File dir) throws Exception {
        Map<String, String> severities = new LinkedHashMap<String, String>();
        severities.put("missing_label", "ERROR");
        severities.put("a_rule_robot_has_never_shipped", "ERROR");

        RuleRunner.Outcome outcome = RuleRunner.runWithDetail(tiny(), severities);

        assertEquals(1, outcome.getSkipped().size(), outcome.getSkipped().toString());
        assertEquals("a_rule_robot_has_never_shipped", outcome.getSkipped().get(0).getRule());
        assertFalse(outcome.getFindings().isEmpty(),
                "the 31 runnable rules must still produce their findings");
    }

    /** An unreadable or absent project profile falls back to ROBOT's defaults, not to silence. */
    @Test
    void anAbsentProjectProfileFallsBackToRobotsDefaults(@TempDir File dir) throws IOException {
        assertTrue(ReportQueries.severitiesIn(new File(dir, "nope.txt")).isEmpty());
        assertTrue(ReportQueries.severitiesIn(null).isEmpty());

        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put(QualityReport.OPTION_PROFILE, new File(dir, "nope.txt").getAbsolutePath());
        assertEquals(32, QualityReport.severitiesFor(options).size(),
                "no usable project profile means ROBOT's 32 defaults, never an empty rule set");
    }

    /** {@code profile.txt} beside the edit file is what ODK projects ship, including ours. */
    @Test
    void theProfileBesideTheEditFileIsFound(@TempDir File dir) throws IOException {
        File edit = new File(dir, "pizza-edit.owl");
        Files.write(edit.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(null, QualityReport.profileBeside(edit));

        File profile = new File(dir, "profile.txt");
        Files.write(profile.toPath(), "ERROR\tmissing_label\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(profile, QualityReport.profileBeside(edit));
        assertEquals(profile.getAbsolutePath(),
                QualityReport.optionsFor(edit).get(QualityReport.OPTION_PROFILE));
    }

    /**
     * An ontology that has imports keeps its own title, description and licence.
     *
     * <p>The report merges the imports closure into a fresh ontology so the rules see one graph.
     * That merge copied axioms - and ontology annotations are not axioms, so dcterms:title,
     * dcterms:description and dcterms:license were silently dropped, and the report answered
     * missing_ontology_title, missing_ontology_description and missing_ontology_license as ERRORs
     * about an ontology that declares all three.
     *
     * <p>It fired on every ontology with an import, which is every real ODK project, and on none
     * without - so every fixture in this suite missed it. It was found by reading a generated
     * release report and noticing that v4 had three errors its three predecessors did not.
     */
    @Test
    void anOntologyWithImportsKeepsItsOwnMetadata() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();

        org.semanticweb.owlapi.model.IRI importedIri =
                org.semanticweb.owlapi.model.IRI.create("http://example.org/imported");
        manager.createOntology(importedIri);

        org.semanticweb.owlapi.model.IRI mainIri =
                org.semanticweb.owlapi.model.IRI.create("http://example.org/withimports");
        OWLOntology main = manager.createOntology(mainIri);
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(main,
                factory.getOWLImportsDeclaration(importedIri)));
        for (String[] pair : new String[][] {
                {"title", "A Titled Ontology"},
                {"description", "It has a description too."}}) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(main,
                    factory.getOWLAnnotation(factory.getOWLAnnotationProperty(
                            org.semanticweb.owlapi.model.IRI.create(
                                    "http://purl.org/dc/terms/" + pair[0])),
                            factory.getOWLLiteral(pair[1]))));
        }
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(main,
                factory.getOWLAnnotation(factory.getOWLAnnotationProperty(
                        org.semanticweb.owlapi.model.IRI.create(
                                "http://purl.org/dc/terms/license")),
                        org.semanticweb.owlapi.model.IRI.create(
                                "https://creativecommons.org/publicdomain/zero/1.0/"))));

        TreeSet<String> rules = rulesIn(QualityReport.run(main));

        assertFalse(rules.contains("missing_ontology_title"),
                "the ontology declares dcterms:title; the merge must not lose it: " + rules);
        assertFalse(rules.contains("missing_ontology_description"), rules.toString());
        assertFalse(rules.contains("missing_ontology_license"), rules.toString());
    }

    private static TreeSet<String> rulesIn(List<QualityFinding> findings) {
        TreeSet<String> rules = new TreeSet<String>();
        for (QualityFinding finding : findings) {
            rules.add(finding.getRule());
        }
        return rules;
    }
}
