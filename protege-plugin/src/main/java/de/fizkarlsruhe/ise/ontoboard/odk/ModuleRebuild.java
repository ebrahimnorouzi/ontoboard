package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
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
 *   <li><b>The module declares what it uses.</b> Its file does, because the writer adds the
 *       declarations an extraction leaves out; a rebuilt copy without them took them out of the
 *       open one, and the next build failed the OWL 2 DL check (1.135.1).
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
        module.getOWLOntologyManager().applyChanges(declarationsMissingFrom(module));
        return extracted;
    }

    /**
     * A declaration for each entity the module uses and does not declare (1.135.1, Windows study
     * rehearsal).
     *
     * <p>An extraction declares the terms it cuts, not the annotation properties their annotations
     * use, nor dcterms' and prov's that the provenance stamp adds. The OWL API writer declares those
     * when it saves, so the file has them - and {@link #changesToMatch} then took them out of the
     * copy Prot&eacute;g&eacute; has open, to match the extraction. A tester took one term out of
     * IAO's module, its copy went from 664 axioms with 22 annotation properties declared to 622
     * with none, and the next test build failed with 375 "Use of undeclared annotation property".
     * With these the open copy is what its file says.
     *
     * <p>A legal pun - a class that is also an individual, say - is declared as each thing it is,
     * as the writer declares it. It was declared neither way (1.135.1 review), so matching the open
     * copy took the file's declarations out of it, and the DL check reported "Use of undeclared
     * class". What OWL 2 DL does not allow one IRI to be at once is not declared: no declaration
     * makes that legal, the DL check names the pun itself, and the 4.5.9 writer's file of it reads
     * back other axioms than were written - a data property's domain lost, an annotation turned
     * into a data property value.
     */
    static List<OWLOntologyChange> declarationsMissingFrom(OWLOntology module) {
        Set<OWLEntity> used = new LinkedHashSet<OWLEntity>(module.getSignature());
        for (OWLAnnotation annotation : module.getAnnotations()) {
            used.addAll(annotation.getSignature());
        }
        Map<IRI, Set<EntityType<?>>> kinds = new HashMap<IRI, Set<EntityType<?>>>();
        for (OWLEntity entity : used) {
            Set<EntityType<?>> of = kinds.get(entity.getIRI());
            if (of == null) {
                of = new HashSet<EntityType<?>>();
                kinds.put(entity.getIRI(), of);
            }
            of.add(entity.getEntityType());
        }
        OWLDataFactory factory = module.getOWLOntologyManager().getOWLDataFactory();
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLEntity entity : used) {
            if (!entity.isBuiltIn() && !module.isDeclared(entity)
                    && !punnedIllegally(entity.getEntityType(), kinds.get(entity.getIRI()))) {
                changes.add(new AddAxiom(module, factory.getOWLDeclarationAxiom(entity)));
            }
        }
        return changes;
    }

    /**
     * Whether OWL 2 DL forbids one IRI to be {@code kind} and one of the {@code others} at once:
     * two of object, data and annotation property, or a class and a datatype. Every other pun is
     * legal.
     */
    static boolean punnedIllegally(EntityType<?> kind, Set<EntityType<?>> others) {
        for (EntityType<?> other : others) {
            if (isProperty(kind) && isProperty(other) && !kind.equals(other)) {
                return true;
            }
            if (EntityType.CLASS.equals(kind) && EntityType.DATATYPE.equals(other)
                    || EntityType.DATATYPE.equals(kind) && EntityType.CLASS.equals(other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProperty(EntityType<?> kind) {
        return EntityType.OBJECT_PROPERTY.equals(kind) || EntityType.DATA_PROPERTY.equals(kind)
                || EntityType.ANNOTATION_PROPERTY.equals(kind);
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
