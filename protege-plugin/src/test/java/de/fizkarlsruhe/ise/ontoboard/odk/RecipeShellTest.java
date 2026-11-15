package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.Links;

/**
 * The recipe lines of ODK's Makefile, run without a shell.
 *
 * <p>make hands every recipe line to {@code /bin/sh}. Without ODK's container there may be no
 * sh at all - Protégé on Windows finds none - so OntoBoard's engine reads the lines itself:
 * the part of the shell ODK's recipes use, and no more. Anything else is refused by name
 * before anything runs. The lines here are make's own, from the recorded dry runs.
 */
class RecipeShellTest {

    @TempDir
    File temp;

    /** src/ontology of a project: where make runs. */
    private File folder;
    private final List<String> said = new ArrayList<String>();
    private final List<List<String>> robotRan = new ArrayList<List<String>>();
    private int robotStatus;

    @BeforeEach
    void aProjectFolder() throws IOException {
        folder = new File(temp, "eggboil/src/ontology");
        Files.createDirectories(folder.toPath());
    }

    private File file(String path) {
        return new File(folder, path);
    }

    private void write(String path, String text) throws IOException {
        Files.createDirectories(file(path).getParentFile().toPath());
        Files.write(file(path).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String path) throws IOException {
        return new String(Files.readAllBytes(file(path).toPath()), StandardCharsets.UTF_8);
    }

    /** The shell's own commands, and a robot that only remembers what it was asked. */
    private Map<String, RecipeShell.Tool> tools() {
        Map<String, RecipeShell.Tool> tools =
                new LinkedHashMap<String, RecipeShell.Tool>(RecipeShell.builtins());
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.contains("expand") ? "robot expand is not run here" : null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                robotRan.add(call.arguments());
                call.say("robot ran");
                return robotStatus;
            }
        });
        return tools;
    }

    private int run(String line) {
        RecipeShell.Script script = RecipeShell.parse(line);
        assertNull(script.whyNot(tools()), line);
        return script.run(tools(), folder, said);
    }

    private String whyNot(String line) {
        try {
            return RecipeShell.parse(line).whyNot(tools());
        } catch (RecipeShell.NotRead notRead) {
            return notRead.getMessage();
        }
    }

    // ------------------------------------------------------------------ words

    @Test
    void aPlainCommandIsItsWords() {
        assertEquals(0, run("robot --catalog catalog-v001.xml convert --input eggboil-edit.owl "
                + "--format ofn --output tmp/eggboil-preprocess.owl"));

        assertEquals(Collections.singletonList(Arrays.asList("--catalog", "catalog-v001.xml",
                "convert", "--input", "eggboil-edit.owl", "--format", "ofn", "--output",
                "tmp/eggboil-preprocess.owl")), robotRan);
    }

    /** make keeps a continued line's backslash and line break; the shell takes them out. */
    @Test
    void aContinuedLineIsOneLine() {
        run("robot --catalog catalog-v001.xml remove --input tmp/eggboil-preprocess.owl "
                + "--select imports --trim false \\\n\t merge \\\n\t       --output "
                + "tmp/merged-eggboil-edit.ofn");

        assertEquals(Arrays.asList("--catalog", "catalog-v001.xml", "remove", "--input",
                "tmp/eggboil-preprocess.owl", "--select", "imports", "--trim", "false", "merge",
                "--output", "tmp/merged-eggboil-edit.ofn"), robotRan.get(0));
    }

    @Test
    void quotesKeepAWordTogetherAndAreNotPartOfIt() {
        assertEquals(0, run("echo \"ODK Makefile v1.6.1\" 'single $quoted' plain\\ escaped"));

        assertEquals(Collections.singletonList("ODK Makefile v1.6.1 single $quoted plain "
                + "escaped"), said);
    }

    /** A message make continues inside its quotes: the line break goes, the blanks stay. */
    @Test
    void aLineBreakInsideDoubleQuotesIsTakenOut() {
        run("echo \"Release files are now in ../.. - now you should commit, push and make a "
                + "release \\\n        on your git hosting site such as GitHub or GitLab\"");

        assertEquals(Collections.singletonList("Release files are now in ../.. - now you "
                + "should commit, push and make a release         on your git hosting site such "
                + "as GitHub or GitLab"), said);
    }

    // ------------------------------------------------------------------ and, or, groups, if

    @Test
    void andRunsTheSecondOnlyAfterSuccess() throws IOException {
        write("test.owl", "x");

        assertEquals(0, run("robot --catalog catalog-v001.xml reason --input "
                + "tmp/eggboil-preprocess.owl --output test.owl && rm test.owl"));
        assertFalse(file("test.owl").exists());

        write("test.owl", "x");
        robotStatus = 1;
        assertEquals(1, run("robot reason --output test.owl && rm test.owl"));
        assertTrue(file("test.owl").exists(), "the reasoner failed: its output is not removed");
    }

    /** ODK's profile check: when robot fails, its report is shown and the line fails. */
    @Test
    void orRunsTheGroupOnlyAfterFailure() throws IOException {
        String line = "robot --catalog catalog-v001.xml validate-profile --profile DL -i "
                + "tmp/validate.ofn -o reports/validate_profile_owl2dl_eggboil.owl.txt || { cat "
                + "reports/validate_profile_owl2dl_eggboil.owl.txt && exit 1; }";
        write("reports/validate_profile_owl2dl_eggboil.owl.txt", "PROFILE VIOLATION\nline two\n");

        assertEquals(0, run(line));
        assertEquals(Collections.singletonList("robot ran"), said);

        said.clear();
        robotStatus = 1;
        assertEquals(1, run(line));
        assertEquals(Arrays.asList("robot ran", "PROFILE VIOLATION", "line two"), said);
    }

    /** ODK's check of the ID ranges: only when the project has the file. */
    @Test
    void ifTestsForAFile() throws IOException {
        Map<String, RecipeShell.Tool> tools = tools();
        final List<List<String>> dicer = new ArrayList<List<String>>();
        tools.put("dicer-cli", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                dicer.add(call.arguments());
                return 0;
            }
        });
        RecipeShell.Script script = RecipeShell.parse("if [ -f eggboil-idranges.owl ]; then \\\n"
                + "\tdicer-cli policy --assume-manchester --show-owlapi-error "
                + "eggboil-idranges.owl ; \\\nfi");

        assertEquals(0, script.run(tools, folder, said));
        assertEquals(0, dicer.size(), "no such file, nothing to check");

        write("eggboil-idranges.owl", "x");
        assertEquals(0, script.run(tools, folder, said));
        assertEquals(Collections.singletonList(Arrays.asList("policy", "--assume-manchester",
                "--show-owlapi-error", "eggboil-idranges.owl")), dicer);
    }

    /**
     * ODK's check that the Makefile is still the one of its configuration: the file's hash,
     * through three commands of the shell, against the hash the Makefile recorded.
     */
    @Test
    void theConfigurationCheckComparesTheFilesHash() throws IOException {
        String config = Goldens.config("eggboil");
        write("eggboil-odk.yaml", config.replace("\n", "\r\n"));
        String hash = OdkProjectModel.configHash(config);
        String line = "if [ \"$(tr -d '\\r' < eggboil-odk.yaml | sha256sum | cut -c1-64)\" = \""
                + hash + "\" ]; then \\\n\techo \"Repository is up-to-date.\" ; else \\\n\techo "
                + "\"Your ODK configuration has changed since this Makefile was generated. You "
                + "may need to run 'make update_repo'.\" ; fi";

        assertEquals(0, run(line));
        assertEquals(Collections.singletonList("Repository is up-to-date."), said);

        said.clear();
        write("eggboil-odk.yaml", config + "# changed\n");
        assertEquals(0, run(line));
        assertEquals(Collections.singletonList("Your ODK configuration has changed since this "
                + "Makefile was generated. You may need to run 'make update_repo'."), said);
    }

    /** A component that was downloaded: kept when it is the one there already. */
    @Test
    void ifComparesTwoFilesAndElseRunsOtherwise() throws IOException {
        String line = "if cmp -s tmp/a.owl tmp/b.owl ; then \\\n        echo \"Component "
                + "identical.\" ; \\\nelse \\\n        echo \"Component different, updating.\" "
                + "&& \\\n        cp tmp/a.owl tmp/b.owl && \\\n        robot annotate --input "
                + "tmp/a.owl ; \\\nfi";
        write("tmp/a.owl", "new");
        write("tmp/b.owl", "new");

        assertEquals(0, run(line));
        assertEquals(Collections.singletonList("Component identical."), said);
        assertEquals(0, robotRan.size());

        said.clear();
        write("tmp/b.owl", "old");
        assertEquals(0, run(line));
        assertEquals(Arrays.asList("Component different, updating.", "robot ran"), said);
        assertEquals("new", read("tmp/b.owl"));
    }

    // ------------------------------------------------------------------ pipes and files

    /** ODK's term list for an import: what the ontology uses, each term once. */
    @Test
    void aPipeHandsEachCommandsOutputToTheNextAndTheLastWritesAFile() throws IOException {
        write("tmp/pre_seed.txt", "http://x/B\nhttp://x/A\nhttp://x/B\n");

        assertEquals(0, run("cat tmp/pre_seed.txt | sort | uniq > tmp/seed.txt"));

        assertEquals("http://x/A\nhttp://x/B\n", read("tmp/seed.txt"));
        assertEquals(0, said.size(), "what went to the file is not said as well");
    }

    /**
     * ODK's seed for the simple artefact: the terms a query found and the ontology's own, each
     * once, and two properties added to the file's end.
     */
    @Test
    void twoGreaterThanSignsAddToTheEndOfAFile() throws IOException {
        write("tmp/simple_seed.txt.tmp", "http://x/B\n");
        write("tmp/ontologyterms.txt", "http://x/A\nhttp://x/B\n");

        assertEquals(0, run("robot --catalog catalog-v001.xml query -f csv -i "
                + "tmp/merged-eggboil-edit.ofn --query ../sparql/simple-seed.sparql "
                + "tmp/simple_seed.txt.tmp &&\\\ncat tmp/simple_seed.txt.tmp "
                + "tmp/ontologyterms.txt | sort | uniq >  tmp/simple_seed.txt &&\\\necho "
                + "\"http://www.geneontology.org/formats/oboInOwl#SubsetProperty\" >> "
                + "tmp/simple_seed.txt &&\\\necho "
                + "\"http://www.geneontology.org/formats/oboInOwl#SynonymTypeProperty\" >> "
                + "tmp/simple_seed.txt"));

        assertEquals("http://x/A\nhttp://x/B\n"
                + "http://www.geneontology.org/formats/oboInOwl#SubsetProperty\n"
                + "http://www.geneontology.org/formats/oboInOwl#SynonymTypeProperty\n",
                read("tmp/simple_seed.txt"));
    }

    /**
     * How ODK puts a ROBOT plugin where robot looks: from the project when it brings one,
     * else from the image, else not at all. Outside the image neither file is there.
     */
    @Test
    void elifIsTheSecondQuestionOfAnIf() throws IOException {
        String line = "if [ -f ../../plugins/kgcl.jar ]; then ln ../../plugins/kgcl.jar "
                + "tmp/plugins/kgcl.jar ; elif [ -f /tools/robot-plugins/kgcl.jar ]; then cp "
                + "/tools/robot-plugins/kgcl.jar tmp/plugins/kgcl.jar ; fi";
        assertEquals(0, run("mkdir -p tmp/plugins"));

        assertEquals(0, run(line));
        assertEquals(0, file("tmp/plugins").list().length, "neither is there: nothing to do");

        write("../../plugins/kgcl.jar", "the project's own");
        assertEquals(0, run(line));
        assertEquals("the project's own", read("tmp/plugins/kgcl.jar"));
    }

    @Test
    void ifElifElseTakeTheFirstBranchWhoseQuestionHolds() throws IOException {
        String line = "if [ -f a ]; then echo first ; elif [ -f b ]; then echo second ; else "
                + "echo neither ; fi";

        run(line);
        write("b", "");
        run(line);
        write("a", "");
        run(line);

        assertEquals(Arrays.asList("neither", "second", "first"), said);
    }

    @Test
    void theShellsOwnCommandsDoWhatTheirNamesSay() throws IOException {
        assertEquals(0, run("mkdir -p reports"));
        assertTrue(file("reports").isDirectory());
        assertEquals(0, run("mkdir -p reports"), "there already: no error");

        assertEquals(1, run("touch tmp/stamp-component-fetched.owl"), "no such folder yet");
        assertEquals(0, run("mkdir -p tmp && touch tmp/stamp-component-fetched.owl"));
        assertTrue(file("tmp/stamp-component-fetched.owl").isFile());

        write("a.owl", "a");
        assertEquals(0, run("rm -f a.owl never-there.owl"));
        assertFalse(file("a.owl").exists());
        assertEquals(1, run("rm never-there.owl"), "without -f a missing file is an error");

        // ODK writes a product under another name and moves it into place when robot is done.
        write("eggboil.owl.tmp.owl", "new");
        write("eggboil.owl", "old");
        assertEquals(0, run("mv eggboil.owl.tmp.owl eggboil.owl"));
        assertEquals("new", read("eggboil.owl"));
        assertFalse(file("eggboil.owl.tmp.owl").exists());
        assertEquals(1, run("mv never-there.owl eggboil.owl"));

        assertEquals(1, run("false"));
        assertEquals(0, run("true"));
    }

    /** {@code rsync -R}: each file under the path it has from here, at the place named. */
    @Test
    void theReleaseFilesAreCopiedToTheTopOfTheProject() throws IOException {
        write("eggboil.owl", "owl");
        write("eggboil-base.owl", "base");
        write("subsets/slim.owl", "slim");

        assertEquals(0, run("rsync -R   eggboil.owl  eggboil-base.owl subsets/slim.owl   "
                + "../.."));

        File root = folder.getParentFile().getParentFile();
        assertEquals("owl", new String(Files.readAllBytes(new File(root, "eggboil.owl")
                .toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(root, "eggboil-base.owl").isFile());
        assertTrue(new File(root, "subsets/slim.owl").isFile());
        assertTrue(file("eggboil.owl").isFile(), "copied, not moved");
    }

    /**
     * ODK's clean: everything inside mirror, tmp and target - when they are inside this
     * folder - and never the folders themselves.
     */
    @Test
    void cleanEmptiesTheFoldersItNames() throws IOException {
        write("tmp/merged.ofn", "x");
        write("tmp/deeper/y.txt", "y");
        write("tmp/.gitkeep", "");
        write("mirror/ro.owl", "ro");
        write("eggboil-edit.owl", "edit");

        assertEquals(0, run("for dir in mirror tmp target ; do      \\\n\treldir=$(realpath "
                + "--relative-to=$(pwd) $dir) ;    \\\n\tcase $reldir in .*|\"\") ;; *) rm -rf "
                + "$reldir/* ;; esac \\\ndone"));

        assertTrue(file("tmp").isDirectory());
        assertEquals(Collections.singletonList(".gitkeep"), Arrays.asList(file("tmp").list()),
                "the shell's star leaves out a hidden file, and ODK keeps this one in git");
        assertEquals(0, file("mirror").list().length);
        assertFalse(file("target").exists());
        assertTrue(file("eggboil-edit.owl").isFile());
    }

    /** A folder of the loop that is this folder, or lies outside it, is left alone. */
    @Test
    void cleanLeavesAloneAFolderThatIsNotInside() throws IOException {
        write("eggboil-edit.owl", "edit");
        write("../keep.txt", "keep");

        assertEquals(0, run("for dir in . .. ../.. ; do reldir=$(realpath --relative-to=$(pwd) "
                + "$dir) ; case $reldir in .*|\"\") ;; *) rm -rf $reldir/* ;; esac done"));

        assertTrue(file("eggboil-edit.owl").isFile());
        assertTrue(file("../keep.txt").isFile());
    }

    // ------------------------------------------------------------------ what is kept safe

    /** A recipe writes inside the project. A path that leaves it is refused before it runs. */
    @Test
    void nothingIsWrittenOrRemovedOutsideTheProject() throws IOException {
        File outside = new File(temp, "outside.txt");
        Files.write(outside.toPath(), "keep".getBytes(StandardCharsets.UTF_8));

        assertTrue(whyNot("rm -f ../../../outside.txt").contains("outside the project"));
        assertTrue(whyNot("cp eggboil.owl ../../../copy.owl").contains("outside the project"));
        assertTrue(whyNot("echo x > ../../../outside.txt").contains("outside the project"));
        assertTrue(whyNot("rsync -R eggboil.owl ../../..").contains("outside the project"));
        assertTrue(whyNot("rm -rf ../..").contains("the project's own folder"),
                whyNot("rm -rf ../.."));
        assertTrue(outside.isFile());
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void whatTheShellPartHereDoesNotReadIsRefusedByName() {
        assertTrue(whyNot("echo $HOME").contains("$HOME"), whyNot("echo $HOME"));
        assertTrue(whyNot("cat *.owl").contains("a file pattern ('*.owl')"), whyNot("cat *.owl"));
        assertTrue(whyNot("cat x[ab].owl").contains("x[ab].owl"), whyNot("cat x[ab].owl"));
        assertTrue(whyNot("cat {a,b}.owl").contains("{a,b}.owl"), whyNot("cat {a,b}.owl"));
        assertTrue(whyNot("robot merge &").contains("&"));
        assertTrue(whyNot("(cd .. && make)").contains("("));
        assertTrue(whyNot("echo `date`").contains("`"));
        assertTrue(whyNot("X=1 robot merge").contains("X=1"));
        assertTrue(whyNot("robot merge 2> errors.txt").contains("2>"));
        assertTrue(whyNot("robot merge >& all.txt").contains(">&"));
        assertTrue(whyNot("while true; do echo x; done").contains("while"));
        assertTrue(whyNot("echo \"unfinished").contains("quote"), whyNot("echo \"unfinished"));
    }

    @Test
    void aCommandNobodyKnowsIsRefusedBeforeAnythingRuns() throws IOException {
        write("a.owl", "a");

        String why = whyNot("rm -f a.owl && owltools a.owl --merge-support-ontologies");

        assertNotNull(why);
        assertTrue(why.contains("owltools"), why);
        assertTrue(file("a.owl").isFile(), "asked first, so the first half did not run");
    }

    @Test
    void aToolSaysItselfWhatItCannotDo() {
        assertEquals("robot expand is not run here", whyNot("mkdir -p x && robot expand -i x"));
        assertTrue(whyNot("mkdir reports").contains("-p"), whyNot("mkdir reports"));
        assertTrue(whyNot("echo -n x").contains("-n"), whyNot("echo -n x"));
        assertTrue(whyNot("rsync -a x ../..").contains("rsync -R"), whyNot("rsync -a x ../.."));
        assertTrue(whyNot("tr a-z A-Z").contains("tr -d"), whyNot("tr a-z A-Z"));
    }

    // ------------------------------------------------------------------ every line ODK has

    /**
     * Every recipe line of the fast test, the fast release and clean, for every seeded
     * project: read, and every command in it known - or the line is one of the few listed
     * here with why. A new construct in a later ODK shows up as a line that is neither.
     */
    @Test
    void everyLineOfTheTargetsTheEngineOffersIsRead() {
        Map<String, RecipeShell.Tool> tools = new LinkedHashMap<String, RecipeShell.Tool>(
                RecipeShell.builtins());
        for (String name : Arrays.asList("robot", "odk-info", "dicer-cli", "check-rdfxml",
                "make")) {
            tools.put(name, new RecipeShell.Tool() {
                @Override
                public String whyNot(List<String> arguments) {
                    return null;
                }

                @Override
                public int run(RecipeShell.Call call) {
                    return 0;
                }
            });
        }
        TreeSet<String> refused = new TreeSet<String>();
        int lines = 0;
        for (String seed : Goldens.seedNames()) {
            Map<String, String> fixed = Goldens.makeFixedVariables();
            for (String goal : Arrays.asList("test_fast", "prepare_release_fast", "clean")) {
                MakeFile make = MakeFile.read("Makefile", new SeedDisk(seed), fixed);
                for (String command : commands(make.runPlan(goal))) {
                    lines++;
                    String why;
                    try {
                        why = RecipeShell.parse(command).whyNot(tools);
                    } catch (RecipeShell.NotRead notRead) {
                        why = notRead.getMessage();
                    }
                    if (why != null) {
                        refused.add(seed + " " + goal + ": " + why);
                    }
                }
            }
        }
        assertTrue(lines > 200, "the plans were read: " + lines + " lines");
        assertEquals(new TreeSet<String>(), refused);
    }

    private static List<String> commands(List<MakeFile.Step> steps) {
        List<String> commands = new ArrayList<String>();
        for (MakeFile.Step step : steps) {
            if (step.kind() == MakeFile.Step.Kind.COMMAND) {
                commands.add(step.command());
            }
            commands.addAll(commands(step.steps()));
        }
        return commands;
    }

    /** A seeded project as make's recorded runs saw it: every file one age. */
    private static final class SeedDisk implements MakeFile.Disk {
        private final Map<String, byte[]> files;

        SeedDisk(String seed) {
            files = Goldens.seedTree(seed);
        }

        private String key(String path) {
            List<String> parts = new ArrayList<String>(Arrays.asList("src", "ontology"));
            if (path.startsWith("/")) {
                return null;
            }
            for (String part : path.split("/")) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (parts.isEmpty()) {
                        return null;
                    }
                    parts.remove(parts.size() - 1);
                } else {
                    parts.add(part);
                }
            }
            return String.join("/", parts);
        }

        @Override
        public boolean exists(String path) {
            String key = key(path);
            if (key == null) {
                return path.startsWith("/tools/robot-plugins");
            }
            if (key.isEmpty() || files.containsKey(key)) {
                return true;
            }
            for (String file : files.keySet()) {
                if (file.startsWith(key + "/")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long modified(String path) {
            return 1_762_862_400_000L;
        }

        @Override
        public List<String> list(String folder) {
            if (folder.startsWith("/tools/robot-plugins")) {
                return Arrays.asList("kgcl.jar", "odk.jar", "sssom.jar");
            }
            String key = key(folder);
            TreeSet<String> names = new TreeSet<String>();
            if (key != null) {
                String prefix = key.isEmpty() ? "" : key + "/";
                for (String file : files.keySet()) {
                    if (file.startsWith(prefix) && file.length() > prefix.length()) {
                        String rest = file.substring(prefix.length());
                        names.add(rest.contains("/") ? rest.substring(0, rest.indexOf('/'))
                                : rest);
                    }
                }
            }
            return new ArrayList<String>(names);
        }

        @Override
        public String read(String path) {
            String key = key(path);
            byte[] bytes = key == null ? null : files.get(key);
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String folder() {
            return "/work/src/ontology";
        }
    }

    // ------------------------------------------------------------------ links (review of 1.153.0)

    private static final String CLEAN = "for dir in mirror tmp target ; do reldir=$(realpath "
            + "--relative-to=$(pwd) $dir) ; case $reldir in .*|\"\") ;; *) rm -rf $reldir/* ;; "
            + "esac done";

    /**
     * A folder of the loop that is a link to somewhere else is left alone, as ODK's own loop
     * leaves it: realpath says where it really is, and that is outside. Found by the review
     * of 1.153.0 - a mirror kept elsewhere behind a link was emptied by every release, which
     * cleans first.
     */
    @Test
    void cleanLeavesAloneAFolderThatLeadsOutOfTheProject() throws Exception {
        File elsewhere = new File(temp, "shared-mirror");
        Files.createDirectories(elsewhere.toPath());
        Files.write(new File(elsewhere, "big.owl").toPath(),
                "keep".getBytes(StandardCharsets.UTF_8));
        Links.link(file("mirror"), elsewhere);
        write("tmp/scratch.txt", "scratch");

        assertEquals(0, run(CLEAN));

        assertTrue(new File(elsewhere, "big.owl").isFile(),
                "what the link leads to is somebody's, and stays");
        assertFalse(file("tmp/scratch.txt").exists(), "the project's own scratch is cleaned");
    }

    /** A link inside a folder that is cleaned goes as the link it is; nothing behind it does. */
    @Test
    void cleanTakesALinkAwayAndNotWhatItLeadsTo() throws Exception {
        File elsewhere = new File(temp, "somebodys");
        Files.createDirectories(elsewhere.toPath());
        Files.write(new File(elsewhere, "theirs.txt").toPath(),
                "keep".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(file("tmp").toPath());
        Links.link(file("tmp/cache"), elsewhere);

        assertEquals(0, run(CLEAN));

        assertTrue(new File(elsewhere, "theirs.txt").isFile());
        assertFalse(file("tmp/cache").exists(), "the link itself is what was in tmp");
    }

    /** rm -rf of a folder that holds a link does the same. */
    @Test
    void rmDoesNotGoThroughALink() throws Exception {
        File elsewhere = new File(temp, "somebodys");
        Files.createDirectories(elsewhere.toPath());
        Files.write(new File(elsewhere, "theirs.txt").toPath(),
                "keep".getBytes(StandardCharsets.UTF_8));
        write("tmp/build/a.txt", "a");
        Links.link(file("tmp/build/linked"), elsewhere);

        assertEquals(0, run("rm -rf tmp/build"));

        assertTrue(new File(elsewhere, "theirs.txt").isFile());
        assertFalse(file("tmp/build").exists());
    }

    /**
     * On Windows a backslash parts folders as a slash does, so the guard reads it as one
     * everywhere: quoted, such a path was one name to it and went through (review of 1.153.0).
     */
    @Test
    void aPathWrittenWithBackslashesIsHeldToTheProjectToo() throws IOException {
        File outside = new File(temp, "outside.txt");
        Files.write(outside.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        String up = "..\\..\\..\\outside.txt";

        assertTrue(String.valueOf(whyNot("rm -f '" + up + "'")).contains("outside the project"),
                String.valueOf(whyNot("rm -f '" + up + "'")));
        assertTrue(String.valueOf(whyNot("echo x > '" + up + "'")).contains(
                "outside the project"));
        assertTrue(String.valueOf(whyNot("rm -rf '..\\..'")).contains(
                "the project's own folder"));
        assertTrue(outside.isFile());
    }
}
