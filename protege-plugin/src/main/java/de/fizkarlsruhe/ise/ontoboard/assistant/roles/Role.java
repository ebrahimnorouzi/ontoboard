package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

/** One role: what the assistant is told it is (spec D4). Plain text the person can read and edit. */
public final class Role {

    private final String id;
    private final String name;
    private final String text;
    private final boolean builtIn;
    private final boolean edited;

    Role(String id, String name, String text, boolean builtIn, boolean edited) {
        this.id = id;
        this.name = name;
        this.text = text;
        this.builtIn = builtIn;
        this.edited = edited;
    }

    /** "ontology-expert", or a custom role's slug. */
    public String getId() {
        return id;
    }

    /** The text's first "# " heading. */
    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    /** Shipped with OntoBoard; can be edited and reset, not deleted. */
    public boolean isBuiltIn() {
        return builtIn;
    }

    /** A built-in role whose text differs from the shipped one. */
    public boolean isEdited() {
        return edited;
    }

    @Override
    public String toString() {
        return name;
    }
}
