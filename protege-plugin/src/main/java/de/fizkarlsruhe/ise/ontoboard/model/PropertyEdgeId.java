package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Locale;

/**
 * The id of a property arrow, written in one place and read in another.
 *
 * <p>Existing ids are untouched. {@code sub|}, {@code rest|some|}, {@code rest|only|},
 * {@code data|}, {@code dr|}, {@code type|} and {@code subprop|} mean exactly what they meant,
 * byte for byte, because a peer in a live session parses these and an id that changed shape
 * would reach an older build as {@code UnknownEdgeException}. Everything this class adds is
 * behind a new prefix, so an older peer meets a clean refusal rather than a misreading.
 *
 * <p>It exists because the writer ({@code OntologyProjection}) and the reader
 * ({@code AxiomRemoval}) already each held their own copy of the format, and two copies of a
 * string are how the two come to disagree. {@code InferredEdges.SUBCLASS_ID_PREFIX} is public
 * for the same reason.
 *
 * <p><b>The origin is the load-bearing part.</b> It records where the restriction was written,
 * and that decides whether the arrow may be deleted at all. A restriction sitting on its own
 * under {@code SubClassOf} can be retracted exactly. The same restriction inside an
 * {@code ObjectIntersectionOf}, or inside an {@code EquivalentClasses}, cannot: retracting the
 * axiom that holds it would also retract the other conjuncts, or the whole definition of the
 * class. Those are refused, by name, rather than approximated.
 */
public final class PropertyEdgeId {

    /** The prefix. Deliberately not one of the existing ones. */
    public static final String PREFIX = "pe";

    /** How many segments a well-formed id has, including the prefix. */
    static final int SEGMENTS = 6;

    /** Where the restriction was written. */
    public enum Origin {
        /** {@code SubClassOf(A restriction)} - the restriction is the whole superclass. */
        SUBCLASS("sub", true, null),

        /**
         * {@code SubClassOf(A ObjectIntersectionOf(... restriction ...))}.
         *
         * <p>Not retractable: the axiom carries the other conjuncts too, and removing it would
         * take away relations the user can see nothing wrong with.
         */
        CONJUNCT("subin", false,
                "This arrow is one conjunct of a larger SubClassOf axiom. Deleting the axiom "
                        + "would remove the other conjuncts with it, so OntoBoard will not do it "
                        + "from here - edit the axiom in Protege's class description instead."),

        /**
         * {@code EquivalentClasses(A ... restriction ...)}.
         *
         * <p>Not retractable: this is part of the class's definition, and removing the axiom
         * would turn a defined class into a primitive one - a change to what the ontology means,
         * not to one arrow.
         */
        EQUIVALENCE("eqv", false,
                "This arrow comes from the class's definition (an EquivalentClasses axiom). "
                        + "Deleting that axiom would turn a defined class into a primitive one, "
                        + "which is a change to what the ontology means rather than to one "
                        + "relation - edit the definition in Protege instead."),

        /** {@code SubClassOf(ObjectSomeValuesFrom(R B) A)} - a scoped domain, read backwards. */
        SCOPED_DOMAIN("sdom", true, null);

        private final String token;
        private final boolean retractable;
        private final String refusal;

        Origin(String token, boolean retractable, String refusal) {
            this.token = token;
            this.retractable = retractable;
            this.refusal = refusal;
        }

        /** The spelling that goes in an id. */
        public String getToken() {
            return token;
        }

        /** Whether one axiom can be removed that takes this arrow and nothing else. */
        public boolean isRetractable() {
            return retractable;
        }

        /** Why not, for the user, or null when it is. */
        public String getRefusal() {
            return refusal;
        }

        static Origin byToken(String token) {
            for (Origin origin : values()) {
                if (origin.token.equals(token)) {
                    return origin;
                }
            }
            return null;
        }
    }

    /** An id taken apart. */
    public static final class Parsed {
        private final Origin origin;
        private final String qualifier;
        private final String subject;
        private final String property;
        private final String filler;

        Parsed(Origin origin, String qualifier, String subject, String property, String filler) {
            this.origin = origin;
            this.qualifier = qualifier;
            this.subject = subject;
            this.property = property;
            this.filler = filler;
        }

        public Origin getOrigin() {
            return origin;
        }

        /** {@code some}, {@code only}, {@code min2}, {@code max1}, {@code exactly3}, {@code value}. */
        public String getQualifier() {
            return qualifier;
        }

        public String getSubject() {
            return subject;
        }

        public String getProperty() {
            return property;
        }

        public String getFiller() {
            return filler;
        }

        /** The cardinality in a {@code min2}-style qualifier, or -1 when there is none. */
        public int getCardinality() {
            String digits = qualifier.replaceAll("^[a-z]+", "");
            if (digits.isEmpty()) {
                return -1;
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException notANumber) {
                return -1;
            }
        }

        /** The qualifier without its cardinality: {@code min2} becomes {@code min}. */
        public String getShape() {
            return qualifier.replaceAll("[0-9]+$", "");
        }
    }

    private PropertyEdgeId() {
    }

    /** The id for one property arrow. */
    public static String of(Origin origin, String qualifier, String subject, String property,
            String filler) {
        return PREFIX + "|" + origin.getToken() + "|" + qualifier + "|" + subject + "|" + property
                + "|" + filler;
    }

    /** Whether this id is one of ours. */
    public static boolean is(String edgeId) {
        return edgeId != null && edgeId.startsWith(PREFIX + "|");
    }

    /**
     * The id taken apart, or null when it is not one of ours or is malformed.
     *
     * <p>Null rather than an exception for a malformed id, because these arrive from a peer in a
     * shared session and the caller already has a refusal path that names the edge. Split with
     * {@code -1} so trailing empties survive: {@code "pe|"} must come back as a short array and
     * not as a zero-length one, which is the trap {@code AxiomRemoval} fell into once already.
     */
    public static Parsed parse(String edgeId) {
        if (!is(edgeId)) {
            return null;
        }
        String[] parts = edgeId.split("\\|", -1);
        if (parts.length != SEGMENTS) {
            return null;
        }
        Origin origin = Origin.byToken(parts[1]);
        if (origin == null || parts[2].isEmpty() || parts[3].isEmpty() || parts[4].isEmpty()
                || parts[5].isEmpty()) {
            return null;
        }
        return new Parsed(origin, parts[2].toLowerCase(Locale.ROOT), parts[3], parts[4], parts[5]);
    }
}
