package de.fizkarlsruhe.ise.ontoboard.menu;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * OntoBoard &gt; Run self-test - actually runs the menu items, in the host, and reports.
 *
 * <p>This is the half of the plan's Phase 2 that the startup check cannot reach. {@link
 * de.fizkarlsruhe.ise.ontoboard.SelfCheck} proves every action class <em>loads and constructs</em>
 * under Felix, which catches a dead menu item. It cannot tell you an item throws the moment it does
 * any work, because loading a class is not running it.
 *
 * <p>So this constructs each action the way Protege does - {@code setEditorKit}, {@code initialise}
 * - and calls its {@code run} against a scratch ontology built here. Everything the action needs
 * from the live session it still gets, because it is wired to the real editor kit; what it operates
 * on is a throwaway. A maintainer runs this before cutting a release and learns, in about a second,
 * whether six of the menu items still do their job in a bundle.
 *
 * <p><b>Why a scratch ontology and not the open one.</b> An action that ran against the user's work
 * and went wrong would be the worst possible failure mode for a self-test, and some of these
 * actions read the open ontology's project directory anyway - so the session is still exercised.
 *
 * <p><b>What is deliberately left out, and why.</b> Only actions that change nothing are run:
 *
 * <ul>
 *   <li>{@code TransformAction} defaults to {@code apply = true}. Without its dialog it would
 *       compute a relax and then push the changes through the model manager, which is the live
 *       session's, not the scratch ontology's. A self-test that edits an ontology is not a test.
 *   <li>{@code RenameAction}, {@code ImportTermsAction}, {@code ObsoleteAction},
 *       {@code ReleaseAction}, {@code BuildAction}, {@code GitAction} and the rest either write
 *       files, need a configured project, or reach the network. They are covered by unit tests and
 *       by the ODK build test; what they are not covered by is this, and saying so is better than
 *       a self-test that quietly checks a quarter of what its name suggests.
 * </ul>
 *
 * <p>So this is six of the twenty-odd items. That is a real and stated limit, not a claim to have
 * driven the menu.
 */
public class SelfTestAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String NS = "http://www.ontoboard.org/self-test#";

    @Override
    protected String operationName() {
        return "Self-test";
    }

    /**
     * No ontology needed: it builds its own.
     *
     * <p>Which also makes this the one item a maintainer can run immediately after installing,
     * before opening anything.
     */
    @Override
    protected boolean needsAnOntology() {
        return false;
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    /**
     * The self-test, callable without going through the menu.
     *
     * <p>{@link de.fizkarlsruhe.ise.ontoboard.OntoBoardStartup} uses this so the automated run and
     * the menu item execute the same code. A self-test with two implementations is two things to
     * keep in step, and the one nobody runs is the one that rots.
     */
    /** The {@code WorkspaceTab} extension id in plugin.xml. */
    private static final String TAB_ID = "OntoBoardTab";

    /** The {@code label} in the same extension - what Window &gt; Tabs shows. */
    private static final String TAB_LABEL = "OntoBoard";

    /**
     * Opens the OntoBoard tab, letting its views construct, and closes it again.
     *
     * <p>On the event thread, because it builds Swing components and this runs on Felix's dispatch
     * queue - every self-test line in the log is tagged {@code [FelixDispatchQueue]}. Constructing a
     * view off the event thread is undefined rather than merely impolite, and the failure it produces
     * would be blamed on the canvas.
     *
     * <p>If the tab is already open the workspace is left exactly as it was: a user running the
     * self-test from the menu should not have their tab closed underneath them. Otherwise it is
     * opened and removed, which returns the workspace to how it was found.
     */
    private String openTheTabOnce() {
        final java.util.concurrent.atomic.AtomicReference<String> outcome =
                new java.util.concurrent.atomic.AtomicReference<String>("nothing ran");
        Runnable onTheEventThread = new Runnable() {
            @Override
            public void run() {
                outcome.set(attemptToOpenTheTab());
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                onTheEventThread.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(onTheEventThread);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted before the event thread could open the tab";
        } catch (java.lang.reflect.InvocationTargetException | RuntimeException | Error broke) {
            return "could not run on the event thread: " + describe(broke);
        }
        return outcome.get();
    }

    private String attemptToOpenTheTab() {
        try {
            org.protege.editor.core.ui.workspace.TabbedWorkspace workspace =
                    getOWLEditorKit().getOWLWorkspace();
            if (workspace.containsTab(TAB_ID)) {
                return "ok: already open in the restored workspace";
            }
            // By id or by label. Protege composes a plugin's id from the bundle and the
            // extension, so the bare extension id from plugin.xml is not necessarily what getId()
            // returns - the first version of this matched on the id alone, failed, and blamed the
            // plugin for what was the lookup's mistake. The label is what the user sees in
            // Window > Tabs, so matching it is both more robust and closer to the thing being
            // checked.
            org.protege.editor.core.ui.workspace.WorkspaceTabPlugin plugin = null;
            StringBuilder offered = new StringBuilder();
            for (org.protege.editor.core.ui.workspace.WorkspaceTabPlugin candidate
                    : workspace.getOrderedPlugins()) {
                String id = candidate.getId();
                String label = candidate.getLabel();
                if (offered.length() > 0) {
                    offered.append("; ");
                }
                offered.append(id).append('=').append(label);
                if (TAB_ID.equals(id) || TAB_LABEL.equals(label)
                        || (id != null && id.endsWith(TAB_ID))) {
                    plugin = candidate;
                    break;
                }
            }
            if (plugin == null) {
                // Naming what was on offer rather than only what was missing: without it this says
                // the tab cannot be opened and gives nobody a way to find out why.
                return "no workspace tab is registered as " + TAB_ID + " or \"" + TAB_LABEL
                        + "\". Protege offers: " + offered;
            }
            // Cleared immediately before the tab is opened, so what comes back describes this
            // run rather than anything the session did earlier.
            de.fizkarlsruhe.ise.ontoboard.views.ViewHealth.forget();
            org.protege.editor.core.ui.workspace.WorkspaceTab tab =
                    workspace.addTabForPlugin(plugin);
            if (tab == null) {
                return "addTabForPlugin returned null for " + TAB_ID;
            }
            try {
                // Selecting it is what normally builds the views: Protege creates a view's
                // content from a hierarchy event, when the view is first shown. Measured in a
                // smoke run, adding the tab and removing it again builds nothing at all - so for
                // every release up to 1.73.0 this check proved the tab was registered and
                // nothing whatever about the canvas, while reporting "its views constructed".
                workspace.setSelectedTab(tab);

                // What SHOULD have been built, read from the extension points. Checking only
                // that something was built is not enough once there is more than one view:
                // Protege builds the visible tab of a tabbed group and not the one behind it,
                // so the sheet editor sat beside the canvas and never constructed while this
                // reported a pass. A view that is green in the suite and broken in Protege is
                // the failure this whole self-test exists for.
                java.util.List<String> expected;
                try {
                    expected = de.fizkarlsruhe.ise.ontoboard.SelfCheck.declaredViewNames();
                } catch (Exception | LinkageError cannotRead) {
                    expected = java.util.Collections.emptyList();
                }

                int forced = 0;
                if (!de.fizkarlsruhe.ise.ontoboard.views.ViewHealth.built()
                        .containsAll(expected)) {
                    // Either nothing was shown - a headless or unrealised frame - or something
                    // was shown and the rest are behind it. Ask the views directly for the work
                    // the hierarchy event would have triggered.
                    forced = buildViewsIn(tab);
                }

                // Asking the view, not inferring from silence. Protege's View.createContent
                // catches whatever initialise() throws and puts an error label in the view's
                // place, so a canvas that crashes on every open looks from out here exactly like
                // one that works. ViewHealth is the view reporting on itself.
                String failure = de.fizkarlsruhe.ise.ontoboard.views.ViewHealth.whatFailed();
                if (failure != null) {
                    return "the tab opened but a view did not build: " + failure;
                }
                java.util.Set<String> built =
                        de.fizkarlsruhe.ise.ontoboard.views.ViewHealth.built();
                if (built.isEmpty()) {
                    return "the tab opened, but no OntoBoard view reported itself built"
                            + (forced == 0
                                    ? " and none could be found in it to build"
                                    : " after building " + forced + " of them by hand");
                }
                java.util.List<String> missing = new java.util.ArrayList<String>();
                for (String wanted : expected) {
                    if (!built.contains(wanted)) {
                        missing.add(wanted);
                    }
                }
                if (!missing.isEmpty()) {
                    // Naming what WAS in the tab, because the first two attempts at forcing
                    // these to build failed and guessing at Protege's docking internals a
                    // third time would be worse than measuring them.
                    return "the tab opened and " + built + " built, but " + missing
                            + " never did. A view registered in plugin.xml that nothing "
                            + "constructs is one nobody has ever seen work. The tab contained: "
                            + describeTree(tab, 0);
                }
                return "ok: opened, " + built + " built without throwing, and closed again";
            } finally {
                workspace.removeTab(tab);
            }
        } catch (RuntimeException | Error broke) {
            // Error included deliberately: a view that references a Protege type the bundle never
            // imported fails with NoClassDefFoundError, which is exactly the class of failure this
            // whole self-test exists to catch, and it is not a RuntimeException.
            return "opening the tab threw " + describe(broke);
        }
    }

    /**
     * Builds every view in this container, the way being shown would.
     *
     * <p>{@code View.createUI} is what Protege's own hierarchy listener calls, so this is the
     * same work and not a back door. It is needed because a smoke run adds the tab and removes
     * it without the frame ever realising it, and a check that only proves the tab is registered
     * is the check that let a crashing canvas ship.
     *
     * <p>Throwables are not caught here on purpose. {@code createUI} already catches what
     * {@code initialise()} throws, which is exactly the problem; anything that escapes it is a
     * failure of Protege's own plumbing and belongs in the caller's report.
     *
     * @return how many views were asked
     */
    /**
     * The component classes inside a container, a few levels deep, as one line.
     *
     * <p>Diagnostic. A view that does not build leaves nothing to look at, and the useful
     * question is what Protege put in the tab instead - a tabbed pane whose tabs can be
     * selected, or a node that creates its views only when first shown.
     */
    private static String describeTree(java.awt.Container container, int depth) {
        if (depth > 4) {
            return "...";
        }
        StringBuilder text = new StringBuilder();
        for (java.awt.Component child : container.getComponents()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(child.getClass().getSimpleName());
            if (child instanceof javax.swing.JTabbedPane) {
                text.append("[").append(((javax.swing.JTabbedPane) child).getTabCount())
                        .append(" tabs]");
            }
            if (child instanceof java.awt.Container && text.length() < 600) {
                String inside = describeTree((java.awt.Container) child, depth + 1);
                if (!inside.isEmpty()) {
                    text.append('(').append(inside).append(')');
                }
            }
        }
        return text.toString();
    }

    private static int buildViewsIn(java.awt.Container container) {
        int asked = 0;
        // SHOW EVERY TAB FIRST. Two views in one mdock CNode render as a tabbed pane, and
        // Protege builds the one on top: the sheet editor sat behind the canvas and never
        // constructed, while the check - which then only required that something had built -
        // reported a pass. Selecting each tab in turn is what a person does and is the path
        // that actually constructs the view, so it is better evidence than reaching past the
        // UI to call createUI directly, which the loop below still does as a fallback for a
        // frame that was never realised.
        if (container instanceof javax.swing.JTabbedPane) {
            javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) container;
            int wasSelected = tabs.getSelectedIndex();
            for (int at = 0; at < tabs.getTabCount(); at++) {
                try {
                    tabs.setSelectedIndex(at);
                } catch (RuntimeException cannotSelect) {
                    continue;
                }
            }
            if (wasSelected >= 0 && wasSelected < tabs.getTabCount()) {
                tabs.setSelectedIndex(wasSelected);
            }
        }
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof org.protege.editor.core.ui.view.View) {
                ((org.protege.editor.core.ui.view.View) child).createUI();
                asked++;
            } else if (child instanceof java.awt.Container) {
                asked += buildViewsIn((java.awt.Container) child);
            }
        }
        return asked;
    }

    public OperationResult selfTest() {
        return run((OWLOntology) null);
    }

    @Override
    protected OperationResult run(OWLOntology ignored) {
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Menu item", "Result", "What it reported");

        File projectRoot;
        OWLOntology scratch;
        try {
            projectRoot = scratchProject();
            scratch = editFileOf(projectRoot);
        } catch (Exception cannotBuild) {
            return result.failed("Could not build the scratch project to test against: "
                    + cannotBuild.getMessage()).build();
        }

        Map<String, OntoBoardAction> underTest = new LinkedHashMap<String, OntoBoardAction>();
        underTest.put("ROBOT > Measure...", new MeasureAction());
        underTest.put("ROBOT > Quality report...", new QualityReportAction());
        underTest.put("ROBOT > Explain...", new ExplainAction());
        underTest.put("ROBOT > SPARQL...", new SparqlAction());
        underTest.put("ROBOT > Export terms...", new ExportAction());
        underTest.put("ROBOT > Profile...", new ProfileAction());
        // These three need a project on disk, which is why the subject is a scaffolded one rather
        // than an ontology held in memory. They report and change nothing: Refresh imports...
        // audits unless asked to rebuild, and the other two only read.
        underTest.put("Project > Imports...", new ImportsAction());
        underTest.put("Project > Refresh imports...", new RefreshImportsAction());
        underTest.put("Notes > All notes...", new AllNotesAction());

        int failed = 0;
        for (Map.Entry<String, OntoBoardAction> entry : underTest.entrySet()) {
            String outcome = runOne(entry.getValue(), scratch);
            boolean ok = outcome.startsWith("ok:");
            if (!ok) {
                failed++;
                result.warn(entry.getKey() + " failed: " + outcome);
            }
            result.row(entry.getKey(), ok ? "ok" : "FAILED",
                    outcome.startsWith("ok:") ? outcome.substring(3).trim() : outcome);
        }

        // The tab, which is not an action and so is not in the map above. It is the last clause
        // of F1 in the plan and the only one never met: the smoke script looks for "Saved tab state
        // for 'OntoBoard' tab", which Protege logs at shutdown - and the smoke kills the process, so
        // that line can never appear. Measured on this machine, the tab is not mentioned anywhere in
        // a smoke run's log slice: it simply never opens, because the self-test runs from the editor
        // kit hook and nothing asks for the tab.
        //
        // This asks for it. Opening the tab constructs SchemaCanvasView - 2,114 lines and the
        // largest class in the plugin, covered until now only by tests that construct no view - so
        // this is also the first check that the canvas can be built at all under Felix.
        String tabOutcome = openTheTabOnce();
        boolean tabOk = tabOutcome.startsWith("ok:");
        if (!tabOk) {
            failed++;
            result.warn("Window > Tabs > OntoBoard failed: " + tabOutcome);
        }
        result.row("Window > Tabs > OntoBoard", tabOk ? "ok" : "FAILED",
                tabOk ? tabOutcome.substring(3).trim() : tabOutcome);

        for (String skipped : skippedWithReasons()) {
            result.note(skipped);
        }
        result.note("Ran against a scratch ODK project in " + projectRoot.getAbsolutePath()
                + ", not the ontology you have open.");
        deleteTree(projectRoot.getParentFile());

        // The menu items plus the tab, which is checked above and is not one of them. Counting
        // only the map said "9 of 9" on a run that had in fact checked ten things - a summary that
        // undercounts its own work is the kind of small lie that makes the rest of it less
        // believable, and the receipts quote this line verbatim.
        int checks = underTest.size() + 1;
        if (failed > 0) {
            return result.failed(failed + " of " + checks
                    + " checks failed. This is a defect in the plugin, not in your "
                    + "ontology.").build();
        }
        return result.summary(checks + " of " + checks
                + " checks ran - " + underTest.size() + " menu items and the tab itself. "
                + skippedWithReasons().size()
                + " others are not covered here - see the notes.").build();
    }

    /**
     * Wires one action the way Protege does and runs it.
     *
     * <p>{@code configure()} is deliberately not called: it opens a modal dialog, and a self-test
     * that asks twenty questions is one nobody runs. Each action therefore runs on its field
     * defaults, which is what it would do if somebody pressed OK without changing anything.
     */
    private String runOne(OntoBoardAction action, OWLOntology scratch) {
        try {
            action.setEditorKit(getEditorKit());
            action.initialise();
        } catch (Exception | LinkageError cannotWire) {
            return "could not be constructed: " + describe(cannotWire);
        }
        try {
            OperationResult outcome = action.run(scratch);
            if (outcome == null) {
                return "returned no result at all";
            }
            if (!outcome.isSuccess()) {
                return "reported a failure: " + outcome.getSummary();
            }
            return "ok: " + outcome.getSummary();
        } catch (Exception | LinkageError threw) {
            return "threw " + describe(threw);
        } finally {
            try {
                action.dispose();
            } catch (Exception ignored) {
                // An action that cannot be disposed has still told us what we came to find out.
            }
        }
    }

    private static List<String> skippedWithReasons() {
        List<String> skipped = new ArrayList<String>();
        skipped.add("Not run - Transform...: it defaults to applying its changes, and without its "
                + "dialog it would push them through the live session's model manager.");
        skipped.add("Not run - Rename IRIs..., Import terms..., Obsolete..., Release..., Build..., "
                + "Git..., Open from GitHub..., New ODK project..., Compare releases...: they "
                + "write files, run make, reach the network, or need a project with dated "
                + "releases that a freshly scaffolded one does not have.");
        skipped.add("Not run - the canvas, the notes and the collaboration items: they are Swing "
                + "surfaces, and nothing here opens a window.");
        return skipped;
    }

    /**
     * A throwaway ODK project, scaffolded by the wizard's own code.
     *
     * <p>A project on disk rather than an ontology in memory, because half the menu is
     * project-aware: it looks for {@code src/ontology}, a catalog, {@code src/sparql}, release
     * directories. An ontology with no file has no project, so those actions could only ever report
     * "this has not been saved" - which tests nothing.
     *
     * <p>Scaffolded with {@link OdkScaffold} rather than assembled here, so what is tested against
     * is the layout the wizard really produces. If the scaffold changes, this follows it.
     */
    private File scratchProject() throws Exception {
        File into = java.nio.file.Files.createTempDirectory("ontoboard-selftest").toFile();
        de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig config =
                new de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig("selftest",
                        "OntoBoard self-test",
                        "A throwaway project the self-test builds and deletes.",
                        // Must end in the ontology id - OdkProjectConfig.validate enforces it,
                        // and caught this the first time the self-test ran.
                        "http://www.ontoboard.org/selftest.owl",
                        "https://creativecommons.org/publicdomain/zero/1.0/", into);
        de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold.create(config);
        return config.getProjectRoot();
    }

    /**
     * The project's edit file, with a few axioms added so the operations have something to say.
     *
     * <p>The scaffold writes an edit file carrying metadata and no terms. A quality report over
     * that finds nothing, which is a passing result that proves very little - so Person, Agent and
     * a property go in, and Person deliberately carries no label.
     */
    private OWLOntology editFileOf(File projectRoot) throws Exception {
        File editFile = new File(new File(new File(projectRoot, "src"), "ontology"),
                "selftest-edit.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(editFile);
        OWLDataFactory factory = manager.getOWLDataFactory();

        OWLClass agent = factory.getOWLClass(IRI.create(NS + "Agent"));
        OWLClass person = factory.getOWLClass(IRI.create(NS + "Person"));
        OWLObjectProperty knows = factory.getOWLObjectProperty(IRI.create(NS + "knows"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(agent));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(person));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(knows));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(person, agent));
        manager.addAxiom(ontology, factory.getOWLObjectPropertyDomainAxiom(knows, person));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                agent.getIRI(), factory.getOWLLiteral("agent")));

        // Saved, so the actions that ask where this ontology lives get an answer.
        manager.saveOntology(ontology, IRI.create(editFile.toURI()));
        return ontology;
    }

    /** Removes the scratch project. A self-test that litters temp directories is a nuisance. */
    private static void deleteTree(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // Best effort: a file still held open is not worth failing the self-test over.
        root.delete();
    }


    /**
     * An exception in one line, with its type, since a message alone is often empty.
     *
     * <p>The fully qualified name rather than the simple one, which is what 1.57.0 shipped: the
     * failures this reports are loader failures, and {@code NoClassDefFoundError} on its own does not
     * say which classpath it came from while
     * {@code java.lang.NoClassDefFoundError: org/protege/editor/core/…} does.
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}
