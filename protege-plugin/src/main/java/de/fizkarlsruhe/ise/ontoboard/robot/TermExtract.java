package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.obolibrary.robot.ExtractOperation;
import org.obolibrary.robot.MireotOperation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.model.parameters.Imports;
import uk.ac.manchester.cs.owlapi.modularity.ModuleType;

/**
 * {@code robot extract} - taking a usable piece of somebody else's ontology.
 *
 * <p>This is how an OBO project reuses a term. You do not import all of ChEBI to say that your
 * material is a polymer; you extract a module containing that class and the handful of axioms that
 * give it meaning, and import that. Doing it by hand means copying a class and losing its
 * hierarchy; doing it with ROBOT means the result is still logically sound, which is the whole
 * point of the extraction methods below.
 *
 * <p>The addition here is that terms the source ontology does not contain are <b>reported</b>. A
 * term list is hand-maintained against an ontology that keeps moving: a class gets obsoleted and
 * its IRI stops resolving, and an extraction quietly returns a smaller module. Nothing about the
 * result looks wrong - it is a valid module, it just no longer has the term you were relying on.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class TermExtract {

    /**
     * How to decide which axioms come with the terms.
     *
     * <p>The descriptions below were checked against what the operations actually return, not
     * taken from their names: BOT walks <em>up</em> and TOP walks <em>down</em>, which is the
     * opposite of what both names suggest to anyone who has not read the modularity papers. A
     * plugin that offered "BOT - takes the subclasses" would hand people the wrong module and be
     * believed, because the module it produces is perfectly valid.
     */
    public enum Method {
        /**
         * The bottom module: the terms and everything above them. The ODK default.
         */
        BOT("BOT",
                "Brings each term together with its ancestors, up to the root - so an imported "
                        + "class arrives with the hierarchy that gives it a place to hang. This is "
                        + "what an ODK import module uses by default and what you almost certainly "
                        + "want. Despite the name it walks upwards, not down."),

        /**
         * The top module: the terms and everything below them.
         */
        TOP("TOP",
                "Brings each term together with everything beneath it - importing a chemical "
                        + "class and getting the specific chemicals under it as well. Despite the "
                        + "name it walks downwards. The module can be enormous for a term high in "
                        + "a large ontology, so check the size before importing."),

        /**
         * The nested (fixpoint) module: minimal, and often startlingly so.
         */
        STAR("STAR",
                "The smallest module that keeps the terms meaning exactly what they meant. For an "
                        + "ontology with real logical definitions that is the right answer. For a "
                        + "plain OBO hierarchy it is often nearly empty - a class whose only axiom "
                        + "is 'is a subclass of X' has no logical content that has to travel with "
                        + "it, so you can get back the labels and nothing else. Surprising, and "
                        + "correct."),

        /**
         * MIREOT: the ancestors with their labels, and no logic.
         */
        MIREOT("MIREOT",
                "Brings each term and the chain of parents above it, with labels and definitions "
                        + "but without the logical axioms. The result is a readable hierarchy "
                        + "rather than a logically complete module, which is what an OBO project "
                        + "wants when it only needs somewhere sensible to hang its own terms. "
                        + "Smaller than BOT and easier to read.");

        private final String label;
        private final String help;

        Method(String label, String help) {
            this.label = label;
            this.help = help;
        }

        /** ROBOT's own spelling, so a result can be compared with a Makefile. */
        public String getLabel() {
            return label;
        }

        /** What this method does differently, for the parameter dialog's "?". */
        public String getHelp() {
            return help;
        }
    }

    /** What an ODK import module uses, and the right answer for nearly every import. */
    public static final Method DEFAULT_METHOD = Method.BOT;

    /** An extracted module, and what could not be extracted. */
    public static final class Result {
        private final OWLOntology module;
        private final List<IRI> missing;
        private final int requested;

        Result(OWLOntology module, List<IRI> missing, int requested) {
            this.module = module;
            this.missing = Collections.unmodifiableList(missing);
            this.requested = requested;
        }

        public OWLOntology getModule() {
            return module;
        }

        /**
         * Terms the source ontology does not contain.
         *
         * <p>The reason this class exists rather than a direct call to ROBOT. A term list is
         * maintained by hand against an ontology that keeps changing; when a class is obsoleted
         * the extraction simply returns a smaller module, and nothing about it looks wrong.
         */
        public List<IRI> getMissing() {
            return missing;
        }

        /** How many terms were asked for, including the missing ones. */
        public int getRequested() {
            return requested;
        }

        public int getAxiomCount() {
            return module.getAxiomCount();
        }

        @Override
        public String toString() {
            return getAxiomCount() + " axioms for " + (requested - missing.size()) + " of "
                    + requested + " terms";
        }
    }

    private TermExtract() {
    }

    /**
     * Extracts a module.
     *
     * @param source the ontology to take from, with its imports already loaded
     * @param terms what to take, normally from a {@link TermList}
     * @param method how much context to bring with them
     * @param moduleIri the IRI to give the module, or null for one derived from the source
     * @throws RobotException if ROBOT cannot do it, with the same failure
     *     shape as every other ROBOT operation here so one handler covers them all
     */
    public static Result run(OWLOntology source, List<IRI> terms, Method method, IRI moduleIri) {
        if (source == null) {
            throw new IllegalArgumentException("no source ontology to extract from");
        }
        if (terms == null || terms.isEmpty()) {
            throw new IllegalArgumentException("no terms to extract");
        }

        // Checked before extracting rather than inferred afterwards: a term missing from the
        // source produces a module that is simply smaller, with nothing to distinguish it from a
        // correct one.
        List<IRI> missing = new ArrayList<IRI>();
        Set<IRI> present = new LinkedHashSet<IRI>();
        for (IRI term : terms) {
            if (source.containsEntityInSignature(term, Imports.INCLUDED)) {
                present.add(term);
            } else {
                missing.add(term);
            }
        }
        if (present.isEmpty()) {
            throw new RobotException("None of the " + terms.size()
                    + " terms are in that ontology. Either it is not the one they came from, or "
                    + "its imports are not loaded.");
        }

        IRI iri = moduleIri != null ? moduleIri : moduleIriFor(source);
        try {
            OWLOntology module;
            if (method == Method.MIREOT) {
                module = MireotOperation.getAncestors(source, null, present,
                        MireotOperation.getDefaultAnnotationProperties());
                // MireotOperation takes no output IRI, so the module comes back carrying the
                // SOURCE ontology's identity. Saved and imported under that IRI it claims to be
                // the whole of the ontology it was cut from - the import IRI the catalog maps
                // then belongs to no ontology at all, so the import never resolves, and any tool
                // that loads both has two different ontologies under one name and keeps whichever
                // it saw first. Every other method takes the IRI as an argument; this one has to
                // be told afterwards.
                module.getOWLOntologyManager().applyChange(new SetOntologyID(module,
                        new OWLOntologyID(com.google.common.base.Optional.of(iri),
                                com.google.common.base.Optional.<IRI>absent())));
            } else {
                module = ExtractOperation.extract(source, present, iri, moduleTypeOf(method));
            }
            return new Result(module, missing, terms.size());
        } catch (LinkageError incompatible) {
            throw new RobotException(
                    "Extract could not run against this Protege's OWL API. Protege 5.6 or newer "
                            + "is expected to work.", incompatible);
        } catch (Exception failed) {
            throw new RobotException(
                    "Extract failed: " + failed.getMessage(), failed);
        }
    }

    private static ModuleType moduleTypeOf(Method method) {
        switch (method) {
            case BOT:
                return ModuleType.BOT;
            case TOP:
                return ModuleType.TOP;
            case STAR:
            default:
                return ModuleType.STAR;
        }
    }

    /**
     * An IRI for the module inside the importing project's own namespace.
     *
     * <p>ODK puts an import module under the <em>project's</em> IRI, not the source's:
     * {@code http://purl.obolibrary.org/obo/mwo/imports/iao_import.owl}, not something under
     * {@code obo/iao}. That is not decoration. The module is not IAO - it is a few dozen axioms
     * this project chose to copy - and publishing it under IAO's namespace claims otherwise, so a
     * tool that resolves it gets this project's fragment where it expected the real ontology.
     *
     * @param projectIri the importing ontology's own IRI, or null to fall back to the source's
     */
    public static IRI moduleIriIn(IRI projectIri, OWLOntology source) {
        if (projectIri == null) {
            return moduleIriFor(source);
        }
        String project = projectIri.toString();
        int lastSlash = project.lastIndexOf('/');
        String stem = lastSlash > 0 ? project.substring(0, lastSlash) : project;
        return IRI.create(stem + "/imports/" + shortNameOf(source) + "_import.owl");
    }

    /**
     * Why this module IRI will cause trouble later, or null when it is fine.
     *
     * <p>A module IRI is derived from the importing ontology's own IRI, so an ontology whose IRI
     * is a {@code file:} URL - which is what Protege gives an ontology created without one, and
     * what older versions of this plugin's own scaffold produced - yields a module IRI naming a
     * path on one machine. Everything works for the person who made it. The import statement and
     * the catalog entry both go into version control pointing at
     * {@code file:/C:/Users/someone/...}, and for everybody else the import does not resolve.
     *
     * <p>Found by running the whole thing against a real project, whose ontology IRI was exactly
     * that. Nothing failed; the module was simply unusable anywhere else.
     */
    public static String warningFor(IRI moduleIri) {
        if (moduleIri == null) {
            return null;
        }
        String iri = moduleIri.toString().toLowerCase(Locale.ROOT);
        if (iri.startsWith("http://") || iri.startsWith("https://")) {
            return null;
        }
        return "The module's IRI is " + moduleIri + ", which names a location on this machine "
                + "rather than a published address. The import statement and the catalog entry "
                + "will both contain it, so the import will resolve here and nowhere else. This "
                + "comes from the ontology's own IRI - give it an http IRI in the ontology header "
                + "and extract again.";
    }

    /** The last path segment of an ontology's IRI, without any .owl. */
    static String shortNameOf(OWLOntology source) {
        String base = source != null && source.getOntologyID().getOntologyIRI().isPresent()
                ? source.getOntologyID().getOntologyIRI().get().toString()
                : "extract";
        int lastSlash = base.lastIndexOf('/');
        String name = lastSlash >= 0 && lastSlash < base.length() - 1
                ? base.substring(lastSlash + 1) : base;
        if (name.toLowerCase().endsWith(".owl")) {
            name = name.substring(0, name.length() - ".owl".length());
        }
        return name.isEmpty() ? "extract" : name;
    }

    /**
     * An IRI for the module, derived from the source.
     *
     * <p>ODK names these {@code .../imports/<name>_import.owl} and so does this, because a module
     * with the same IRI as the ontology it came from is a trap: loading both puts two different
     * ontologies under one name and OWL API takes whichever it saw first.
     */
    static IRI moduleIriFor(OWLOntology source) {
        String base = source.getOntologyID().getOntologyIRI().isPresent()
                ? source.getOntologyID().getOntologyIRI().get().toString()
                : "http://www.ontoboard.org/extract";
        String name = base;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < name.length() - 1) {
            name = name.substring(lastSlash + 1);
        }
        if (name.toLowerCase().endsWith(".owl")) {
            name = name.substring(0, name.length() - ".owl".length());
        }
        if (name.isEmpty()) {
            name = "extract";
        }
        String stem = lastSlash >= 0 ? base.substring(0, lastSlash) : base;
        return IRI.create(stem + "/imports/" + name + "_import.owl");
    }
}
