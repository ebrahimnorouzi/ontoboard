package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * What makes a published file a release rather than a copy.
 *
 * <p>The ODK layout gives a project two files: {@code mwo-edit.owl}, which people edit, and
 * {@code mwo.owl}, which the build produces. That is the "edit version and published version" the
 * request asked for, and it is already there. What is missing is everything that makes the second
 * one identifiable.
 *
 * <p>A release without a version IRI is not a release; it is a file with the same name as last
 * month's. Somebody who imports {@code .../mwo.owl} and gets a different ontology each time has no
 * way to say which one their results came from, no way to pin it, and no way to tell whether a
 * disagreement with a colleague is a disagreement about method or about which Tuesday they
 * downloaded it. OBO's answer is a dated version IRI - {@code <base>/releases/2026-08-30/mwo.owl} -
 * and a dated copy kept alongside, so the IRI resolves to something that never changes.
 *
 * <p>The scaffold's own {@code prepare_release} target copies the file unversioned, so each release
 * destroys the last. That is the gap this closes.
 *
 * <p>Pure OWL API; the date is a parameter rather than read from the clock, so the whole thing is
 * testable and so that a release rerun for a given date produces the same artefact.
 */
public final class Release {

    /** {@code YYYY-MM-DD}, which is what OBO release directories are named. */
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Release() {
    }

    /**
     * The version IRI for a release: {@code <base>/releases/<date>/<id>.owl}.
     *
     * <p>The OBO pattern, and the reason it is worth following exactly: a consumer who sees
     * {@code http://purl.obolibrary.org/obo/mwo/releases/2026-08-30/mwo.owl} can read the date out
     * of it without fetching anything, and the PURL layer resolves it to a file that will never
     * change again.
     *
     * @param ontologyIri the ontology's own IRI, e.g. {@code http://purl.obolibrary.org/obo/mwo.owl}
     * @param date {@code YYYY-MM-DD}
     * @throws IllegalArgumentException on a date that is not ISO, because a release directory
     *     named "today" or "30/08/2026" is one nothing can sort and nothing can parse
     */
    public static IRI versionIri(IRI ontologyIri, String date) {
        requireIsoDate(date);
        return versionIri(ontologyIri, OntologyVersion.dated(date));
    }

    /**
     * The version IRI for a release in whichever convention {@code version} is written.
     *
     * <p>{@link OntologyVersion} holds the two shapes and why they differ. The short of it: a
     * dated release publishes OBO's {@code <stem>/releases/<date>/<id>.owl}, and a semantic one
     * publishes {@code <stem>/<version>}, which is what MatWerk's own {@code mwo.owl} carries.
     * Cutting a semantic project's release under the dated pattern would start a second, parallel
     * version history under a different IRI shape - which is the one mistake a version IRI
     * cannot recover from once anybody has imported it.
     */
    public static IRI versionIri(IRI ontologyIri, OntologyVersion version) {
        if (ontologyIri == null) {
            throw new IllegalArgumentException(
                    "the ontology has no IRI, so a release IRI cannot be derived from it");
        }
        // Strips the .owl extension, not the last path segment. Cutting at the last slash
        // removed the ontology's own name - so mwo.owl released as
        // .../obo/releases/<date>/mwo.owl, in OBO's shared root, where every ontology built with
        // this plugin would collide. See ProjectIri for the three places that got this wrong and
        // the three different answers they produced for one release.
        String versionIri = version.versionIriUnder(ontologyIri.toString());
        if (versionIri == null) {
            throw new IllegalArgumentException(
                    "the ontology IRI '" + ontologyIri + "' has no name to build a release IRI "
                            + "from");
        }
        return IRI.create(versionIri);
    }

    /**
     * Where the dated copy is kept: {@code <project>/releases/<date>/<id>.owl}.
     *
     * <p>Kept rather than overwritten. A release that replaces the last one leaves the version IRI
     * of the previous release resolving to the current file, which is worse than having no version
     * IRI at all - it is one that lies.
     */
    public static File releaseFile(File projectRoot, String ontologyId, String version) {
        // Either convention: releases/2026-10-12/mwo.owl or releases/3.0.2/mwo.owl. Still
        // validated, because a directory named "today" or "final" is one nothing can sort and
        // nothing can parse, and the mistake stays invisible until somebody looks for the
        // previous release.
        OntologyVersion.of(version);
        return new File(new File(new File(projectRoot, "releases"), version),
                ontologyId + ".owl");
    }

    /**
     * Changes that stamp an ontology as the release of {@code date}.
     *
     * <p>Both halves are needed and they are different kinds of thing. {@code owl:versionIRI} lives
     * on the ontology's identity, so it takes a {@link SetOntologyID}; {@code owl:versionInfo} is
     * an ontology annotation. A release carrying only the second is one whose identity is still
     * indistinguishable from every other release.
     *
     * <p>Any previous {@code owl:versionInfo} is removed rather than added to: two version numbers
     * on one ontology is not a history, it is an ambiguity, and consumers pick whichever their
     * parser happened to return first.
     */
    public static List<OWLOntologyChange> stamp(OWLOntology ontology, String date) {
        requireIsoDate(date);
        return stamp(ontology, OntologyVersion.dated(date), null);
    }

    /**
     * Changes that stamp an ontology as {@code version}, in whichever convention it is written,
     * optionally linking the release it follows.
     *
     * <p>Three annotations, each a different kind of thing. {@code owl:versionIRI} lives on the
     * ontology's identity, so it takes a {@link SetOntologyID}. {@code owl:versionInfo} is an
     * ontology annotation holding the version as text. And {@code owl:priorVersion} points at
     * the release this one follows - which is what lets a consumer walk the chain backwards, and
     * what MatWerk's own custom Makefile adds
     * ({@code --link-annotation owl:priorVersion $(ONTBASE)/$(PRIOR_VERSION)}).
     *
     * <p>Any previous {@code owl:versionInfo} or {@code owl:priorVersion} is removed rather than
     * added to: two version numbers on one ontology is not a history, it is an ambiguity, and
     * consumers pick whichever their parser happened to return first.
     *
     * @param previous the release this one follows, or null when it is the first
     */
    public static List<OWLOntologyChange> stamp(OWLOntology ontology, OntologyVersion version,
            OntologyVersion previous) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        if (ontology == null) {
            return changes;
        }
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        com.google.common.base.Optional<IRI> ontologyIri =
                ontology.getOntologyID().getOntologyIRI();
        if (!ontologyIri.isPresent()) {
            throw new IllegalArgumentException("This ontology has no IRI of its own, so it cannot "
                    + "carry a version IRI. Give it one in the ontology header before releasing.");
        }
        changes.add(new SetOntologyID(ontology, new OWLOntologyID(ontologyIri,
                com.google.common.base.Optional.of(versionIri(ontologyIri.get(), version)))));

        for (OWLAnnotation existing : ontology.getAnnotations()) {
            IRI property = existing.getProperty().getIRI();
            if (OWLRDFVocabulary.OWL_VERSION_INFO.getIRI().equals(property)
                    || PRIOR_VERSION.equals(property)) {
                changes.add(new RemoveOntologyAnnotation(ontology, existing));
            }
        }
        changes.add(new AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_VERSION_INFO.getIRI()),
                factory.getOWLLiteral(version.getText()))));
        if (previous != null) {
            String priorIri = previous.versionIriUnder(ontologyIri.get().toString());
            if (priorIri != null) {
                changes.add(new AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                        factory.getOWLAnnotationProperty(PRIOR_VERSION),
                        IRI.create(priorIri))));
            }
        }
        return changes;
    }

    /** {@code owl:priorVersion}, which OWL API 4 does not put in its vocabulary enum. */
    private static final IRI PRIOR_VERSION =
            IRI.create("http://www.w3.org/2002/07/owl#priorVersion");

    /** The {@code owl:priorVersion} an ontology currently carries, or null. */
    public static IRI priorVersionOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (PRIOR_VERSION.equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof IRI) {
                return (IRI) annotation.getValue();
            }
        }
        return null;
    }

    /**
     * The version this ontology already declares, or null.
     *
     * <p>What the release dialog should default to. Read from the artefact rather than from a
     * preference, so that opening somebody else's project and releasing it continues their
     * version history instead of starting a second one beside it.
     */
    public static OntologyVersion declaredVersionOf(OWLOntology ontology) {
        IRI versionIri = versionIriOf(ontology);
        return OntologyVersion.declaredBy(versionInfoOf(ontology),
                versionIri == null ? null : versionIri.toString());
    }

    /** The version IRI an ontology currently carries, or null. */
    public static IRI versionIriOf(OWLOntology ontology) {
        if (ontology == null || !ontology.getOntologyID().getVersionIRI().isPresent()) {
            return null;
        }
        return ontology.getOntologyID().getVersionIRI().get();
    }

    /** The {@code owl:versionInfo} an ontology currently carries, or null. */
    public static String versionInfoOf(OWLOntology ontology) {
        if (ontology == null) {
            return null;
        }
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (OWLRDFVocabulary.OWL_VERSION_INFO.getIRI()
                    .equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                        .getLiteral();
            }
        }
        return null;
    }

    /**
     * Why releasing today would overwrite something, or null when it is safe.
     *
     * <p>Re-releasing on the same date is ordinary - a mistake found an hour later - and it is also
     * the one case where the dated copy is not a new file. Saying so lets the caller ask rather
     * than silently replacing an artefact whose version IRI is already published.
     */
    public static String wouldOverwrite(File projectRoot, String ontologyId, String date) {
        File existing = releaseFile(projectRoot, ontologyId, date);
        if (!existing.isFile()) {
            return null;
        }
        return "A release for " + date + " already exists at " + existing.getAbsolutePath()
                + ". Its version IRI is already published, so anybody who has downloaded it has "
                + "a file that will no longer match. Releasing again replaces it.";
    }

    /** The ontology id an ODK project uses in file names, from the edit file's name. */
    public static String ontologyIdFrom(File editFile) {
        if (editFile == null) {
            return "ontology";
        }
        String name = editFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("-edit")) {
            name = name.substring(0, name.length() - "-edit".length());
        }
        return name.isEmpty() ? "ontology" : name;
    }

    private static void requireIsoDate(String date) {
        if (date == null || !ISO_DATE.matcher(date).matches()) {
            throw new IllegalArgumentException("a release date must be YYYY-MM-DD, not '"
                    + date + "' - release directories are sorted and parsed by that shape");
        }
    }
}
