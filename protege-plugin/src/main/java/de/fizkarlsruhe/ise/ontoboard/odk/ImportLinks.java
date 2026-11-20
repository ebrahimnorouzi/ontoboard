package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

/**
 * Imports Protégé read and left out: the catalog gives a file for the import, Protégé loaded
 * it, and the file calls itself by another name than the one it is imported by.
 *
 * <p>nfdicore's import modules call themselves {@code .../ontology/imports/<m>_import.owl} - its
 * release.sh builds them with {@code ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology} - while its
 * edit file imports {@code .../ontology/nfdicore/imports/<m>_import.owl}. ROBOT and ODK's make
 * take the file the catalog gives for an import, whatever it calls itself. Protégé 5.6.9 reads
 * the files with a manager of its own and moves the ontologies into the workspace's, which then
 * looks each import up by its name and finds none: the closure held 3 of nfdicore's 11
 * ontologies, and Measure, the board, the reasoner and the labels of every imported object
 * property went without the eight modules (1.155.0).
 *
 * <p>{@link #link} puts them in as ROBOT's loading does: the workspace's manager is asked to load
 * each such import and finds the ontology it already has from that file. Nothing is read again
 * and no ontology changes, so nothing is left unsaved; the files keep their names, and Refresh
 * imports gives the modules ODK's names again.
 *
 * <p>{@link #forgetRenamed} keeps them in when a module is read again under another name.
 */
public final class ImportLinks {

    /** An import that the catalog's file answers, under another name, and that is not linked. */
    public static final class Unlinked {
        private final OWLOntology importer;
        private final OWLImportsDeclaration declaration;
        private final OWLOntology loaded;
        private final File file;

        Unlinked(OWLOntology importer, OWLImportsDeclaration declaration, OWLOntology loaded,
                File file) {
            this.importer = importer;
            this.declaration = declaration;
            this.loaded = loaded;
            this.file = file;
        }

        /** The name it is imported by. */
        public IRI imported() {
            return declaration.getIRI();
        }

        /** The name the file gives its ontology. */
        public IRI calledItself() {
            return loaded.getOntologyID().getOntologyIRI().get();
        }

        /** The file the catalog gives for the import. */
        public File file() {
            return file;
        }

        /** The ontology that imports it. */
        public OWLOntology importer() {
            return importer;
        }

        @Override
        public String toString() {
            return imported() + " -> " + file + " (" + calledItself() + ")";
        }
    }

    private ImportLinks() {
    }

    /**
     * The imports of the ontology, and of what it imports, that its manager has not linked and
     * that the catalog maps to a file the manager has read - an ontology that calls itself by
     * another name. An import whose file is not there, or was not read, did not arrive: that is
     * {@link ImportHealth}'s to say.
     *
     * @param catalog {@code catalog-v001.xml} beside the ontology; null or absent, nothing
     */
    public static List<Unlinked> of(OWLOntology ontology, File catalog) {
        String xml = read(catalog);
        if (xml == null) {
            return Collections.emptyList();
        }
        File folder = catalog.getAbsoluteFile().getParentFile();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        List<Unlinked> found = new ArrayList<Unlinked>();
        for (OWLOntology importer : ontology.getImportsClosure()) {
            for (OWLImportsDeclaration declaration : importer.getImportsDeclarations()) {
                if (manager.getImportedOntology(declaration) != null) {
                    continue;
                }
                String mapped = Catalog.entryFor(xml, declaration.getIRI().toString());
                File file = mapped == null ? null : new File(folder, mapped);
                OWLOntology loaded = file == null || !file.isFile() ? null
                        : readFrom(manager, file);
                if (loaded == null || !loaded.getOntologyID().getOntologyIRI().isPresent()
                        || loaded.getOntologyID().getOntologyIRI().get()
                                .equals(declaration.getIRI())) {
                    continue;
                }
                found.add(new Unlinked(importer, declaration, loaded, file));
            }
        }
        return found;
    }

    /**
     * Forgets what the manager still links to the old name of an ontology that was renamed.
     *
     * <p>Protégé reads a file again by renaming the open ontology to what the file calls it now
     * ({@code OntologyReloader}: a {@link SetOntologyID}). OWL API's manager moves the ontology
     * to its new name and keeps which import it answers - and that the import was asked for -
     * under the old one, which no ontology has: the import finds nothing, and the module drops
     * out of the closure. A Build that rebuilt nfdicore's modules under ODK's names left 8 of
     * its 10 imports out until the project was opened again (Phase 7, 2026-11-20).
     * {@code removeOntology} of a name no ontology has takes out no ontology - only those
     * records (OWL API 4.5.9 and 4.5.29). The import then finds its module by its name, or
     * {@link #link} links it again. Nothing is read again and no ontology changes.
     *
     * @param changes changes the manager applied; all but renames are passed over
     * @return how many old names were forgotten - none that an ontology has now
     */
    public static int forgetRenamed(OWLOntologyManager manager,
            List<? extends OWLOntologyChange> changes) {
        Set<OWLOntologyID> forgotten = new LinkedHashSet<OWLOntologyID>();
        OWLOntology renamed = null;
        for (OWLOntologyChange change : changes) {
            if (!(change instanceof SetOntologyID)) {
                continue;
            }
            OWLOntologyID was = ((SetOntologyID) change).getOriginalOntologyID();
            if (forgotten.contains(was) || isHeld(manager, was)) {
                continue;
            }
            manager.removeOntology(was);
            forgotten.add(was);
            renamed = change.getOntology();
        }
        if (renamed != null && manager.contains(renamed)) {
            // The closures a listener before this one asked for were made with the old links.
            forgetClosures(manager, renamed);
        }
        return forgotten.size();
    }

    /** Whether an ontology of the manager has this name now. */
    private static boolean isHeld(OWLOntologyManager manager, OWLOntologyID id) {
        for (OWLOntology one : manager.getOntologies()) {
            if (one.getOntologyID().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Links each import {@link #of} finds - and what those import in turn - to the ontology
     * already read from its file, as ROBOT's loading links it.
     *
     * @return the imports linked, in the order they were
     */
    public static List<Unlinked> link(OWLOntology ontology, File catalog) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        List<Unlinked> linked = new ArrayList<Unlinked>();
        // A module linked can bring in imports of its own: asked again while any is linked.
        for (int round = 0; round <= manager.getOntologies().size(); round++) {
            int before = linked.size();
            for (Unlinked one : of(ontology, catalog)) {
                if (linkOne(manager, one)) {
                    linked.add(one);
                }
            }
            if (linked.size() == before) {
                break;
            }
            forgetClosures(manager, linked.get(linked.size() - 1).loaded);
        }
        return linked;
    }

    /**
     * The manager asked to load the import while a mapper names the file the ontology was read
     * from: it finds that ontology among those it has, and records which import it answers.
     */
    private static boolean linkOne(OWLOntologyManager manager, Unlinked one) {
        IRI document = manager.getOntologyDocumentIRI(one.loaded);
        SimpleIRIMapper mapper = new SimpleIRIMapper(one.imported(), document);
        manager.getIRIMappers().add(mapper);
        try {
            manager.makeLoadImportRequest(one.declaration, new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        } catch (RuntimeException notLinked) {
            return false;
        } finally {
            manager.getIRIMappers().remove(mapper);
        }
        return manager.getImportedOntology(one.declaration) == one.loaded;
    }

    /**
     * The manager keeps each ontology's imports closure once asked; setting a document IRI -
     * here the one it has - empties that store, and nothing else changes.
     */
    private static void forgetClosures(OWLOntologyManager manager, OWLOntology any) {
        manager.setOntologyDocumentIRI(any, manager.getOntologyDocumentIRI(any));
    }

    /** The ontology the manager read from this file, or null. */
    static OWLOntology readFrom(OWLOntologyManager manager, File file) {
        File wanted = canonical(file);
        for (OWLOntology one : manager.getOntologies()) {
            IRI document = manager.getOntologyDocumentIRI(one);
            if (document == null || !"file".equalsIgnoreCase(document.getScheme())) {
                continue;
            }
            try {
                if (canonical(new File(URI.create(document.toString()))).equals(wanted)) {
                    return one;
                }
            } catch (IllegalArgumentException notAFile) {
                // A file IRI no File can be made of is not this file.
            }
        }
        return null;
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException cannot) {
            return file.getAbsoluteFile();
        }
    }

    private static String read(File catalog) {
        if (catalog == null || !catalog.isFile()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(catalog.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * For Check imports: each file that answers an import of the ontology, or of what it
     * imports, and calls itself by another name than the one it is imported by - once for each
     * file, as "imports/x.owl calls itself A and is imported as B". An import by the version IRI
     * the file gives is no other name. Imports left out are not named: they did not arrive.
     *
     * @param catalog {@code catalog-v001.xml} beside the ontology; its folder shortens the paths
     */
    public static List<String> namedOtherwise(OWLOntology ontology, File catalog) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        File folder = catalog == null ? null : catalog.getAbsoluteFile().getParentFile();
        Set<File> files = new LinkedHashSet<File>();
        List<String> named = new ArrayList<String>();
        for (OWLOntology importer : ontology.getImportsClosure()) {
            for (OWLImportsDeclaration declaration : importer.getImportsDeclarations()) {
                OWLOntology imported = manager.getImportedOntology(declaration);
                if (imported == null || !imported.getOntologyID().getOntologyIRI().isPresent()) {
                    continue;
                }
                IRI name = imported.getOntologyID().getOntologyIRI().get();
                IRI version = imported.getOntologyID().getVersionIRI().orNull();
                if (name.equals(declaration.getIRI()) || declaration.getIRI().equals(version)) {
                    continue;
                }
                File file = fileOf(manager.getOntologyDocumentIRI(imported));
                if (file == null || !files.add(canonical(file))) {
                    continue;
                }
                named.add(shownFrom(file, folder) + " calls itself " + name
                        + " and is imported as " + declaration.getIRI());
            }
        }
        return named;
    }

    private static File fileOf(IRI document) {
        if (document == null || !"file".equalsIgnoreCase(document.getScheme())) {
            return null;
        }
        try {
            return new File(URI.create(document.toString()));
        } catch (IllegalArgumentException notAFile) {
            return null;
        }
    }

    /**
     * What the person reads once the imports are linked: how many, the first by its file, the
     * name it calls itself and the one it is imported by, that ROBOT and ODK's make read them so,
     * and how the files get ODK's names.
     */
    public static String said(List<Unlinked> linked, File editFile) {
        if (linked.isEmpty()) {
            return "";
        }
        // Counted by file: nfdicore's edit file, imports-edit and nfdicore-main import the same
        // eight modules - fifteen imports.
        Set<File> files = new LinkedHashSet<File>();
        for (Unlinked one : linked) {
            files.add(canonical(one.file));
        }
        int count = files.size();
        boolean one = count == 1;
        Unlinked first = linked.get(0);
        String name = editFile == null ? "this ontology" : editFile.getName();
        File folder = editFile == null ? null : editFile.getAbsoluteFile().getParentFile();
        return count + (one ? " file that " : " files that ") + name + " imports "
                + (one ? "calls itself" : "call themselves") + " by another name than the one "
                + (one ? "it is" : "they are") + " imported by: " + shownFrom(first.file, folder)
                + " calls itself " + first.calledItself() + " and is imported as "
                + first.imported() + (one ? "" : ", and " + (count - 1) + " more") + ". Protégé "
                + "reads such a file and leaves it out of the ontology; ROBOT and ODK's make take "
                + "the file the catalog gives, and OntoBoard has put " + (one ? "it" : "them")
                + " in as they do - the terms and labels " + (one ? "it brings" : "they bring")
                + " are shown, reasoned with and measured. No file was changed. "
                + "OntoBoard > Reuse > Check imports names " + (one ? "it" : "each") + "; "
                + "OntoBoard > Reuse > Refresh imports... builds the modules again under the "
                + "names they are imported by, as ODK names them.";
    }

    /** The file as a path from the folder when it is under it, else whole. */
    private static String shownFrom(File file, File folder) {
        if (folder != null) {
            String prefix = folder.getAbsolutePath() + File.separator;
            String path = file.getAbsolutePath();
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length()).replace(File.separatorChar, '/');
            }
        }
        return file.getAbsolutePath();
    }
}
