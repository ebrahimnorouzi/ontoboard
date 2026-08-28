package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Decides what IRI a new term gets, and makes the axioms that introduce it.
 *
 * <p>Two projects want two different things and both are correct, so the policy is discovered
 * rather than configured:
 *
 * <ul>
 *   <li>An <b>ODK or OBO project</b> - one with an {@code -idranges.owl} beside its ontology -
 *       mints opaque numeric identifiers like {@code MWO_0001000} from the editor's own allocated
 *       block, and carries the name the user typed as an {@code rdfs:label}. That is what makes
 *       two people editing one ontology safe, and it is the convention every OBO ontology follows.
 *   <li>Any <b>other project</b> keeps the existing behaviour: an IRI built from the name, under
 *       the ontology's own namespace. Forcing numeric identifiers on a project that never asked
 *       for them would be a worse imposition than the collision risk it avoids, since a lone
 *       editor has no one to collide with.
 * </ul>
 *
 * <p><b>The label is not optional in numeric mode.</b> {@code MWO_0001000} is unreadable, so
 * failing to attach {@code rdfs:label} would turn every new term into an opaque number in every
 * view - the canvas, the class hierarchy, and every downstream consumer. {@link #declare} always
 * emits it, which is why creation goes through this class rather than through
 * {@link EntityFactory#declare} directly.
 */
public final class TermMinter {

    /** How this project names new terms. */
    public enum Style {
        /** Numeric identifiers from an allocated range, with the typed name as rdfs:label. */
        NUMERIC,
        /** An IRI built from the typed name, under the ontology's namespace. */
        FROM_NAME
    }

    private final Style style;
    private final IdRanges ranges;
    private final String editor;
    private final File rangesFile;

    private TermMinter(Style style, IdRanges ranges, String editor, File rangesFile) {
        this.style = style;
        this.ranges = ranges;
        this.editor = editor;
        this.rangesFile = rangesFile;
    }

    /**
     * Works out the policy for the ontology in {@code ontologyFile}.
     *
     * <p>Looks for a sibling {@code *-idranges.owl}, which is where ODK puts it. An unreadable or
     * malformed one falls back to naming from the typed name rather than failing: being unable to
     * create a class at all because a metadata file is broken would be a disproportionate
     * response, and the fallback is the behaviour every non-ODK project already has.
     *
     * @param ontologyFile the ontology's own file, or null when it has never been saved
     * @param editor who is minting - matched against the ranges' allocations
     */
    public static TermMinter forOntologyFile(File ontologyFile, String editor) {
        File found = findRangesFile(ontologyFile);
        if (found == null) {
            return new TermMinter(Style.FROM_NAME, null, editor, null);
        }
        try {
            String text = new String(Files.readAllBytes(found.toPath()), StandardCharsets.UTF_8);
            return new TermMinter(Style.NUMERIC, IdRanges.parse(text), editor, found);
        } catch (IOException unreadable) {
            return new TermMinter(Style.FROM_NAME, null, editor, null);
        } catch (IdRanges.NoRangeException malformed) {
            return new TermMinter(Style.FROM_NAME, null, editor, null);
        }
    }

    /**
     * The {@code *-idranges.owl} beside {@code ontologyFile}, or null.
     *
     * <p>Package-private so the search itself is testable without an ontology. Only a sibling
     * counts: an idranges file elsewhere in the repository belongs to a different ontology, and
     * minting from another ontology's ranges would hand out identifiers in someone else's space.
     */
    static File findRangesFile(File ontologyFile) {
        if (ontologyFile == null) {
            return null;
        }
        File directory = ontologyFile.getParentFile();
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] candidates = directory.listFiles();
        if (candidates == null) {
            return null;
        }
        for (File candidate : candidates) {
            if (candidate.isFile()
                    && candidate.getName().toLowerCase().endsWith("-idranges.owl")) {
                return candidate;
            }
        }
        return null;
    }

    public Style getStyle() {
        return style;
    }

    public boolean isNumeric() {
        return style == Style.NUMERIC;
    }

    /** The ranges in force, or null when naming from the typed name. */
    public IdRanges getRanges() {
        return ranges;
    }

    /**
     * One line describing what will happen, for a dialog to show before the user commits.
     *
     * <p>Worth showing because the two styles produce very different IRIs, and a user who expects
     * {@code #Person} and gets {@code MWO_0001000} will think something has gone wrong.
     */
    public String describe() {
        if (!isNumeric()) {
            return "New terms are named from what you type, under this ontology's namespace.";
        }
        IdRanges.Range mine = ranges.rangeFor(editor);
        if (mine == null) {
            return "This project allocates numeric identifiers, but none are allocated to '"
                    + editor + "'. Add a range to " + rangesFile.getName() + " before creating "
                    + "terms.";
        }
        return "New terms get numeric identifiers from your range (" + mine.getLower() + " to "
                + mine.getUpper() + "), with what you type as the label.";
    }

    /**
     * The IRI for a new term whose label is {@code label}.
     *
     * @throws IllegalArgumentException when a from-name IRI cannot be built from the label
     * @throws IdRanges.NoRangeException when numeric minting is not possible for this editor,
     *     which is deliberately not caught here - creating a term with a colliding identifier is
     *     worse than not creating one
     */
    public IRI mintFor(OWLOntology ontology, String label) {
        if (!isNumeric()) {
            return EntityFactory.iriFor(ontology, label);
        }
        Set<String> taken = new HashSet<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            taken.add(entity.getIRI().toString());
        }
        return IRI.create(ranges.mint(editor, taken));
    }

    /**
     * Everything needed to introduce the term: its declaration and, in numeric mode, its label.
     *
     * <p>The label is added only in numeric mode. In from-name mode the IRI already carries the
     * name, and adding a redundant {@code rdfs:label} to every term would be an unrequested change
     * to how the ontology is written.
     */
    public List<OWLOntologyChange> declare(OWLOntology ontology, IRI iri, EntityFactory.Kind kind,
            String label) {
        List<OWLOntologyChange> changes =
                new ArrayList<OWLOntologyChange>(EntityFactory.declare(ontology, iri, kind));
        if (isNumeric() && label != null && !label.trim().isEmpty()) {
            OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), iri, factory.getOWLLiteral(label.trim(), "en"))));
        }
        return changes;
    }
}
