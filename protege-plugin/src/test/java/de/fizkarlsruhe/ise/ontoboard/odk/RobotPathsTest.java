package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which words of a ROBOT command line name files, so that they can be read where make reads
 * them.
 *
 * <p>A recipe says {@code robot --catalog catalog-v001.xml merge --input tmp/x.owl ...}: paths
 * relative to {@code src/ontology}, where make runs. Inside Protégé a relative path is read
 * against the folder Protégé was started from, and Java cannot change its working folder. So
 * every argument that names a file or a folder is made absolute first - and for that one has to
 * know, for every option of every ROBOT command, whether its value is a file. What this class
 * cannot place it refuses: a file left relative would be read in the wrong folder, silently.
 */
class RobotPathsTest {

    @TempDir
    File temp;

    private File folder() {
        return new File(temp, "eggboil/src/ontology");
    }

    private static String prefix(File folder) {
        return folder.getAbsolutePath() + File.separator;
    }

    private static List<String> words(String line) {
        return Arrays.asList(line.trim().split(" +"));
    }

    // ------------------------------------------------------------------ every line ODK has

    /**
     * The options whose value changed in a {@code robot} call of the 96 recorded dry runs and the
     * two equivalence transcripts: the files of ODK's recipes, as {@code <command> <option>}. A
     * reader sees here which options name files; ODK's next Makefile that names a file another
     * way shows up as a difference.
     */
    private static final List<String> FILES_ODK_NAMES = Arrays.asList(
            "(global) --catalog",
            "annotate --input", "annotate --output",
            "convert --input", "convert --output", "convert -i", "convert -o",
            "extract --term-file",
            "filter --term-file",
            "merge --input", "merge --output", "merge -i",
            "query --input", "query --query", "query -i", "query -s",
            "reason --input", "reason --output",
            "remove --input", "remove --term-file",
            "report --profile", "report -i", "report -o",
            "template --template",
            "validate-profile -i", "validate-profile -o",
            "verify --queries", "verify -O", "verify -i");

    @Test
    void everyRobotLineOdkHasIsPlaced() {
        List<List<String>> calls = new ArrayList<List<String>>();
        for (String seed : Goldens.seedNames()) {
            for (String label : Goldens.makeLabels().keySet()) {
                collect(Goldens.makeDryRun(seed, label), calls);
            }
        }
        collect(new String(Goldens.equivalence("test_fast/transcript.txt"),
                StandardCharsets.UTF_8), calls);
        collect(new String(Goldens.equivalence("prepare_release_fast/transcript.txt"),
                StandardCharsets.UTF_8), calls);
        assertTrue(calls.size() > 300, "the recorded robot lines were read: " + calls.size());

        File folder = new File(temp, "Mein Projekt \u00e4/src/ontology");
        Set<String> changed = new TreeSet<String>();
        for (List<String> call : calls) {
            List<String> absolute = RobotPaths.absolute(call, folder);
            assertEquals(call.size(), absolute.size(), call.toString());
            for (int i = 0; i < call.size(); i++) {
                if (absolute.get(i).equals(call.get(i))) {
                    continue;
                }
                assertEquals(prefix(folder) + call.get(i), absolute.get(i), call.toString());
                assertFalse(call.get(i).startsWith("http"), call.get(i) + " in " + call);
                changed.add(commandOf(call, i) + " " + optionOf(call, i));
            }
        }
        assertEquals(new TreeSet<String>(FILES_ODK_NAMES), changed);
    }

    /** Every {@code robot} call of a recorded run, its arguments as the shell hands them over. */
    private static void collect(String recorded, final List<List<String>> calls) {
        final RecipeShell.Tool robot = new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                calls.add(new ArrayList<String>(arguments));
                return null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                throw new AssertionError("nothing is run here");
            }
        };
        final RecipeShell.Tool anything = new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                throw new AssertionError("nothing is run here");
            }
        };
        Map<String, RecipeShell.Tool> tools = new LinkedHashMap<String, RecipeShell.Tool>() {
            private static final long serialVersionUID = 1L;

            @Override
            public RecipeShell.Tool get(Object name) {
                return "robot".equals(name) ? robot : anything;
            }
        };
        StringBuilder line = new StringBuilder();
        for (String printed : recorded.split("\n", -1)) {
            line.append(printed);
            if (printed.endsWith("\\")) {
                line.append('\n');
                continue;
            }
            String command = line.toString();
            line.setLength(0);
            if (!command.matches("(?s).*\\brobot\\b.*")) {
                continue;
            }
            try {
                RecipeShell.parse(command).whyNot(tools);
            } catch (RecipeShell.NotRead notRead) {
                fail("a recorded robot line the shell reader refuses: " + command + " - "
                        + notRead.getMessage());
            }
        }
    }

    /** The command a word belongs to: the nearest command name before it. */
    private static String commandOf(List<String> call, int at) {
        List<String> commands = OdkRobot.commandNames();
        for (int i = at - 1; i >= 0; i--) {
            if (commands.contains(call.get(i))) {
                return call.get(i);
            }
        }
        return "(global)";
    }

    /** The option a value belongs to: the nearest option before it. */
    private static String optionOf(List<String> call, int at) {
        for (int i = at - 1; i >= 0; i--) {
            if (call.get(i).startsWith("-")) {
                return call.get(i);
            }
        }
        return "(none)";
    }

    // ------------------------------------------------------------------ every option ROBOT has

    /**
     * Every option of every command OdkRobot registers - and of the options before the first
     * command - is placed, and the place agrees with how many values the live option takes. A
     * ROBOT that gains an option fails here until somebody says whether it names a file.
     */
    @Test
    void everyOptionOfEveryCommandIsPlaced() {
        int placed = 0;
        List<String> commands = new ArrayList<String>();
        commands.add(null);
        commands.addAll(OdkRobot.commandNames());
        assertTrue(commands.size() >= 27, commands.toString());
        for (String command : commands) {
            Options options = command == null ? RobotPaths.globalOptions()
                    : OdkRobot.options(command);
            assertNotNull(options, command);
            for (Option option : everyOption(options)) {
                String name = (command == null ? "(global)" : command) + " "
                        + (option.getLongOpt() != null ? "--" + option.getLongOpt()
                                : "-" + option.getOpt());
                RobotPaths.Kind kind;
                try {
                    kind = RobotPaths.kind(command, option);
                } catch (RobotPaths.Unknown unknown) {
                    fail(name + " is not placed: " + unknown.getMessage());
                    return;
                }
                switch (kind) {
                    case NONE:
                        assertFalse(option.hasArg(), name + " takes a value");
                        break;
                    case VALUE:
                        assertTrue(option.hasArg(), name + " takes no value");
                        break;
                    case FILE:
                        assertEquals(1, option.getArgs(), name);
                        break;
                    case FILES:
                        assertEquals(Option.UNLIMITED_VALUES, option.getArgs(), name);
                        break;
                    case FILE_PAIRS:
                        assertEquals(2, option.getArgs(), name);
                        break;
                    default:
                        fail(name + ": " + kind);
                }
                placed++;
            }
        }
        assertTrue(placed > 500, "options placed: " + placed);
    }

    /** What the parser knows: the options by their short keys, and those only a long name reaches. */
    private static Set<Option> everyOption(Options options) {
        Set<Option> every = new LinkedHashSet<Option>(options.getOptions());
        for (String longName : options.getMatchingOptions("")) {
            every.add(options.getOption(longName));
        }
        return every;
    }

    // ------------------------------------------------------------------ one case per rule

    @Test
    void severalValuesAreEachMadeAbsolute() {
        List<String> line = words("verify -i tmp/merged.ofn --queries ../sparql/a.sparql "
                + "../sparql/b.sparql ../sparql/c.sparql -O reports");
        String at = prefix(folder());

        assertEquals(words("verify -i " + at + "tmp/merged.ofn --queries " + at
                + "../sparql/a.sparql " + at + "../sparql/b.sparql " + at + "../sparql/c.sparql -O "
                + at + "reports"), RobotPaths.absolute(line, folder()));
    }

    /** {@code query --query} and {@code --select} take a query file and an output file each. */
    @Test
    void aPairIsAQueryFileAndAnOutputFile() {
        List<String> line = words("query -f tsv --use-graphs true -i tmp/pre.owl -s "
                + "../sparql/edges.sparql reports/edges.tsv -s ../sparql/xrefs.sparql "
                + "reports/xrefs.tsv --query ../sparql/terms.sparql tmp/terms.txt");
        String at = prefix(folder());

        assertEquals(words("query -f tsv --use-graphs true -i " + at + "tmp/pre.owl -s " + at
                + "../sparql/edges.sparql " + at + "reports/edges.tsv -s " + at
                + "../sparql/xrefs.sparql " + at + "reports/xrefs.tsv --query " + at
                + "../sparql/terms.sparql " + at + "tmp/terms.txt"),
                RobotPaths.absolute(line, folder()));
    }

    /** ODK's {@code ROBOT} variable is {@code robot --catalog catalog-v001.xml}: before any command. */
    @Test
    void theGlobalCatalogIsAFile() {
        String at = prefix(folder());

        assertEquals(words("--catalog " + at + "catalog-v001.xml merge -i " + at + "a.owl"),
                RobotPaths.absolute(words("--catalog catalog-v001.xml merge -i a.owl"), folder()));
    }

    @Test
    void anAbsolutePathStays() {
        String absolute = new File(temp, "elsewhere/a.owl").getAbsolutePath();

        assertEquals(words("convert -i " + absolute + " -o " + prefix(folder()) + "b.owl"),
                RobotPaths.absolute(words("convert -i " + absolute + " -o b.owl"), folder()));
    }

    @Test
    void aWindowsDrivePathStays() {
        assertEquals(words("convert -i C:\\data\\a.owl -o D:/out/b.owl"),
                RobotPaths.absolute(words("convert -i C:\\data\\a.owl -o D:/out/b.owl"),
                        folder()));
    }

    @Test
    void anIriStays() {
        List<String> line = words("convert --input https://example.org/a.owl --catalog "
                + "http://example.org/catalog.xml annotate --annotation-file file:/tmp/a.ttl");

        assertEquals(line, RobotPaths.absolute(line, folder()));
    }

    /** Values that are no files - words, IRIs, numbers - are left as they are. */
    @Test
    void aValueThatIsNoFileStays() {
        List<String> line = words("annotate --ontology-iri https://example.org/x.owl -V "
                + "https://example.org/1.0/x.owl --annotation owl:versionInfo 1.0.0 "
                + "--link-annotation dc:type obo:IAO_8000001 reason --reasoner ELK "
                + "--exclude-tautologies structural");

        assertEquals(line, RobotPaths.absolute(line, folder()));
    }

    /**
     * {@code validate-profile --profile DL} is a word; {@code report --profile profile.txt} is a
     * file. The same option name, two commands, two tables.
     */
    @Test
    void theSameOptionNameIsAFileInOneCommandAndAWordInAnother() {
        String at = prefix(folder());

        assertEquals(words("validate-profile --profile DL -i " + at + "tmp/v.ofn"),
                RobotPaths.absolute(words("validate-profile --profile DL -i tmp/v.ofn"), folder()));
        assertEquals(words("report -i " + at + "x.ofn --profile " + at + "profile.txt -o " + at
                + "r.tsv"), RobotPaths.absolute(words("report -i x.ofn --profile profile.txt "
                        + "-o r.tsv"), folder()));
        // -p is report's profile, and merge's wildcard pattern of inputs.
        assertEquals(words("report -p " + at + "profile.txt"),
                RobotPaths.absolute(words("report -p profile.txt"), folder()));
    }

    /**
     * ODK's Makefile has {@code query ... --format --csv}: ROBOT's parser takes {@code --csv},
     * which is no option of query, as the format's value. It is a word, and the line goes on.
     */
    @Test
    void aValueThatLooksLikeAnOptionIsTheValue() {
        String at = prefix(folder());

        assertEquals(words("query --input " + at + "tmp/m.ofn --format --csv --query " + at
                + "../sparql/terms.sparql " + at + "tmp/pre_seed.txt"),
                RobotPaths.absolute(words("query --input tmp/m.ofn --format --csv --query "
                        + "../sparql/terms.sparql tmp/pre_seed.txt"), folder()));
    }

    /** The prefix is the folder and a separator, and the path itself is kept as written. */
    @Test
    void aPathIsNotNormalised() {
        List<String> absolute = RobotPaths.absolute(words("verify -i ./tmp/../tmp/m.ofn "
                + "--queries ../sparql/check_labels-violation.sparql -O reports"), folder());

        assertEquals(prefix(folder()) + "./tmp/../tmp/m.ofn", absolute.get(2));
        assertEquals(prefix(folder()) + "../sparql/check_labels-violation.sparql",
                absolute.get(4));
    }

    @Test
    void aFolderWithABlankAndAnUmlautIsTheFolder() {
        File folder = new File(temp, "Mein Projekt \u00e4/src/ontology");

        List<String> absolute = RobotPaths.absolute(words("convert -i a.owl -o b.owl"), folder);

        assertEquals(folder.getAbsolutePath() + File.separator + "a.owl", absolute.get(2));
        assertTrue(absolute.get(2).contains("Mein Projekt \u00e4"), absolute.get(2));
        assertEquals(new File(folder, "b.owl").getAbsolutePath(), absolute.get(4));
    }

    /** ODK's plugin commands are commands like ROBOT's own. */
    @Test
    void odksPluginCommandsArePlaced() {
        String at = prefix(folder());

        assertEquals(words("odk:normalize -i " + at + "a.owl --add-source true odk:subset "
                + "--term-file " + at + "terms.txt --write-to " + at + "subset.owl odk:import "
                + "--add https://example.org/i.owl -o " + at + "b.owl"),
                RobotPaths.absolute(words("odk:normalize -i a.owl --add-source true odk:subset "
                        + "--term-file terms.txt --write-to subset.owl odk:import --add "
                        + "https://example.org/i.owl -o b.owl"), folder()));
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void anUnknownCommandIsRefusedByName() {
        RobotPaths.Unknown unknown = assertThrows(RobotPaths.Unknown.class,
                () -> RobotPaths.absolute(words("merge -i a.owl frobnicate -o b.owl"),
                        folder()));

        assertTrue(unknown.getMessage().contains("'frobnicate'"), unknown.getMessage());
    }

    @Test
    void anUnknownOptionIsRefusedByName() {
        RobotPaths.Unknown unknown = assertThrows(RobotPaths.Unknown.class,
                () -> RobotPaths.absolute(words("merge -i a.owl --frobnicate b.owl"), folder()));

        assertTrue(unknown.getMessage().contains("--frobnicate"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("merge"), unknown.getMessage());
    }

    /**
     * ROBOT's parser also reads {@code --input=a.owl}, {@code -ia.owl} and a long name cut short;
     * ODK writes none of them. They are refused rather than guessed at.
     */
    @Test
    void anOptionWrittenInAnotherFormIsRefusedByName() {
        for (String written : Arrays.asList("--input=a.owl", "--inp", "-ia.owl")) {
            RobotPaths.Unknown unknown = assertThrows(RobotPaths.Unknown.class,
                    () -> RobotPaths.absolute(words("convert " + written + " -o b.owl"),
                            folder()), written);
            assertTrue(unknown.getMessage().contains(written), unknown.getMessage());
        }
    }

    @Test
    void aLineWithoutACommandIsLeftToRobotToRefuse() {
        assertEquals(new ArrayList<String>(), RobotPaths.absolute(new ArrayList<String>(),
                folder()));
    }
}
