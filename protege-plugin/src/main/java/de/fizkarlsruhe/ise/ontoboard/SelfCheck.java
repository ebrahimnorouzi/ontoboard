package de.fizkarlsruhe.ise.ontoboard;

import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologyDataset;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.ReportQueries;
import de.fizkarlsruhe.ise.ontoboard.robot.RuleRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Checks, at startup, the things only a running Protege can prove.
 *
 * <p><b>Why this exists.</b> The plugin's quality report was broken on every host for nine versions
 * while the whole unit suite stayed green, because the defect was in the environment rather than in
 * the logic: {@code ReportOperation} locates its query files with
 * {@code ClassLoader.getResource("report_queries")} and rejects any URL protocol but {@code file}
 * and {@code jar}, and Felix answers {@code bundle}. Maven runs the tests against an exploded
 * classpath where that call succeeds, so no test could have failed.
 *
 * <p>The fix - reading those resources as streams - has exactly the same property: it can only be
 * proved in a bundle. Add to that the fact that {@code robot-core} and Jena are <em>nested jars</em>
 * on {@code Bundle-ClassPath} rather than exploded classes, and "the resources are reachable" is a
 * claim about Felix's classloader, not about this code. So the plugin now makes that claim at
 * startup, in the host, and writes the answer to {@code protege.log}, where
 * {@code tools/smoke.ps1} asserts it.
 *
 * <p>Cheap on purpose: a three-axiom ontology held in memory, no file and no network, so the cost to
 * a user opening Protege is milliseconds. This is a smoke check, not a test suite - it covers the
 * ROBOT paths whose in-bundle behaviour cannot be established any other way, not the 21 menu items.
 *
 * <p>No Protege types and no Swing, so it is testable directly - though a green test here proves
 * only that the logic works on a classpath. {@link OntoBoardStartup} is what runs it where it counts.
 */
public final class SelfCheck {

    /** robot-core 1.9.8's own profile lists this many rules. */
    static final int EXPECTED_RULES = 32;

    private SelfCheck() {
    }

    /** One check and what it found. */
    public static final class Check {
        private final String name;
        private final boolean passed;
        private final String detail;

        Check(String name, boolean passed, String detail) {
            this.name = name;
            this.passed = passed;
            this.detail = detail;
        }

        public String getName() {
            return name;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return (passed ? "ok   " : "FAIL ") + name + ": " + detail;
        }
    }

    /** Every check, and the one-line verdict a log scanner can match on. */
    public static final class Result {
        private final List<Check> checks;

        Result(List<Check> checks) {
            this.checks = Collections.unmodifiableList(checks);
        }

        public List<Check> getChecks() {
            return checks;
        }

        public int getPassed() {
            int passed = 0;
            for (Check check : checks) {
                if (check.isPassed()) {
                    passed++;
                }
            }
            return passed;
        }

        public boolean isPassed() {
            return getPassed() == checks.size() && !checks.isEmpty();
        }

        /**
         * The line {@code tools/smoke.ps1} greps for.
         *
         * <p>Stable wording, deliberately: it is an assertion in a script and in a release receipt,
         * so changing it silently turns a failing host into a passing one.
         */
        public String summary() {
            return "OntoBoard self-check: " + (isPassed() ? "PASS" : "FAIL") + " "
                    + getPassed() + "/" + checks.size();
        }
    }

    /**
     * Runs every check. Never throws - a broken self-check must not stop Protege starting.
     *
     * <p>A check that throws is reported as a failure carrying the exception, which is the useful
     * outcome: the point is to get the diagnosis into the log, not to punish the user for it.
     */
    public static Result run() {
        List<Check> checks = new ArrayList<Check>();
        Map<String, String> severities = null;

        try {
            severities = ReportQueries.defaultSeverities();
            checks.add(new Check("robot report profile readable",
                    severities.size() == EXPECTED_RULES,
                    severities.size() + " rules read from robot-core's report_profile.txt, expected "
                            + EXPECTED_RULES));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report profile readable", false, describe(cannot)));
        }

        try {
            int withQuery = 0;
            List<String> missing = new ArrayList<String>();
            if (severities != null) {
                for (String rule : severities.keySet()) {
                    if (ReportQueries.queryFor(rule) != null) {
                        withQuery++;
                    } else {
                        missing.add(rule);
                    }
                }
            }
            int total = severities == null ? 0 : severities.size();
            checks.add(new Check("robot report queries readable",
                    total > 0 && withQuery == total,
                    withQuery + "/" + total + " rules resolved a .rq file"
                            + (missing.isEmpty() ? "" : ", missing: " + missing)));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report queries readable", false, describe(cannot)));
        }

        OWLOntology subject = null;
        try {
            subject = tinyOntology();
            long triples = OntologyDataset.modelOf(subject).size();
            checks.add(new Check("jena reads what the owl api writes", triples > 0,
                    triples + " triples round-tripped through RDF/XML"));
        } catch (Exception | LinkageError cannot) {
            checks.add(new Check("jena reads what the owl api writes", false, describe(cannot)));
        }

        try {
            List<QualityFinding> findings = subject == null || severities == null
                    ? Collections.<QualityFinding>emptyList()
                    : RuleRunner.run(subject, severities);
            checks.add(new Check("robot report runs end to end", !findings.isEmpty(),
                    findings.size() + " findings over a three-axiom ontology that has no labels, "
                            + "no title and no licence"));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot report runs end to end", false, describe(cannot)));
        }

        try {
            // new IOHelper() and createExportTable are the two calls on the export path that could
            // fail here and nowhere else: IOHelper reads its own resources, and export.Table is the
            // class that references Apache POI - whose logging backend this bundle cannot embed.
            // Only Table.asWorkbook touches POI, so this must pass; if it ever does not, "Export
            // terms..." is broken in the host while every test still passes, which is precisely the
            // failure this whole class exists to catch.
            List<String> columns = TermExport.defaultColumns();
            TermExport.Result export = subject == null
                    ? null
                    : TermExport.run(subject, columns, TermExport.defaultOptions());
            checks.add(new Check("robot export runs end to end",
                    export != null && !export.getRows().isEmpty(),
                    export == null
                            ? "no ontology to export"
                            : export.getTermCount() + " terms across " + columns.size()
                                    + " columns, written as " + export.getFormat()));
        } catch (RuntimeException | LinkageError cannot) {
            checks.add(new Check("robot export runs end to end", false, describe(cannot)));
        }

        try {
            // owlexplanation is an embedded jar, and Protege *exports* the package one of its
            // classes lives in - the two-copies-one-package situation that resolves differently
            // depending on what else is installed. Explanations deliberately avoids the class in
            // question, but that is an argument, and an argument about a classloader is what was
            // wrong about the report for nine versions. So the explanation path runs here too.
            Explanations.Result explained = Explanations.run(unsatisfiableOntology(),
                    Reasoners.Choice.ELK.newFactory(), 1);
            checks.add(new Check("robot explain runs end to end",
                    !explained.isClean() && !explained.getJustifications().isEmpty(),
                    explained.getUnsatisfiable().size() + " unsatisfiable class explained by "
                            + explained.getJustifications().size() + " justification(s)"));
        } catch (Exception | LinkageError cannot) {
            checks.add(new Check("robot explain runs end to end", false, describe(cannot)));
        }

        checks.add(menuClassesResolve());

        return new Result(checks);
    }

    /**
     * Every class plugin.xml names can be loaded and constructed, here, under Felix.
     *
     * <p>{@code PluginXmlTest} already calls {@code Class.forName} on each of them - on Maven's
     * classpath, where everything resolves. Felix is a different class space: a menu action that
     * references a Protege type the bundle never imported resolves fine in a test and throws
     * {@code NoClassDefFoundError} the moment a user clicks the item. Nothing in the plugin would
     * notice, because a menu action that fails to load simply does nothing visible.
     *
     * <p>Constructed, not merely loaded. Loading proves the class file is reachable; constructing
     * proves its static initialiser and its constructor run, which is where a missing dependency
     * usually surfaces. Protege's actions are {@code AbstractAction} subclasses whose constructors
     * do no work and open no windows, so this is cheap and has no side effects - {@code initialise}
     * is what Protege calls later, and this does not call it.
     *
     * <p>This is the resolvable half of what the plan calls Phase 2. It does not click anything, so
     * it cannot tell you a dialog is wrong; it can tell you an item is dead.
     */
    private static Check menuClassesResolve() {
        List<String> declared;
        try {
            declared = declaredClasses();
        } catch (Exception | LinkageError cannotRead) {
            return new Check("menu classes resolve", false,
                    "plugin.xml could not be read: " + describe(cannotRead));
        }
        if (declared.isEmpty()) {
            return new Check("menu classes resolve", false,
                    "plugin.xml named no classes, which cannot be right");
        }

        List<String> broken = new ArrayList<String>();
        int loaded = 0;
        for (String name : declared) {
            try {
                Class<?> type = Class.forName(name, true, SelfCheck.class.getClassLoader());
                // Only ours. A Protege class named here belongs to Protege's own bundle, and
                // constructing one of those would be testing Protege.
                if (name.startsWith("de.fizkarlsruhe.")) {
                    type.newInstance();
                }
                loaded++;
            } catch (Exception | LinkageError cannotLoad) {
                broken.add(shortName(name) + " (" + describe(cannotLoad) + ")");
            }
        }
        return new Check("menu classes resolve", broken.isEmpty(),
                loaded + "/" + declared.size() + " classes named in plugin.xml loaded"
                        + (broken.isEmpty() ? "" : ", broken: " + broken));
    }

    /** The {@code <class value="..."/>} entries in plugin.xml, in document order, deduplicated. */
    private static List<String> declaredClasses() throws Exception {
        List<String> names = new ArrayList<String>();
        java.io.InputStream in = SelfCheck.class.getClassLoader()
                .getResourceAsStream("plugin.xml");
        if (in == null) {
            throw new java.io.IOException("plugin.xml is not on the bundle classpath");
        }
        try {
            org.w3c.dom.NodeList declared = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(in).getElementsByTagName("class");
            for (int i = 0; i < declared.getLength(); i++) {
                String name = ((org.w3c.dom.Element) declared.item(i)).getAttribute("value");
                if (name != null && !name.trim().isEmpty() && !names.contains(name.trim())) {
                    names.add(name.trim());
                }
            }
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // Closing a classpath resource cannot usefully fail.
            }
        }
        return names;
    }

    private static String shortName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /**
     * Two disjoint parents, one child - the smallest ontology with something to explain.
     *
     * <p>Disjointness rather than negation, so ELK can do it: ELK is much cheaper to start than
     * HermiT, and this runs every time a user opens an ontology.
     */
    private static OWLOntology unsatisfiableOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://www.ontoboard.org/self-check/unsatisfiable"));
        String ns = "http://www.ontoboard.org/self-check/unsatisfiable#";
        org.semanticweb.owlapi.model.OWLClass person =
                factory.getOWLClass(IRI.create(ns + "Person"));
        org.semanticweb.owlapi.model.OWLClass robot =
                factory.getOWLClass(IRI.create(ns + "Robot"));
        org.semanticweb.owlapi.model.OWLClass android =
                factory.getOWLClass(IRI.create(ns + "Android"));
        manager.addAxiom(ontology, factory.getOWLDisjointClassesAxiom(person, robot));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(android, person));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(android, robot));
        return ontology;
    }

    /**
     * An ontology that every default rule can be run against and several must flag.
     *
     * <p>Person has a superclass, so it is not excluded by the {@code rdf:type}-only filter in
     * ROBOT's {@code missing_label} query; the ontology declares no title, description or licence.
     * So a working report cannot come back empty, which is what makes "0 findings" a failure here
     * rather than good news.
     */
    private static OWLOntology tinyOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(
                IRI.create("http://www.ontoboard.org/self-check"));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://www.ontoboard.org/self-check#Person")),
                factory.getOWLClass(IRI.create("http://www.ontoboard.org/self-check#Agent"))));
        return ontology;
    }

    /** The throwable in words, since a bare {@code getMessage()} is often null. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
