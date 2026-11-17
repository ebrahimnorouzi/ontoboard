package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How far OntoBoard's own engine reaches over ODK's own sample configurations - the 79 that
 * ODK v1.6.1 keeps under {@code configs/}, {@code tests/} and {@code examples/}: each seeded
 * here from ODK's templates (the seed is ODK's, file for file: {@code SeedCorpusTest}), and
 * every target of its Makefile that a person runs asked of the engine. Nothing is run.
 *
 * <p>{@link EngineReachTest} asks the same of OntoBoard's own kind of project and keeps the
 * first reason. Here a row keeps every reason a target is not run: each program a line needs
 * that the engine has no stand-in for, each call a program it has refuses, each part of the
 * shell it does not read. So the table also says what would be left of a target if one reason
 * were taken away - which is what decides the order of the work.
 *
 * <p>dosdp-tools is asked as it is once OntoBoard has downloaded it, with a Java 11 to run it
 * on: the table says what the engine runs then, and is the same on every machine.
 *
 * <p>The table is kept in {@code odk/engine-reach-79.txt}; a change that makes the engine run
 * a target it refused, or refuse one it ran, or that changes why, shows as a line that
 * differs. It is then brought up to date on purpose: the run leaves what it found in
 * {@code target/engine-reach-79.txt}, the reasons in short with the configurations each
 * keeps from running in {@code target/engine-reach-79-reasons.txt}, and every refusal as
 * said in {@code target/engine-reach-79-said.txt}.
 */
class EngineReachCorpusTest {

    private static final long SAME_TIME = 1_794_398_400_000L;

    /** The targets of Build, Release and Refresh imports: what OntoBoard's menus run. */
    private static final List<String> MENUS = Arrays.asList("test", "test_fast",
            "prepare_release", "prepare_release_fast", "refresh-imports",
            "no-mirror-refresh-imports");

    @TempDir
    File temp;

    @Test
    void whatTheEngineRunsOfTheTargetsOfOdksOwnConfigurations() throws IOException {
        StringBuilder table = new StringBuilder();
        StringBuilder said = new StringBuilder();
        // reason in short -> configuration -> the targets it keeps from running there
        Map<String, Map<String, Set<String>>> blocks =
                new TreeMap<String, Map<String, Set<String>>>();
        int rows = 0;
        int runs = 0;
        StringBuilder took = new StringBuilder();
        // dosdp-tools as it is once downloaded, on a Java 11: what the engine runs then - the
        // same on every machine, whether this one has it or not.
        File dosdpHome = new File(temp, "dosdp-tools-0.19.3");
        File launcher = new File(new File(dosdpHome, "lib"), DosdpTools.LAUNCHER);
        Files.createDirectories(launcher.getParentFile().toPath());
        Files.write(launcher.toPath(), new byte[0]);
        RecipeShell.Tool dosdp = new DosdpToolsRun(dosdpHome,
                () -> DosdpToolsRun.Java.at(new File("java")), null);
        for (String config : Goldens.corpusNames()) {
            long start = System.nanoTime();
            Map<String, Object> model = Goldens.corpusModel(config);
            File edit = seeded(config, model);
            List<String> goals = targets(model);
            for (String goal : goals) {
                Asking asked = reasons(edit, goal, dosdp);
                rows++;
                runs += asked.refusals.isEmpty() ? 1 : 0;
                table.append(config).append(' ').append(goal).append(": ")
                        .append(asked.refusals.isEmpty() ? "runs"
                                : "not run - " + String.join(" | ", asked.refusals))
                        .append('\n');
                for (String reason : asked.refusals) {
                    Map<String, Set<String>> where = blocks.get(reason);
                    if (where == null) {
                        where = new TreeMap<String, Set<String>>();
                        blocks.put(reason, where);
                    }
                    Set<String> blocked = where.get(config);
                    if (blocked == null) {
                        blocked = new TreeSet<String>();
                        where.put(config, blocked);
                    }
                    blocked.add(goal);
                }
                for (String raw : asked.said) {
                    said.append(config).append(' ').append(goal).append(": ").append(raw)
                            .append('\n');
                }
            }
            took.append((System.nanoTime() - start) / 1_000_000).append(" ms ").append(config)
                    .append(" (").append(goals.size()).append(" targets)\n");
        }
        Files.createDirectories(new File("target").toPath());
        Files.write(new File("target/engine-reach-79-took.txt").toPath(),
                took.toString().getBytes(StandardCharsets.UTF_8));
        File kept = new File("target/engine-reach-79.txt");
        Files.createDirectories(kept.getAbsoluteFile().getParentFile().toPath());
        Files.write(kept.toPath(), table.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File("target/engine-reach-79-said.txt").toPath(),
                said.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File("target/engine-reach-79-reasons.txt").toPath(),
                byReason(blocks).getBytes(StandardCharsets.UTF_8));

        File recorded = new File(Goldens.root().getParentFile(), "engine-reach-79.txt");
        assertTrue(recorded.isFile(), "no table recorded yet; the one found is in "
                + kept.getPath());
        assertEquals(new String(Goldens.bytes(recorded), StandardCharsets.UTF_8)
                .replace("\r\n", "\n"), table.toString(), runs + " of " + rows
                        + " targets run; the table found is in " + kept.getPath());
    }

    // ------------------------------------------------------------------ the projects

    private static void sameAge(File folder) {
        File[] children = folder.listFiles();
        for (File child : children == null ? new File[0] : children) {
            if (child.isDirectory()) {
                sameAge(child);
            }
            assertTrue(child.setLastModified(SAME_TIME), child.getPath());
        }
    }

    /** ODK's seed of a configuration, written out; its edit file. */
    private File seeded(String config, Map<String, Object> model) throws IOException {
        File root = new File(temp, config);
        for (Map.Entry<String, byte[]> file : OdkInstaller.renderSeed(
                Goldens.corpusConfig(config)).files().entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        sameAge(root);
        File edit = new File(root, "src/ontology/" + model.get("id") + "-edit."
                + model.get("edit_format"));
        assertTrue(edit.isFile(), config + " has its edit file: " + edit);
        return edit;
    }

    /**
     * The targets a person runs, as ODK's own {@code make help} lists them: the build and
     * the release, the imports - all, all without fetching, and one import of each kind -
     * and what the configuration adds: components, DOSDP patterns, SSSOM mappings, subsets.
     *
     * <p>One import of each kind: {@code refresh-<id>} of the first import of each module
     * type, mirror type, source (ODK's PURL or its own address), base, compression and size.
     * The others of a kind run the same recipes under another name. Not asked: ODK's
     * {@code update_repo}, which is {@code odk.py update} - OntoBoard runs ODK's update by
     * its own code ({@link OdkUpdate}) - and {@code public_release}, the GitHub release,
     * which is no part of building an ontology.
     */
    static List<String> targets(Map<String, Object> model) {
        List<String> goals = new ArrayList<String>(MENUS.subList(0, 4));
        Map<?, ?> imports = map(model.get("import_group"));
        List<Map<?, ?>> products = imports == null ? Collections.<Map<?, ?>>emptyList()
                : list(imports.get("products"));
        if (!products.isEmpty()) {
            goals.addAll(MENUS.subList(4, 6));
            Set<String> kinds = new TreeSet<String>();
            for (Map<?, ?> product : products) {
                if (kinds.add(kindOf(product, imports))) {
                    goals.add("refresh-" + product.get("id"));
                }
            }
        }
        Map<?, ?> components = map(model.get("components"));
        if (components != null && !list(components.get("products")).isEmpty()) {
            goals.add("recreate-components");
        }
        if (Boolean.TRUE.equals(model.get("use_dosdps"))) {
            goals.addAll(Arrays.asList("patterns", "dosdp_validation", "update_patterns",
                    "dosdp-docs-default"));
            Map<?, ?> pipelines = map(model.get("pattern_pipelines_group"));
            if (pipelines != null) {
                for (Map<?, ?> pipeline : list(pipelines.get("products"))) {
                    goals.add("dosdp-docs-" + pipeline.get("id"));
                }
                for (Map<?, ?> matches : list(pipelines.get("matches"))) {
                    goals.add("dosdp-matches-" + matches.get("id"));
                }
            }
        }
        if (Boolean.TRUE.equals(model.get("use_mappings"))) {
            goals.addAll(Arrays.asList("all_mappings", "validate_mappings",
                    "normalize_mappings"));
        }
        Map<?, ?> subsets = map(model.get("subset_group"));
        if (subsets != null && !list(subsets.get("products")).isEmpty()) {
            goals.add("all_subsets");
        }
        return goals;
    }

    /** What decides an import's recipes in ODK's Makefile, as one word. */
    private static String kindOf(Map<?, ?> product, Map<?, ?> group) {
        Object type = product.get("module_type") != null ? product.get("module_type")
                : group.get("module_type");
        return type + "/" + product.get("mirror_type") + "/" + (product.get("mirror_from") != null)
                + "/" + product.get("use_base") + "/" + product.get("use_gzipped") + "/"
                + product.get("is_large") + "/" + product.get("make_base") + "/"
                + (product.get("base_iris") != null);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static List<Map<?, ?>> list(Object value) {
        List<Map<?, ?>> maps = new ArrayList<Map<?, ?>>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    maps.add((Map<?, ?>) item);
                }
            }
        }
        return maps;
    }

    // ------------------------------------------------------------------ asking

    /**
     * What each program said of each call it was asked about: a program's answer depends on
     * the call alone - nothing a program checks before a run looks at the files - so a call
     * that comes again in another target or another project is answered from here. ROBOT's
     * parser takes its time over a line, and a release asks hundreds.
     */
    private static final Map<String, String> ANSWERED = new java.util.HashMap<String, String>();

    /**
     * Every reason the engine has for not running a target; none when it runs it.
     *
     * @param dosdp dosdp-tools as it is asked here: installed
     */
    private static Asking reasons(File edit, String goal, RecipeShell.Tool dosdp) {
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();
        tools.put("dosdp-tools", dosdp);
        Asking asked = new Asking(tools, edit);
        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, goal,
                Goldens.makeFixedVariables());
        if (!verdict.allowed()) {
            asked.refused(verdict.why());
            return asked;
        }
        ask(verdict.plan(), asked);
        return asked;
    }

    /** Each line of a run, as {@code OdkEngine} asks it before a run - every line, not the first. */
    private static void ask(List<MakeFile.Step> plan, Asking asked) {
        for (MakeFile.Step step : plan) {
            if (step.kind() != MakeFile.Step.Kind.COMMAND) {
                ask(step.steps(), asked);
                continue;
            }
            try {
                String why = RecipeShell.parse(step.command()).whyNot(asked);
                if (why != null) {
                    asked.refused(why);
                }
            } catch (RecipeShell.NotRead notRead) {
                asked.refused(notRead.getMessage());
            }
        }
    }

    /**
     * The programs a run names, each asked whether it can run its call. Where one cannot, or
     * is not there at all, the reason is kept and the asking goes on, so that every reason of
     * a target is found and not only its first.
     */
    private static final class Asking extends LinkedHashMap<String, RecipeShell.Tool> {
        private static final long serialVersionUID = 1L;

        final Set<String> refusals = new TreeSet<String>();
        final Set<String> said = new TreeSet<String>();
        private final String folder;

        Asking(Map<String, RecipeShell.Tool> tools, File edit) {
            super(tools);
            this.folder = edit.getAbsoluteFile().getParentFile().getAbsolutePath();
        }

        void refused(String why) {
            String plain = why.replace(folder + File.separator, "").replace(folder,
                    "src/ontology").replace('\\', '/').replaceAll("\\s+", " ").trim();
            said.add(plain);
            String inShort = inShort(plain);
            if (inShort != null) {
                refusals.add(inShort);
            }
        }

        @Override
        public RecipeShell.Tool get(Object key) {
            final String name = String.valueOf(key);
            final RecipeShell.Tool tool = super.get(key);
            return new RecipeShell.Tool() {
                @Override
                public String whyNot(List<String> arguments) {
                    if (tool == null) {
                        refused("no stand-in for " + name);
                        return null;
                    }
                    String call = name + "\u0000" + String.join("\u0000", arguments);
                    String why;
                    if (ANSWERED.containsKey(call)) {
                        why = ANSWERED.get(call);
                    } else {
                        why = tool.whyNot(arguments);
                        ANSWERED.put(call, why);
                    }
                    if (why != null) {
                        refused(name + ": " + why);
                    }
                    return null;
                }

                @Override
                public int run(RecipeShell.Call call) {
                    throw new IllegalStateException("asked only, never run");
                }
            };
        }
    }

    /**
     * A refusal as the table says it: what is missing, without the line it was found on, so
     * that one reason reads the same in every project it occurs in. JSON written on Java 8 is
     * left out: the table is of Java 11 and later, where it is written (as in
     * {@link EngineReachTest}); on Java 8, Protégé 5.5's, every release of an ontology in JSON
     * is refused for that one reason as well ({@link OdkRobot#whyNoJson}).
     */
    static String inShort(String said) {
        String whyNoJson = OdkRobot.whyNoJson(System.getProperty("java.specification.version"));
        if (whyNoJson != null && said.contains(OdkEngine.firstSentence(whyNoJson))) {
            return null;
        }
        // The shell's refusals end with the whole line in brackets: the reason is before it.
        return OdkEngine.firstSentence(said.replaceFirst(" in a recipe line \\(.*$",
                " in a recipe line."));
    }

    /** The reasons, by how many configurations each keeps a target from running in. */
    private static String byReason(Map<String, Map<String, Set<String>>> blocks) {
        List<Map.Entry<String, Map<String, Set<String>>>> sorted =
                new ArrayList<Map.Entry<String, Map<String, Set<String>>>>(blocks.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue().size() != a.getValue().size()
                ? b.getValue().size() - a.getValue().size() : a.getKey().compareTo(b.getKey()));
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Map<String, Set<String>>> reason : sorted) {
            text.append(reason.getValue().size()).append(" configurations: ")
                    .append(reason.getKey()).append('\n');
            for (Map.Entry<String, Set<String>> where : reason.getValue().entrySet()) {
                text.append("    ").append(where.getKey()).append(": ")
                        .append(String.join(" ", where.getValue())).append('\n');
            }
        }
        return text.toString();
    }
}
