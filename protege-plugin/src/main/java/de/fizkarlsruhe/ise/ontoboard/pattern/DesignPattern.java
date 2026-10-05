package de.fizkarlsruhe.ise.ontoboard.pattern;

/**
 * One ontology design pattern in the bundled library.
 *
 * <p>Deliberately holds no class or property count. The harvested metadata states one for each
 * pattern and it is wrong for 41 of the 123: {@code affordance} says two classes and has eight,
 * {@code eventprocessing} says eight and has seventeen, {@code vesselspecies} says zero and has
 * five. Seventeen property counts are wrong the same way. A number shown in a chooser is a
 * number somebody decides on, so the only one worth showing is the one read from the file at the
 * moment it is opened - which is what {@link PatternLibrary#contentsOf} does.
 */
public final class DesignPattern {

    private final String id;
    private final String name;
    private final String collection;
    private final String publisher;
    private final String category;
    private final String domain;
    private final String sameAs;
    private final String description;
    private final String competencyQuestions;

    DesignPattern(String id, String name, String collection, String publisher, String category,
            String domain, String sameAs, String description, String competencyQuestions) {
        this.id = id;
        this.name = name;
        this.collection = collection;
        this.publisher = publisher;
        this.category = category;
        this.domain = domain;
        this.sameAs = sameAs == null ? "" : sameAs;
        this.description = description == null ? "" : description;
        this.competencyQuestions = competencyQuestions == null ? "" : competencyQuestions;
    }

    /** The directory name, which is also the resource path and the stable identifier. */
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Where OntoBoard got the pattern - the collection it was harvested from.
     *
     * <p>Separate from {@link #getPublisher()} because they are different facts and the library
     * currently makes them look like one. All 123 patterns were harvested from the ODP portal,
     * so every {@code collection} is {@code odp}; but the portal is a catalogue of submissions,
     * and the patterns in it were published by thirteen different places. Grouping by the
     * harvest would put everything in one bucket; grouping by the publisher is the division that
     * carries information.
     */
    public String getCollection() {
        return collection;
    }

    /** Who published the pattern, from the host of the IRI it declares for itself. */
    public String getPublisher() {
        return publisher;
    }

    public String getCategory() {
        return category;
    }

    public String getDomain() {
        return domain;
    }

    /**
     * The id of the pattern this one duplicates, or empty.
     *
     * <p>Ten of the 123 are another one under a second name - {@code agentrole} and
     * {@code agent-role}, {@code collection}, {@code collectionentity} and
     * {@code collection-entity}, {@code partof} and {@code part-of}. Importing both would import
     * the same terms twice, and a library that lists 123 entries while holding 113 patterns is
     * miscounting itself in the one place a user would not think to check.
     */
    public String getSameAs() {
        return sameAs;
    }

    public boolean isDuplicate() {
        return !sameAs.isEmpty();
    }

    /** What the pattern is for, or empty when the harvest captured nothing usable. */
    public String getDescription() {
        return description;
    }

    /**
     * The questions the pattern claims to answer, or empty for the 33 that state none.
     *
     * <p>Carried because it is how an ODP is actually chosen. "What role does this agent play?"
     * decides whether Agent Role is the pattern wanted; "behavioral, general" does not.
     */
    public String getCompetencyQuestions() {
        return competencyQuestions;
    }

    /** The classpath resource holding the pattern's OWL file. */
    public String getResourcePath() {
        return PatternLibrary.DIRECTORY + id + "/pattern.owl";
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
