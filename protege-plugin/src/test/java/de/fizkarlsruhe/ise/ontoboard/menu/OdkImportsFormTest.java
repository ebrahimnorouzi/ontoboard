package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportCases;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Refresh imports for an ODK project, as the menu does it: ODK's own targets for the imports,
 * run by ODK's tools on the machine, in ODK's container, or by OntoBoard itself - and what the
 * run changed read again by whoever has the project open.
 */
class OdkImportsFormTest {

    private static final String NONE = "Neither Docker nor Podman answers on this machine";
    private static final String IMAGE = "obolibrary/odkfull:v1.6.1";

    @TempDir
    File top;

    private File edit;
    private File ontology;
    private List<ImportProducts.Product> declared;

    /** Whoever has the project open: what it was asked to read again, and what it answers. */
    private final List<List<File>> readAgain = new ArrayList<List<File>>();
    private String cannotReadAgain;
    private final ReadsAgain session = new ReadsAgain() {
        @Override
        public WhatARunWrote.Done readAgain(List<File> modules) {
            readAgain.add(new ArrayList<File>(modules));
            return cannotReadAgain == null
                    ? new WhatARunWrote.Done(modules, Collections.<String>emptyList())
                    : new WhatARunWrote.Done(Collections.<File>emptyList(),
                            Collections.singletonList(cannotReadAgain));
        }
    };

    private void aFreshProject() throws IOException {
        edit = ImportCases.fresh(top);
        ontology = edit.getParentFile();
        declared = ImportProducts.declaredIn(ontology);
    }

    private void aRefreshedProjectWithOneMoreTermAskedFor() throws IOException {
        edit = ImportCases.refreshedAndOneMoreTermAskedFor(top);
        ontology = edit.getParentFile();
        declared = ImportProducts.declaredIn(ontology);
    }

    /** make's variables of the recording: the ontologies to mirror are files here. */
    private List<String> variables() {
        List<String> words = new ArrayList<String>();
        for (Map.Entry<String, String> variable : ImportCases.commandLine(top).entrySet()) {
            words.add(variable.getKey() + "=" + variable.getValue());
        }
        return words;
    }

    private static List<String> names(List<File> files) {
        List<String> names = new ArrayList<String>();
        for (File file : files) {
            names.add(file.getName());
        }
        return new ArrayList<String>(new TreeSet<String>(names));
    }

    private OdkImportsForm.Way itself() {
        return OdkImportsForm.way(null, OdkRoute.Use.AUTOMATIC, null, null, NONE, null);
    }

    /** ODK as a test writes it: what it is asked, what it then does, says and answers. */
    private static final class Scripted implements OdkRelease.Odk {
        final List<List<String>> ran = new ArrayList<List<String>>();
        final Map<String, Runnable> does = new LinkedHashMap<String, Runnable>();
        final Map<String, List<String>> says = new LinkedHashMap<String, List<String>>();
        final Map<String, Integer> exits = new LinkedHashMap<String, Integer>();

        @Override
        public ProcessRunner.Outcome run(List<String> command, boolean passThroughRunSh) {
            ran.add(new ArrayList<String>(command));
            String target = command.get(1);
            if (does.containsKey(target)) {
                does.get(target).run();
            }
            return new ProcessRunner.Outcome(exits.containsKey(target) ? exits.get(target) : 0,
                    says.containsKey(target) ? says.get(target)
                            : Collections.<String>emptyList(), false);
        }

        @Override
        public String shown(List<String> command, boolean passThroughRunSh) {
            return String.join(" ", command);
        }
    }

    // ------------------------------------------------------------------ who refreshes

    @Test
    void odksOwnToolsOnTheMachineComeFirst() {
        OdkImportsForm.Way way = OdkImportsForm.way(new File("activate-odk-environment.sh"),
                OdkRoute.Use.AUTOMATIC, "docker", IMAGE, null, null);

        assertEquals(OdkImportsForm.Way.Kind.NATIVE, way.kind());
        assertNull(way.refusal());
        assertTrue(way.who().contains("ODK's own tools"), way.who());
    }

    @Test
    void thenOdksContainer() {
        OdkImportsForm.Way way = OdkImportsForm.way(null, OdkRoute.Use.AUTOMATIC, "docker",
                IMAGE, null, null);

        assertEquals(OdkImportsForm.Way.Kind.CONTAINER, way.kind());
        assertNull(way.refusal());
        assertTrue(way.who().contains(IMAGE) && way.who().contains("docker"), way.who());
    }

    /** "Always in a container" is kept to: the tools on the machine are then not the ones. */
    @Test
    void withAlwaysInAContainerTheToolsOnTheMachineAreNotUsed() {
        OdkImportsForm.Way way = OdkImportsForm.way(new File("activate-odk-environment.sh"),
                OdkRoute.Use.ALWAYS_CONTAINER, "podman", IMAGE, null, null);

        assertEquals(OdkImportsForm.Way.Kind.CONTAINER, way.kind());
    }

    @Test
    void withAlwaysInAContainerAndNoneNothingRunsAndTheSettingIsNamed() {
        OdkImportsForm.Way way = OdkImportsForm.way(new File("activate-odk-environment.sh"),
                OdkRoute.Use.ALWAYS_CONTAINER, null, null, NONE, "Start it, or change the "
                        + "setting.");

        assertNull(way.kind());
        assertTrue(way.refusal().startsWith(NONE), way.refusal());
        assertTrue(way.refusal().contains("\"" + OdkRoute.Use.ALWAYS_CONTAINER.label() + "\""),
                way.refusal());
        assertTrue(way.refusal().contains("nothing is run"), way.refusal());
        assertTrue(way.refusal().endsWith("Start it, or change the setting."), way.refusal());
    }

    /** Without ODK's tools and without a container OntoBoard runs ODK's targets itself. */
    @Test
    void elseOntoBoardItself() {
        OdkImportsForm.Way way = itself();

        assertEquals(OdkImportsForm.Way.Kind.ITSELF, way.kind());
        assertNull(way.refusal());
        assertTrue(way.who().contains("OntoBoard itself"), way.who());
        assertTrue(way.who().contains(NONE), "and why not a container: " + way.who());
    }

    /** A program for containers without ODK's image is no container to run in. */
    @Test
    void aContainerProgramWithoutOdksImageIsNoContainer() {
        OdkImportsForm.Way way = OdkImportsForm.way(null, OdkRoute.Use.AUTOMATIC, "docker", null,
                "ODK's image is not on this machine", null);

        assertEquals(OdkImportsForm.Way.Kind.ITSELF, way.kind());
        assertTrue(way.who().contains("ODK's image is not on this machine"), way.who());
    }

    // ------------------------------------------------------------------ the form

    @Test
    void theFormSaysWhatWasFoundAndWhoWouldRebuild() {
        String explanation = OdkImportsForm.explanation("3 imports declared.", itself());

        assertTrue(explanation.contains("3 imports declared."), explanation);
        assertTrue(explanation.contains("OntoBoard itself"), explanation);
        assertTrue(explanation.contains("refresh-imports"), "ODK's own targets: " + explanation);
    }

    @Test
    void whereNobodyWouldRebuildTheFormSaysWhy() {
        String explanation = OdkImportsForm.explanation("3 imports declared.",
                OdkImportsForm.way(null, OdkRoute.Use.ALWAYS_CONTAINER, null, null, NONE,
                        "Start it, or change the setting."));

        assertTrue(explanation.contains("3 imports declared."), explanation);
        assertTrue(explanation.contains(NONE), explanation);
        assertTrue(explanation.contains("nothing is run"), explanation);
    }

    @Test
    void theFormOffersEveryImportOrOne() throws IOException {
        aFreshProject();

        List<Parameter> fields = OdkImportsForm.parameters(declared, itself());

        List<String> keys = new ArrayList<String>();
        for (Parameter field : fields) {
            keys.add(field.getKey());
        }
        assertEquals(Arrays.asList(OdkImportsForm.REBUILD, OdkImportsForm.FETCH,
                OdkImportsForm.WHICH), keys);
        assertEquals("false", fields.get(0).getDefaultValue(), "a look, unless asked");
        assertEquals("false", fields.get(1).getDefaultValue());
        assertEquals(Arrays.asList(OdkImportsForm.ALL, "ro", "iao", "bfo"),
                fields.get(2).getChoices());
        assertEquals(OdkImportsForm.ALL, fields.get(2).getDefaultValue());
        assertTrue(fields.get(1).getHelp().contains("no-mirror-refresh"),
                "ODK's own word for it: " + fields.get(1).getHelp());
    }

    @Test
    void aProjectThatDeclaresNoImportsHasNoChoiceOfOne() {
        List<Parameter> fields = OdkImportsForm.parameters(
                Collections.<ImportProducts.Product>emptyList(), itself());

        assertEquals(2, fields.size(), fields.toString());
    }

    // ------------------------------------------------------------------ what is asked of make

    @Test
    void everyImportFetchedAgainIsOdksRefreshImports() throws IOException {
        aFreshProject();

        OdkImportsForm.Asked asked = OdkImportsForm.asked(declared, OdkImportsForm.ALL, true,
                ontology);

        assertEquals(Collections.singletonList("refresh-imports"), asked.targets());
        assertEquals(Collections.<String>emptyList(), asked.notes());
    }

    @Test
    void everyImportFromTheMirrorsAsTheyAreWhenAllAreThere() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();

        OdkImportsForm.Asked asked = OdkImportsForm.asked(declared, OdkImportsForm.ALL, false,
                ontology);

        assertEquals(Collections.singletonList("no-mirror-refresh-imports"), asked.targets());
        assertEquals(Collections.<String>emptyList(), asked.notes());
    }

    /**
     * ODK's no-mirror targets stop where a mirror is missing - on a fresh clone every one is,
     * for git does not keep them. An import without its mirror is fetched, and that is said.
     */
    @Test
    void anImportWithoutItsMirrorIsFetchedAndThatIsSaid() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();
        assertTrue(new File(ontology, "mirror/iao.owl").delete());

        OdkImportsForm.Asked asked = OdkImportsForm.asked(declared, OdkImportsForm.ALL, false,
                ontology);

        assertEquals(Arrays.asList("no-mirror-refresh-ro", "refresh-iao",
                "no-mirror-refresh-bfo"), asked.targets());
        assertEquals(1, asked.notes().size(), asked.notes().toString());
        assertTrue(asked.notes().get(0).contains("iao"), asked.notes().toString());
        assertTrue(asked.notes().get(0).contains("mirror"), asked.notes().toString());
    }

    @Test
    void onAFreshCloneEveryImportIsFetched() throws IOException {
        aFreshProject();

        OdkImportsForm.Asked asked = OdkImportsForm.asked(declared, OdkImportsForm.ALL, false,
                ontology);

        assertEquals(Arrays.asList("refresh-ro", "refresh-iao", "refresh-bfo"), asked.targets());
        assertEquals(3, asked.notes().size(), asked.notes().toString());
    }

    @Test
    void oneImportIsOdksTargetForIt() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();

        assertEquals(Collections.singletonList("refresh-ro"),
                OdkImportsForm.asked(declared, "ro", true, ontology).targets());
        assertEquals(Collections.singletonList("no-mirror-refresh-ro"),
                OdkImportsForm.asked(declared, "ro", false, ontology).targets());

        assertTrue(new File(ontology, "mirror/ro.owl").delete());
        OdkImportsForm.Asked fetched = OdkImportsForm.asked(declared, "ro", false, ontology);
        assertEquals(Collections.singletonList("refresh-ro"), fetched.targets());
        assertEquals(1, fetched.notes().size(), fetched.notes().toString());
    }

    /** An import that is declared to have no mirror needs none: nothing is fetched for it. */
    @Test
    void anImportDeclaredWithoutAMirrorNeedsNone() throws IOException {
        aFreshProject();
        List<ImportProducts.Product> frozen = ImportProducts.declaredIn("id: loim\n"
                + "import_group:\n  products:\n    - id: whole\n      module_type: custom\n"
                + "      mirror_type: no_mirror\n");

        OdkImportsForm.Asked asked = OdkImportsForm.asked(frozen, OdkImportsForm.ALL, false,
                ontology);

        assertEquals(Collections.singletonList("no-mirror-refresh-imports"), asked.targets());
        assertEquals(Collections.<String>emptyList(), asked.notes());
    }

    /** Their own rule builds a custom module, from whatever it reads: no mirror is asked for. */
    @Test
    void aCustomModuleOrOneWithoutAMirrorIsNotFetchedForItsMirror() throws IOException {
        aFreshProject();
        List<ImportProducts.Product> theirs = ImportProducts.declaredIn("id: loim\n"
                + "import_group:\n  products:\n    - id: bespoke\n      module_type: custom\n"
                + "    - id: local\n      mirror_type: no_mirror\n");

        assertEquals(Collections.singletonList("no-mirror-refresh-imports"),
                OdkImportsForm.asked(theirs, OdkImportsForm.ALL, false, ontology).targets());
    }

    /** An import the configuration does not declare is asked of ODK's Makefile as it is. */
    @Test
    void anImportThatIsNotDeclaredIsAskedOfMakeAsItIs() throws IOException {
        aFreshProject();

        assertEquals(Collections.singletonList("no-mirror-refresh-xyz"),
                OdkImportsForm.asked(declared, "xyz", false, ontology).targets());
        assertEquals(Collections.singletonList("refresh-xyz"),
                OdkImportsForm.asked(declared, "xyz", true, ontology).targets());
    }

    // ------------------------------------------------------------------ the run

    /** make's variables, where a caller has some, follow the target on each command line. */
    @Test
    void theVariablesFollowTheTargetOnMakesCommandLine() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();
        assertTrue(new File(ontology, "mirror/iao.owl").delete());
        Scripted odk = new Scripted();

        OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, "iao", false, ontology),
                Arrays.asList("IMP_LARGE=false", "OBODATE=11:11:2026 12:00"), odk, session);

        assertEquals(Collections.singletonList(Arrays.asList("make", "refresh-iao",
                "IMP_LARGE=false", "OBODATE=11:11:2026 12:00")), odk.ran);
    }

    /** Without a container: OntoBoard's own engine, ODK's Makefile, the recording's project. */
    @Test
    void ontoBoardItselfRefreshesTheImportsAndWhatChangedIsReadAgain() throws IOException {
        aFreshProject();

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, true, ontology), variables(),
                OdkRelease.byOntoBoardItself(edit, null), session);

        assertTrue(ran.isOk(), ran.failure() + "\n" + String.join("\n", ran.transcript()));
        assertEquals(Arrays.asList("bfo_import.owl", "iao_import.owl", "ro_import.owl"),
                names(ran.changed()));
        assertEquals(ran.changed(), ran.written());
        assertEquals(Collections.singletonList(ran.written()), readAgain);
        assertTrue(ran.transcript().get(0).startsWith("$ make refresh-imports"),
                ran.transcript().get(0));
        boolean fetched = false;
        for (String line : ran.transcript()) {
            fetched |= line.startsWith("curl -L ");
        }
        assertTrue(fetched, "ODK's own line fetched: " + ran.transcript());
        assertEquals(ran.written(), ran.readAgain().read());
        assertEquals(Collections.<String>emptyList(), ran.readAgain().notRead());
    }

    /**
     * After a term was added: the module whose list changed is the one that changed. ODK's
     * target builds the other module with a term list again too, as it was - and Protege,
     * which goes by a file's time, is handed both; the one the run did not write is not.
     */
    @Test
    void whatTheRunWroteIsReadAgainAndWhatItDidNotWriteIsNot() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, false, ontology), variables(),
                OdkRelease.byOntoBoardItself(edit, null), session);

        assertTrue(ran.isOk(), ran.failure() + "\n" + String.join("\n", ran.transcript()));
        assertEquals(Collections.singletonList("ro_import.owl"), names(ran.changed()));
        assertEquals(Arrays.asList("iao_import.owl", "ro_import.owl"), names(ran.written()),
                "a mirrored import has no term list and was not built again");
        assertEquals(Collections.singletonList(ran.written()), readAgain);
    }

    /**
     * A second refresh from the same mirrors and lists writes the same modules again: no
     * module changed. They are read again all the same: their files are newer than what
     * Protege read, and it would ask about an ontology that "changed outside of Protege"
     * (seen in a running Protege 5.5.0 after a refresh that changed nothing).
     */
    @Test
    void aModuleWrittenAgainAsItWasIsNoChangeAndIsReadAgain() throws IOException {
        aFreshProject();
        OdkRelease.Odk itself = OdkRelease.byOntoBoardItself(edit, null);
        assertTrue(OdkImportsForm.refresh(edit, OdkImportsForm.asked(declared,
                OdkImportsForm.ALL, true, ontology), variables(), itself, session).isOk());
        readAgain.clear();

        OdkImportsForm.Asked again = OdkImportsForm.asked(declared, OdkImportsForm.ALL, false,
                ontology);
        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit, again, variables(), itself,
                session);

        assertEquals(Collections.singletonList("no-mirror-refresh-imports"), again.targets());
        assertTrue(ran.isOk(), ran.failure() + "\n" + String.join("\n", ran.transcript()));
        assertEquals(Collections.<File>emptyList(), ran.changed());
        assertEquals(Arrays.asList("iao_import.owl", "ro_import.owl"), names(ran.written()));
        assertEquals(Collections.singletonList(ran.written()), readAgain);
    }

    /** Each target is one call of make, in order, wherever ODK runs. */
    @Test
    void eachTargetIsAskedOfOdkInOrder() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();
        assertTrue(new File(ontology, "mirror/iao.owl").delete());
        Scripted odk = new Scripted();

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, false, ontology),
                Collections.<String>emptyList(), odk, session);

        assertTrue(ran.isOk(), ran.failure());
        assertEquals(Arrays.asList(Arrays.asList("make", "no-mirror-refresh-ro"),
                Arrays.asList("make", "refresh-iao"),
                Arrays.asList("make", "no-mirror-refresh-bfo")), odk.ran);
        assertEquals(Arrays.asList("$ make no-mirror-refresh-ro", "$ make refresh-iao",
                "$ make no-mirror-refresh-bfo"), ran.transcript());
    }

    /**
     * A target that fails ends the run - what comes after it is not asked for - and what the
     * targets before it changed is on disk: it is read again all the same.
     */
    @Test
    void aTargetThatFailsEndsTheRunAndWhatHadChangedIsReadAgain() throws IOException {
        aRefreshedProjectWithOneMoreTermAskedFor();
        assertTrue(new File(ontology, "mirror/iao.owl").delete());
        final File module = new File(ontology, "imports/ro_import.owl");
        Scripted odk = new Scripted();
        odk.does.put("no-mirror-refresh-ro", new Runnable() {
            @Override
            public void run() {
                try {
                    Files.write(module.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
                    // And a file of the folder that is no module: not Protege's to read.
                    Files.write(new File(module.getParentFile(), "ro_terms.txt").toPath(),
                            "http://example.org/other\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        });
        odk.exits.put("refresh-iao", 2);
        odk.says.put("refresh-iao", Arrays.asList("curl: (6) Could not resolve host: "
                + "purl.obolibrary.org", "make: *** [Makefile:484: mirror-iao] Error 6"));

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, false, ontology),
                Collections.<String>emptyList(), odk, session);

        assertFalse(ran.isOk());
        assertEquals(2, odk.ran.size(), "the third was not asked for: " + odk.ran);
        assertTrue(ran.failure().contains("could not download"), ran.failure());
        assertEquals(Collections.singletonList(module), ran.changed());
        assertEquals(Collections.singletonList(module), ran.written());
        assertEquals(Collections.singletonList(Collections.singletonList(module)), readAgain);
        assertTrue(ran.transcript().contains("curl: (6) Could not resolve host: "
                + "purl.obolibrary.org"), ran.transcript().toString());
    }

    /** make's "No rule to make target": the Makefile is older than the configuration. */
    @Test
    void aMakefileThatDoesNotKnowAnImportSaysWhatBringsItUpToDate() throws IOException {
        aFreshProject();
        List<ImportProducts.Product> newer = ImportProducts.declaredIn("id: loim\n"
                + "import_group:\n  products:\n    - id: xyz\n");
        Scripted odk = new Scripted();
        odk.exits.put("refresh-xyz", 2);
        odk.says.put("refresh-xyz", Arrays.asList("make[1]: *** No rule to make target "
                + "'mirror/xyz.owl', needed by 'imports/xyz_import.owl'.  Stop.",
                "make: *** [Makefile:459: refresh-xyz] Error 2"));

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(newer, "xyz", true, ontology),
                Collections.<String>emptyList(), odk, session);

        assertFalse(ran.isOk());
        assertTrue(ran.failure().contains("OntoBoard > Project > Update project files..."),
                ran.failure());
        assertTrue(ran.failure().contains("mirror/xyz.owl"), ran.failure());
    }

    /** A run the person stopped is said as that, and what it had not changed is not read. */
    @Test
    void aRunThatWasStoppedSaysSo() throws IOException {
        aFreshProject();

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, true, ontology), variables(),
                OdkRelease.byOntoBoardItself(edit, () -> true), session);

        assertFalse(ran.isOk());
        assertTrue(ran.failure().startsWith("You stopped"), ran.failure());
        assertEquals(Collections.<File>emptyList(), ran.changed());
        assertEquals(Collections.<File>emptyList(), ran.written());
        assertEquals(Collections.<List<File>>emptyList(), readAgain);
    }

    /** The modules are on disk either way: a reading again that fails is said, and is no failure. */
    @Test
    void whatCouldNotBeReadAgainIsSaidAndIsNoFailure() throws IOException {
        aFreshProject();
        cannotReadAgain = "ro_import.owl could not be read again: it is in use.";

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, true, ontology), variables(),
                OdkRelease.byOntoBoardItself(edit, null), session);

        assertTrue(ran.isOk(), ran.failure());
        assertEquals(Collections.singletonList("ro_import.owl could not be read again: it is "
                + "in use."), ran.readAgain().notRead());
        assertEquals(Collections.<File>emptyList(), ran.readAgain().read());
    }

    /** ODK that cannot be started at all - no such program - is said with the target. */
    @Test
    void odkThatCannotBeStartedIsSaid() throws IOException {
        aFreshProject();
        OdkRelease.Odk broken = new OdkRelease.Odk() {
            @Override
            public ProcessRunner.Outcome run(List<String> command, boolean passThroughRunSh)
                    throws IOException {
                throw new IOException("Cannot run program \"docker\"");
            }

            @Override
            public String shown(List<String> command, boolean passThroughRunSh) {
                return String.join(" ", command);
            }
        };

        OdkImportsForm.Ran ran = OdkImportsForm.refresh(edit,
                OdkImportsForm.asked(declared, OdkImportsForm.ALL, true, ontology),
                Collections.<String>emptyList(), broken, session);

        assertFalse(ran.isOk());
        assertTrue(ran.failure().contains("refresh-imports"), ran.failure());
        assertTrue(ran.failure().contains("Cannot run program"), ran.failure());
    }
}
