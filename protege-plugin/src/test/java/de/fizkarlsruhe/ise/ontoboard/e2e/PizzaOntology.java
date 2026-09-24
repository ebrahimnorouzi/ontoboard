package de.fizkarlsruhe.ise.ontoboard.e2e;

import com.google.common.base.Optional;
import java.util.ArrayList;
import java.util.List;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The pizza ontology, in three releases, built in code so the whole end-to-end run is reproducible.
 *
 * <p>Pizza is the standard teaching ontology for a reason: it is the smallest thing that needs
 * every construct worth testing. Classes with a real hierarchy, object and data properties,
 * individuals, disjointness, and - the part that matters - <em>defined</em> classes, so that a
 * reasoner has something to conclude that nobody stated. Without a defined class there is nothing
 * to infer, and an "inferences" feature tested against an ontology with nothing to infer proves
 * only that it does not crash.
 *
 * <p>Three releases rather than one, because the request is about seeing an ontology evolve:
 *
 * <ul>
 *   <li><b>v1</b> - the hierarchy, the properties, four individuals.
 *   <li><b>v2</b> - adds two defined classes, a data property and more toppings; relabels one term
 *       and rewrites one definition, so a release diff has a relabel and a redefinition to find.
 *   <li><b>v3</b> - obsoletes a term the OBO way and moves another in the hierarchy, so the diff
 *       has an obsoletion and a move, and the release check has a published term to worry about.
 * </ul>
 *
 * <p>Two deliberate faults are available separately, because a test that only ever sees a healthy
 * ontology never exercises the code that reports an unhealthy one: {@link #withUnsatisfiableClass}
 * and {@link #madeInconsistent}.
 */
public final class PizzaOntology {

    public static final String IRI_BASE = "http://example.org/pizza";
    public static final String NS = IRI_BASE + "#";

    /** {@code IAO:0000115 definition}, so ReleaseDiff sees definitions where it looks for them. */
    private static final IRI DEFINITION =
            IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");

    private PizzaOntology() {
    }

    public static IRI term(String name) {
        return IRI.create(NS + name);
    }

    // ------------------------------------------------------------------ v1

    /** The first release: a hierarchy, properties and individuals, and nothing defined yet. */
    public static OWLOntology v1() throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology pizza = manager.createOntology(new OWLOntologyID(
                Optional.of(IRI.create(IRI_BASE)),
                Optional.of(IRI.create(IRI_BASE + "/2026-01-15/pizza.owl"))));
        OWLDataFactory f = manager.getOWLDataFactory();

        // --- the ontology's own metadata ------------------------------------------------
        // Not decoration: ROBOT report treats a missing title, description or licence as an
        // ERROR, so without these the project fails its own `make report` and its own CI. Found
        // by running the real ODK build rather than by reading about it.
        // Declared before use. An undeclared annotation property is an OWL 2 DL violation in its
        // own right, so metadata added to satisfy ROBOT report would otherwise push the whole
        // ontology outside DL - one check satisfied by breaking another.
        for (String property : new String[] {"title", "description", "license"}) {
            manager.addAxiom(pizza, f.getOWLDeclarationAxiom(f.getOWLAnnotationProperty(
                    IRI.create("http://purl.org/dc/terms/" + property))));
        }
        manager.addAxiom(pizza, f.getOWLDeclarationAxiom(
                f.getOWLAnnotationProperty(DEFINITION)));

        ontologyAnnotation(manager, pizza, f, "http://purl.org/dc/terms/title",
                f.getOWLLiteral("Pizza Ontology"));
        ontologyAnnotation(manager, pizza, f, "http://purl.org/dc/terms/description",
                f.getOWLLiteral("A pizza ontology built end to end with OntoBoard, to exercise "
                        + "the plugin against ODK and ROBOT."));
        ontologyAnnotation(manager, pizza, f, "http://purl.org/dc/terms/license",
                IRI.create("https://creativecommons.org/publicdomain/zero/1.0/"));

        // --- the vocabulary -------------------------------------------------------------
        for (String name : new String[] {"Pizza", "PizzaBase", "PizzaTopping", "CheeseTopping",
            "VegetableTopping", "MeatTopping", "TomatoTopping", "MozzarellaTopping",
            "PepperoniTopping", "ThinAndCrispyBase", "Country"}) {
            declareClass(manager, pizza, f, name);
        }
        declareObjectProperty(manager, pizza, f, "hasTopping",
                "The relation from a pizza to something on it.");
        declareObjectProperty(manager, pizza, f, "hasBase",
                "The relation from a pizza to the base it is built on.");
        declareObjectProperty(manager, pizza, f, "hasCountryOfOrigin",
                "Where the recipe is from.");

        // --- the hierarchy --------------------------------------------------------------
        subClassOf(manager, pizza, f, "CheeseTopping", "PizzaTopping");
        subClassOf(manager, pizza, f, "VegetableTopping", "PizzaTopping");
        subClassOf(manager, pizza, f, "MeatTopping", "PizzaTopping");
        subClassOf(manager, pizza, f, "TomatoTopping", "VegetableTopping");
        subClassOf(manager, pizza, f, "MozzarellaTopping", "CheeseTopping");
        subClassOf(manager, pizza, f, "PepperoniTopping", "MeatTopping");
        subClassOf(manager, pizza, f, "ThinAndCrispyBase", "PizzaBase");

        // A pizza has a base and at least one topping. Existential, so it stays inside OWL 2 EL
        // and ELK can act on it - which is what the release build actually runs.
        manager.addAxiom(pizza, f.getOWLSubClassOfAxiom(cls(f, "Pizza"),
                f.getOWLObjectSomeValuesFrom(objectProperty(f, "hasBase"), cls(f, "PizzaBase"))));
        manager.addAxiom(pizza, f.getOWLSubClassOfAxiom(cls(f, "Pizza"),
                f.getOWLObjectSomeValuesFrom(objectProperty(f, "hasTopping"),
                        cls(f, "PizzaTopping"))));

        // --- what cannot be true at once ------------------------------------------------
        manager.addAxiom(pizza, f.getOWLDisjointClassesAxiom(
                cls(f, "CheeseTopping"), cls(f, "VegetableTopping"), cls(f, "MeatTopping")));
        manager.addAxiom(pizza, f.getOWLDisjointClassesAxiom(
                cls(f, "Pizza"), cls(f, "PizzaTopping"), cls(f, "PizzaBase")));

        // --- domains and ranges, which the canvas draws as edges -------------------------
        manager.addAxiom(pizza, f.getOWLObjectPropertyDomainAxiom(
                objectProperty(f, "hasTopping"), cls(f, "Pizza")));
        manager.addAxiom(pizza, f.getOWLObjectPropertyRangeAxiom(
                objectProperty(f, "hasTopping"), cls(f, "PizzaTopping")));
        manager.addAxiom(pizza, f.getOWLObjectPropertyDomainAxiom(
                objectProperty(f, "hasBase"), cls(f, "Pizza")));
        manager.addAxiom(pizza, f.getOWLObjectPropertyRangeAxiom(
                objectProperty(f, "hasBase"), cls(f, "PizzaBase")));

        // --- individuals ----------------------------------------------------------------
        individual(manager, pizza, f, "margherita", "Pizza", "Margherita");
        individual(manager, pizza, f, "mozzarella", "MozzarellaTopping", "the mozzarella on it");
        individual(manager, pizza, f, "tomato", "TomatoTopping", "the tomato on it");
        individual(manager, pizza, f, "thinBase", "ThinAndCrispyBase", "a thin and crispy base");
        individual(manager, pizza, f, "italy", "Country", "Italy");

        relate(manager, pizza, f, "margherita", "hasTopping", "mozzarella");
        relate(manager, pizza, f, "margherita", "hasTopping", "tomato");
        relate(manager, pizza, f, "margherita", "hasBase", "thinBase");
        relate(manager, pizza, f, "margherita", "hasCountryOfOrigin", "italy");

        return pizza;
    }

    // ------------------------------------------------------------------ v2

    /**
     * The second release: defined classes, a data property, more toppings - and two edits that
     * exist so the release diff has something other than additions to report.
     */
    public static OWLOntology v2() throws OWLOntologyCreationException {
        OWLOntology pizza = v1();
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        version(manager, pizza, "2026-03-02");

        declareClass(manager, pizza, f, "CheesyPizza");
        declareClass(manager, pizza, f, "VegetarianPizza");
        declareClass(manager, pizza, f, "MeatyPizza");
        declareClass(manager, pizza, f, "OliveTopping");
        subClassOf(manager, pizza, f, "OliveTopping", "VegetableTopping");

        // The whole reason a reasoner has anything to say. Existential - inside OWL 2 EL, so ELK
        // concludes it; the universal one below needs HermiT, which is exactly the contrast the
        // profile warning exists to make visible.
        manager.addAxiom(pizza, f.getOWLEquivalentClassesAxiom(cls(f, "CheesyPizza"),
                f.getOWLObjectIntersectionOf(cls(f, "Pizza"),
                        f.getOWLObjectSomeValuesFrom(objectProperty(f, "hasTopping"),
                                cls(f, "CheeseTopping")))));
        manager.addAxiom(pizza, f.getOWLEquivalentClassesAxiom(cls(f, "MeatyPizza"),
                f.getOWLObjectIntersectionOf(cls(f, "Pizza"),
                        f.getOWLObjectSomeValuesFrom(objectProperty(f, "hasTopping"),
                                cls(f, "MeatTopping")))));
        // Universal restriction: outside OWL 2 EL. Deliberate - the run reports that ELK ignores
        // it and HermiT does not, which is the contradiction ProfileCheck was written for.
        manager.addAxiom(pizza, f.getOWLEquivalentClassesAxiom(cls(f, "VegetarianPizza"),
                f.getOWLObjectIntersectionOf(cls(f, "Pizza"),
                        f.getOWLObjectAllValuesFrom(objectProperty(f, "hasTopping"),
                                f.getOWLObjectUnionOf(cls(f, "CheeseTopping"),
                                        cls(f, "VegetableTopping"))))));

        OWLDataProperty calories = f.getOWLDataProperty(term("hasCalories"));
        manager.addAxiom(pizza, f.getOWLDeclarationAxiom(calories));
        label(manager, pizza, f, term("hasCalories"), "has calories");
        manager.addAxiom(pizza, f.getOWLDataPropertyDomainAxiom(calories, cls(f, "Pizza")));
        manager.addAxiom(pizza, f.getOWLDataPropertyRangeAxiom(calories,
                f.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#integer"))));
        manager.addAxiom(pizza, f.getOWLDataPropertyAssertionAxiom(calories,
                individual(f, "margherita"), f.getOWLLiteral(1150)));

        // A second pizza, so there is something the vegetarian conclusion must NOT catch.
        individual(manager, pizza, f, "americanHot", "Pizza", "American Hot");
        individual(manager, pizza, f, "pepperoni", "PepperoniTopping", "the pepperoni on it");
        relate(manager, pizza, f, "americanHot", "hasTopping", "pepperoni");
        relate(manager, pizza, f, "americanHot", "hasBase", "thinBase");

        // Closure. Without this nobody can conclude margherita is vegetarian, and not because
        // the tool is wrong - because the ontology does not say it. OWL is open-world: knowing
        // margherita has mozzarella and tomato says nothing about whether it ALSO has something
        // else. The universal restriction in VegetarianPizza only bites once the toppings are
        // closed, which is the single most misunderstood step in the pizza tutorial and the
        // reason this fixture states it explicitly rather than hoping.
        manager.addAxiom(pizza, f.getOWLClassAssertionAxiom(
                f.getOWLObjectAllValuesFrom(objectProperty(f, "hasTopping"),
                        f.getOWLObjectUnionOf(cls(f, "MozzarellaTopping"),
                                cls(f, "TomatoTopping"))),
                individual(f, "margherita")));
        manager.addAxiom(pizza, f.getOWLClassAssertionAxiom(
                f.getOWLObjectAllValuesFrom(objectProperty(f, "hasTopping"),
                        cls(f, "PepperoniTopping")),
                individual(f, "americanHot")));

        // --- the two edits a diff should notice -----------------------------------------
        // Relabelled from what declareClass generated for it in v1.
        relabel(manager, pizza, f, term("TomatoTopping"), "tomato topping", "tomato");
        define(manager, pizza, f, term("Pizza"),
                "A dish of Italian origin: a base, a topping, and an oven.");

        return pizza;
    }

    // ------------------------------------------------------------------ v3

    /**
     * The third release: one term retired the OBO way, one moved in the hierarchy.
     *
     * <p>Obsoleting rather than deleting is the point. A deleted published term is the one edit a
     * consumer cannot recover from, and the release check exists to refuse it.
     */
    public static OWLOntology v3() throws OWLOntologyCreationException {
        OWLOntology pizza = v2();
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        version(manager, pizza, "2026-06-10");

        declareClass(manager, pizza, f, "CaperTopping");
        subClassOf(manager, pizza, f, "CaperTopping", "VegetableTopping");
        define(manager, pizza, f, term("CaperTopping"), "The pickled flower bud of Capparis.");

        // Retired, not deleted - with a replacement, so anybody who used it knows where to go.
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.odk.Obsoletion.obsolete(
                pizza, term("OliveTopping"), term("CaperTopping"), false,
                "Too specific for this ontology; the example it existed for now uses capers."));

        // Moved: pepperoni is cured meat, and the hierarchy should say so.
        declareClass(manager, pizza, f, "CuredMeatTopping");
        subClassOf(manager, pizza, f, "CuredMeatTopping", "MeatTopping");
        manager.removeAxiom(pizza, f.getOWLSubClassOfAxiom(
                cls(f, "PepperoniTopping"), cls(f, "MeatTopping")));
        subClassOf(manager, pizza, f, "PepperoniTopping", "CuredMeatTopping");

        return pizza;
    }

    // ------------------------------------------------------------------ v4

    /**
     * The fourth release: a curation pass.
     *
     * <p>Everything up to v3 was modelling. This is the editorial surface a curator actually
     * spends their time in, and none of it was exercised end to end before: who added a term and
     * when, notes for whoever edits it next, a link to where the argument happened, and terms
     * borrowed from somebody else's ontology.
     *
     * @param agent the ORCID to record; the caller owns identity as well as the clock
     * @param isoDate {@code YYYY-MM-DD}
     */
    public static OWLOntology v4(String agent, String isoDate) throws OWLOntologyCreationException {
        OWLOntology pizza = v3();
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        version(manager, pizza, "2026-09-23");

        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.Provenance
                .declareProperties(pizza));
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes
                .declareProperties(pizza));

        // Who made the terms this release added, recorded the way released OBO ontologies do it.
        for (String name : new String[] {"CaperTopping", "CuredMeatTopping"}) {
            manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.Provenance
                    .stampNew(pizza, term(name), agent, isoDate));
        }

        // A note for whoever edits this next, attributed, and a link to where the argument is.
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.addNote(
                pizza, term("VegetarianPizza"),
                de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Kind.EDITOR,
                "Defined with a universal restriction, so ELK cannot classify it and the ODK "
                        + "build will not. Decide whether that matters before relying on it.",
                agent, isoDate));
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.addNote(
                pizza, term("CaperTopping"),
                de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.Kind.CURATOR,
                "Added to replace the obsoleted olive topping. Definition taken from the "
                        + "Capparis entry rather than written fresh.",
                agent, isoDate));
        manager.applyChanges(de.fizkarlsruhe.ise.ontoboard.prov.EditorNotes.addTrackerItem(
                pizza, term("VegetarianPizza"),
                "https://github.com/ebrahimnorouzi/pizza-ontoboard-odk-test/issues/1"));

        return pizza;
    }

    /**
     * A small upstream to borrow terms from, so the import path can be exercised offline.
     *
     * <p>A real one would be ChEBI. Downloading ChEBI to prove that an extraction works is not a
     * test, it is a network dependency pretending to be one - so this is a plausible stand-in with
     * a version IRI, which is what the import provenance records.
     */
    public static OWLOntology upstreamFoodOntology() throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology food = manager.createOntology(new OWLOntologyID(
                Optional.of(IRI.create("http://example.org/food")),
                Optional.of(IRI.create("http://example.org/food/2026-05-01/food.owl"))));
        OWLDataFactory f = manager.getOWLDataFactory();
        String ns = "http://example.org/food#";

        for (String[] pair : new String[][] {
            {"FoodMaterial", null}, {"DairyProduct", "FoodMaterial"},
            {"Cheese", "DairyProduct"}, {"Vegetable", "FoodMaterial"},
            {"Fruit", "FoodMaterial"}, {"Tomato", "Fruit"}}) {
            OWLClass cls = f.getOWLClass(IRI.create(ns + pair[0]));
            manager.addAxiom(food, f.getOWLDeclarationAxiom(cls));
            manager.addAxiom(food, f.getOWLAnnotationAssertionAxiom(f.getRDFSLabel(),
                    cls.getIRI(), f.getOWLLiteral(readable(pair[0]))));
            if (pair[1] != null) {
                manager.addAxiom(food, f.getOWLSubClassOfAxiom(cls,
                        f.getOWLClass(IRI.create(ns + pair[1]))));
            }
        }
        return food;
    }

    /** The terms the pizza ontology borrows from the food ontology. */
    public static List<IRI> borrowedTerms() {
        List<IRI> terms = new ArrayList<IRI>();
        terms.add(IRI.create("http://example.org/food#Cheese"));
        terms.add(IRI.create("http://example.org/food#Tomato"));
        return terms;
    }

    // ------------------------------------------------------------------ the deliberate faults

    /**
     * v2 with a class that cannot have instances, for the unsatisfiable-class path.
     *
     * <p>{@code ImpossiblePizza} is both a Pizza and a PizzaTopping, and those are disjoint. A
     * reasoner marks it unsatisfiable; the canvas draws it in red.
     */
    public static OWLOntology withUnsatisfiableClass() throws OWLOntologyCreationException {
        OWLOntology pizza = v2();
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        declareClass(manager, pizza, f, "ImpossiblePizza");
        manager.addAxiom(pizza, f.getOWLEquivalentClassesAxiom(cls(f, "ImpossiblePizza"),
                f.getOWLObjectIntersectionOf(cls(f, "Pizza"), cls(f, "PizzaTopping"))));
        return pizza;
    }

    /**
     * v2 with an individual in two disjoint classes, so the ontology itself is inconsistent.
     *
     * <p>Different from an unsatisfiable class and worth testing separately: an inconsistent
     * ontology entails everything, so a reasoner cannot answer any question about it at all.
     */
    public static OWLOntology madeInconsistent() throws OWLOntologyCreationException {
        OWLOntology pizza = v2();
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        manager.addAxiom(pizza, f.getOWLClassAssertionAxiom(
                cls(f, "CheeseTopping"), individual(f, "pepperoni")));
        return pizza;
    }

    // ------------------------------------------------------------------ small helpers

    /** Every entity the canvas can draw, for the "Add all" path. */
    public static List<String> everyTermIri(OWLOntology pizza) {
        List<String> iris = new ArrayList<String>();
        for (org.semanticweb.owlapi.model.OWLEntity entity : pizza.getSignature()) {
            if (entity.isOWLClass() || entity.isOWLNamedIndividual()
                    || entity.isOWLObjectProperty() || entity.isOWLDataProperty()) {
                iris.add(entity.getIRI().toString());
            }
        }
        return iris;
    }

    private static OWLClass cls(OWLDataFactory f, String name) {
        return f.getOWLClass(term(name));
    }

    private static OWLObjectProperty objectProperty(OWLDataFactory f, String name) {
        return f.getOWLObjectProperty(term(name));
    }

    private static OWLNamedIndividual individual(OWLDataFactory f, String name) {
        return f.getOWLNamedIndividual(term(name));
    }

    private static void declareClass(OWLOntologyManager m, OWLOntology o, OWLDataFactory f,
            String name) {
        m.addAxiom(o, f.getOWLDeclarationAxiom(cls(f, name)));
        label(m, o, f, term(name), readable(name));
    }

    private static void declareObjectProperty(OWLOntologyManager m, OWLOntology o,
            OWLDataFactory f, String name, String definition) {
        m.addAxiom(o, f.getOWLDeclarationAxiom(objectProperty(f, name)));
        label(m, o, f, term(name), readable(name));
        define(m, o, f, term(name), definition);
    }

    private static void individual(OWLOntologyManager m, OWLOntology o, OWLDataFactory f,
            String name, String type, String label) {
        m.addAxiom(o, f.getOWLDeclarationAxiom(individual(f, name)));
        m.addAxiom(o, f.getOWLClassAssertionAxiom(cls(f, type), individual(f, name)));
        label(m, o, f, term(name), label);
    }

    private static void relate(OWLOntologyManager m, OWLOntology o, OWLDataFactory f,
            String subject, String property, String object) {
        m.addAxiom(o, f.getOWLObjectPropertyAssertionAxiom(objectProperty(f, property),
                individual(f, subject), individual(f, object)));
    }

    private static void subClassOf(OWLOntologyManager m, OWLOntology o, OWLDataFactory f,
            String child, String parent) {
        m.addAxiom(o, f.getOWLSubClassOfAxiom(cls(f, child), cls(f, parent)));
    }

    private static void ontologyAnnotation(OWLOntologyManager m, OWLOntology o, OWLDataFactory f,
            String property, org.semanticweb.owlapi.model.OWLAnnotationValue value) {
        m.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                f.getOWLAnnotation(f.getOWLAnnotationProperty(IRI.create(property)), value)));
    }

    private static void label(OWLOntologyManager m, OWLOntology o, OWLDataFactory f, IRI subject,
            String text) {
        m.addAxiom(o, f.getOWLAnnotationAssertionAxiom(f.getRDFSLabel(), subject,
                f.getOWLLiteral(text)));
    }

    private static void define(OWLOntologyManager m, OWLOntology o, OWLDataFactory f, IRI subject,
            String text) {
        m.addAxiom(o, f.getOWLAnnotationAssertionAxiom(
                f.getOWLAnnotationProperty(DEFINITION), subject, f.getOWLLiteral(text)));
    }

    private static void relabel(OWLOntologyManager m, OWLOntology o, OWLDataFactory f, IRI subject,
            String from, String to) {
        m.removeAxiom(o, f.getOWLAnnotationAssertionAxiom(f.getRDFSLabel(), subject,
                f.getOWLLiteral(from)));
        label(m, o, f, subject, to);
    }

    private static void version(OWLOntologyManager m, OWLOntology o, String date) {
        m.applyChange(new org.semanticweb.owlapi.model.SetOntologyID(o, new OWLOntologyID(
                Optional.of(IRI.create(IRI_BASE)),
                Optional.of(IRI.create(IRI_BASE + "/" + date + "/pizza.owl")))));
    }

    /** "MozzarellaTopping" -&gt; "mozzarella topping", which is what an OBO label looks like. */
    private static String readable(String camelCase) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                text.append(' ');
            }
            text.append(Character.toLowerCase(c));
        }
        return text.toString();
    }
}
