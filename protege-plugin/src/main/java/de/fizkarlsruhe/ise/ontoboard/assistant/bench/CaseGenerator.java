package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextBuilder;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.UnsupportedEntailmentTypeException;

/**
 * Makes benchmark cases from a finished ontology (spec D8): a known fault is injected and the
 * reasoner's explanation becomes the context, or a term the question needs is held back.
 *
 * <p>Deterministic: candidates are sorted before a seeded {@link Random} picks from them, so the
 * same ontology and seed give byte-identical cases. Every injected fault is checked with the
 * reasoner and dropped if it did not have the intended effect. The context is rendered by the
 * plugin's own {@code ContextBuilder} and {@code Problems}, and the questions are the plugin's
 * own ({@link Questions}).
 */
public final class CaseGenerator {

    /** Where injected faults live, so they are never mistaken for the ontology's own terms. */
    public static final String NS = "http://example.org/ontoboard-bench#";

    private final OWLOntology ontology;
    private final String ontologyPath;
    private final String sha256;
    private final OWLReasonerFactory factory;
    private final String reasonerName;
    private final long seed;
    private final String base;
    private final Map<String, Set<OWLAxiom>> faults = new LinkedHashMap<String, Set<OWLAxiom>>();

    public CaseGenerator(OWLOntology ontology, String ontologyPath, String sha256,
            OWLReasonerFactory factory, String reasonerName, long seed) {
        this.ontology = ontology;
        this.ontologyPath = ontologyPath;
        this.sha256 = sha256;
        this.factory = factory;
        this.reasonerName = reasonerName;
        this.seed = seed;
        String name = new File(ontologyPath).getName();
        this.base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    /** SHA-256 of the file with every CRLF read as LF, so a Windows checkout matches. */
    public static String sha256Lf(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] lf = new byte[bytes.length];
        int length = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                continue;
            }
            lf[length++] = bytes[i];
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.copyOf(lf, length));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException cannotHappen) {
            throw new IllegalStateException(cannotHappen);
        }
    }

    // ------------------------------------------------------------------ explain-problem

    /**
     * Up to {@code count} cases.
     *
     * <p>A problem the ontology already has comes first, as it is: an inconsistency is the only
     * case an inconsistent ontology can give - nothing can be injected into it, and its own
     * problem is the one a person arrives with (the pizza example is inconsistent on purpose) -
     * and each class that is already unsatisfiable is a case.
     *
     * <p>Then injected faults: for every asserted disjoint pair, an unsatisfiable class (a fresh
     * class made a subclass of both sides) and an inconsistency (a fresh individual given both
     * types), every pair-and-kind combination shuffled with the seed and tried in turn, so a
     * fault that has no effect on one combination never stops the others from being tried.
     */
    public List<BenchCase> explainProblem(int count) {
        return problems(count, BenchCase.EXPLAIN, seed);
    }

    /**
     * Repair cases (round 2): the same kinds of problem as explain-problem, shuffled with their
     * own seed, each carrying its injected axioms in functional syntax so the runner can add
     * them, let the model remove what it proposes, and reason again.
     */
    public List<BenchCase> repair(int count) {
        return problems(count, BenchCase.REPAIR, seed + 3);
    }

    private List<BenchCase> problems(int count, String task, long shuffleSeed) {
        List<BenchCase> cases = new ArrayList<BenchCase>();
        if (count <= 0) {
            return cases;
        }
        Explanations.Result existing = Problems.diagnose(ontology, factory);
        if (!existing.isConsistent()) {
            ContextItem problem = Problems.inconsistency(ontology, existing, reasonerName);
            cases.add(asIs(task, 1, "inconsistent", problem, Questions.whyInconsistent(), ""));
            return cases;
        }
        for (String label : existing.getUnsatisfiable()) {
            if (cases.size() >= count) {
                return cases;
            }
            OWLClass cls = classLabelled(label);
            if (cls != null) {
                ContextItem problem = Problems.unsatisfiable(ontology, factory, cls,
                        reasonerName);
                cases.add(asIs(task, cases.size() + 1, "unsatisfiable", problem,
                        Questions.whyUnsatisfiable(label), cls.getIRI().toString()));
            }
        }
        List<Object[]> combinations = new ArrayList<Object[]>();
        for (OWLClass[] pair : disjointPairs()) {
            combinations.add(new Object[] {pair, Boolean.TRUE});
            combinations.add(new Object[] {pair, Boolean.FALSE});
        }
        Collections.shuffle(combinations, new Random(shuffleSeed));
        int number = 0;
        for (Object[] combination : combinations) {
            if (cases.size() >= count) {
                break;
            }
            OWLClass[] pair = (OWLClass[]) combination[0];
            number++;
            BenchCase made = (Boolean) combination[1] ? unsatisfiable(task, number, pair)
                    : inconsistent(task, number, pair);
            if (made != null) {
                cases.add(made);
            }
        }
        return cases;
    }

    private String id(String task, String kind, int number) {
        String prefix = BenchCase.REPAIR.equals(task) ? "repair-" : "";
        return String.format("%s-%s%s-%02d", base, prefix, kind, number);
    }

    /** A problem the ontology already has, with nothing injected. */
    private BenchCase asIs(String task, int number, String kind, ContextItem problem,
            String message, String targetIri) {
        String id = id(task, "asis", number);
        faults.put(id, Collections.<OWLAxiom>emptySet());
        BenchCase made = new BenchCase(id, task, ontologyPath, sha256, kind,
                Collections.<String>emptyList(), false, ContextBuilder.header(ontology),
                Collections.singletonList(new RenderedItem(problem.getTitle(), problem.getText())),
                message, termsIn(ontology, problem.getText()),
                sharedTerms(ontology, problem.getText()), Collections.<String>emptyList(),
                Collections.<String>emptyList());
        return BenchCase.REPAIR.equals(task) ? made.with(new BenchCase.Extra("", "", "",
                Collections.<String>emptyList(), "", targetIri)) : made;
    }

    /** The repair gold: the injected axioms, readable again, and the class to make satisfiable. */
    private static BenchCase withFault(BenchCase made, String task, Set<OWLAxiom> axioms,
            String targetIri) {
        if (!BenchCase.REPAIR.equals(task)) {
            return made;
        }
        return made.with(new BenchCase.Extra(ofn(axioms), "", "",
                Collections.<String>emptyList(), "", targetIri));
    }

    /** Axioms in OWL functional syntax, sorted by the writer, so the same axioms read alike. */
    static String ofn(Set<OWLAxiom> axioms) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology holder = manager.createOntology(IRI.create(NS + "fault"));
            manager.addAxioms(holder, axioms);
            StringDocumentTarget target = new StringDocumentTarget();
            manager.saveOntology(holder, new FunctionalSyntaxDocumentFormat(), target);
            return target.toString().replace("\r\n", "\n");
        } catch (OWLOntologyCreationException | OWLOntologyStorageException failed) {
            throw new IllegalStateException("could not write the fault", failed);
        }
    }

    // ------------------------------------------------------------------ definition

    /**
     * Definition cases (round 2): classes with one definition and a named parent, each with its
     * definition held back. The context is the class's block from a copy without the definition,
     * and the message is exactly what "Suggest a definition" sends.
     */
    public List<BenchCase> definition(int count) {
        List<BenchCase> cases = new ArrayList<BenchCase>();
        if (count <= 0) {
            return cases;
        }
        List<OWLClass> candidates = new ArrayList<OWLClass>();
        Map<OWLClass, String> references = new LinkedHashMap<OWLClass, String>();
        List<OWLClass> classes = new ArrayList<OWLClass>(ontology.getClassesInSignature());
        Collections.sort(classes);
        for (OWLClass cls : classes) {
            List<String> definitions = definitionsOf(ontology, cls.getIRI());
            if (definitions.size() == 1
                    && !Questions.parentsOf(ontology, cls.getIRI()).isEmpty()) {
                candidates.add(cls);
                references.put(cls, definitions.get(0));
            }
        }
        Collections.shuffle(candidates, new Random(seed + 2));
        for (int i = 0; i < count && i < candidates.size(); i++) {
            OWLClass cls = candidates.get(i);
            OWLOntology without = withoutDefinition(ontology, cls.getIRI());
            String label = DisplayLabels.forEntity(without, cls);
            List<String> genus = Questions.parentsOf(without, cls.getIRI());
            String id = String.format("%s-definition-%02d", base, i + 1);
            cases.add(new BenchCase(id, BenchCase.DEFINITION, ontologyPath, sha256, null,
                    Collections.<String>emptyList(), false, ContextBuilder.header(ontology),
                    Collections.singletonList(new RenderedItem(label,
                            ContextBuilder.term(without, cls.getIRI()))),
                    Questions.suggestDefinition(label, genus, false),
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList())
                    .with(new BenchCase.Extra("", label, cls.getIRI().toString(), genus,
                            references.get(cls), "")));
        }
        return cases;
    }

    private static List<String> definitionsOf(OWLOntology ontology, IRI iri) {
        List<String> found = new ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
            if (Proposal.Property.DEFINITION.getIris().contains(axiom.getProperty().getIRI())
                    && axiom.getValue().asLiteral().isPresent()) {
                found.add(axiom.getValue().asLiteral().get().getLiteral());
            }
        }
        return found;
    }

    /** A copy of the ontology without the class's definitions: what the model is shown. */
    public static OWLOntology withoutDefinition(OWLOntology ontology, IRI iri) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology(ontology.getOntologyID());
            manager.addAxioms(copy, ontology.getAxioms());
            for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(iri)) {
                if (Proposal.Property.DEFINITION.getIris().contains(
                        axiom.getProperty().getIRI())) {
                    manager.removeAxiom(copy, axiom);
                }
            }
            return copy;
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new IllegalStateException("could not copy the ontology", cannotCopy);
        }
    }

    // ------------------------------------------------------------------ text to axiom

    /** A name that says nothing to a reader: an ID such as NFDI_0000001 shown for want of a label. */
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z]+_[0-9]+");

    /** A held-back axiom's class and the sentence that says the axiom. */
    private static final class Said {
        final OWLClass subject;
        final String sentence;

        Said(OWLClass subject, String sentence) {
            this.subject = subject;
            this.sentence = sentence;
        }
    }

    /**
     * Text-to-axiom cases (1.140.0): an axiom about a class in the ontology's own namespace, held
     * back and said as a sentence. "At least one" says {@code some} and "nothing but" says
     * {@code only}; the word "only" is avoided, so the quantifier is the model's to choose.
     * <ul>
     *   <li>{@code C SubClassOf p some F}: "Every C p at least one F."
     *   <li>{@code C EquivalentTo G and (p some F)}, the form NFDIcore and MWO define their
     *       classes in: "Every C is a G that p at least one F, and every G that p at least one F
     *       is a C." Both directions, since a definition says both.
     *   <li>Since 1.144.0, more restrictions, with numbers as digits: {@code p min n F} "p at
     *       least n F" ({@code min 1} as "at least one"), {@code p max n F} "p at most n F",
     *       {@code p exactly n F} "p exactly n F", {@code p value i} "p i", {@code p some D}
     *       for a datatype "p at least one D value", {@code p value "v"^^D} "p the D value v".
     * </ul>
     * The context is the class's block from a copy without the axiom, and the message is exactly
     * what "Write as OWL…" sends.
     *
     * <p>An axiom the rest of the ontology already entails is skipped, since any answer would
     * entail it; an inconsistent ontology gives no case, since there everything is entailed.
     */
    public List<BenchCase> textToAxiom(int count) {
        List<BenchCase> cases = new ArrayList<BenchCase>();
        if (count <= 0 || !consistent(ontology.getAxioms(Imports.INCLUDED), factory)) {
            return cases;
        }
        Map<OWLAxiom, Said> said = new TreeMap<OWLAxiom, Said>();
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            Said one = said(axiom);
            if (one != null) {
                said.put(axiom.getAxiomWithoutAnnotations(), one);
            }
        }
        for (OWLEquivalentClassesAxiom axiom : ontology.getAxioms(AxiomType.EQUIVALENT_CLASSES)) {
            Said one = said(axiom);
            if (one != null) {
                said.put(axiom.getAxiomWithoutAnnotations(), one);
            }
        }
        // The shapes added in 1.144.0 come after those of some and only, shuffled on their own:
        // the cases of some and only stay the ones every earlier version chose, so live runs of
        // different versions measure the same cases.
        List<OWLAxiom> candidates = new ArrayList<OWLAxiom>();
        List<OWLAxiom> newer = new ArrayList<OWLAxiom>();
        for (OWLAxiom axiom : said.keySet()) {
            (usesNewShapes(axiom) ? newer : candidates).add(axiom);
        }
        Collections.shuffle(candidates, new Random(seed + 4));
        Collections.shuffle(newer, new Random(seed + 5));
        candidates.addAll(newer);
        for (OWLAxiom gold : candidates) {
            if (cases.size() >= count) {
                break;
            }
            OWLOntology without = without(ontology, gold);
            if (!entails(without.getAxioms(Imports.INCLUDED), gold, factory)
                    && safeToAdd(without, gold, factory)) {
                cases.add(textCase(cases.size() + 1, gold, said.get(gold), without));
            }
        }
        return cases;
    }

    private BenchCase textCase(int number, OWLAxiom gold, Said said, OWLOntology without) {
        OWLClass cls = said.subject;
        String subject = ContextBuilder.titleOf(without, ContextItem.term(cls.getIRI(), true));
        String id = String.format("%s-text-to-axiom-%02d", base, number);
        // What "Write as OWL…" sends: the subject's block, then the blocks of the terms the
        // sentence names, as the chips the session adds (1.141.0).
        List<RenderedItem> context = new ArrayList<RenderedItem>();
        context.add(new RenderedItem(subject, ContextBuilder.term(without, cls.getIRI())));
        for (IRI term : Questions.sentenceTerms(without, cls.getIRI(), said.sentence)) {
            ContextItem item = ContextItem.term(term, false);
            context.add(new RenderedItem(ContextBuilder.titleOf(without, item),
                    ContextBuilder.render(without, item)));
        }
        return new BenchCase(id, BenchCase.TEXT_TO_AXIOM, ontologyPath, sha256, null,
                Collections.<String>emptyList(), false, ContextBuilder.header(ontology),
                context, Questions.sentenceToAxiom(subject, said.sentence),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList())
                .with(new BenchCase.Extra("", subject, cls.getIRI().toString(),
                        Collections.<String>emptyList(), said.sentence, "",
                        ofn(Collections.singleton(gold))));
    }

    /** "Every C p at least one F." for C SubClassOf p some F; null for any other shape. */
    private Said said(OWLSubClassOfAxiom axiom) {
        OWLClass cls = ownClass(axiom.getSubClass());
        String restriction = cls == null ? null : restriction(axiom.getSuperClass());
        return restriction == null ? null
                : new Said(cls, "Every " + name(cls) + " " + restriction + ".");
    }

    /**
     * Both directions of C EquivalentTo [G and] (p some F) [and (q only H)...]: one named class
     * of the ontology's own, at most one named genus, and simple restrictions only.
     */
    private Said said(OWLEquivalentClassesAxiom axiom) {
        OWLClass cls = null;
        OWLClassExpression definition = null;
        for (OWLClassExpression operand : axiom.getClassExpressions()) {
            if (operand.isAnonymous() && definition == null) {
                definition = operand;
            } else if (!operand.isAnonymous() && cls == null) {
                cls = ownClass(operand);
                if (cls == null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        if (cls == null || definition == null) {
            return null;
        }
        List<OWLClassExpression> parts = new ArrayList<OWLClassExpression>(
                definition instanceof OWLObjectIntersectionOf
                        ? ((OWLObjectIntersectionOf) definition).getOperands()
                        : Collections.singleton(definition));
        String genus = null;
        List<String> restrictions = new ArrayList<String>();
        for (OWLClassExpression part : parts) {
            if (!part.isAnonymous()) {
                if (genus != null || part.asOWLClass().isBuiltIn()
                        || !readable(part.asOWLClass())) {
                    return null;
                }
                genus = name(part.asOWLClass());
            } else {
                String restriction = restriction(part);
                if (restriction == null) {
                    return null;
                }
                restrictions.add(restriction);
            }
        }
        if (restrictions.isEmpty()) {
            return null;
        }
        String described = (genus == null ? "thing" : genus) + " that "
                + String.join(" and ", restrictions);
        return new Said(cls, "Every " + name(cls) + " is " + article(described) + " " + described
                + ", and every " + described + " is " + article(name(cls)) + " " + name(cls)
                + ".");
    }

    /**
     * "p at least one F" or "p nothing but F", for a named p and F with readable names; since
     * 1.144.0 also cardinalities, values and data restrictions (see {@link #textToAxiom}).
     */
    private String restriction(OWLClassExpression expression) {
        String more = moreShapes(expression);
        if (more != null) {
            return more.isEmpty() ? null : more;
        }
        OWLObjectPropertyExpression property;
        OWLClassExpression filler;
        String quantifier;
        if (expression instanceof OWLObjectSomeValuesFrom) {
            property = ((OWLObjectSomeValuesFrom) expression).getProperty();
            filler = ((OWLObjectSomeValuesFrom) expression).getFiller();
            quantifier = " at least one ";
        } else if (expression instanceof OWLObjectAllValuesFrom) {
            property = ((OWLObjectAllValuesFrom) expression).getProperty();
            filler = ((OWLObjectAllValuesFrom) expression).getFiller();
            quantifier = " nothing but ";
        } else {
            return null;
        }
        if (property.isAnonymous() || property.asOWLObjectProperty().isBuiltIn()
                || filler.isAnonymous() || filler.asOWLClass().isBuiltIn()
                || !readable(property.asOWLObjectProperty()) || !readable(filler.asOWLClass())) {
            return null;
        }
        return name(property.asOWLObjectProperty()) + quantifier + name(filler.asOWLClass());
    }

    /**
     * The shapes added in 1.144.0, said in words; "" for one of them that cannot be said (an
     * anonymous or unreadable part); null for any other shape, left to {@link #restriction}.
     */
    private String moreShapes(OWLClassExpression expression) {
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction) {
            org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction counted =
                    (org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction) expression;
            String property = objectProperty(counted.getProperty());
            String filler = namedClass(counted.getFiller());
            if (property == null || filler == null) {
                return "";
            }
            int n = counted.getCardinality();
            String quantity;
            if (expression instanceof org.semanticweb.owlapi.model.OWLObjectMinCardinality) {
                quantity = n == 1 ? "at least one" : "at least " + n;
            } else if (expression instanceof org.semanticweb.owlapi.model.OWLObjectMaxCardinality) {
                quantity = "at most " + n;
            } else {
                quantity = "exactly " + n;
            }
            return property + " " + quantity + " " + filler;
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLObjectHasValue) {
            org.semanticweb.owlapi.model.OWLObjectHasValue has =
                    (org.semanticweb.owlapi.model.OWLObjectHasValue) expression;
            String property = objectProperty(has.getProperty());
            if (property == null || !has.getFiller().isNamed()
                    || !readable(has.getFiller().asOWLNamedIndividual())) {
                return "";
            }
            return property + " " + name(has.getFiller().asOWLNamedIndividual());
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLDataSomeValuesFrom) {
            org.semanticweb.owlapi.model.OWLDataSomeValuesFrom some =
                    (org.semanticweb.owlapi.model.OWLDataSomeValuesFrom) expression;
            String property = dataProperty(some.getProperty());
            if (property == null || !some.getFiller().isDatatype()) {
                return "";
            }
            return property + " at least one " + datatypeName(some.getFiller().asOWLDatatype())
                    + " value";
        }
        if (expression instanceof org.semanticweb.owlapi.model.OWLDataHasValue) {
            org.semanticweb.owlapi.model.OWLDataHasValue has =
                    (org.semanticweb.owlapi.model.OWLDataHasValue) expression;
            String property = dataProperty(has.getProperty());
            if (property == null) {
                return "";
            }
            return property + " the " + datatypeName(has.getFiller().getDatatype()) + " value "
                    + has.getFiller().getLiteral();
        }
        return null;
    }

    /** Whether the axiom says something only the 1.144.0 shapes say. */
    private static boolean usesNewShapes(OWLAxiom axiom) {
        for (OWLClassExpression part : axiom.getNestedClassExpressions()) {
            if (part instanceof org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction
                    || part instanceof org.semanticweb.owlapi.model.OWLObjectHasValue
                    || part instanceof org.semanticweb.owlapi.model.OWLDataSomeValuesFrom
                    || part instanceof org.semanticweb.owlapi.model.OWLDataHasValue) {
                return true;
            }
        }
        return false;
    }

    private String objectProperty(OWLObjectPropertyExpression property) {
        return property.isAnonymous() || property.asOWLObjectProperty().isBuiltIn()
                || !readable(property.asOWLObjectProperty()) ? null
                        : name(property.asOWLObjectProperty());
    }

    private String dataProperty(org.semanticweb.owlapi.model.OWLDataPropertyExpression property) {
        return property.isAnonymous() || property.asOWLDataProperty().isBuiltIn()
                || !readable(property.asOWLDataProperty()) ? null
                        : name(property.asOWLDataProperty());
    }

    private String namedClass(OWLClassExpression filler) {
        return filler.isAnonymous() || filler.asOWLClass().isBuiltIn()
                || !readable(filler.asOWLClass()) ? null : name(filler.asOWLClass());
    }

    /** "decimal" for xsd:decimal: the datatype's own short name, as a person says it. */
    private static String datatypeName(org.semanticweb.owlapi.model.OWLDatatype datatype) {
        String remainder = datatype.getIRI().getRemainder().or("");
        return remainder.isEmpty() ? datatype.getIRI().toString() : remainder;
    }

    /** The named class of the ontology's own namespace with a readable name; else null. */
    private OWLClass ownClass(OWLClassExpression expression) {
        if (expression.isAnonymous() || expression.asOWLClass().isBuiltIn()
                || !own(expression.asOWLClass().getIRI()) || !readable(expression.asOWLClass())) {
            return null;
        }
        return expression.asOWLClass();
    }

    private String name(OWLEntity entity) {
        return AssistantRendering.unique(ontology, entity);
    }

    private static String article(String noun) {
        return noun.matches("(?i)[aeiou].*") ? "an" : "a";
    }

    private boolean readable(OWLEntity entity) {
        String name = name(entity);
        return !name.startsWith("<") && !OPAQUE.matcher(name).matches();
    }

    /**
     * Under the folder of the ontology's IRI: .../mwo/ for .../mwo/mwo.owl, and
     * .../ontoboard-fixtures/ for .../ontoboard-fixtures/consistent, whose classes are
     * .../ontoboard-fixtures/materials#... An anonymous ontology owns every class.
     */
    private boolean own(IRI iri) {
        if (!ontology.getOntologyID().getOntologyIRI().isPresent()) {
            return true;
        }
        String namespace = ontology.getOntologyID().getOntologyIRI().get().toString();
        int slash = namespace.lastIndexOf('/');
        if (slash > namespace.indexOf("://") + 2) {
            namespace = namespace.substring(0, slash + 1);
        }
        return iri.toString().startsWith(namespace);
    }

    /** A copy of the ontology without the axiom, its annotations ignored: what the model saw. */
    public static OWLOntology without(OWLOntology ontology, OWLAxiom axiom) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology(ontology.getOntologyID());
            OWLAxiom bare = axiom.getAxiomWithoutAnnotations();
            Set<OWLAxiom> kept = new HashSet<OWLAxiom>();
            for (OWLAxiom each : ontology.getAxioms()) {
                if (!each.getAxiomWithoutAnnotations().equals(bare)) {
                    kept.add(each);
                }
            }
            manager.addAxioms(copy, kept);
            return copy;
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new IllegalStateException("could not copy the ontology", cannotCopy);
        }
    }

    /**
     * Whether the axioms entail the goal, by the reasoner. An inconsistent set entails
     * everything; an entailment the reasoner cannot check counts as not entailed.
     */
    static boolean entails(Set<OWLAxiom> axioms, OWLAxiom goal, OWLReasonerFactory factory) {
        OWLReasoner reasoner = null;
        try {
            reasoner = factory.createReasoner(holding(axioms));
            return !reasoner.isConsistent()
                    || reasoner.isEntailed(goal.getAxiomWithoutAnnotations());
        } catch (UnsupportedEntailmentTypeException cannotCheck) {
            return false;
        } catch (RuntimeException cannotReason) {
            // HermiT's UnsupportedFacetException and UnsupportedDatatypeException are plain
            // RuntimeExceptions: one answer with such a facet ended a whole run (review 1.140.0
            // I2). What the reasoner cannot check is not entailed.
            return false;
        } finally {
            if (reasoner != null) {
                reasoner.dispose();
            }
        }
    }

    /**
     * Whether putting the held-back axiom back is safe, as the scorer judges an answer: the copy
     * stays consistent and no class becomes unsatisfiable (1.142.0, L1). Otherwise not even the
     * axiom itself would pass, and a right answer would score as a fail.
     */
    static boolean safeToAdd(OWLOntology without, OWLAxiom gold, OWLReasonerFactory factory) {
        Set<OWLAxiom> axioms = ProposalCheck.snapshot(without);
        return ProposalCheck.after(axioms,
                ProposalCheck.before(axioms, factory, new ProposalCheck.Run()), factory, null,
                gold, "HermiT", new ProposalCheck.Run()).isSafe();
    }

    static boolean consistent(Set<OWLAxiom> axioms, OWLReasonerFactory factory) {
        OWLReasoner reasoner = factory.createReasoner(holding(axioms));
        try {
            return reasoner.isConsistent();
        } finally {
            reasoner.dispose();
        }
    }

    private static OWLOntology holding(Set<OWLAxiom> axioms) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology holder = manager.createOntology();
            manager.addAxioms(holder, axioms);
            return holder;
        } catch (OWLOntologyCreationException cannotCreate) {
            throw new IllegalStateException("could not hold the axioms", cannotCreate);
        }
    }

    /**
     * For a problem nobody designed, the terms of its first justification that appear in more
     * than one of its axioms: the place where the axioms meet is where they clash - two disjoint
     * classes, or a property and the value it was given. An injected fault names its own clash.
     */
    private static List<String> sharedTerms(OWLOntology ontology, String text) {
        List<String> axioms = new ArrayList<String>();
        boolean inFirst = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("Justification ")) {
                if (inFirst) {
                    break;
                }
                inFirst = true;
            } else if (inFirst && line.startsWith("  ")) {
                axioms.add(line.trim());
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String axiom : axioms) {
            for (String term : termsIn(ontology, axiom)) {
                Integer seen = counts.get(term);
                counts.put(term, seen == null ? 1 : seen + 1);
            }
        }
        List<String> shared = new ArrayList<String>();
        for (Map.Entry<String, Integer> term : counts.entrySet()) {
            if (term.getValue() > 1) {
                shared.add(term.getKey());
            }
        }
        Collections.sort(shared);
        return shared;
    }

    private OWLClass classLabelled(String label) {
        for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
            if (label.equals(DisplayLabels.forEntity(ontology, cls))) {
                return cls;
            }
        }
        return null;
    }

    private BenchCase unsatisfiable(String task, int number, OWLClass[] pair) {
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass fault = data.getOWLClass(IRI.create(NS + "BenchFault" + number));
        Set<OWLAxiom> axioms = new LinkedHashSet<OWLAxiom>();
        axioms.add(data.getOWLDeclarationAxiom(fault));
        axioms.add(data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), fault.getIRI(),
                data.getOWLLiteral("bench fault " + number)));
        axioms.add(data.getOWLSubClassOfAxiom(fault, pair[0]));
        axioms.add(data.getOWLSubClassOfAxiom(fault, pair[1]));
        OWLOntology mutated = copyWith(axioms);
        OWLReasoner reasoner = factory.createReasoner(mutated);
        try {
            if (!reasoner.isConsistent() || reasoner.isSatisfiable(fault)) {
                return null;
            }
        } finally {
            reasoner.dispose();
        }
        ContextItem problem = Problems.unsatisfiable(mutated, factory, fault, reasonerName);
        String label = DisplayLabels.forEntity(mutated, fault);
        String id = id(task, BenchCase.REPAIR.equals(task) ? "unsat" : "explain", number);
        faults.put(id, axioms);
        return withFault(new BenchCase(id, task, ontologyPath, sha256, "unsatisfiable",
                written(axioms), false, ContextBuilder.header(ontology),
                Collections.singletonList(new RenderedItem(problem.getTitle(), problem.getText())),
                Questions.whyUnsatisfiable(label), termsIn(mutated, problem.getText()),
                Arrays.asList(DisplayLabels.forEntity(mutated, pair[0]),
                        DisplayLabels.forEntity(mutated, pair[1])),
                Collections.<String>emptyList(), Collections.<String>emptyList()), task, axioms,
                fault.getIRI().toString());
    }

    private BenchCase inconsistent(String task, int number, OWLClass[] pair) {
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLNamedIndividual fault =
                data.getOWLNamedIndividual(IRI.create(NS + "benchIndividual" + number));
        Set<OWLAxiom> axioms = new LinkedHashSet<OWLAxiom>();
        axioms.add(data.getOWLDeclarationAxiom(fault));
        axioms.add(data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), fault.getIRI(),
                data.getOWLLiteral("bench individual " + number)));
        axioms.add(data.getOWLClassAssertionAxiom(pair[0], fault));
        axioms.add(data.getOWLClassAssertionAxiom(pair[1], fault));
        OWLOntology mutated = copyWith(axioms);
        OWLReasoner reasoner = factory.createReasoner(mutated);
        try {
            if (reasoner.isConsistent()) {
                return null;
            }
        } finally {
            reasoner.dispose();
        }
        ContextItem problem = Problems.inconsistency(mutated,
                Problems.diagnose(mutated, factory), reasonerName);
        String id = id(task, BenchCase.REPAIR.equals(task) ? "incon" : "explain", number);
        faults.put(id, axioms);
        return withFault(new BenchCase(id, task, ontologyPath, sha256, "inconsistent",
                written(axioms), false, ContextBuilder.header(ontology),
                Collections.singletonList(new RenderedItem(problem.getTitle(), problem.getText())),
                Questions.whyInconsistent(), termsIn(mutated, problem.getText()),
                Arrays.asList(DisplayLabels.forEntity(mutated, pair[0]),
                        DisplayLabels.forEntity(mutated, pair[1])),
                Collections.<String>emptyList(), Collections.<String>emptyList()), task, axioms,
                "");
    }

    /** Every pair of named classes an asserted disjointness separates, sorted. */
    private List<OWLClass[]> disjointPairs() {
        Set<String> seen = new HashSet<String>();
        List<OWLClass[]> pairs = new ArrayList<OWLClass[]>();
        for (OWLDisjointClassesAxiom axiom : ontology.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            List<OWLClass> named = new ArrayList<OWLClass>();
            for (OWLClassExpression operand : axiom.getClassExpressions()) {
                if (!operand.isAnonymous() && !operand.isOWLThing() && !operand.isOWLNothing()) {
                    named.add(operand.asOWLClass());
                }
            }
            Collections.sort(named);
            for (int i = 0; i < named.size(); i++) {
                for (int j = i + 1; j < named.size(); j++) {
                    if (seen.add(named.get(i).getIRI() + " " + named.get(j).getIRI())) {
                        pairs.add(new OWLClass[] {named.get(i), named.get(j)});
                    }
                }
            }
        }
        Collections.sort(pairs, new Comparator<OWLClass[]>() {
            @Override
            public int compare(OWLClass[] a, OWLClass[] b) {
                int first = a[0].getIRI().compareTo(b[0].getIRI());
                return first != 0 ? first : a[1].getIRI().compareTo(b[1].getIRI());
            }
        });
        return pairs;
    }

    // ------------------------------------------------------------------ context-request

    /**
     * Cases that need a term the context leaves out - a class T whose restriction names a class
     * N with subclasses, asked "which kinds of N", with only T's block - and controls with N's
     * block included, where asking is the wrong answer.
     */
    public List<BenchCase> contextRequest(int count, int controls) {
        List<OWLClass[]> candidates = restrictionPairs();
        List<BenchCase> cases = new ArrayList<BenchCase>();
        if (candidates.isEmpty()) {
            return cases;
        }
        Collections.shuffle(candidates, new Random(seed + 1));
        for (int i = 0; i < count && i < candidates.size(); i++) {
            cases.add(contextCase(i + 1, candidates.get(i), false));
        }
        // Never more controls than candidates (review #5b): wrapping round made the same control
        // count five times on the pizza example.
        for (int i = 0; i < controls && i < candidates.size(); i++) {
            OWLClass[] pair = candidates.get((count + i) % candidates.size());
            cases.add(contextCase(i + 1, pair, true));
        }
        return cases;
    }

    private BenchCase contextCase(int number, OWLClass[] pair, boolean control) {
        OWLClass term = pair[0];
        OWLClass needed = pair[1];
        String termLabel = DisplayLabels.forEntity(ontology, term);
        String neededLabel = DisplayLabels.forEntity(ontology, needed);
        List<RenderedItem> context = new ArrayList<RenderedItem>();
        context.add(new RenderedItem(termLabel, ContextBuilder.term(ontology, term.getIRI())));
        if (control) {
            context.add(new RenderedItem(neededLabel,
                    ContextBuilder.term(ontology, needed.getIRI())));
        }
        String id = String.format("%s-%s-%02d", base, control ? "control" : "context", number);
        return new BenchCase(id, BenchCase.CONTEXT, ontologyPath, sha256, null,
                Collections.<String>emptyList(), control, ContextBuilder.header(ontology),
                context, "Which kinds of " + neededLabel + " are there in this ontology, and how"
                        + " does that affect " + termLabel + "?",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                control ? Collections.<String>emptyList() : Collections.singletonList(neededLabel),
                control ? Collections.<String>emptyList()
                        : Collections.singletonList(needed.getIRI().toString()));
    }

    /** (T, N) for every T SubClassOf (p some N) or (p only N) where N has a named subclass. */
    private List<OWLClass[]> restrictionPairs() {
        Set<OWLClass> haveChildren = new HashSet<OWLClass>();
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (!axiom.getSuperClass().isAnonymous() && !axiom.getSubClass().isAnonymous()) {
                haveChildren.add(axiom.getSuperClass().asOWLClass());
            }
        }
        Set<String> seen = new HashSet<String>();
        List<OWLClass[]> pairs = new ArrayList<OWLClass[]>();
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (axiom.getSubClass().isAnonymous()) {
                continue;
            }
            OWLClassExpression filler = null;
            if (axiom.getSuperClass() instanceof OWLObjectSomeValuesFrom) {
                filler = ((OWLObjectSomeValuesFrom) axiom.getSuperClass()).getFiller();
            } else if (axiom.getSuperClass() instanceof OWLObjectAllValuesFrom) {
                filler = ((OWLObjectAllValuesFrom) axiom.getSuperClass()).getFiller();
            }
            if (filler == null || filler.isAnonymous() || filler.isOWLThing()) {
                continue;
            }
            OWLClass term = axiom.getSubClass().asOWLClass();
            OWLClass needed = filler.asOWLClass();
            if (!term.equals(needed) && haveChildren.contains(needed)
                    && seen.add(term.getIRI() + " " + needed.getIRI())) {
                pairs.add(new OWLClass[] {term, needed});
            }
        }
        Collections.sort(pairs, new Comparator<OWLClass[]>() {
            @Override
            public int compare(OWLClass[] a, OWLClass[] b) {
                int first = a[0].getIRI().compareTo(b[0].getIRI());
                return first != 0 ? first : a[1].getIRI().compareTo(b[1].getIRI());
            }
        });
        return pairs;
    }

    // ------------------------------------------------------------------ shared

    /** The ontology with a case's fault added, for checking it from a test. */
    OWLOntology mutated(BenchCase c) {
        Set<OWLAxiom> fault = faults.get(c.getId());
        return copyWith(fault == null ? Collections.<OWLAxiom>emptySet() : fault);
    }

    private OWLOntology copyWith(Set<OWLAxiom> extra) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology(ontology.getOntologyID());
            manager.addAxioms(copy, ontology.getAxioms());
            manager.addAxioms(copy, extra);
            return copy;
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new IllegalStateException("could not copy the ontology", cannotCopy);
        }
    }

    private static List<String> written(Set<OWLAxiom> axioms) {
        List<String> lines = new ArrayList<String>();
        for (OWLAxiom axiom : axioms) {
            lines.add(axiom.toString());
        }
        return lines;
    }

    /**
     * Labels of the named terms the text mentions, as whole words, sorted. Longest first: a
     * label found only inside a longer label's match does not count, so "funghi price" names the
     * individual and not also the class "funghi" (measured on the pizza example, where it did).
     */
    static List<String> termsIn(OWLOntology ontology, String text) {
        List<String> labels = new ArrayList<String>();
        for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                String label = DisplayLabels.forEntity(ontology, entity);
                if (label.length() >= 2 && !labels.contains(label)) {
                    labels.add(label);
                }
            }
        }
        Collections.sort(labels, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.length() != b.length() ? b.length() - a.length() : a.compareTo(b);
            }
        });
        boolean[] covered = new boolean[text.length()];
        Set<String> found = new TreeSet<String>();
        for (String label : labels) {
            java.util.regex.Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}_])"
                    + Pattern.quote(label) + "(?![\\p{L}\\p{N}_])").matcher(text);
            while (matcher.find()) {
                if (covered[matcher.start()]) {
                    continue;
                }
                found.add(label);
                for (int i = matcher.start(); i < matcher.end(); i++) {
                    covered[i] = true;
                }
            }
        }
        return new ArrayList<String>(found);
    }
}
