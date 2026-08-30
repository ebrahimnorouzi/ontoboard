package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.Obsoletion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Obsolete the selected term - retire it without breaking everything that used it.
 *
 * <p>Deleting a published term is the one edit a consumer cannot recover from: their import still
 * resolves, their axioms still parse, and the term they referenced is simply gone, with nothing to
 * distinguish it from one that never existed. Three of the five expert panels raised this
 * independently, and the release comparison now refuses to ship a release that drops a published
 * term - advice that would be empty if there were no way to retire one properly.
 *
 * <p>Protege will happily delete a class, and nothing in it suggests obsoleting instead. This is
 * the alternative, done the OBO way and in one step.
 */
public class ObsoleteAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_REPLACEMENT = "replacement";
    private static final String OPTION_KIND = "kind";
    private static final String OPTION_REASON = "reason";

    private static final String EXACT = "means the same thing - safe to swap in";
    private static final String SUGGESTION = "is only a suggestion - somebody must look";
    private static final String NONE = "there is no replacement";

    private volatile OWLEntity subject;
    private volatile String replacement = "";
    private volatile String kind = NONE;
    private volatile String reason = "";

    @Override
    protected String operationName() {
        return "Obsolete term";
    }

    /** A handful of annotation changes; nothing worth a worker thread. */
    @Override
    protected boolean runsInBackground() {
        return false;
    }

    @Override
    protected boolean configure() {
        subject = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        String why = subject == null ? "Select the term to retire first."
                : Obsoletion.whyNot(ontology, subject.getIRI());
        if (why != null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), why,
                    "Cannot obsolete", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        Set<IRI> affected = Obsoletion.termsReferencing(ontology, subject.getIRI());
        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_KIND, "Is there a replacement", Parameter.Kind.CHOICE)
                        .choices(NONE, EXACT, SUGGESTION)
                        .defaultValue(NONE)
                        .required()
                        .help("The difference matters to anybody migrating. An exact replacement "
                                + "is written as IAO:0100001 term replaced by, and a consumer can "
                                + "swap it in mechanically. A suggestion is written as "
                                + "oboInOwl:consider, which means a person has to look at each "
                                + "use and decide. Saying 'exact' when it is not is how a "
                                + "downstream ontology quietly changes meaning.")
                        .build(),
                Parameter.of(OPTION_REPLACEMENT, "Replacement term", Parameter.Kind.TEXT)
                        .defaultValue("")
                        .help("The IRI of the term to use instead. Ignored when there is no "
                                + "replacement.")
                        .build(),
                Parameter.of(OPTION_REASON, "Why", Parameter.Kind.MULTILINE)
                        .defaultValue("")
                        .help("Recorded as IAO:0000231 has obsolescence reason. This is what "
                                + "somebody reads when they find the term years later and want to "
                                + "know whether their use of it was wrong or merely renamed - so "
                                + "'duplicate' costs them an afternoon and 'duplicates "
                                + "CHEBI:60027, which has the fuller definition' does not.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(),
                "Obsolete " + getOWLModelManager().getRendering(subject),
                "The term stays in the ontology so every reference to it still resolves. It is "
                        + "marked deprecated, its label gains an 'obsolete' prefix, and it is "
                        + "taken out of the hierarchy."
                        + (affected.isEmpty() ? ""
                                : "\n\n" + affected.size() + " term"
                                        + (affected.size() == 1 ? "" : "s")
                                        + " in this ontology will lose a connection to it."),
                parameters);
        if (chosen == null) {
            return false;
        }
        kind = chosen.get(OPTION_KIND);
        replacement = chosen.get(OPTION_REPLACEMENT) == null ? ""
                : chosen.get(OPTION_REPLACEMENT).trim();
        reason = chosen.get(OPTION_REASON) == null ? "" : chosen.get(OPTION_REASON).trim();
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName());
        String label = getOWLModelManager().getRendering(subject);

        IRI replacementIri = null;
        if (!NONE.equals(kind)) {
            if (replacement.isEmpty()) {
                return result.failed("Give the IRI of the replacement term, or say there is "
                        + "no replacement.").build();
            }
            if (!replacement.toLowerCase().startsWith("http")) {
                return result.failed("A replacement has to be the term's IRI, so a consumer can "
                        + "follow it. '" + replacement + "' is not one.").build();
            }
            replacementIri = IRI.create(replacement);
        }

        Set<IRI> affected = Obsoletion.termsReferencing(ontology, subject.getIRI());
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>(
                Obsoletion.obsolete(ontology, subject.getIRI(), replacementIri,
                        EXACT.equals(kind), reason));
        if (changes.isEmpty()) {
            return result.failed("Nothing to do - that term is already obsolete.").build();
        }
        getOWLModelManager().applyChanges(changes);

        result.note("Marked owl:deprecated, label prefixed, taken out of the hierarchy.");
        if (replacementIri != null) {
            result.note((EXACT.equals(kind) ? "Replaced by: " : "Consider instead: ")
                    + replacementIri);
        } else {
            result.warn("No replacement was given, so anybody who used this term is left to work "
                    + "out what to do. Add IAO:0100001 later if one appears.");
        }
        if (!affected.isEmpty()) {
            result.columns("Lost a connection to it");
            for (IRI iri : affected) {
                result.row(getOWLModelManager().getRendering(
                        getOWLModelManager().getOWLDataFactory().getOWLClass(iri)));
            }
            result.warn(affected.size() + " term" + (affected.size() == 1 ? "" : "s")
                    + " lost a connection. Check they still sit where they should - a subclass of "
                    + "an obsoleted parent now has no parent at all.");
        }
        result.note("Edit > Undo reverses all of this in one step.");
        return result.summary("Obsoleted " + label + ". It is still in the ontology, so every "
                + "reference to it still resolves.").build();
    }
}
