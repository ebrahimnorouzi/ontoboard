package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.reason.ProfileCheck;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT &gt; Profile - which OWL 2 profile this ontology is in, and which axioms take it out.
 *
 * <p>ROBOT's measure already answers the first half as a yes or no, and this plugin already shows
 * it. The half that was missing is attribution, and it is the half a person can act on: "this
 * ontology is not EL" is a mood; "these four axioms, on these terms, are why" is an afternoon's
 * work with an end.
 *
 * <p>Worth having here rather than leaving to the command line because the plugin contradicts
 * itself without it. Its release action classifies with ELK, the ODK build it scaffolds classifies
 * with ELK, and ELK ignores axioms outside EL silently - while the canvas offers relation readings
 * that leave EL. Somebody who has been drawing arrows for a month deserves to be able to ask which
 * of them their reasoner has been ignoring.
 */
public class ProfileAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_PROFILE = "profile";

    /** Enough to work through; beyond it the answer is "this ontology is not close to EL". */
    private static final int MAX_LISTED = 500;

    private volatile ProfileCheck.Target target = ProfileCheck.Target.EL;

    @Override
    protected String operationName() {
        return "Profile";
    }

    @Override
    protected boolean configure() {
        List<String> labels = new ArrayList<String>();
        StringBuilder help = new StringBuilder(
                "Which profile to check against. A profile is a subset of OWL 2 that some class "
                        + "of reasoner can handle completely and quickly.\n");
        for (ProfileCheck.Target choice : ProfileCheck.Target.values()) {
            labels.add(choice.getLabel());
            help.append('\n').append(choice.getLabel()).append(": ").append(choice.getHelp())
                    .append('\n');
        }

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Profile",
                "Reports which axioms take this ontology outside the profile you pick, and which "
                        + "terms they are about.",
                Arrays.asList(Parameter.of(OPTION_PROFILE, "Profile", Parameter.Kind.CHOICE)
                        .choices(labels.toArray(new String[0]))
                        .defaultValue(ProfileCheck.Target.EL.getLabel())
                        .required()
                        .help(help.toString())
                        .build()));
        if (chosen == null) {
            return false;
        }
        for (ProfileCheck.Target choice : ProfileCheck.Target.values()) {
            if (choice.getLabel().equals(chosen.get(OPTION_PROFILE))) {
                target = choice;
            }
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Term", "Why");
        result.note("Checked against: OWL 2 " + target.getLabel());

        ProfileCheck.Target tightest = ProfileCheck.tightestProfile(ontology);
        result.note("Tightest profile this ontology is in: "
                + (tightest == null ? "none - it is outside OWL 2 DL"
                        : "OWL 2 " + tightest.getLabel()));

        List<ProfileCheck.Violation> violations = ProfileCheck.violations(ontology, target);
        if (violations.isEmpty()) {
            return result.summary("This ontology is in OWL 2 " + target.getLabel()
                    + ". Every axiom is one a " + target.getLabel()
                    + " reasoner can act on.").build();
        }

        int listed = 0;
        for (ProfileCheck.Violation violation : violations) {
            if (listed++ >= MAX_LISTED) {
                break;
            }
            result.row(termsOf(violation), violation.getMessage());
        }
        if (violations.size() > MAX_LISTED) {
            result.note("Listing the first " + MAX_LISTED + " of " + violations.size() + ".");
        }

        if (target == ProfileCheck.Target.EL) {
            result.warn("ELK ignores these axioms - it does not reject them or warn about them, "
                    + "it simply does not reason over them. OntoBoard's release action and the "
                    + "ODK build both use ELK by default, so whatever these axioms say is absent "
                    + "from every classification and every release.");
        }
        if (target == ProfileCheck.Target.DL) {
            result.warn("Outside OWL 2 DL usually means something is malformed rather than merely "
                    + "expressive - an undeclared entity, or a property used as two kinds at "
                    + "once. A complete reasoner may refuse the ontology altogether.");
        }
        return result.summary(violations.size() + " axiom"
                + (violations.size() == 1 ? "" : "s") + " outside OWL 2 " + target.getLabel()
                + ".").build();
    }

    /** The terms an offending axiom is about, as the rest of Protege renders them. */
    private String termsOf(ProfileCheck.Violation violation) {
        StringBuilder text = new StringBuilder();
        for (IRI iri : violation.getTerms()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(getOWLModelManager().getRendering(
                    getOWLModelManager().getOWLDataFactory().getOWLClass(iri)));
            if (text.length() > 120) {
                text.append(" ...");
                break;
            }
        }
        return text.length() == 0 ? "(the ontology itself)" : text.toString();
    }
}
