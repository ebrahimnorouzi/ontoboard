package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * What a reasoner says about every bundled pattern, and about every example of one.
 *
 * <p>TWO DIFFERENT QUESTIONS, and only the first had ever been asked here:
 *
 * <ol>
 *   <li>Is the pattern's own set of axioms consistent, with no unsatisfiable class? A class that
 *       cannot have an instance is one nobody can use, and every ontology importing the pattern
 *       inherits it.
 *   <li>Is the pattern MERGED WITH ITS EXAMPLE consistent? MEASURED: not one of the 163 examples
 *       declares {@code owl:imports} of its pattern - every one reuses the pattern's IRIs by
 *       prefix alone - so an example loaded on its own is a document of undeclared names that
 *       cannot contradict anything. Merging them is what turns "the example parses" into "the
 *       example is a model of the pattern", and it found a real one: the generated example for
 *       spatial-graph-adapter-sga asserted an individual into two classes the pattern declares
 *       disjoint.
 * </ol>
 *
 * <p><b>IMPORTS ARE DELIBERATELY NOT RESOLVED.</b> 101 of the 163 patterns declare
 * {@code owl:imports}; among those targets are five on a server that stopped answering, two
 * unresolvable {@code file:/schemas/} URIs, and an ODP portal whose HTTPS endpoint does not
 * complete a TLS handshake from here. Fetching them would make this gate depend on the weather:
 * the same pattern would be verified today and unverified tomorrow, and a pattern that passed
 * only because its import failed to download would be indistinguishable from one that passed on
 * its own merits.
 *
 * <p>So every import is answered with an empty local document and reasoning runs over what each
 * pattern file itself asserts. The cost of that choice is stated rather than hidden: a
 * contradiction that arises only against DUL or BFO is NOT caught, and the {@code imports} column
 * says for each pattern how much was left out of its own check.
 *
 * <p><b>WHERE HermiT COMES FROM, because it cost an hour to find out.</b> It is not declared
 * anywhere: {@code robot-core} brings {@code org.semanticweb.hermit:1.4.5.456} transitively, and
 * that build is the one compatible with the OWL API 4.5.9 this project pins. Declaring a HermiT
 * dependency directly BREAKS this: every newer build on Maven Central is compiled against an OWL
 * API whose {@code OWLOntologyID.getDefaultDocumentIRI()} returns {@code java.util.Optional},
 * where 4.5.9 returns Guava's, so clausification dies with a NoSuchMethodError. A direct
 * dependency also sorts ahead of robot-core on the classpath, so adding one silently replaces the
 * working reasoner with a broken one. Take HermiT from robot-core, and do not pin it.
 */
final class PatternSoundness {

    /** One pattern's verdict. */
    static final class Verdict {
        private final String id;
        private final int imports;
        private final int patternAxioms;
        private final boolean patternConsistent;
        private final Set<String> patternUnsatisfiable;
        private final String exampleAxioms;
        private final String mergedConsistent;
        private final String mergedUnsatisfiable;
        private final String note;

        Verdict(String id, int imports, int patternAxioms, boolean patternConsistent,
                Set<String> patternUnsatisfiable, String exampleAxioms, String mergedConsistent,
                String mergedUnsatisfiable, String note) {
            this.id = id;
            this.imports = imports;
            this.patternAxioms = patternAxioms;
            this.patternConsistent = patternConsistent;
            this.patternUnsatisfiable = patternUnsatisfiable;
            this.exampleAxioms = exampleAxioms;
            this.mergedConsistent = mergedConsistent;
            this.mergedUnsatisfiable = mergedUnsatisfiable;
            this.note = note;
        }

        String getId() {
            return id;
        }

        /** Whether a reasoner found nothing wrong with the pattern or with its example. */
        boolean isClean() {
            return patternConsistent && patternUnsatisfiable.isEmpty()
                    && !"false".equals(mergedConsistent) && mergedUnsatisfiable.isEmpty()
                    && !"PARSE_FAILED".equals(exampleAxioms);
        }

        /** The sentence a reader of the pattern's page sees. */
        String describe() {
            if (!patternConsistent) {
                return patternUnsatisfiable.contains("NOT_OWL_2_DL")
                        ? "HermiT refuses this pattern: it is outside OWL 2 DL, so no DL reasoner "
                          + "can draw conclusions from it."
                        : "HermiT finds this pattern inconsistent as published.";
            }
            if (!patternUnsatisfiable.isEmpty()) {
                return "HermiT finds " + join(patternUnsatisfiable) + " unsatisfiable: as "
                        + "published, " + (patternUnsatisfiable.size() == 1 ? "that class" : "those classes")
                        + " cannot have an instance.";
            }
            if ("false".equals(mergedConsistent)) {
                return "HermiT finds the pattern consistent, but its example contradicts it.";
            }
            if ("-".equals(mergedConsistent)) {
                return "HermiT finds the pattern consistent. It has no example to check.";
            }
            return "HermiT finds the pattern consistent, with no unsatisfiable class, and its "
                    + "example consistent with it.";
        }

        String asRow() {
            return String.join("\t", id, String.valueOf(imports), String.valueOf(patternAxioms),
                    String.valueOf(patternConsistent),
                    patternUnsatisfiable.isEmpty() ? "none" : join(patternUnsatisfiable),
                    exampleAxioms, mergedConsistent,
                    mergedUnsatisfiable.isEmpty() ? "none" : mergedUnsatisfiable, note);
        }
    }

    static final String HEADER = "id\timports\tpattern_axioms\tpattern_consistent"
            + "\tpattern_unsat\texample_axioms\tmerged_consistent\tmerged_unsat\tnotes";

    private final File stubs;

    PatternSoundness(File stubs) {
        this.stubs = stubs;
    }

    /** Every pattern directory, reasoned over, in directory order. */
    List<Verdict> judge(File patterns) throws IOException {
        OWLReasonerFactory hermit = new org.semanticweb.HermiT.ReasonerFactory();
        File[] directories = patterns.listFiles();
        Arrays.sort(directories);
        List<Verdict> verdicts = new ArrayList<Verdict>();
        for (File directory : directories) {
            if (!directory.isDirectory() || !new File(directory, "pattern.ttl").exists()) {
                continue;
            }
            verdicts.add(judgeOne(hermit, directory));
        }
        return verdicts;
    }

    private Verdict judgeOne(OWLReasonerFactory hermit, File directory) throws IOException {
        String id = directory.getName();
        OWLOntology pattern;
        try {
            pattern = manager().loadOntologyFromOntologyDocument(
                    new File(directory, "pattern.ttl"));
        } catch (Exception willNotParse) {          // NOPMD - any failure is the same answer
            return new Verdict(id, 0, 0, false, oneOf("PARSE_FAILED"), "-", "-", "",
                    willNotParse.getClass().getSimpleName());
        }
        int imports = pattern.getImportsDeclarations().size();
        Set<OWLAxiom> own = new HashSet<OWLAxiom>(pattern.getAxioms());

        Answer alone = ask(hermit, own);
        File exampleFile = new File(directory, "example.ttl");
        if (!exampleFile.exists()) {
            return new Verdict(id, imports, own.size(), alone.consistent, alone.unsatisfiable,
                    "-", "-", "", "no example.ttl");
        }
        OWLOntology example;
        try {
            example = manager().loadOntologyFromOntologyDocument(exampleFile);
        } catch (Exception willNotParse) {          // NOPMD - any failure is the same answer
            return new Verdict(id, imports, own.size(), alone.consistent, alone.unsatisfiable,
                    "PARSE_FAILED", "-", "", willNotParse.getClass().getSimpleName());
        }
        Set<OWLAxiom> both = new HashSet<OWLAxiom>(own);
        both.addAll(example.getAxioms());
        Answer merged = ask(hermit, both);
        return new Verdict(id, imports, own.size(), alone.consistent, alone.unsatisfiable,
                String.valueOf(example.getAxiomCount()), String.valueOf(merged.consistent),
                merged.unsatisfiable.isEmpty() ? "" : join(merged.unsatisfiable), "");
    }

    private static final class Answer {
        private boolean consistent;
        private final Set<String> unsatisfiable = new TreeSet<String>();
    }

    private Answer ask(OWLReasonerFactory factory, Set<OWLAxiom> axioms) {
        Answer answer = new Answer();
        OWLReasoner reasoner = null;
        try {
            OWLOntology ontology = manager().createOntology(axioms);
            reasoner = factory.createReasoner(ontology);
            answer.consistent = reasoner.isConsistent();
            if (answer.consistent) {
                for (OWLClass unsatisfiable
                        : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                    answer.unsatisfiable.add(shortForm(unsatisfiable.getIRI()));
                }
            }
        } catch (IllegalArgumentException outsideOwl2Dl) {
            // HermiT refuses an ontology outside OWL 2 DL rather than answering about it: a
            // cyclic property hierarchy breaks the regularity restriction, and a non-simple
            // property in an asymmetry axiom breaks the simplicity one. Both are real in this
            // library, and neither is an inconsistency - it is a question no DL reasoner can be
            // asked, which is worth saying differently.
            answer.consistent = false;
            answer.unsatisfiable.add("NOT_OWL_2_DL");
        } catch (Exception reasonerFailed) {        // NOPMD - recorded rather than thrown
            answer.consistent = false;
            answer.unsatisfiable.add("REASONER_" + reasonerFailed.getClass().getSimpleName());
        } finally {
            if (reasoner != null) {
                reasoner.dispose();
            }
        }
        return answer;
    }

    /** A manager that answers every import with an empty local document, so nothing is fetched. */
    private OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(new OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI ontologyIRI) {
                try {
                    return IRI.create(stubFor(ontologyIRI));
                } catch (IOException cannotWrite) {
                    return null;
                }
            }
        });
        return manager;
    }

    /**
     * An empty ontology document standing in for one import.
     *
     * <p>It carries the import's own ontology IRI, so the manager is satisfied it loaded what was
     * asked for, and holds no axioms, so nothing it would have said can affect a verdict. One
     * file per IRI, named by a hash: two imports mapped to one document collide on document IRI.
     */
    private File stubFor(IRI ontologyIRI) throws IOException {
        File stub = new File(stubs,
                Integer.toHexString(ontologyIRI.toString().hashCode()) + ".ttl");
        if (!stub.exists()) {
            Files.write(stub.toPath(), ("<" + ontologyIRI
                    + "> a <http://www.w3.org/2002/07/owl#Ontology> .\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return stub;
    }

    private static Set<String> oneOf(String only) {
        Set<String> one = new TreeSet<String>();
        one.add(only);
        return one;
    }

    private static String shortForm(IRI iri) {
        String text = iri.toString();
        int cut = Math.max(text.lastIndexOf('#'), text.lastIndexOf('/'));
        return cut < 0 ? text : text.substring(cut + 1);
    }

    private static String join(Iterable<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(item);
        }
        return text.toString();
    }
}
