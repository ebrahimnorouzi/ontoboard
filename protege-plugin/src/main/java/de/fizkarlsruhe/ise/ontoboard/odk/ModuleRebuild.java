package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.util.AutoIRIMapper;

/**
 * Rebuilding one import module from its terms, the same way wherever it is done.
 *
 * <p>Shared by Refresh imports and Imported terms, for two things rebuilding a module has to get
 * right and once did not:
 *
 * <ul>
 *   <li><b>The module keeps its IRI.</b> Refresh imports gave a rebuilt module the address of its
 *       own file as its IRI - {@code file:/Users/...} - so the edit file's import no longer named
 *       it, and the next release carried a path on one person's machine. The IRI the module
 *       already declares is kept; only a module that has none is given one.
 *   <li><b>What Prot&eacute;g&eacute; has open changes with the file.</b> Writing only the file
 *       left the open copy as it was until the project was reopened, so a term just added could
 *       not be used yet. {@link #changesToMatch} is what turns the open copy into the rebuilt one.
 * </ul>
 *
 * <p>Pure OWL API; no Prot&eacute;g&eacute; types and no Swing.
 */
public final class ModuleRebuild {

    private ModuleRebuild() {
    }

    /**
     * The ontology IRI a module file declares, or null when it declares none or cannot be read.
     *
     * <p>Read with its imports pointed nowhere, so asking a module its name never downloads
     * whatever it imports.
     */
    public static IRI declaredIriOf(File moduleFile) {
        if (moduleFile == null || !moduleFile.isFile()) {
            return null;
        }
        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        final File nowhere = new File(moduleFile.getAbsoluteFile().getParentFile(),
                ".ontoboard-no-such-import");
        reader.getIRIMappers().add(iri -> IRI.create(nowhere));
        reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        try {
            OWLOntology module = reader.loadOntologyFromOntologyDocument(moduleFile);
            return module.getOntologyID().getOntologyIRI().isPresent()
                    ? module.getOntologyID().getOntologyIRI().get() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Extracts the module and records where it came from, as Import terms does.
     *
     * @param moduleIri the IRI the module has now; see {@link #declaredIriOf}
     * @param isoDate today, {@code YYYY-MM-DD}, for the extraction date
     */
    public static TermExtract.Result extract(OWLOntology upstream, List<IRI> terms,
            TermExtract.Method method, IRI moduleIri, String isoDate) {
        TermExtract.Result extracted = TermExtract.run(upstream, terms, method, moduleIri);
        OWLOntology module = extracted.getModule();
        module.getOWLOntologyManager().applyChanges(
                ImportProvenance.stamp(module, upstream, isoDate));
        return extracted;
    }

    /**
     * The ontology a module is cut from: the project's mirror when it holds that ontology, which
     * needs no network; otherwise read from where the import says it comes from.
     *
     * <p>The mirror is asked first whether it has the ontology, rather than loading the address
     * through it: an address the mirror does not have would otherwise fall through to the OWL
     * API's own download, with its five retries.
     */
    public static OWLOntology upstream(File projectRoot, String from,
            WebOntology.Attempt attempt, WebOntology.Progress progress, List<String> notes) {
        if (projectRoot != null && ImportModules.hasMirror(projectRoot)) {
            AutoIRIMapper mirror = new AutoIRIMapper(ImportModules.mirrorDirectoryIn(projectRoot),
                    true);
            IRI address = OntologySource.toIri(from);
            if (mirror.getDocumentIRI(address) != null) {
                try {
                    OWLOntologyManager offline = OWLManager.createOWLOntologyManager();
                    offline.getIRIMappers().add(mirror);
                    OWLOntology fromMirror = offline.loadOntology(address);
                    notes.add("Read " + from + " from the project's mirror, "
                            + ImportModules.MIRROR_DIRECTORY + ".");
                    return fromMirror;
                } catch (Exception unreadable) {
                    notes.add("The mirror's copy of " + from + " could not be read ("
                            + unreadable.getMessage() + "), so it was read from the source.");
                }
            }
        }
        return OntologySource.loadLeavingOutMissingImports(OWLManager.createOWLOntologyManager(),
                OntologySource.toIri(from), attempt, progress, notes);
    }

    /**
     * The changes that make {@code open} say what {@code rebuilt} says: axioms, ontology
     * annotations and imports, each removed or added. Nothing for what both already have, so a
     * rebuild that changed nothing changes nothing.
     */
    public static List<OWLOntologyChange> changesToMatch(OWLOntology open, OWLOntology rebuilt) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        Set<OWLAxiom> after = rebuilt.getAxioms();
        for (OWLAxiom axiom : open.getAxioms()) {
            if (!after.contains(axiom)) {
                changes.add(new RemoveAxiom(open, axiom));
            }
        }
        Set<OWLAxiom> before = open.getAxioms();
        for (OWLAxiom axiom : after) {
            if (!before.contains(axiom)) {
                changes.add(new AddAxiom(open, axiom));
            }
        }
        Set<OWLAnnotation> annotationsAfter = new HashSet<OWLAnnotation>(rebuilt.getAnnotations());
        for (OWLAnnotation annotation : open.getAnnotations()) {
            if (!annotationsAfter.contains(annotation)) {
                changes.add(new RemoveOntologyAnnotation(open, annotation));
            }
        }
        for (OWLAnnotation annotation : annotationsAfter) {
            if (!open.getAnnotations().contains(annotation)) {
                changes.add(new AddOntologyAnnotation(open, annotation));
            }
        }
        Set<OWLImportsDeclaration> importsAfter = rebuilt.getImportsDeclarations();
        for (OWLImportsDeclaration declaration : open.getImportsDeclarations()) {
            if (!importsAfter.contains(declaration)) {
                changes.add(new RemoveImport(open, declaration));
            }
        }
        for (OWLImportsDeclaration declaration : importsAfter) {
            if (!open.getImportsDeclarations().contains(declaration)) {
                changes.add(new AddImport(open, declaration));
            }
        }
        return changes;
    }

    /**
     * The module Prot&eacute;g&eacute; has open from this file, among {@code ontology}'s imports,
     * or null when it is not open.
     */
    public static OWLOntology openFrom(OWLOntology ontology, File moduleFile) {
        if (ontology == null || moduleFile == null) {
            return null;
        }
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        File wanted = moduleFile.getAbsoluteFile();
        for (OWLOntology part : ontology.getImportsClosure()) {
            IRI document = manager.getOntologyDocumentIRI(part);
            if (document != null && "file".equalsIgnoreCase(document.getScheme())) {
                try {
                    if (new File(document.toURI()).getAbsoluteFile().equals(wanted)) {
                        return part;
                    }
                } catch (IllegalArgumentException notAFile) {
                    continue;
                }
            }
        }
        return null;
    }
}
