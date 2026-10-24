package de.fizkarlsruhe.ise.ontoboard.model;

/**
 * What a node on the canvas stands for.
 *
 * <p>Properties are nodes only when a user puts them there. A schema diagram is usually about
 * classes, and drawing every property as a box turns it into a hairball - but a property hierarchy
 * cannot be drawn at all unless properties can be nodes, and rdfs:subPropertyOf was simply
 * invisible before this. So they are available and opt-in, like everything else on this canvas.
 */
public enum NodeKind {
    CLASS, INDIVIDUAL, DATATYPE, LITERAL, OBJECT_PROPERTY, DATA_PROPERTY,

    /**
     * Something the ontology says that is not a term.
     *
     * <p>An anonymous class expression - a restriction, a union, a complement, an enumeration
     * - or a property chain, which has three terms and an order and so fits no two-ended
     * shape either.
     *
     * <p><b>Why this had to exist.</b> An arrow needs a box at each end, and an anonymous
     * expression has no IRI to be a box. So until 1.123.0 every shape whose filler was itself
     * anonymous was dropped without trace - {@code A SubClassOf R some (B or C)} drew nothing at
     * all, and nor did a union, a complement, an enumeration or a restriction on an inverse
     * property. Measured over MWO 3.0.1: 39 of its 116 restriction occurrences, 34%, were
     * invisible, and the page showing the diagram said nothing about it.
     *
     * <p>Drawn the way Graffoo draws them - a small node carrying the operator, with the operands
     * hanging off it - because the alternative, an edge labelled with the whole expression,
     * stops being readable at the first nesting.
     *
     * <p>It is NOT a term. It has no IRI, it is not in the ontology's signature, clicking it
     * selects nothing in Protege, and it is minted fresh by every projection rather than
     * remembered in the layout - the same way a {@link #DATATYPE} node already behaves.
     */
    EXPRESSION
}
