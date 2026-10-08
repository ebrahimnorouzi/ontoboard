package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.obolibrary.robot.IOHelper;
import org.obolibrary.robot.RenameOperation;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * ROBOT's {@code rename} - change term IRIs in bulk, or move a whole prefix.
 *
 * <p>The job this exists for is migrating a namespace: an ontology drafted under
 * {@code http://example.org/} that is about to be published under
 * {@code http://purl.obolibrary.org/obo/}, or a term set moving between projects. Protege renames
 * one entity at a time and cannot be told "everything under this prefix"; doing a few hundred by
 * hand is where mistakes come from.
 *
 * <p>Like {@link RobotTransform}, this computes the change without touching the open ontology and
 * hands back {@link OWLOntologyChange}s, so the caller applies them through Protege's own model
 * manager and Edit &gt; Undo reverses the whole rename in one step. A bulk IRI change is exactly the
 * operation somebody wants to undo.
 *
 * <p>{@code RenameOperation} carries no reference to RDF4J, openrdf or Apache POI - checked with
 * {@code javap -v} over the whole constant pool, which is the check that matters here after
 * {@code ReportOperation} and {@code QueryOperation} both turned out to be unusable in a bundle for
 * reasons no method signature revealed.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class TermRename {

    private TermRename() {
    }

    /** Whether a mapping names whole IRIs or the start of them. */
    public enum Mode {
        /**
         * Each mapping is one complete IRI to one complete IRI.
         *
         * <p>What you want for a handful of specific terms - a duplicate being merged into the term
         * that supersedes it, say.
         */
        FULL_IRI("Whole IRIs",
                "Each line renames one complete IRI to another. Use this for specific terms."),

        /**
         * Each mapping is a prefix; every IRI starting with it moves.
         *
         * <p>What you want for publishing under a new namespace, which is the common case and the
         * one that is painful by hand.
         */
        PREFIX("IRI prefixes",
                "Each line moves every IRI that starts with the old text to the new text. This is "
                        + "the one for changing namespace - http://example.org/ to "
                        + "http://purl.obolibrary.org/obo/ - and it renames every term at once.");

        private final String label;
        private final String help;

        Mode(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String getLabel() {
            return label;
        }

        public String getHelp() {
            return help;
        }
    }

    /**
     * Names the whitespace in a mapping's side, or null when there is none.
     *
     * <p>Named rather than just detected because "has a tab inside it" tells somebody who pasted
     * three columns what happened, where "is not a valid IRI" would send them looking at the IRI.
     */
    private static String whitespaceIn(String value) {
        for (int at = 0; at < value.length(); at++) {
            char character = value.charAt(at);
            if (character == '\t') {
                return "a tab";
            }
            if (character == ' ') {
                return "a space";
            }
            if (Character.isWhitespace(character)) {
                return "whitespace";
            }
        }
        return null;
    }

    /** What a rename would do, before anything is changed. */
    public static final class Plan {
        private final List<OWLOntologyChange> changes;
        private final List<String> unmatched;
        private final int entitiesAffected;

        Plan(List<OWLOntologyChange> changes, List<String> unmatched, int entitiesAffected) {
            this.changes = Collections.unmodifiableList(changes);
            this.unmatched = Collections.unmodifiableList(unmatched);
            this.entitiesAffected = entitiesAffected;
        }

        /** Apply these through Protege's model manager so the whole rename is one undo. */
        public List<OWLOntologyChange> getChanges() {
            return changes;
        }

        /**
         * Mappings that matched nothing in this ontology.
         *
         * <p>Surfaced rather than ignored, because a mapping that matches nothing is almost always
         * a typo in the IRI - and a silent no-op is how somebody concludes the rename worked.
         */
        public List<String> getUnmatched() {
            return unmatched;
        }

        /** How many entities changed IRI. */
        public int getEntitiesAffected() {
            return entitiesAffected;
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    /**
     * Reads {@code old -> new} or {@code old<TAB>new} mappings, one per line.
     *
     * <p>Both separators, because one comes from a text editor and the other from a spreadsheet, and
     * refusing either would send somebody to reformat a list by hand. Blank lines and {@code #}
     * comments are skipped.
     *
     * @throws RobotException naming the line, when a line has no separator - a mapping silently
     *     dropped is worse than a rename that refuses to start
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> mappings = new LinkedHashMap<String, String>();
        if (text == null) {
            return mappings;
        }
        int number = 0;
        for (String line : text.split("\\r?\\n")) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] halves = trimmed.contains("->")
                    ? trimmed.split("->", 2)
                    : trimmed.split("\\t", 2);
            if (halves.length != 2 || halves[0].trim().isEmpty() || halves[1].trim().isEmpty()) {
                throw new RobotException("Line " + number + " is not a mapping: '" + trimmed
                        + "'. Write 'old -> new', or separate the two with a tab.");
            }
            String from = halves[0].trim();
            String to = halves[1].trim();
            // Both splits above have a limit of 2, so anything after the second field stays
            // attached to it. A line copied out of a spreadsheet that has a third column - a
            // note, a date, whoever decided it - therefore used to arrive as a target IRI with a
            // tab and a sentence inside it, and nothing downstream objected: the plan came back
            // as one entity affected, no unmatched mappings, and it would have declared
            // Class(<http://example.org/o#new    renamed for clarity>) while deleting the real
            // term. Silent corruption reported as success, from an ordinary paste.
            //
            // No IRI may contain whitespace, in either mode - PREFIX takes the leading part of an
            // IRI rather than a whole one, but that cannot contain a space either. So this is one
            // rule for both, and it is checked here rather than in plan() because the dialog's
            // own parse is where a person can still see what they pasted.
            String offending = whitespaceIn(from) != null ? from : to;
            String whitespace = whitespaceIn(from) != null ? whitespaceIn(from) : whitespaceIn(to);
            if (whitespace != null) {
                throw new RobotException("Line " + number + " has " + whitespace + " inside '"
                        + offending.replace("\t", "    ") + "', and no IRI can contain "
                        + "whitespace. A line with a third column does this: only the first two "
                        + "are the mapping, so the rest ends up inside the new IRI. Keep two "
                        + "columns, or write 'old -> new'.");
            }
            mappings.put(from, to);
        }
        return mappings;
    }

    /**
     * Works out what renaming would change, without changing anything.
     *
     * @throws RobotException if ROBOT cannot perform the rename
     */
    public static Plan plan(OWLOntology ontology, Mode mode, Map<String, String> mappings) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to rename in");
        }
        if (mappings == null || mappings.isEmpty()) {
            throw new RobotException("There are no mappings, so there is nothing to rename.");
        }

        Set<OWLAxiom> before = new HashSet<OWLAxiom>(ontology.getAxioms());
        Set<String> irisBefore = iriStrings(ontology);

        OWLOntology copy;
        try {
            copy = copyOf(ontology);
        } catch (OWLOntologyCreationException cannotCopy) {
            throw new RobotException("Could not copy the ontology to preview the rename: "
                    + cannotCopy.getMessage(), cannotCopy);
        }

        try {
            if (mode == Mode.PREFIX) {
                RenameOperation.renamePrefixes(copy, new IOHelper(), mappings);
            } else {
                // The four-argument form with true, not the three-argument one. The short form
                // delegates with false, which makes ROBOT throw MISSING ENTITY ERROR and abandon
                // the whole rename the moment one mapping names an IRI the ontology does not have.
                // For a list pasted out of a spreadsheet that is the wrong trade: the useful
                // behaviour is to rename what matches and say which lines matched nothing, which
                // is what Plan.getUnmatched reports.
                RenameOperation.renameFull(copy, new IOHelper(), mappings, true);
            }
        } catch (RuntimeException | LinkageError failed) {
            throw new RobotException("The rename could not run: " + describe(failed), failed);
        } catch (Exception failed) {
            throw new RobotException("The rename could not run: " + describe(failed), failed);
        }

        Set<OWLAxiom> after = new HashSet<OWLAxiom>(copy.getAxioms());
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : after) {
            if (!before.contains(axiom)) {
                changes.add(new AddAxiom(ontology, axiom));
            }
        }
        for (OWLAxiom axiom : before) {
            if (!after.contains(axiom)) {
                changes.add(new RemoveAxiom(ontology, axiom));
            }
        }

        Set<String> irisAfter = iriStrings(copy);
        Set<String> gone = new HashSet<String>(irisBefore);
        gone.removeAll(irisAfter);

        return new Plan(changes, unmatched(mode, mappings, irisBefore), gone.size());
    }

    /** Mappings whose left-hand side matches no IRI in the ontology. */
    private static List<String> unmatched(Mode mode, Map<String, String> mappings,
            Set<String> iris) {
        List<String> missed = new ArrayList<String>();
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            boolean matched = false;
            for (String iri : iris) {
                if (mode == Mode.PREFIX ? iri.startsWith(mapping.getKey())
                        : iri.equals(mapping.getKey())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                missed.add(mapping.getKey());
            }
        }
        return missed;
    }

    /** Every entity IRI the ontology itself declares or uses, as strings. */
    private static Set<String> iriStrings(OWLOntology ontology) {
        Set<String> iris = new HashSet<String>();
        for (OWLEntity entity : ontology.getSignature(Imports.EXCLUDED)) {
            iris.add(entity.getIRI().toString());
        }
        return iris;
    }

    /**
     * The ontology's own axioms in a manager of its own.
     *
     * <p>Own axioms only, deliberately: renaming inside an import would produce changes aimed at
     * axioms that do not live in the ontology the user has open, and applying those would do
     * nothing while reporting success.
     */
    private static OWLOntology copyOf(OWLOntology ontology) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        IRI iri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get()
                : IRI.create("http://www.ontoboard.org/rename-preview");
        return manager.createOntology(new HashSet<OWLAxiom>(ontology.getAxioms()), iri);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }
}
