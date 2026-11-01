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
    /**
     * Every workspace tab this plugin ships: extension id, the label Window &gt; Tabs shows, and
     * the view config it is laid out from.
     *
     * <p>Two of them since 1.117.0. The sheet editor moved out of the OntoBoard tab into one of
     * its own, which immediately made the old check wrong in both directions: it required every
     * view plugin.xml registers to be in the OntoBoard tab - so the canvas tab would fail for
     * not containing the sheet editor - while nothing opened the new tab at all, which is
     * exactly the hole this check was added to close. What each tab must contain is now read
     * from that tab's own layout.
     */
    private static final String[][] TABS = {
        {"OntoBoardTab", "OntoBoard", "/viewconfig-ontoboardtab.xml"},
        {"SheetsTab", "OntoBoard Sheets", "/viewconfig-sheetstab.xml"},
    };

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
    private String openTheTabOnce(final String tabId, final String tabLabel,
            final String configResource) {
        final java.util.concurrent.atomic.AtomicReference<String> outcome =
                new java.util.concurrent.atomic.AtomicReference<String>("nothing ran");
        Runnable onTheEventThread = new Runnable() {
            @Override
            public void run() {
                outcome.set(attemptToOpenTheTab(tabId, tabLabel, configResource));
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

    private String attemptToOpenTheTab(String tabId, String tabLabel, String configResource) {
        try {
            org.protege.editor.core.ui.workspace.TabbedWorkspace workspace =
                    getOWLEditorKit().getOWLWorkspace();
            if (workspace.containsTab(tabId)) {
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
                if (tabId.equals(id) || tabLabel.equals(label)
                        || (id != null && id.endsWith(tabId))) {
                    plugin = candidate;
                    break;
                }
            }
            if (plugin == null) {
                // Naming what was on offer rather than only what was missing: without it this says
                // the tab cannot be opened and gives nobody a way to find out why.
                return "no workspace tab is registered as " + tabId + " or \"" + tabLabel
                        + "\". Protege offers: " + offered;
            }
            // Cleared immediately before the tab is opened, so what comes back describes this
            // run rather than anything the session did earlier.
            de.fizkarlsruhe.ise.ontoboard.views.ViewHealth.forget();
            org.protege.editor.core.ui.workspace.WorkspaceTab tab =
                    workspace.addTabForPlugin(plugin);
            if (tab == null) {
                return "addTabForPlugin returned null for " + tabId;
            }
            try {
                // Selecting it is what normally builds the views: Protege creates a view's
                // content from a hierarchy event, when the view is first shown. Measured in a
                // smoke run, adding the tab and removing it again builds nothing at all - so for
                // every release up to 1.73.0 this check proved the tab was registered and
                // nothing whatever about the canvas, while reporting "its views constructed".
                workspace.setSelectedTab(tab);

                // What SHOULD have been built, read from THIS TAB'S OWN LAYOUT. Checking
                // only that something was built is not enough once there is more than one
                // view: Protege builds the visible tab of a tabbed group and not the one
                // behind it, so the sheet editor sat beside the canvas and never constructed
                // while this reported a pass. A view that is green in the suite and broken in
                // Protege is the failure this whole self-test exists for.
                //
                // Per layout rather than per plugin.xml, because the two are no longer the
                // same question: every registered view in every tab was right while there was
                // one tab and became wrong the moment the sheet editor got its own.
                java.util.List<String> expected;
                try {
                    expected = de.fizkarlsruhe.ise.ontoboard.views.ShippedLayout
                            .viewsNamedIn(configResource);
                } catch (Exception | LinkageError cannotRead) {
                    expected = java.util.Collections.emptyList();
                }
                if (expected.isEmpty()) {
                    // A layout naming none of our views has nothing for this check to prove,
                    // and claiming a pass over an empty expectation is how a check becomes
                    // decoration. Said out loud instead.
                    return "ok: opened and closed again, but " + configResource + " names no"
                            + " OntoBoard view, so nothing was proved about one";
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
        underTest.put("Check and test > Measure...", new MeasureAction());
        underTest.put("Check and test > Quality report...", new QualityReportAction());
        underTest.put("Check and test > Explain...", new ExplainAction());
        underTest.put("Check and test > SPARQL...", new SparqlAction());
        underTest.put("Terms > Export terms...", new ExportAction());
        underTest.put("Check and test > Profile...", new ProfileAction());
        // These three need a project on disk, which is why the subject is a scaffolded one rather
        // than an ontology held in memory. They report and change nothing: Refresh imports...
        // audits unless asked to rebuild, and the other two only read.
        underTest.put("Reuse > Check imports", new ImportsAction());
        underTest.put("Reuse > Refresh imports...", new RefreshImportsAction());
        underTest.put("Terms > All notes", new AllNotesAction());

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
        // The question a PASS has to answer on a machine with nothing installed - asked from a Mac
        // with no robot, no ODK and no Docker, where every line above said ok and Build was the
        // thing that did not work.
        String build;
        File buildInto = null;
        try {
            buildInto = java.nio.file.Files.createTempDirectory("ontoboard-selftest-build")
                    .toFile();
            build = buildsWithNothingInstalled(buildInto);
        } catch (Exception cannotBuild) {
            build = "could not scaffold a project to build: " + cannotBuild;
        } finally {
            if (buildInto != null) {
                deleteTree(buildInto);
            }
        }
        boolean buildOk = build.startsWith("ok:");
        if (!buildOk) {
            failed++;
            result.warn(BUILD_ROW + " failed: " + build);
        }
        result.row(BUILD_ROW, buildOk ? "ok" : "FAILED",
                buildOk ? build.substring(3).trim() : build);

        for (String[] tab : TABS) {
            String tabOutcome = openTheTabOnce(tab[0], tab[1], tab[2]);
            boolean tabOk = tabOutcome.startsWith("ok:");
            if (!tabOk) {
                failed++;
                result.warn("Window > Tabs > " + tab[1] + " failed: " + tabOutcome);
            }
            result.row("Window > Tabs > " + tab[1], tabOk ? "ok" : "FAILED",
                    tabOk ? tabOutcome.substring(3).trim() : tabOutcome);
        }

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
        int checks = underTest.size() + 1 + TABS.length;
        if (failed > 0) {
            return result.failed(failed + " of " + checks
                    + " checks failed. This is a defect in the plugin, not in your "
                    + "ontology.").build();
        }
        return result.summary(checks + " of " + checks
                + " checks ran - " + underTest.size() + " menu items, the in-process build and "
                + TABS.length + " tabs. "
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
    private static final String BUILD_ROW = "Build and release > Build (nothing installed)";

    /**
     * Builds the scratch project the way Build does for a project OntoBoard scaffolded, and says
     * what this machine has on its PATH.
     *
     * <p>In a project of its own, freshly scaffolded into {@code into}. The first version reused
     * the scratch project above and failed in the host: the checks before it save their unlabelled
     * test classes into that edit file, and report rejected them with missing_label - the build
     * doing its job on an ontology this check never meant to hand it.
     *
     * <p>The PATH is looked at, never run: executing robot or docker to see whether they answer
     * is the very dependency this check exists to rule out. A Protege started from the Finder on
     * macOS inherits a launchd PATH of /usr/bin:/bin:/usr/sbin:/sbin, so a robot installed with a
     * package manager is invisible to it anyway - which is why the build must not need one.
     */
    static String buildsWithNothingInstalled(File into) {
        de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig config =
                new de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig("selftest",
                        "OntoBoard self-test", "A throwaway project the self-test builds.",
                        "http://www.ontoboard.org/selftest.owl",
                        "https://creativecommons.org/publicdomain/zero/1.0/", into);
        de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold.create(config);
        File editFile = new File(new File(new File(config.getProjectRoot(), "src"), "ontology"),
                "selftest-edit.owl");
        String ineligible = de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets
                .whyNotEligible(editFile);
        if (ineligible != null) {
            return "a project OntoBoard just scaffolded does not build in-process: " + ineligible;
        }
        long started = System.currentTimeMillis();
        de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.Outcome outcome;
        try {
            OWLOntology fresh = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(editFile);
            outcome = de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.run(editFile, fresh,
                    de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK.newFactory(), "test");
        } catch (Exception cannotRun) {
            return "the in-process build threw " + cannotRun;
        }
        if (!outcome.isOk()) {
            return "make test, run inside Protege, failed: " + outcome.getFailure();
        }
        StringBuilder tools = new StringBuilder();
        for (String tool : new String[] {"robot", "make", "docker", "podman", "odk"}) {
            String found = onPath(tool);
            tools.append(tools.length() == 0 ? "" : ", ").append(tool).append(' ')
                    .append(found == null ? "absent" : found + " (not used)");
        }
        return "ok: test (reason, report, sparql_test, validate_profile) ran inside Protege in "
                + (System.currentTimeMillis() - started) + " ms without starting any program; "
                + "on this PATH: " + tools;
    }

    /** Where a program is on the PATH, or null - found by looking, not by running it. */
    static String onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] names = os.contains("win")
                ? new String[] {tool + ".exe", tool + ".bat", tool + ".cmd"}
                : new String[] {tool};
        for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            for (String name : names) {
                File candidate = new File(directory, name);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

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
