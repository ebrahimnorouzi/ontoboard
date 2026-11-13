package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.OntologyManagerFactory;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;

/**
 * Protege, as ODK's update needs whoever has the project open: whether the person stopped
 * waiting, the question about the imports the update would take out, and the edit file read
 * again once the update has rewritten it on disk. For Update project files, and for every
 * action that ends with bringing a project in line with its configuration.
 */
final class ProtegeSession implements OdkUpdateForm.Session {

    private final java.awt.Component workspace;
    private final OWLModelManager models;
    private final File root;

    /**
     * @param workspace Protege's window, for the question
     * @param models    Protege's model manager
     * @param root      the project's root
     */
    ProtegeSession(java.awt.Component workspace, OWLModelManager models, File root) {
        this.workspace = workspace;
        this.models = models;
        this.root = root;
    }

    @Override
    public boolean stopped() {
        return BackgroundRun.abandoned();
    }

    @Override
    public boolean isOpen(File editFile) {
        return loadedFrom(editFile) != null;
    }

    /**
     * Protege keeps the time of each file it has open and asks about one that is newer
     * ("An ontology has changed outside of Protege"). What it holds is what the file
     * holds, so nothing is read again - which would also bring up Protege's question
     * about the ID range again - and the time alone is noted.
     */
    @Override
    public void writtenAsItWas(File editFile) {
        final OWLOntology open = loadedFrom(editFile);
        if (open == null) {
            return;
        }
        OntoBoardAction.onDispatchThread(new OntoBoardAction.Step() {
            @Override
            public void take() {
                OntoBoardAction.noteItsFileTime(models, open);
            }
        });
    }

    /**
     * The question about the imports the update would take out: a choice for each, the
     * one that keeps it first; Cancel answers nothing, and nothing is written then. Asked
     * on the dispatch thread, while the update waits.
     */
    @Override
    public List<ImportGuard.Decision> decide(final List<ImportGuard.Finding> findings) {
        final List<Parameter> fields = new ArrayList<Parameter>();
        final List<LinkedHashMap<String, ImportGuard.Decision>> meanings =
                new ArrayList<LinkedHashMap<String, ImportGuard.Decision>>();
        for (int i = 0; i < findings.size(); i++) {
            LinkedHashMap<String, ImportGuard.Decision> choices =
                    OdkUpdateForm.choicesFor(findings.get(i));
            meanings.add(choices);
            String[] words = choices.keySet().toArray(new String[0]);
            fields.add(Parameter.of("import." + i, findings.get(i).item().iri(),
                    Parameter.Kind.CHOICE)
                    .choices(words)
                    .defaultValue(words[0])
                    .help(OdkUpdateForm.helpFor(findings.get(i), root))
                    .build());
        }
        final List<ImportGuard.Decision> decided = new ArrayList<ImportGuard.Decision>();
        OntoBoardAction.onDispatchThread(new OntoBoardAction.Step() {
            @Override
            public void take() {
                Map<String, String> chosen = ParameterDialog.show(workspace,
                        "Imports the update would take out",
                        OdkUpdateForm.importsExplanation(), fields);
                if (chosen == null) {
                    return;
                }
                for (int i = 0; i < findings.size(); i++) {
                    ImportGuard.Decision decision =
                            meanings.get(i).get(chosen.get("import." + i));
                    if (decision != null) {
                        decided.add(decision);
                    }
                }
            }
        });
        return decided.size() == findings.size() ? decided : null;
    }

    /**
     * Three steps, each the way Protege does it itself (read from the classes of 5.5.0
     * and 5.6.9, which agree).
     *
     * <p>The catalog first ({@code OntologyCatalogManager.reloadFolder}): the update wrote
     * it, and Protege keeps what it read of it when the project was opened.
     *
     * <p>Then what the edit file imports now and Protege has not loaded: read from its
     * file in a manager on the side and moved into Protege's own, as Protege does when an
     * import is added by hand ({@code AddImportsStrategy}). Protege's reload reads the
     * edit file in a manager without the catalog, where an import resolves only if
     * Protege has it loaded already; a new one was looked for on the web and the reload
     * failed (seen in a running Protege: "Could not load imported ontology").
     *
     * <p>Then Protege's own reload ({@code OWLModelManager.reload}), on the dispatch
     * thread, where it shows its progress: what is open is patched to what the file now
     * holds, and the file's time is noted afterwards ({@link #readAgainFromItsFile}).
     * After a reload that fails nothing is noted, and Protege goes on to ask about the
     * file that changed outside of it - which is then the truth.
     */
    @Override
    public String readAgain(File editFile, Map<String, File> imports) {
        final OWLOntology open = loadedFrom(editFile);
        if (open == null) {
            return null;
        }
        final OWLModelManager manager = models;
        final File folder = editFile.getAbsoluteFile().getParentFile();
        // What goes wrong in the first two steps shows in the third, in Protege's words.
        OntoBoardAction.onDispatchThread(new OntoBoardAction.Step() {
            @Override
            public void take() throws Exception {
                manager.getOntologyCatalogManager().reloadFolder(folder);
            }
        });
        OWLOntologyManager owl = manager.getOWLOntologyManager();
        for (Map.Entry<String, File> served : imports.entrySet()) {
            if (served.getValue() == null || owl.contains(IRI.create(served.getKey()))) {
                continue;
            }
            try {
                OWLOntologyManager aside = OntologyManagerFactory.createManager();
                aside.getIRIMappers().add(owl.getIRIMappers());
                aside.loadOntologyFromOntologyDocument(
                        new IRIDocumentSource(IRI.create(served.getValue())),
                        new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                                MissingImportHandlingStrategy.SILENT));
                for (OWLOntology loaded : aside.getOntologies()) {
                    if (!owl.contains(loaded.getOntologyID())) {
                        owl.copyOntology(loaded, OntologyCopy.MOVE);
                    }
                }
            } catch (Exception | LinkageError notLoaded) {
                // Said by the reload below.
            }
        }
        return OntoBoardAction.readAgainFromItsFile(models, open);
    }

    /** The ontology Protege has loaded from a file, or null when it has none from there. */
    private OWLOntology loadedFrom(File file) {
        for (OWLOntology loaded : models.getOntologies()) {
            File document = OntoBoardAction.fileOf(models, loaded);
            if (document != null && sameFile(document, file)) {
                return loaded;
            }
        }
        return null;
    }

    private static boolean sameFile(File one, File other) {
        try {
            return Files.isSameFile(one.toPath(), other.toPath());
        } catch (IOException | RuntimeException cannotTell) {
            return one.getAbsoluteFile().equals(other.getAbsoluteFile());
        }
    }
}
