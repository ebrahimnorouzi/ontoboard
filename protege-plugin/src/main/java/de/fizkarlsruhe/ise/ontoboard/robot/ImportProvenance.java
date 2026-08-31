package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.List;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Which release of an upstream ontology an import module was cut from.
 *
 * <p>Four of the five expert panels raised this independently, and it is the question nobody can
 * answer about an existing project. A module holding forty terms from ChEBI sits in
 * {@code src/ontology/imports/}, and there is nothing in it saying which ChEBI it came from, when,
 * or by what method. So nobody dares regenerate it - regenerating means guessing - and nobody can
 * tell whether the definitions in it are the current ones or four years stale. The module quietly
 * becomes a fork of the upstream ontology that nobody decided to make.
 *
 * <p>Two properties, each used in its ordinary sense:
 *
 * <ul>
 *   <li>{@code dcterms:source} - the upstream <b>ontology IRI</b>. What you would load to get the
 *       current release. Stable across releases, which is what makes it the thing to look up.
 *   <li>{@code prov:wasDerivedFrom} - the upstream <b>version IRI</b>. Which release, exactly. Left
 *       off when the upstream publishes no version IRI, because writing the ontology IRI here
 *       would say "this release" about something that does not identify a release.
 * </ul>
 *
 * <p>Both are needed and they are not interchangeable. The version IRI answers "which release", and
 * only the ontology IRI can be re-resolved to find out whether that release is still the current
 * one. Recording one without the other leaves the question half-answered.
 *
 * <p>Pure OWL API; no Protege types and no Swing.
 */
public final class ImportProvenance {

    /** The upstream ontology IRI - what to load to see whether it has moved. */
    public static final IRI SOURCE = IRI.create("http://purl.org/dc/terms/source");

    /** The upstream version IRI - which release this was cut from. */
    public static final IRI DERIVED_FROM = IRI.create("http://www.w3.org/ns/prov#wasDerivedFrom");

    /** When the module was extracted. */
    public static final IRI EXTRACTED_ON = IRI.create("http://purl.org/dc/terms/created");

    /** What a module can be said about its upstream. */
    public enum Freshness {
        /** Nothing recorded; the module cannot be traced or safely regenerated. */
        UNRECORDED,
        /** Recorded, but the upstream is not open here, so there is nothing to compare against. */
        UNCHECKED,
        /** The upstream publishes no version IRI, so "which release" has no answer to compare. */
        UNVERSIONED_UPSTREAM,
        /** The upstream is open and is the release this was cut from. */
        CURRENT,
        /** The upstream is open and is a different release. */
        MOVED
    }

    /** What is known about one module's relationship to its upstream. */
    public static final class Report {
        private final Freshness freshness;
        private final IRI source;
        private final IRI cutFrom;
        private final IRI upstreamNow;
        private final String extractedOn;

        Report(Freshness freshness, IRI source, IRI cutFrom, IRI upstreamNow, String extractedOn) {
            this.freshness = freshness;
            this.source = source;
            this.cutFrom = cutFrom;
            this.upstreamNow = upstreamNow;
            this.extractedOn = extractedOn == null ? "" : extractedOn;
        }

        public Freshness getFreshness() {
            return freshness;
        }

        /** The upstream ontology IRI, or null when none was recorded. */
        public IRI getSource() {
            return source;
        }

        /** The upstream version IRI this was cut from, or null. */
        public IRI getCutFrom() {
            return cutFrom;
        }

        /** The version IRI the upstream carries now, or null when it is not open here. */
        public IRI getUpstreamNow() {
            return upstreamNow;
        }

        /** {@code YYYY-MM-DD}, or empty. */
        public String getExtractedOn() {
            return extractedOn;
        }

        /** Whether this is worth telling somebody about unprompted. */
        public boolean isWorthWarningAbout() {
            return freshness == Freshness.MOVED || freshness == Freshness.UNRECORDED;
        }

        /**
         * What to tell a person, in the terms of what they can do about it.
         *
         * <p>Never "stale" on its own. A module cut from an old release is not automatically
         * wrong - the terms in it may not have changed at all - and telling somebody their project
         * is stale every time upstream publishes teaches them to ignore the message.
         */
        public String explain() {
            switch (freshness) {
                case MOVED:
                    return "Cut from " + cutFrom + (extractedOn.isEmpty() ? ""
                            : " on " + extractedOn) + ". The copy of " + source
                            + " open here is " + upstreamNow + ". Whether that matters depends on "
                            + "whether the terms you took have changed - re-extract and compare "
                            + "before assuming either way.";
                case CURRENT:
                    return "Cut from " + cutFrom + (extractedOn.isEmpty() ? ""
                            : " on " + extractedOn) + ", which is the release open here.";
                case UNCHECKED:
                    return "Cut from " + (cutFrom == null ? source : cutFrom)
                            + (extractedOn.isEmpty() ? "" : " on " + extractedOn)
                            + ". Open " + source + " to find out whether it has moved since.";
                case UNVERSIONED_UPSTREAM:
                    return "Cut from " + source + (extractedOn.isEmpty() ? ""
                            : " on " + extractedOn) + ", which publishes no version IRI - so "
                            + "there is no release identifier to compare, and the extraction date "
                            + "is the only clue to its age.";
                case UNRECORDED:
                default:
                    return "This module records nothing about where it came from, so there is no "
                            + "way to tell which release it holds or to regenerate it without "
                            + "guessing. Modules extracted with OntoBoard from now on record it; "
                            + "for this one, re-extracting is the way to find out.";
            }
        }

        @Override
        public String toString() {
            return freshness + ": " + explain();
        }
    }

    private ImportProvenance() {
    }

    /**
     * The annotations recording where a module came from.
     *
     * <p>Changes rather than applied edits, so the caller decides when - the module is usually
     * still being assembled when this is worked out.
     *
     * @param module the extracted module
     * @param source the ontology it was cut from, as loaded at the time
     * @param isoDate {@code YYYY-MM-DD}; the caller owns the clock
     */
    public static List<OWLOntologyChange> stamp(OWLOntology module, OWLOntology source,
            String isoDate) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (module == null || source == null) {
            return changes;
        }
        IRI sourceIri = source.getOntologyID().getOntologyIRI().isPresent()
                ? source.getOntologyID().getOntologyIRI().get() : null;
        if (sourceIri == null) {
            // An anonymous ontology has no identity to record. Writing the document location
            // instead would name a file on one machine, which is worse than saying nothing.
            return changes;
        }
        OWLDataFactory factory = module.getOWLOntologyManager().getOWLDataFactory();
        changes.add(annotation(module, factory, SOURCE, sourceIri));
        if (source.getOntologyID().getVersionIRI().isPresent()) {
            changes.add(annotation(module, factory, DERIVED_FROM,
                    source.getOntologyID().getVersionIRI().get()));
        }
        if (isoDate != null && !isoDate.trim().isEmpty()) {
            changes.add(new AddOntologyAnnotation(module, factory.getOWLAnnotation(
                    factory.getOWLAnnotationProperty(EXTRACTED_ON),
                    factory.getOWLLiteral(isoDate.trim(), factory.getOWLDatatype(
                            IRI.create("http://www.w3.org/2001/XMLSchema#date"))))));
        }
        return changes;
    }

    /** The upstream ontology IRI recorded on a module, or null. */
    public static IRI sourceOf(OWLOntology module) {
        return iriValue(module, SOURCE);
    }

    /** The upstream version IRI recorded on a module, or null. */
    public static IRI cutFromOf(OWLOntology module) {
        return iriValue(module, DERIVED_FROM);
    }

    /** The extraction date recorded on a module, or empty. */
    public static String extractedOnOf(OWLOntology module) {
        if (module == null) {
            return "";
        }
        for (OWLAnnotation annotation : module.getAnnotations()) {
            if (EXTRACTED_ON.equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                        .getLiteral();
            }
        }
        return "";
    }

    /**
     * What can be said about a module, given whatever else is open.
     *
     * <p>The upstream is looked for among the ontologies already loaded rather than fetched.
     * Fetching would mean downloading ChEBI to read one line of its header, which is not a thing
     * to do behind somebody's back - so the answer is {@link Freshness#UNCHECKED} until they open
     * the upstream themselves, and it says so rather than pretending to know.
     *
     * @param manager the manager whose loaded ontologies to look in; may be null
     */
    public static Report check(OWLOntology module, OWLOntologyManager manager) {
        IRI source = sourceOf(module);
        IRI cutFrom = cutFromOf(module);
        String extractedOn = extractedOnOf(module);
        if (source == null && cutFrom == null) {
            return new Report(Freshness.UNRECORDED, null, null, null, extractedOn);
        }
        if (cutFrom == null) {
            return new Report(Freshness.UNVERSIONED_UPSTREAM, source, null, null, extractedOn);
        }
        OWLOntology upstream = source == null ? null : loadedUpstream(manager, source, module);
        if (upstream == null) {
            return new Report(Freshness.UNCHECKED, source, cutFrom, null, extractedOn);
        }
        IRI now = upstream.getOntologyID().getVersionIRI().isPresent()
                ? upstream.getOntologyID().getVersionIRI().get() : null;
        if (now == null) {
            return new Report(Freshness.UNVERSIONED_UPSTREAM, source, cutFrom, null, extractedOn);
        }
        return new Report(cutFrom.equals(now) ? Freshness.CURRENT : Freshness.MOVED,
                source, cutFrom, now, extractedOn);
    }

    /**
     * The loaded upstream ontology, or null.
     *
     * <p>Matched on the ontology IRI only, never the version IRI: the whole question is whether the
     * version has changed, and a match that required the version to be equal could only ever
     * answer "yes, current". The module itself is excluded, since a module cut from an ontology
     * and then given that ontology's IRI by mistake would otherwise report itself as its own
     * upstream and always look current.
     */
    private static OWLOntology loadedUpstream(OWLOntologyManager manager, IRI source,
            OWLOntology module) {
        if (manager == null) {
            return null;
        }
        for (OWLOntology candidate : manager.getOntologies()) {
            if (candidate.equals(module)) {
                continue;
            }
            if (candidate.getOntologyID().getOntologyIRI().isPresent()
                    && source.equals(candidate.getOntologyID().getOntologyIRI().get())) {
                return candidate;
            }
        }
        return null;
    }

    private static IRI iriValue(OWLOntology module, IRI property) {
        if (module == null) {
            return null;
        }
        for (OWLAnnotation annotation : module.getAnnotations()) {
            if (property.equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof IRI) {
                return (IRI) annotation.getValue();
            }
        }
        return null;
    }

    private static AddOntologyAnnotation annotation(OWLOntology module, OWLDataFactory factory,
            IRI property, IRI value) {
        return new AddOntologyAnnotation(module, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(property), value));
    }
}
