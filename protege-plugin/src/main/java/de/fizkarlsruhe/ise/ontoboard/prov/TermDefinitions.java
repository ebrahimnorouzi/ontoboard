package de.fizkarlsruhe.ise.ontoboard.prov;

import java.util.ArrayList;
import java.util.List;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * A term's definition: {@code IAO:0000115}, which is what a quality report asks every term for.
 *
 * <p>Writing one meant finding the annotation editor in Protege, and in it a property that on a
 * new project is listed as {@code IAO_0000115}, if it is listed at all. The first thing a quality
 * report says about a new class is that it has no definition, so the answer belongs where the class
 * is: on the board.
 *
 * <p><b>One definition is edited; the others are left alone.</b> A term can have several - one per
 * language, each perhaps with a source on it ({@code hasDbXref}) - and editing used to remove them
 * all and write back one, tagged English whatever it had been, without a word. Found by a code
 * review, with a class defined in English and in German: the German text was shown, and after OK
 * both definitions and the English one's source were gone.
 */
public final class TermDefinitions {

    /** The property a definition is written with. */
    public static final IRI DEFINITION = IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");

    /** The language a definition is preferred in when there are several. */
    private static final String PREFERRED_LANGUAGE = "en";

    private TermDefinitions() {
    }

    /**
     * The term's definition, wherever it is written in the imports closure; empty for none.
     *
     * <p>When there are several, the one {@link #set} edits: written in this ontology rather than
     * in an import, then English, then without a language, then any; the shortest of equals, so
     * it is the same one every time.
     */
    public static String of(OWLOntology ontology, IRI term) {
        OWLAnnotationAssertionAxiom shown = shownIn(ontology, term);
        return shown == null ? "" : ((OWLLiteral) shown.getValue()).getLiteral();
    }

    /**
     * The changes that make {@code text} the term's definition in this ontology: the definition
     * {@link #of} shows is replaced, keeping its language and its annotations, and any others stay
     * as they are; blank removes that one. The property is declared with its label the first time,
     * so that it reads as "definition" everywhere afterwards and passes the project's label check.
     */
    public static List<OWLOntologyChange> set(OWLOntology ontology, IRI term, String text) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAnnotationProperty property = factory.getOWLAnnotationProperty(DEFINITION);
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        OWLAnnotationAssertionAxiom shown = shownIn(ontology, term);
        // Only one written here can be replaced; one shown from an import is its author's.
        OWLAnnotationAssertionAxiom replaced = shown != null && ontology.containsAxiom(shown)
                ? shown : null;
        if (replaced != null) {
            changes.add(new RemoveAxiom(ontology, replaced));
        }
        String wanted = text == null ? "" : text.trim();
        if (wanted.isEmpty()) {
            return changes;
        }
        OWLLiteral was = replaced == null ? null : (OWLLiteral) replaced.getValue();
        OWLLiteral literal = was == null ? factory.getOWLLiteral(wanted, PREFERRED_LANGUAGE)
                : was.hasLang() ? factory.getOWLLiteral(wanted, was.getLang())
                : factory.getOWLLiteral(wanted, was.getDatatype());
        changes.add(new AddAxiom(ontology, replaced == null
                ? factory.getOWLAnnotationAssertionAxiom(property, term, literal)
                : factory.getOWLAnnotationAssertionAxiom(property, term, literal,
                        replaced.getAnnotations())));
        boolean known = false;
        for (OWLOntology part : ontology.getImportsClosure()) {
            known |= !part.getAnnotationAssertionAxioms(DEFINITION).isEmpty();
        }
        if (!known) {
            changes.add(new AddAxiom(ontology, factory.getOWLDeclarationAxiom(property)));
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), DEFINITION, factory.getOWLLiteral("definition", "en"))));
        }
        return changes;
    }

    /** The definition shown and edited - see {@link #of} - or null when there is none. */
    private static OWLAnnotationAssertionAxiom shownIn(OWLOntology ontology, IRI term) {
        OWLAnnotationAssertionAxiom best = shownAmong(ontology, term);
        if (best != null) {
            return best;
        }
        for (OWLOntology part : ontology.getImportsClosure()) {
            if (part != ontology) {
                OWLAnnotationAssertionAxiom found = shownAmong(part, term);
                if (found != null && (best == null || better(found, best))) {
                    best = found;
                }
            }
        }
        return best;
    }

    private static OWLAnnotationAssertionAxiom shownAmong(OWLOntology part, IRI term) {
        OWLAnnotationAssertionAxiom best = null;
        for (OWLAnnotationAssertionAxiom axiom : part.getAnnotationAssertionAxioms(term)) {
            if (DEFINITION.equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral
                    && (best == null || better(axiom, best))) {
                best = axiom;
            }
        }
        return best;
    }

    /** English before no language before any other, then the shorter text, then the lesser. */
    private static boolean better(OWLAnnotationAssertionAxiom one,
            OWLAnnotationAssertionAxiom other) {
        OWLLiteral a = (OWLLiteral) one.getValue();
        OWLLiteral b = (OWLLiteral) other.getValue();
        int rank = Integer.compare(rank(a), rank(b));
        if (rank != 0) {
            return rank < 0;
        }
        int length = Integer.compare(a.getLiteral().length(), b.getLiteral().length());
        if (length != 0) {
            return length < 0;
        }
        return one.compareTo(other) < 0;
    }

    private static int rank(OWLLiteral literal) {
        return literal.hasLang(PREFERRED_LANGUAGE) ? 0 : !literal.hasLang() ? 1 : 2;
    }
}
