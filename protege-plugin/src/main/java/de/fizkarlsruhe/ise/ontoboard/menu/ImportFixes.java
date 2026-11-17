package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkUpdate;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveImport;

/**
 * What Check imports puts right in one press in an ODK project: an import of the edit file
 * that the configuration does not declare, which ODK's update would take out.
 *
 * <p>One that can be read is declared as the update's own question declares it by its first
 * answer - a module of what the ontology uses of it, or the whole ontology where it uses none -
 * and the update then puts the module's import in its place: the project is as Update project
 * files leaves it. One that cannot be read cannot be declared, and stops the update (ruling
 * 102 of 1.153.0: the update reads the edit file with everything it imports, and the person
 * takes such an import out in Protege) - its button takes it out of what is open, as a person
 * does by hand.
 */
final class ImportFixes {

    static final String DECLARE = "Declare it";
    static final String TAKE_OUT = "Take it out";

    private ImportFixes() {
    }

    /**
     * The imports of the project's edit file that its configuration does not declare, as the
     * update finds them, each with what is known of it from what is open - nothing is fetched
     * again. Empty where ODK leaves the edit file's imports alone, and where what is open is
     * not the edit file.
     *
     * @param openFile the file of what is open
     * @param open     what is open, read with what it imports
     * @throws IOException when the project cannot be looked at as the update looks at it
     */
    static List<ImportGuard.Finding> undeclared(File root, File openFile, OWLOntology open)
            throws IOException {
        OdkUpdate.Plan plan;
        try {
            plan = OdkUpdate.plan(root);
        } catch (OdkUpdate.Refused refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        if (plan.undeclared().isEmpty() || plan.editFile() == null || !plan.editFile()
                .getAbsoluteFile().equals(openFile.getAbsoluteFile())) {
            return Collections.<ImportGuard.Finding>emptyList();
        }
        return ImportGuard.reading(root, open).findings(plan.undeclared());
    }

    /** What the button says: declare one that can be read, take out one that cannot. */
    static String labelFor(ImportGuard.Finding finding) {
        return finding.isReadable() ? DECLARE : TAKE_OUT;
    }

    /** What Check imports says of one, and what its button does. */
    static String warningFor(ImportGuard.Finding finding) {
        String iri = finding.item().iri();
        if (!finding.isReadable()) {
            return iri + " is imported by the edit file, the project's configuration does not "
                    + "declare it, and it cannot be read - " + finding.whyUnreadable() + ". "
                    + "ODK's update reads the edit file with everything it imports, so it stops "
                    + "until the import is taken out.";
        }
        return iri + " is imported by the edit file, and the project's configuration does not "
                + "declare it: ODK's update would take it out. Declared, it becomes the product "
                + finding.name() + ", its module " + (first(finding) == ImportGuard.Decision.MODULE
                        ? "the " + finding.termsUsed() + (finding.termsUsed() == 1 ? " term"
                                : " terms") + " of it the ontology uses"
                        : "the whole ontology") + ".";
    }

    /** The update's question's first answer for an import: what its button applies. */
    private static ImportGuard.Decision first(ImportGuard.Finding finding) {
        return OdkUpdateForm.choicesFor(finding).values().iterator().next();
    }

    /**
     * Declares the import as the update's question does by its first answer, and makes the
     * update: its module takes its place in the edit file. Any other import the update would
     * take out is asked about as always.
     *
     * @param asks whoever has the project open, asked about the others
     */
    static OperationResult declare(String operation, File root, String iri,
            OdkUpdateForm.Way way, ProcessRunner.Runner runner, OdkUpdateForm.Session asks) {
        return OdkUpdateForm.run(operation, root, true, way, runner, answering(asks, iri));
    }

    /** {@code asks}, with the question about this import answered by its first answer. */
    private static OdkUpdateForm.Session answering(final OdkUpdateForm.Session asks,
            final String iri) {
        return new OdkUpdateForm.Session() {
            @Override
            public boolean stopped() {
                return asks.stopped();
            }

            @Override
            public boolean isOpen(File editFile) {
                return asks.isOpen(editFile);
            }

            @Override
            public String readAgain(File editFile, Map<String, File> imports) {
                return asks.readAgain(editFile, imports);
            }

            @Override
            public void writtenAsItWas(File editFile) {
                asks.writtenAsItWas(editFile);
            }

            @Override
            public List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings) {
                ImportGuard.Decision mine = null;
                List<ImportGuard.Finding> others = new ArrayList<ImportGuard.Finding>();
                for (ImportGuard.Finding finding : findings) {
                    if (finding.item().iri().equals(iri)) {
                        mine = first(finding);
                    } else {
                        others.add(finding);
                    }
                }
                if (mine == ImportGuard.Decision.REMOVE) {
                    // It cannot be read now: it is not to be declared, and nothing is said.
                    return null;
                }
                List<ImportGuard.Decision> theirs = others.isEmpty()
                        ? Collections.<ImportGuard.Decision>emptyList() : asks.decide(others);
                if (theirs == null || theirs.size() != others.size()) {
                    return null;
                }
                List<ImportGuard.Decision> decided = new ArrayList<ImportGuard.Decision>();
                Iterator<ImportGuard.Decision> next = theirs.iterator();
                for (ImportGuard.Finding finding : findings) {
                    decided.add(finding.item().iri().equals(iri) ? mine : next.next());
                }
                return decided;
            }
        };
    }

    /** Takes the import out of what is open, as a person does by hand in Protege. */
    static List<OWLOntologyChange> takingOut(OWLOntology open, String iri) {
        return Collections.<OWLOntologyChange>singletonList(new RemoveImport(open,
                open.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLImportsDeclaration(IRI.create(iri))));
    }
}
