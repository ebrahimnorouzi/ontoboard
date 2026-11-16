package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * F7: the name of a new property, asked in Create relation, is checked as a class's is on the
 * board. A blank one was dropped without a word - OK did nothing at all - and one that another
 * term already has as its label went through without the question the board asks for a class.
 */
class RelationDialogNameTest {

    private static final String EGG = "https://example.org/eggboil/EGGBOIL_";

    private OWLOntology edit;
    private OWLClass boiling;
    private OWLClass egg;

    @BeforeEach
    void anEggProjectWithNoPropertyYet() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        edit = manager.createOntology(IRI.create("https://example.org/eggboil/eggboil.owl"));
        boiling = df.getOWLClass(IRI.create(EGG + "0001000"));
        egg = df.getOWLClass(IRI.create(EGG + "0001001"));
        for (OWLClass named : Arrays.asList(boiling, egg)) {
            manager.addAxiom(edit, df.getOWLDeclarationAxiom(named));
        }
        manager.addAxiom(edit, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                boiling.getIRI(), df.getOWLLiteral("egg boiling")));
        manager.addAxiom(edit, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                egg.getIRI(), df.getOWLLiteral("egg")));
    }

    /** A person at the dialog: the names typed at each showing of the form, and the answers. */
    private static final class Person implements RelationDialog.Dialogs {
        private final RelationDialog.Form form;
        private final Deque<String> names;
        private final Deque<Boolean> answers;
        final List<String> told = new ArrayList<String>();
        final List<String> asked = new ArrayList<String>();
        int forms;

        Person(RelationDialog.Form form, List<String> names, List<Boolean> answers) {
            this.form = form;
            this.names = new ArrayDeque<String>(names);
            this.answers = new ArrayDeque<Boolean>(answers);
        }

        @Override
        public boolean form(JPanel panel) {
            forms++;
            if (names.isEmpty()) {
                return false;
            }
            form.newName.setText(names.removeFirst());
            return true;
        }

        @Override
        public void tell(String title, String message) {
            told.add(message);
        }

        @Override
        public boolean ask(String title, String question) {
            asked.add(question);
            return answers.removeFirst();
        }
    }

    private RelationDialog.Choice asked(Person person) {
        return RelationDialog.ask(person.form, edit, person);
    }

    @Test
    void aBlankNameIsRefusedWithASentenceAndTheFormComesBack() {
        RelationDialog.Form form = RelationDialog.build(edit, boiling, egg);
        Person person = new Person(form, Arrays.asList("   ", "boils"),
                Collections.<Boolean>emptyList());

        RelationDialog.Choice choice = asked(person);

        assertEquals("boils", choice.getNewPropertyName());
        assertEquals(Collections.singletonList("Give the new property a name."), person.told);
        assertEquals(2, person.forms, "the form again, with what was chosen in it");
    }

    @Test
    void aNameAnotherTermHasIsAskedAboutAndNoGoesBackToTheForm() {
        RelationDialog.Form form = RelationDialog.build(edit, boiling, egg);
        Person person = new Person(form, Arrays.asList("Egg", "boils"), Arrays.asList(false));

        RelationDialog.Choice choice = asked(person);

        assertEquals("boils", choice.getNewPropertyName());
        assertEquals(1, person.asked.size(), person.asked.toString());
        assertEquals("\"Egg\" is already the name of EGGBOIL_0001001.\nMake another property "
                + "with the same name?", person.asked.get(0));
    }

    @Test
    void yesMakesItAllTheSame() {
        RelationDialog.Form form = RelationDialog.build(edit, boiling, egg);
        Person person = new Person(form, Arrays.asList(" egg "), Arrays.asList(true));

        assertEquals("egg", asked(person).getNewPropertyName());
    }

    @Test
    void cancelIsNothingAtAll() {
        RelationDialog.Form form = RelationDialog.build(edit, boiling, egg);
        Person person = new Person(form, Collections.<String>emptyList(),
                Collections.<Boolean>emptyList());

        assertNull(asked(person));
        assertTrue(person.told.isEmpty() && person.asked.isEmpty());
    }
}
