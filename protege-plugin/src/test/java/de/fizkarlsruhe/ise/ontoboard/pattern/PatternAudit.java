package de.fizkarlsruhe.ise.ontoboard.pattern;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.obolibrary.robot.ReportOperation;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * The pattern audit (1.155.0): one library pattern as a project that imports it sees it.
 *
 * <p>Its {@code pattern.owl} - the file an import copies - is put to HermiT, to the label check
 * (every non-built-in entity of its signature has an {@code rdfs:label}), and to OntoBoard's own
 * quality report with the profile a new ODK project carries; and its {@code pattern.ttl} is
 * compared with it, logical axiom for logical axiom.
 *
 * <p>Imports are answered with empty stubs, as {@link PatternSoundness} does, so what is judged is
 * what the pattern's own file asserts - unless a folder of fetched imports is given: then an
 * import with a copy there is read from it, and only the rest are stubbed. {@link Row#stubbed()}
 * says which imports were left out of a verdict.
 *
 * <p>{@link #main} runs it over the whole library: {@code PatternAudit <patterns dir> <out.tsv>
 * [fetched imports dir]}, writing one row per pattern, {@code <out.tsv>.errors.tsv} (every ERROR
 * finding) and {@code <out.tsv>.labels.tsv} (every entity with more than one label, each label
 * with its language tag).
 */
final class PatternAudit {

    /** ODK v1.6.1's report profile, as a new project carries it. */
    static final String ODK_PROFILE = "src/main/odk/v1.6.1/templates/src/ontology/profile.txt";

    private static final List<String> BUILT_IN = Arrays.asList(
            "http://www.w3.org/2002/07/owl#", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#", "http://www.w3.org/2001/XMLSchema#");

    /** One label of an entity: its text and its language tag ("" for none). */
    static final class Label {
        private final String text;
        private final String lang;

        Label(String text, String lang) {
            this.text = text;
            this.lang = lang;
        }

        String text() {
            return text;
        }

        String lang() {
            return lang;
        }

        @Override
        public String toString() {
            return lang.isEmpty() ? "\"" + text + "\"" : "\"" + text + "\"@" + lang;
        }
    }

    /** One pattern's audit. */
    static final class Row {
        private final String id;
        private final int axioms;
        private final Set<String> imports;
        private final Set<String> stubbed;
        private final String reasoner;
        private final Set<String> unsatisfiable;
        private final Set<String> unlabelled;
        private final Set<String> unlabelledDeclared;
        private final Map<String, List<Label>> labels;
        private final List<QualityFinding> errors;
        private final boolean owlTtlDiffer;
        private final List<String> headerComments;

        Row(String id, int axioms, Set<String> imports, Set<String> stubbed, String reasoner,
                Set<String> unsatisfiable, Set<String> unlabelled, Set<String> unlabelledDeclared,
                Map<String, List<Label>> labels, List<QualityFinding> errors,
                boolean owlTtlDiffer, List<String> headerComments) {
            this.id = id;
            this.axioms = axioms;
            this.imports = imports;
            this.stubbed = stubbed;
            this.reasoner = reasoner;
            this.unsatisfiable = unsatisfiable;
            this.unlabelled = unlabelled;
            this.unlabelledDeclared = unlabelledDeclared;
            this.labels = labels;
            this.errors = errors;
            this.owlTtlDiffer = owlTtlDiffer;
            this.headerComments = headerComments;
        }

        String id() {
            return id;
        }

        int axioms() {
            return axioms;
        }

        /** The ontologies the pattern imports, by IRI. */
        Set<String> imports() {
            return imports;
        }

        /** The imports (direct or not) answered with an empty stub: left out of every verdict. */
        Set<String> stubbed() {
            return stubbed;
        }

        /**
         * HermiT's word: "coherent", "UNSATISFIABLE" (consistent, with a class that cannot have
         * an instance), "INCONSISTENT", "NOT_OWL_2_DL" (HermiT refuses to answer), or
         * "FAILED: " and the reasoner's exception.
         */
        String reasoner() {
            return reasoner;
        }

        /** The unsatisfiable classes, by IRI. */
        Set<String> unsatisfiable() {
            return unsatisfiable;
        }

        /** Every non-built-in entity of the pattern's signature without an rdfs:label, by IRI. */
        Set<String> unlabelled() {
            return unlabelled;
        }

        /** Those of {@link #unlabelled()} the pattern declares; the rest it only uses. */
        Set<String> unlabelledDeclared() {
            return unlabelledDeclared;
        }

        /** Every labelled entity's labels, by IRI, in the file's order. */
        Map<String, List<Label>> labels() {
            return labels;
        }

        /** The quality report's findings at ERROR, with ODK's default profile. */
        List<QualityFinding> errors() {
            return errors;
        }

        /** Whether pattern.ttl's logical axioms differ from pattern.owl's. */
        boolean owlTtlDiffer() {
            return owlTtlDiffer;
        }

        /** The rdfs:comment annotations of the pattern's ontology itself, in the file's order. */
        List<String> headerComments() {
            return headerComments;
        }

        @Override
        public String toString() {
            return id + ": " + reasoner + ", " + unsatisfiable.size() + " unsatisfiable, "
                    + unlabelled.size() + " unlabelled, " + errors.size() + " errors, .ttl "
                    + (owlTtlDiffer ? "differs" : "agrees");
        }
    }

    private final File stubs;
    private final File fetched;

    /** Every import answered with an empty stub kept in {@code stubs}. */
    PatternAudit(File stubs) {
        this(stubs, null);
    }

    /**
     * Imports read from {@code fetched} where a copy is there - named by {@link #fileNameFor} -
     * and answered with a stub in {@code stubs} where not.
     */
    PatternAudit(File stubs, File fetched) {
        this.stubs = stubs;
        this.fetched = fetched;
    }

    /** The name a fetched copy of the ontology {@code iri} has in the fetched-imports folder. */
    static String fileNameFor(String iri) {
        return Integer.toHexString(iri.hashCode()) + ".owl";
    }

    /** Audits the pattern in {@code patternDir}. */
    Row audit(File patternDir) throws Exception {
        String id = patternDir.getName();
        Set<String> stubbed = new TreeSet<String>();
        OWLOntology ontology = manager(stubbed)
                .loadOntologyFromOntologyDocument(new File(patternDir, "pattern.owl"));

        Set<String> imports = new TreeSet<String>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            imports.add(declaration.getIRI().toString());
        }

        Map<String, List<Label>> labels = new LinkedHashMap<String, List<Label>>();
        for (OWLAnnotationAssertionAxiom assertion
                : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().getIRI().equals(OWLRDFVocabulary.RDFS_LABEL.getIRI())
                    && assertion.getSubject() instanceof IRI
                    && assertion.getValue() instanceof OWLLiteral) {
                OWLLiteral value = (OWLLiteral) assertion.getValue();
                String subject = assertion.getSubject().toString();
                if (!labels.containsKey(subject)) {
                    labels.put(subject, new ArrayList<Label>());
                }
                labels.get(subject).add(new Label(value.getLiteral(), value.getLang()));
            }
        }
        Set<String> declared = new HashSet<String>();
        for (OWLDeclarationAxiom declaration : ontology.getAxioms(AxiomType.DECLARATION)) {
            declared.add(declaration.getEntity().getIRI().toString());
        }
        Set<String> unlabelled = new TreeSet<String>();
        Set<String> unlabelledDeclared = new TreeSet<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            String iri = entity.getIRI().toString();
            if (entity.isBuiltIn() || builtIn(iri) || labels.containsKey(iri)) {
                continue;
            }
            unlabelled.add(iri);
            if (declared.contains(iri)) {
                unlabelledDeclared.add(iri);
            }
        }

        Set<String> unsatisfiable = new TreeSet<String>();
        String reasoner = reason(ontology, unsatisfiable);

        Map<String, String> options = ReportOperation.getDefaultOptions();
        options.put(QualityReport.OPTION_PROFILE, new File(ODK_PROFILE).getAbsolutePath());
        List<QualityFinding> errors = new ArrayList<QualityFinding>();
        for (QualityFinding finding : QualityReport.run(ontology, options)) {
            if (finding.getSeverity() == QualityFinding.Severity.ERROR) {
                errors.add(finding);
            }
        }

        List<String> headerComments = new ArrayList<String>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().isComment() && annotation.getValue() instanceof OWLLiteral) {
                headerComments.add(((OWLLiteral) annotation.getValue()).getLiteral());
            }
        }

        return new Row(id, ontology.getAxiomCount(), imports, stubbed, reasoner, unsatisfiable,
                unlabelled, unlabelledDeclared, labels, errors,
                !logical(ontology).equals(logical(manager(new TreeSet<String>())
                        .loadOntologyFromOntologyDocument(new File(patternDir, "pattern.ttl")))),
                headerComments);
    }

    /** HermiT on the pattern as loaded; fills {@code unsatisfiable}. */
    private static String reason(OWLOntology ontology, Set<String> unsatisfiable) {
        OWLReasoner hermit = null;
        try {
            hermit = new org.semanticweb.HermiT.ReasonerFactory().createReasoner(ontology);
            if (!hermit.isConsistent()) {
                return "INCONSISTENT";
            }
            for (OWLClass c : hermit.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                unsatisfiable.add(c.getIRI().toString());
            }
            return unsatisfiable.isEmpty() ? "coherent" : "UNSATISFIABLE";
        } catch (IllegalArgumentException outsideOwl2Dl) {
            // As in PatternSoundness: HermiT refuses an ontology outside OWL 2 DL.
            return "NOT_OWL_2_DL";
        } catch (Exception failed) {                // NOPMD - recorded rather than thrown
            return "FAILED: " + failed.getClass().getSimpleName();
        } finally {
            if (hermit != null) {
                hermit.dispose();
            }
        }
    }

    /** The logical axioms, without their annotations. */
    private static Set<OWLAxiom> logical(OWLOntology ontology) {
        Set<OWLAxiom> axioms = new HashSet<OWLAxiom>();
        for (OWLAxiom axiom : ontology.getLogicalAxioms()) {
            axioms.add(axiom.getAxiomWithoutAnnotations());
        }
        return axioms;
    }

    private OWLOntologyManager manager(final Set<String> stubbed) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(new OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI ontologyIRI) {
                String iri = ontologyIRI.toString();
                if (iri.startsWith("file:")) {
                    return null;
                }
                File copy = fetched == null ? null : new File(fetched, fileNameFor(iri));
                if (copy != null && copy.isFile()) {
                    return IRI.create(copy);
                }
                stubbed.add(iri);
                try {
                    return IRI.create(stubFor(iri));
                } catch (IOException cannotWrite) {
                    return null;
                }
            }
        });
        return manager;
    }

    /** An empty document carrying the import's own IRI, one per IRI, as PatternSoundness's. */
    private File stubFor(String iri) throws IOException {
        File stub = new File(stubs, Integer.toHexString(iri.hashCode()) + ".ttl");
        if (!stub.exists()) {
            Files.write(stub.toPath(), ("<" + iri
                    + "> a <http://www.w3.org/2002/07/owl#Ontology> .\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return stub;
    }

    private static boolean builtIn(String iri) {
        for (String prefix : BUILT_IN) {
            if (iri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The whole library: see the class comment. Each pattern gets two minutes. */
    public static void main(String[] args) throws Exception {
        File patterns = new File(args[0]);
        File out = new File(args[1]);
        File fetched = args.length > 2 ? new File(args[2]) : null;
        File stubs = new File(out.getAbsoluteFile().getParentFile(), "pattern-audit-stubs");
        if (!stubs.isDirectory() && !stubs.mkdirs()) {
            throw new IOException("could not make " + stubs);
        }
        final PatternAudit audit = new PatternAudit(stubs, fetched);
        File[] directories = patterns.listFiles();
        Arrays.sort(directories);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PrintWriter rows = new PrintWriter(out, "UTF-8");
                PrintWriter errors = new PrintWriter(out.getPath() + ".errors.tsv", "UTF-8");
                PrintWriter labels = new PrintWriter(out.getPath() + ".labels.tsv", "UTF-8");
                PrintWriter unlabelled = new PrintWriter(out.getPath() + ".unlabelled.tsv",
                        "UTF-8")) {
            rows.println("id\taxioms\timports\tstubbed\treasoner\tunsatisfiable"
                    + "\tunlabelled_declared\tunlabelled_used\terrors\terror_rules"
                    + "\towl_ttl_differ\tunsatisfiable_list");
            errors.println("id\trule\tsubject\tproperty\tvalue");
            labels.println("id\tentity\tlabels");
            unlabelled.println("id\tentity\tdeclared");
            for (final File directory : directories) {
                if (!new File(directory, "pattern.owl").isFile()) {
                    continue;
                }
                Future<Row> next = pool.submit(() -> audit.audit(directory));
                Row row;
                try {
                    row = next.get(120, TimeUnit.SECONDS);
                } catch (TimeoutException late) {
                    next.cancel(true);
                    pool.shutdownNow();
                    pool = Executors.newSingleThreadExecutor();
                    rows.println(directory.getName() + "\t\t\t\tTIMEOUT");
                    continue;
                } catch (ExecutionException failed) {
                    rows.println(directory.getName() + "\t\t\t\tFAILED: "
                            + clean(String.valueOf(failed.getCause())));
                    continue;
                }
                Map<String, Integer> rules = new TreeMap<String, Integer>();
                for (QualityFinding error : row.errors()) {
                    rules.merge(error.getRule(), 1, Integer::sum);
                    errors.println(String.join("\t", row.id(), error.getRule(),
                            clean(error.getSubject()), clean(error.getProperty()),
                            clean(error.getValue())));
                }
                StringBuilder ruleList = new StringBuilder();
                for (Map.Entry<String, Integer> rule : rules.entrySet()) {
                    ruleList.append(ruleList.length() == 0 ? "" : ",").append(rule.getKey())
                            .append('=').append(rule.getValue());
                }
                rows.println(String.join("\t", row.id(), String.valueOf(row.axioms()),
                        String.valueOf(row.imports().size()), String.valueOf(row.stubbed().size()),
                        row.reasoner(), String.valueOf(row.unsatisfiable().size()),
                        String.valueOf(row.unlabelledDeclared().size()),
                        String.valueOf(row.unlabelled().size() - row.unlabelledDeclared().size()),
                        String.valueOf(row.errors().size()), ruleList,
                        String.valueOf(row.owlTtlDiffer()),
                        String.join(" ", row.unsatisfiable())));
                for (Map.Entry<String, List<Label>> entity : row.labels().entrySet()) {
                    if (entity.getValue().size() > 1) {
                        StringBuilder all = new StringBuilder();
                        for (Label label : entity.getValue()) {
                            all.append(all.length() == 0 ? "" : " | ").append(clean(label.toString()));
                        }
                        labels.println(row.id() + "\t" + entity.getKey() + "\t" + all);
                    }
                }
                for (String entity : row.unlabelled()) {
                    unlabelled.println(row.id() + "\t" + entity + "\t"
                            + row.unlabelledDeclared().contains(entity));
                }
                rows.flush();
                System.out.println(row);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
