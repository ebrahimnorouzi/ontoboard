package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Turning what a user typed into an ontology to read from.
 *
 * <p>The field says "a file or a URL" because both are what people actually have: a downloaded
 * {@code chebi.owl} on disk, or the OBO PURL they copied out of the documentation. Making them
 * choose a kind first, or offering only a file chooser, means the PURL case ends with someone
 * downloading a 700MB file by hand.
 *
 * <p>The other job is not downloading something twice. An OBO PURL takes minutes to fetch and
 * Protege may already have the ontology open - as an import of the file being edited, most
 * commonly. Reusing it is the difference between an extraction that returns immediately and one
 * that appears to hang.
 *
 * <p>Pure OWL API; no Protege types and no Swing, so the matching is testable.
 */
public final class OntologySource {

    private OntologySource() {
    }

    /**
     * What was typed, as an IRI to load.
     *
     * @throws RobotException if it is neither a URL nor a file that exists, saying which, because
     *     "could not load ontology" for a mistyped path is a message that helps nobody
     */
    public static IRI toIri(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new RobotException("No ontology was named to take terms from.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("ftp://")) {
            return IRI.create(trimmed);
        }
        if (lower.startsWith("file:")) {
            return IRI.create(trimmed);
        }
        File file = new File(trimmed);
        if (!file.isFile()) {
            throw new RobotException("There is no file at " + file.getAbsolutePath()
                    + ". Give a path to an ontology file, or its web address - "
                    + "https://example.org/ontology.owl, for instance.");
        }
        return IRI.create(file.toURI());
    }

    /**
     * An ontology the manager already has for this IRI, or null.
     *
     * <p>Matched three ways because the same ontology is known by three names: its ontology IRI
     * ({@code http://purl.obolibrary.org/obo/iao.owl}), its version IRI, and wherever the document
     * was actually read from - which for a project's own import module is a local file whose path
     * looks nothing like the IRI inside it.
     */
    public static OWLOntology findAlreadyLoaded(OWLOntologyManager manager, IRI iri) {
        if (manager == null || iri == null) {
            return null;
        }
        for (OWLOntology ontology : manager.getOntologies()) {
            if (ontology.getOntologyID().getOntologyIRI().isPresent()
                    && iri.equals(ontology.getOntologyID().getOntologyIRI().get())) {
                return ontology;
            }
            if (ontology.getOntologyID().getVersionIRI().isPresent()
                    && iri.equals(ontology.getOntologyID().getVersionIRI().get())) {
                return ontology;
            }
            try {
                if (iri.equals(manager.getOntologyDocumentIRI(ontology))) {
                    return ontology;
                }
            } catch (RuntimeException noDocumentIri) {
                // An ontology the manager has no document IRI for simply cannot match this way.
                continue;
            }
        }
        return null;
    }

    /**
     * The ontology at this IRI, loaded if it is not already.
     *
     * <p>Into the given manager, which for an extraction is deliberately <em>not</em> Protege's:
     * loading a 700MB ontology into the editing manager to take four terms out of it would leave
     * it in the ontology list, in the imports dialog, and in memory for the rest of the session.
     *
     * @throws RobotException with the reason, since "load failed" for an unreachable PURL and for
     *     a malformed file need different responses from a user
     */
    public static OWLOntology load(OWLOntologyManager manager, IRI iri) {
        return load(manager, iri, null, null, null);
    }

    /**
     * {@link #load}, with a way to stop it and to watch it. Sentences worth showing the user -
     * that the address was a page about the ontology, that an import could not be read - are
     * added to {@code notes} when it is not null.
     *
     * <p>A web address is read by {@link WebOntology}, which gives up on a silent server after
     * {@link WebOntology#SILENT_SECONDS} instead of the OWL API's minutes - unless the manager
     * maps that address to somewhere else, a catalog's local copy say, which is then what loads.
     */
    public static OWLOntology load(OWLOntologyManager manager, IRI iri,
            WebOntology.Attempt attempt, WebOntology.Progress progress, List<String> notes) {
        OWLOntology alreadyOpen = findAlreadyLoaded(manager, iri);
        if (alreadyOpen != null) {
            return alreadyOpen;
        }
        if (WebOntology.isWebAddress(iri) && !isMappedElsewhere(manager, iri)) {
            return WebOntology.load(manager, iri, attempt, progress,
                    notes == null ? new ArrayList<String>() : notes);
        }
        try {
            File local = localFileFor(manager, iri);
            return local != null ? loadFile(manager, local) : manager.loadOntology(iri);
        } catch (LinkageError incompatible) {
            throw new RobotException("Could not load " + iri
                    + " with this Protege's OWL API.", incompatible);
        } catch (Exception failed) {
            throw new RobotException("Could not load " + iri + ": "
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                            : failed.getMessage()), failed);
        }
    }

    /**
     * {@link #load}, except that an import which cannot be fetched is left out instead of failing
     * the whole load. {@link #missingImportsOf} then says which were left out.
     *
     * <p>For reading terms from: one unreachable import - a server gone, a PURL that was never
     * registered - otherwise makes an ontology whose own terms are all there impossible to search
     * or take a term from, and the message names the import, not the ontology the user asked for.
     */
    public static OWLOntology loadLeavingOutMissingImports(OWLOntologyManager manager, IRI iri) {
        return loadLeavingOutMissingImports(manager, iri, null, null, null);
    }

    /** {@link #loadLeavingOutMissingImports}, stoppable and watchable as {@link #load} is. */
    public static OWLOntology loadLeavingOutMissingImports(OWLOntologyManager manager, IRI iri,
            WebOntology.Attempt attempt, WebOntology.Progress progress, List<String> notes) {
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return load(manager, iri, attempt, progress, notes);
    }

    /**
     * An ontology document read from a file, without the file being held open afterwards.
     *
     * <p>{@code loadOntologyFromOntologyDocument(File)}, and {@code loadOntology} on an address a
     * mapper sends to a file, leave a handle on the file whenever the OWL API has to guess the
     * format and a parser fails before one succeeds - Turtle, functional syntax, OWL/XML, all but
     * RDF/XML. Measured with lsof: one handle left on a Turtle file read either way, none when
     * the format is named or the file is RDF/XML; only the garbage collector closes it. On
     * Windows an open file cannot be deleted or replaced, so a Turtle file OntoBoard had read
     * stayed locked until then, and seven tests failed on Windows CI in 1.133.0 because JUnit
     * could not remove their temporary folders.
     *
     * <p>The way out is the one {@code PatternIndex.load} measured: a stream this method closes,
     * under a document IRI that is not the file's, so that when the OWL API falls back to opening
     * the document IRI itself after a parser fails, there is nothing there to hold. The file is
     * then recorded as the ontology's document, which is what OntoBoard and Protege ask for later.
     * A relative IRI inside the file resolves against the stand-in rather than the file's path;
     * an IRI that depends on where a file happens to sit names nothing anyone can find anyway.
     */
    public static OWLOntology loadFile(OWLOntologyManager manager, File file)
            throws IOException, OWLOntologyCreationException {
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            OWLOntology ontology;
            try {
                ontology = manager.loadOntologyFromOntologyDocument(
                        new StreamDocumentSource(stream, standInFor(file)));
            } catch (OWLOntologyAlreadyExistsException already) {
                return manager.getOntology(already.getOntologyID());
            }
            manager.setOntologyDocumentIRI(ontology, IRI.create(file));
            return ontology;
        }
    }

    /** A file: IRI with the file's name, in a folder that does not exist. */
    static IRI standInFor(File file) {
        return IRI.create(new File("/ontoboard-reading", file.getName()).toURI());
    }

    /**
     * The local file this address is read from: the file a mapper sends it to, or the address
     * itself when it is a file: IRI - null when it is neither, or the file is not there.
     */
    static File localFileFor(OWLOntologyManager manager, IRI iri) {
        IRI document = iri;
        for (org.semanticweb.owlapi.model.OWLOntologyIRIMapper mapper : manager.getIRIMappers()) {
            IRI mapped = mapper.getDocumentIRI(iri);
            if (mapped != null) {
                document = mapped;
                break;
            }
        }
        if (!"file".equalsIgnoreCase(document.getScheme())) {
            return null;
        }
        try {
            File file = new File(document.toURI());
            return file.isFile() ? file : null;
        } catch (IllegalArgumentException notAPlainFile) {
            return null;
        }
    }

    /** Whether one of the manager's IRI mappers sends this address somewhere else. */
    private static boolean isMappedElsewhere(OWLOntologyManager manager, IRI iri) {
        for (org.semanticweb.owlapi.model.OWLOntologyIRIMapper mapper : manager.getIRIMappers()) {
            IRI document = mapper.getDocumentIRI(iri);
            if (document != null && !document.equals(iri)) {
                return true;
            }
        }
        return false;
    }

    /** The imports declared anywhere in {@code ontology}'s closure that are not loaded. */
    public static List<IRI> missingImportsOf(OWLOntology ontology) {
        List<IRI> missing = new ArrayList<IRI>();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLImportsDeclaration declaration : part.getImportsDeclarations()) {
                if (manager.getImportedOntology(declaration) == null
                        && !missing.contains(declaration.getIRI())) {
                    missing.add(declaration.getIRI());
                }
            }
        }
        return missing;
    }

    /** "1 of its imports could not be loaded: ..." for a status line, or null for none. */
    public static String describeMissingImports(List<IRI> missing) {
        if (missing.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(missing.size() == 1
                ? "1 of its imports could not be loaded, so its terms are not here: "
                : missing.size() + " of its imports could not be loaded, so their terms are not"
                        + " here: ");
        for (int i = 0; i < missing.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(missing.get(i));
        }
        return text.toString();
    }
}
