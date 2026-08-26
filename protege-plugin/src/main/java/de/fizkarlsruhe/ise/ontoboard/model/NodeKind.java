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
    CLASS, INDIVIDUAL, DATATYPE, LITERAL, OBJECT_PROPERTY, DATA_PROPERTY
}
