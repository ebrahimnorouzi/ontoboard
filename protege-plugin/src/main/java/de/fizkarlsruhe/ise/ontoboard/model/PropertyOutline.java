package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * What the board's properties panel shows (1.155.0): every object and data property of the open
 * ontology and its imports, in their hierarchy, each with its characteristics and its domain and
 * range in words. On a board drawn UML-like no property is a box of its own, and this is where a
 * property's place among the others is seen.
 *
 * <p>A property under each property it is a sub-property of, as Prot&eacute;g&eacute;'s hierarchy
 * puts it; OWL's top and bottom properties are no entry of their own.
 */
public final class PropertyOutline {

    /** One property in the outline. */
    public static final class Entry {
        private final String iri;
        private final String label;
        private final boolean own;
        private final List<String> characteristics;
        private final String domainToRange;
        private final List<Entry> children = new ArrayList<Entry>();

        Entry(String iri, String label, boolean own, List<String> characteristics,
                String domainToRange) {
            this.iri = iri;
            this.label = label;
            this.own = own;
            this.characteristics = Collections.unmodifiableList(characteristics);
            this.domainToRange = domainToRange;
        }

        public String getIri() {
            return iri;
        }

        /** Its label, as the board names it. */
        public String getLabel() {
            return label;
        }

        /** Whether the open ontology declares it, rather than an import. */
        public boolean isOwn() {
            return own;
        }

        /** "functional", "transitive", "inverse of hasPart", ... in that order. */
        public List<String> getCharacteristics() {
            return characteristics;
        }

        /** "Pizza → Topping", "?" for a side nothing states; empty when neither is stated. */
        public String getDomainToRange() {
            return domainToRange;
        }

        /** Its sub-properties, by name. */
        public List<Entry> getChildren() {
            return Collections.unmodifiableList(children);
        }
    }

    private final List<Entry> objectProperties;
    private final List<Entry> dataProperties;

    private PropertyOutline(List<Entry> objectProperties, List<Entry> dataProperties) {
        this.objectProperties = Collections.unmodifiableList(objectProperties);
        this.dataProperties = Collections.unmodifiableList(dataProperties);
    }

    /** The object properties that are no other's sub-property, by name, each with its subtree. */
    public List<Entry> getObjectProperties() {
        return objectProperties;
    }

    /** The same for the data properties. */
    public List<Entry> getDataProperties() {
        return dataProperties;
    }

    /** The outline of the open ontology's properties and its imports'. */
    public static PropertyOutline of(OWLOntology ontology) {
        if (ontology == null) {
            return new PropertyOutline(new ArrayList<Entry>(), new ArrayList<Entry>());
        }
        Map<OWLEntity, Set<OWLEntity>> objectSupers = new HashMap<OWLEntity, Set<OWLEntity>>();
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
            if (property.isOWLTopObjectProperty() || property.isOWLBottomObjectProperty()) {
                continue;
            }
            Set<OWLEntity> supers = new HashSet<OWLEntity>();
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLSubObjectPropertyOfAxiom axiom : part.getObjectSubPropertyAxiomsForSubProperty(property)) {
                    OWLObjectPropertyExpression up = axiom.getSuperProperty();
                    if (!up.isAnonymous() && !up.isOWLTopObjectProperty()) {
                        supers.add(up.asOWLObjectProperty());
                    }
                }
            }
            objectSupers.put(property, supers);
        }
        Map<OWLEntity, Set<OWLEntity>> dataSupers = new HashMap<OWLEntity, Set<OWLEntity>>();
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (property.isOWLTopDataProperty() || property.isOWLBottomDataProperty()) {
                continue;
            }
            Set<OWLEntity> supers = new HashSet<OWLEntity>();
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLSubDataPropertyOfAxiom axiom : part.getDataSubPropertyAxiomsForSubProperty(property)) {
                    if (!axiom.getSuperProperty().isAnonymous()
                            && !axiom.getSuperProperty().isOWLTopDataProperty()) {
                        supers.add(axiom.getSuperProperty().asOWLDataProperty());
                    }
                }
            }
            dataSupers.put(property, supers);
        }
        return new PropertyOutline(forest(ontology, objectSupers), forest(ontology, dataSupers));
    }

    /**
     * Whether the open ontology has a term as its own: it declares it, or nothing declares it and
     * the open ontology names it. A term only an import declares is the import's, however often
     * the open ontology uses it.
     */
    public static boolean isOwn(OWLOntology ontology, OWLEntity entity) {
        if (ontology.isDeclared(entity, Imports.EXCLUDED)) {
            return true;
        }
        return !ontology.isDeclared(entity, Imports.INCLUDED)
                && ontology.containsEntityInSignature(entity.getIRI(), Imports.EXCLUDED);
    }

    /** The roots and their subtrees, from each property's named super-properties. */
    private static List<Entry> forest(OWLOntology ontology, Map<OWLEntity, Set<OWLEntity>> supers) {
        Map<OWLEntity, List<OWLEntity>> under = new HashMap<OWLEntity, List<OWLEntity>>();
        List<OWLEntity> roots = new ArrayList<OWLEntity>();
        for (Map.Entry<OWLEntity, Set<OWLEntity>> each : supers.entrySet()) {
            boolean placed = false;
            for (OWLEntity up : each.getValue()) {
                if (supers.containsKey(up) && !up.equals(each.getKey())) {
                    List<OWLEntity> children = under.get(up);
                    if (children == null) {
                        children = new ArrayList<OWLEntity>();
                        under.put(up, children);
                    }
                    children.add(each.getKey());
                    placed = true;
                }
            }
            if (!placed) {
                roots.add(each.getKey());
            }
        }
        // A circle of properties - each the other's sub-property - has no root, and vanished
        // from the panel: the first of it by IRI is one (the final review of 1.155.0).
        Set<OWLEntity> reached = new HashSet<OWLEntity>();
        for (OWLEntity root : roots) {
            reach(root, under, reached);
        }
        List<OWLEntity> rest = new ArrayList<OWLEntity>(supers.keySet());
        rest.sort((a, b) -> a.getIRI().toString().compareTo(b.getIRI().toString()));
        for (OWLEntity left : rest) {
            if (!reached.contains(left)) {
                roots.add(left);
                reach(left, under, reached);
            }
        }
        List<Entry> forest = new ArrayList<Entry>();
        for (OWLEntity root : roots) {
            forest.add(entry(ontology, root, under, new HashSet<OWLEntity>()));
        }
        sort(forest);
        return forest;
    }

    /** Every property under this one, itself with them. */
    private static void reach(OWLEntity property, Map<OWLEntity, List<OWLEntity>> under,
            Set<OWLEntity> reached) {
        if (!reached.add(property)) {
            return;
        }
        List<OWLEntity> children = under.get(property);
        if (children != null) {
            for (OWLEntity child : children) {
                reach(child, under, reached);
            }
        }
    }

    private static Entry entry(OWLOntology ontology, OWLEntity property, Map<OWLEntity, List<OWLEntity>> under,
            Set<OWLEntity> above) {
        Entry entry = new Entry(property.getIRI().toString(), DisplayLabels.forEntity(ontology, property),
                isOwn(ontology, property), characteristicsOf(ontology, property),
                domainToRange(ontology, property));
        above.add(property);
        List<OWLEntity> children = under.get(property);
        if (children != null) {
            for (OWLEntity child : children) {
                // A hierarchy that runs round in a circle - two properties each the other's
                // sub-property - is shown once round, not for ever.
                if (!above.contains(child)) {
                    entry.children.add(entry(ontology, child, under, new HashSet<OWLEntity>(above)));
                }
            }
        }
        sort(entry.children);
        return entry;
    }

    private static void sort(List<Entry> entries) {
        Collections.sort(entries, (a, b) -> {
            int byName = String.CASE_INSENSITIVE_ORDER.compare(a.getLabel(), b.getLabel());
            return byName != 0 ? byName : a.getIri().compareTo(b.getIri());
        });
    }

    private static List<String> characteristicsOf(OWLOntology ontology, OWLEntity entity) {
        List<String> characteristics = new ArrayList<String>();
        if (entity.isOWLDataProperty()) {
            OWLDataProperty property = entity.asOWLDataProperty();
            for (OWLOntology part : ontology.getImportsClosure()) {
                if (!part.getFunctionalDataPropertyAxioms(property).isEmpty()) {
                    characteristics.add("functional");
                    break;
                }
            }
            return characteristics;
        }
        OWLObjectProperty property = entity.asOWLObjectProperty();
        boolean functional = false;
        boolean inverseFunctional = false;
        boolean transitive = false;
        boolean symmetric = false;
        boolean asymmetric = false;
        boolean reflexive = false;
        boolean irreflexive = false;
        Set<String> inverses = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (OWLOntology part : ontology.getImportsClosure()) {
            functional |= !part.getFunctionalObjectPropertyAxioms(property).isEmpty();
            inverseFunctional |= !part.getInverseFunctionalObjectPropertyAxioms(property).isEmpty();
            transitive |= !part.getTransitiveObjectPropertyAxioms(property).isEmpty();
            symmetric |= !part.getSymmetricObjectPropertyAxioms(property).isEmpty();
            asymmetric |= !part.getAsymmetricObjectPropertyAxioms(property).isEmpty();
            reflexive |= !part.getReflexiveObjectPropertyAxioms(property).isEmpty();
            irreflexive |= !part.getIrreflexiveObjectPropertyAxioms(property).isEmpty();
            for (OWLInverseObjectPropertiesAxiom axiom : part.getInverseObjectPropertyAxioms(property)) {
                for (OWLObjectPropertyExpression other : axiom.getProperties()) {
                    if (!other.isAnonymous() && !other.equals(property)) {
                        inverses.add(DisplayLabels.forEntity(ontology, other.asOWLObjectProperty()));
                    }
                }
            }
        }
        if (functional) {
            characteristics.add("functional");
        }
        if (inverseFunctional) {
            characteristics.add("inverse functional");
        }
        if (transitive) {
            characteristics.add("transitive");
        }
        if (symmetric) {
            characteristics.add("symmetric");
        }
        if (asymmetric) {
            characteristics.add("asymmetric");
        }
        if (reflexive) {
            characteristics.add("reflexive");
        }
        if (irreflexive) {
            characteristics.add("irreflexive");
        }
        for (String inverse : inverses) {
            characteristics.add("inverse of " + inverse);
        }
        return characteristics;
    }

    private static String domainToRange(OWLOntology ontology, OWLEntity entity) {
        List<String> domains = new ArrayList<String>();
        List<String> ranges = new ArrayList<String>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            if (entity.isOWLDataProperty()) {
                for (OWLDataPropertyDomainAxiom axiom : part.getDataPropertyDomainAxioms(entity.asOWLDataProperty())) {
                    addOnce(domains, inWords(ontology, axiom.getDomain()));
                }
                for (OWLDataPropertyRangeAxiom axiom : part.getDataPropertyRangeAxioms(entity.asOWLDataProperty())) {
                    addOnce(ranges, inWords(axiom.getRange()));
                }
            } else {
                for (OWLObjectPropertyDomainAxiom axiom
                        : part.getObjectPropertyDomainAxioms(entity.asOWLObjectProperty())) {
                    addOnce(domains, inWords(ontology, axiom.getDomain()));
                }
                for (OWLObjectPropertyRangeAxiom axiom
                        : part.getObjectPropertyRangeAxioms(entity.asOWLObjectProperty())) {
                    addOnce(ranges, inWords(ontology, axiom.getRange()));
                }
            }
        }
        if (domains.isEmpty() && ranges.isEmpty()) {
            return "";
        }
        return (domains.isEmpty() ? "?" : String.join(" and ", domains)) + " → "
                + (ranges.isEmpty() ? "?" : String.join(" and ", ranges));
    }

    private static void addOnce(List<String> list, String text) {
        if (!list.contains(text)) {
            list.add(text);
        }
    }

    /** A named class by its label; a union or an intersection of named classes in words. */
    private static String inWords(OWLOntology ontology, OWLClassExpression expression) {
        if (!expression.isAnonymous()) {
            return DisplayLabels.forEntity(ontology, expression.asOWLClass());
        }
        Set<OWLClassExpression> operands = null;
        String joint = null;
        if (expression instanceof OWLObjectUnionOf) {
            operands = ((OWLObjectUnionOf) expression).getOperands();
            joint = " or ";
        } else if (expression instanceof OWLObjectIntersectionOf) {
            operands = ((OWLObjectIntersectionOf) expression).getOperands();
            joint = " and ";
        }
        if (operands != null) {
            List<String> names = new ArrayList<String>();
            for (OWLClassExpression operand : operands) {
                if (operand.isAnonymous()) {
                    return "…";
                }
                names.add(DisplayLabels.forEntity(ontology, operand.asOWLClass()));
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            return String.join(joint, names);
        }
        return "…";
    }

    /** A datatype by its short name. */
    private static String inWords(OWLDataRange range) {
        if (range.isDatatype()) {
            return DisplayLabels.shortNameOf(range.asOWLDatatype().getIRI());
        }
        return "…";
    }
}
