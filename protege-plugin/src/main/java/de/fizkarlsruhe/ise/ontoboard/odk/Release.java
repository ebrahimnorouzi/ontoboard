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
        if (ontologyIri == null) {
            throw new IllegalArgumentException(
                    "the ontology has no IRI, so a release IRI cannot be derived from it");
        }
        String iri = ontologyIri.toString();
        int lastSlash = iri.lastIndexOf('/');
        String stem = lastSlash > 0 ? iri.substring(0, lastSlash) : iri;
        String file = lastSlash > 0 && lastSlash < iri.length() - 1
                ? iri.substring(lastSlash + 1) : "ontology.owl";
        return IRI.create(stem + "/releases/" + date + "/" + file);
    }

    /**
     * Where the dated copy is kept: {@code <project>/releases/<date>/<id>.owl}.
     *
     * <p>Kept rather than overwritten. A release that replaces the last one leaves the version IRI
     * of the previous release resolving to the current file, which is worse than having no version
     * IRI at all - it is one that lies.
     */
    public static File releaseFile(File projectRoot, String ontologyId, String date) {
        requireIsoDate(date);
        return new File(new File(new File(projectRoot, "releases"), date), ontologyId + ".owl");
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
                com.google.common.base.Optional.of(versionIri(ontologyIri.get(), date)))));

        for (OWLAnnotation existing : ontology.getAnnotations()) {
            if (OWLRDFVocabulary.OWL_VERSION_INFO.getIRI()
                    .equals(existing.getProperty().getIRI())) {
                changes.add(new RemoveOntologyAnnotation(ontology, existing));
            }
        }
        changes.add(new AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_VERSION_INFO.getIRI()),
                factory.getOWLLiteral(date))));
        return changes;
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
