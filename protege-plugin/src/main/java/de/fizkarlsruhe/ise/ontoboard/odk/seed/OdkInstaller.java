package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Writes an ODK project's files as {@code odk.py} v1.6.1 writes them, without ODK's container:
 * its {@code seed}, {@code install_template_files}, {@code unpack_files} and
 * {@code must_install_file}, over ODK's own templates ({@link TemplateSet}).
 *
 * <p>What ODK does, and so what happens here:
 *
 * <ul>
 *   <li>Folder by folder, a folder before the folders inside it: first the files that are not
 *       templates are copied as they are, then each {@code .jinja2} file is rendered to the same
 *       name without the suffix, keeping the template's executable mark.
 *   <li>A template whose name starts {@code _dynamic} is a pack: its rendered text is cut at
 *       every line that starts {@code ^^^ }, the rest of that line being the file the lines
 *       below go to, each written with a line feed.
 *   <li>Each file has a {@link Policy}, from the first {@link Rule} whose pattern matches its
 *       name: written always, never, or - when no rule matches - only if it is not there yet.
 *       "Not there yet" counts files written a moment ago, so of two templates that give the
 *       same file the first wins.
 *   <li>A seed then copies the configuration itself to {@code src/ontology/<id>-odk.yaml}, byte
 *       for byte.
 * </ul>
 *
 * <p>Proved against ODK: {@code SeedParityTest} (eight projects, every byte) and
 * {@code SeedCorpusTest} (ODK's own 79 sample configurations, every file's hash).
 *
 * <p>Two things are refused that ODK would do. A configuration that sets
 * {@code export_project_yaml}, because the {@code project.yaml} ODK then writes comes from
 * PyYAML's dumper and is not reproduced here. And a file name that leaves the project: ODK joins
 * whatever a configuration gives onto the project's folder, {@code ../..} and all.
 */
public final class OdkInstaller {

    /** How {@code odk.py} treats a file a template gives. */
    public enum Policy {
        /** Written unless a file of that name is already there: what no rule means. */
        IF_MISSING,
        /** Written, over whatever is there. */
        ALWAYS,
        /** Not written. */
        NEVER
    }

    /** A policy for the files a pattern names. Of several rules the first that matches decides. */
    public static final class Rule {
        private final String pattern;
        private final Policy policy;

        /**
         * @param pattern a file's name from the project's root, where {@code *} stands for any
         *                run of characters, slashes included, and {@code ?} for one
         */
        public Rule(String pattern, Policy policy) {
            if (pattern.indexOf('[') >= 0) {
                throw new IllegalArgumentException("A policy pattern with '[' is not "
                        + "implemented: " + pattern);
            }
            this.pattern = pattern;
            this.policy = policy;
        }

        public String pattern() {
            return pattern;
        }

        public Policy policy() {
            return policy;
        }

        /** Python's {@code fnmatch.fnmatch(name, pattern)} on Linux: case matters. */
        public boolean matches(String name) {
            int p = 0;
            int s = 0;
            int star = -1;
            int mark = 0;
            while (s < name.length()) {
                char c = p < pattern.length() ? pattern.charAt(p) : 0;
                if (p < pattern.length() && c == '*') {
                    star = p++;
                    mark = s;
                } else if (p < pattern.length() && (c == '?' || c == name.charAt(s))) {
                    p++;
                    s++;
                } else if (star >= 0) {
                    p = star + 1;
                    s = ++mark;
                } else {
                    return false;
                }
            }
            while (p < pattern.length() && pattern.charAt(p) == '*') {
                p++;
            }
            return p == pattern.length();
        }

        @Override
        public String toString() {
            return pattern + "=" + policy;
        }
    }

    /** The files of one seed or one update: what goes where. */
    public static final class Rendered {
        private final Map<String, byte[]> files;
        private final Set<String> executable;

        Rendered(Map<String, byte[]> files, Set<String> executable) {
            this.files = files;
            this.executable = executable;
        }

        /** Each file's path from the project's root, with {@code /}, in the order written. */
        public Map<String, byte[]> files() {
            return Collections.unmodifiableMap(files);
        }

        /**
         * The files ODK marks executable. A file not named here keeps whatever mark it has: ODK
         * sets the mark on what it copies and renders, and leaves it alone on what it unpacks.
         */
        public Set<String> executable() {
            return Collections.unmodifiableSet(executable);
        }
    }

    private static final String MARKER = "^^^ ";
    private static final String PROFILE = "src/ontology/profile.txt";

    private OdkInstaller() {
    }

    // ------------------------------------------------------------------ seeding

    /** Every file {@code odk.py seed -C config --skipgit} writes. */
    public static Rendered renderSeed(String configText) {
        return renderSeed(configText, null);
    }

    /**
     * Every file {@code odk.py seed -C config --skipgit [--source ontology]} writes.
     *
     * @param configText           the configuration, as the file has it: its bytes are copied
     *                             into the project unchanged
     * @param sourceEditFileOrNull an ontology to start from instead of ODK's empty one
     * @throws OdkConfigException when ODK would refuse the configuration, or could not render
     *                            its templates with it
     */
    public static Rendered renderSeed(String configText, byte[] sourceEditFileOrNull) {
        PyObject project = OdkProjectModel.load(configText);
        Object id = project.get("id");
        if (!(id instanceof String) || ((String) id).isEmpty()) {
            throw new OdkConfigException("The ODK configuration has no 'id:'. ODK names the "
                    + "project's files after it.");
        }
        if (Py.truthy(project.get("export_project_yaml"))) {
            throw new OdkConfigException("The configuration sets export_project_yaml, which "
                    + "makes ODK write a project.yaml when it seeds the project. OntoBoard's own "
                    + "seed does not write that file. ODK's own seed does, in ODK's container "
                    + "(Docker or Podman with ODK's image) - or take the line out.");
        }
        Installation run = installation(project, seedPolicies(project), new Predicate<String>() {
            @Override
            public boolean test(String path) {
                return false;
            }
        });
        String stem = "src/ontology/" + id;
        if (sourceEditFileOrNull != null) {
            run.put(safe(stem + "-edit." + Py.str(project.get("edit_format")), "the seed"),
                    sourceEditFileOrNull);
        }
        run.put(safe(stem + "-odk.yaml", "the seed"),
                configText.getBytes(StandardCharsets.UTF_8));
        return run.rendered();
    }

    /**
     * Seeds a project into a folder of its own.
     *
     * @param projectRoot a folder that does not exist yet, or holds nothing
     * @return the files written, in the order written
     * @throws IllegalStateException when the folder holds something
     * @throws UncheckedIOException  when a file cannot be written; what was written is taken
     *                               back
     */
    public static List<File> seed(String configText, File projectRoot,
            byte[] sourceEditFileOrNull) {
        Rendered rendered = renderSeed(configText, sourceEditFileOrNull);
        if (projectRoot.exists()) {
            String[] inside = projectRoot.list();
            if (inside == null || inside.length > 0) {
                throw new IllegalStateException(projectRoot.getAbsolutePath() + " is not an "
                        + "empty folder. A new ODK project needs a folder of its own.");
            }
        }
        return write(rendered.files, rendered.executable, projectRoot);
    }

    /**
     * Writes files into a folder that holds nothing. If one cannot be written, the files and
     * folders made so far are removed again, so that the folder is as it was found.
     */
    static List<File> write(Map<String, byte[]> files, Set<String> executable, File root) {
        List<File> written = new ArrayList<File>();
        List<File> folders = new ArrayList<File>();
        String writing = "";
        try {
            File home = root.getCanonicalFile();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                writing = file.getKey();
                File target = new File(root, file.getKey().replace('/', File.separatorChar));
                if (!target.getCanonicalFile().toPath().startsWith(home.toPath())) {
                    throw new IOException("it is not inside " + home);
                }
                makeFolders(target.getParentFile(), folders);
                Files.write(target.toPath(), file.getValue());
                written.add(target);
                if (executable.contains(file.getKey())) {
                    target.setExecutable(true, false);
                }
            }
            return written;
        } catch (IOException failed) {
            for (int i = written.size() - 1; i >= 0; i--) {
                written.get(i).delete();
            }
            for (int i = folders.size() - 1; i >= 0; i--) {
                folders.get(i).delete();
            }
            throw new UncheckedIOException("Could not write " + writing + " into "
                    + root.getAbsolutePath() + ": " + failed.getMessage()
                    + ". Nothing was left behind.", failed);
        }
    }

    /** Makes {@code folder} and the folders above it that are missing, noting each one made. */
    private static void makeFolders(File folder, List<File> made) throws IOException {
        List<File> missing = new ArrayList<File>();
        for (File up = folder; up != null && !up.exists(); up = up.getParentFile()) {
            missing.add(up);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            if (!missing.get(i).mkdir()) {
                throw new IOException("the folder " + missing.get(i) + " could not be made");
            }
            made.add(missing.get(i));
        }
    }

    // ------------------------------------------------------------------ policies

    /** The policies of {@code odk.py seed}: ODK's ROBOT profile only for a project with its own. */
    public static List<Rule> seedPolicies(PyObject project) {
        List<Rule> rules = new ArrayList<Rule>();
        if (!hasOwnProfile(project)) {
            rules.add(new Rule(PROFILE, Policy.NEVER));
        }
        return rules;
    }

    /**
     * The policies of {@code odk.py update}: the Makefile, {@code run.sh}, the SPARQL checks,
     * the workflow documentation and the CI workflows the configuration names are rewritten;
     * the files a person edits are never touched; everything else is added when missing.
     */
    public static List<Rule> updatePolicies(PyObject project) {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule("CODE_OF_CONDUCT.md", Policy.NEVER));
        rules.add(new Rule("CONTRIBUTING.md", Policy.NEVER));
        rules.add(new Rule("issue_template.md", Policy.NEVER));
        rules.add(new Rule("README.md", Policy.NEVER));
        rules.add(new Rule("src/patterns/data/default/example.tsv", Policy.NEVER));
        rules.add(new Rule("src/patterns/dosdp-patterns/example.yaml", Policy.NEVER));
        rules.add(new Rule("src/ontology/Makefile", Policy.ALWAYS));
        rules.add(new Rule("src/ontology/run.sh", Policy.ALWAYS));
        rules.add(new Rule("src/ontology/catalog-v001.xml", Policy.NEVER));
        rules.add(new Rule("src/sparql/*", Policy.ALWAYS));
        rules.add(new Rule("docs/odk-workflows/*", Policy.ALWAYS));
        rules.add(new Rule(".gitignore", Policy.NEVER));
        if (names(project, "ci", "github_actions")) {
            for (String workflow : new String[] {"qc", "diff", "release-diff"}) {
                if (names(project, "workflows", workflow)) {
                    rules.add(new Rule(".github/workflows/" + workflow + ".yml", Policy.ALWAYS));
                }
            }
            if (!(project.get("documentation") instanceof PyNone)
                    && names(project, "workflows", "docs")) {
                rules.add(new Rule(".github/workflows/docs.yml", Policy.ALWAYS));
            }
        }
        if (!hasOwnProfile(project)) {
            rules.add(new Rule(PROFILE, Policy.NEVER));
        }
        return rules;
    }

    /** {@code project.robot_report.get('custom_profile', False)}, as Python judges it true. */
    private static boolean hasOwnProfile(PyObject project) {
        Object report = project.get("robot_report");
        if (!(report instanceof Map)) {
            throw new OdkConfigException("The configuration's robot_report is not a set of "
                    + "'key: value' lines.");
        }
        Object own = ((Map<?, ?>) report).get("custom_profile");
        return own != null && Py.truthy(own);
    }

    /** {@code item in project.<field>}. */
    private static boolean names(PyObject project, String field, String item) {
        try {
            return Py.contains(project.get(field), item);
        } catch (IllegalStateException refused) {
            throw new OdkConfigException("The configuration's '" + field + ":' is not a list; "
                    + "ODK's update stops there too (" + refused.getMessage() + ").");
        }
    }

    // ------------------------------------------------------------------ installing

    /** Every file the templates give for this project, with no policy: the first of a name wins. */
    public static Rendered renderAll(PyObject project) {
        return install(project, Collections.<Rule>emptyList(), new Predicate<String>() {
            @Override
            public boolean test(String path) {
                return false;
            }
        });
    }

    /**
     * What {@code install_template_files} writes into a project's folder.
     *
     * @param policies the rules; a file none of them matches is written only when missing
     * @param exists   whether the folder already holds a file or folder of this path (from the
     *                 project's root, with {@code /}) before anything is written
     * @return the files written - not those held back - in the order written
     * @throws OdkConfigException when a template cannot be rendered with this configuration, or
     *                            the configuration names a file outside the project
     */
    public static Rendered install(PyObject project, List<Rule> policies,
            Predicate<String> exists) {
        return installation(project, policies, exists).rendered();
    }

    private static Installation installation(PyObject project, List<Rule> policies,
            Predicate<String> exists) {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("project", project);
        // The image sets ODK_VERSION, and odk.py hands it to the templates.
        Map<String, Object> environment = new LinkedHashMap<String, Object>();
        environment.put("ODK_VERSION", OdkPin.VERSION);
        context.put("env", environment);

        // A folder before the folders inside it, as os.walk goes. Among folders side by side
        // ODK goes in whatever order the disk lists them; here the order is fixed.
        Map<String, List<TemplateSet.Entry>> folders =
                new TreeMap<String, List<TemplateSet.Entry>>();
        for (TemplateSet.Entry entry : TemplateSet.bundled().entries()) {
            int slash = entry.path().lastIndexOf('/');
            String folder = slash < 0 ? "" : entry.path().substring(0, slash);
            if (!folders.containsKey(folder)) {
                folders.put(folder, new ArrayList<TemplateSet.Entry>());
            }
            folders.get(folder).add(entry);
        }
        Installation run = new Installation(policies, exists);
        for (Map.Entry<String, List<TemplateSet.Entry>> folder : folders.entrySet()) {
            for (TemplateSet.Entry entry : folder.getValue()) {
                if (!entry.isTemplate() && run.mustInstall(entry.path(), entry.path())) {
                    run.put(entry.path(), entry.bytes());
                    run.mark(entry.path(), entry.isExecutable());
                }
            }
            for (TemplateSet.Entry entry : folder.getValue()) {
                if (!entry.isTemplate()) {
                    continue;
                }
                String name = entry.path().replace(TemplateSet.SUFFIX, "");
                boolean pack = entry.path().substring(folder.getKey().isEmpty() ? 0
                        : folder.getKey().length() + 1).startsWith("_dynamic");
                if (pack) {
                    run.unpack(folder.getKey(), render(entry, context), entry.path());
                } else if (run.mustInstall(name, name)) {
                    run.put(name, render(entry, context).getBytes(StandardCharsets.UTF_8));
                    run.mark(name, entry.isExecutable());
                }
            }
        }
        run.checkNoFileIsAlsoAFolder();
        return run;
    }

    private static String render(TemplateSet.Entry entry, Map<String, Object> context) {
        try {
            return JinjaTemplate.parse(entry.path(),
                    new String(entry.bytes(), StandardCharsets.UTF_8)).render(context);
        } catch (JinjaException stopped) {
            OdkConfigException refused = new OdkConfigException("ODK's template "
                    + stopped.getTemplate() + " cannot be rendered with this configuration; it "
                    + "stops at its line " + stopped.getLine() + ": " + stopped.getMessage());
            refused.initCause(stopped);
            throw refused;
        }
    }

    /**
     * A path a template or a configuration gives, refused unless it stays inside the project:
     * no {@code ..}, nothing absolute, no backslash or colon that Windows would read as a way
     * out.
     */
    private static String safe(String path, String from) {
        boolean inside = !path.isEmpty() && path.indexOf('\\') < 0 && path.indexOf(':') < 0;
        for (String part : path.split("/", -1)) {
            inside &= !part.isEmpty() && !part.equals(".") && !part.equals("..");
        }
        for (int i = 0; i < path.length(); i++) {
            inside &= path.charAt(i) >= ' ';
        }
        if (!inside) {
            throw new OdkConfigException("With this configuration " + from + " would write a "
                    + "file named '" + path + "', which is not a place inside the project. "
                    + "Check the ids and file names the configuration gives.");
        }
        return path;
    }

    /** One run of install_template_files: the policies, what was there, what is written. */
    private static final class Installation {
        private final List<Rule> policies;
        private final Predicate<String> existedBefore;
        private final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        private final Set<String> executable = new LinkedHashSet<String>();

        Installation(List<Rule> policies, Predicate<String> existedBefore) {
            this.policies = policies;
            this.existedBefore = existedBefore;
        }

        Rendered rendered() {
            return new Rendered(files, executable);
        }

        /** {@code must_install_file}. */
        boolean mustInstall(String templateFile, String target) {
            Policy policy = Policy.IF_MISSING;
            for (Rule rule : policies) {
                if (rule.matches(templateFile)) {
                    policy = rule.policy();
                    break;
                }
            }
            if (policy != Policy.IF_MISSING) {
                return policy == Policy.ALWAYS;
            }
            return !exists(target);
        }

        /** {@code os.path.exists}: a file, or a folder - one made for a file written earlier. */
        private boolean exists(String target) {
            if (files.containsKey(target) || existedBefore.test(target)) {
                return true;
            }
            for (String written : files.keySet()) {
                if (written.startsWith(target + "/")) {
                    return true;
                }
            }
            return false;
        }

        void put(String path, byte[] bytes) {
            files.put(path, bytes);
        }

        /** What copying a file, or rendering a template, does to the executable mark. */
        void mark(String path, boolean isExecutable) {
            if (isExecutable) {
                executable.add(path);
            } else {
                executable.remove(path);
            }
        }

        /**
         * {@code unpack_files}: the text of a pack, cut into the files its marker lines name.
         *
         * @param folder the pack's folder, which its files' names start from
         */
        void unpack(String folder, String text, String pack) {
            String target = null;
            StringBuilder content = null;
            boolean anyOpened = false;
            boolean ignore = false;
            for (String line : text.split("\n", -1)) {
                if (line.startsWith(MARKER)) {
                    close(target, content);
                    // Every "^^^ " in the line goes, not only the first: odk.py's replace().
                    String filename = line.replace(MARKER, "");
                    String path = safe(folder.isEmpty() ? filename : folder + "/" + filename,
                            "ODK's template " + pack);
                    ignore = !mustInstall(filename, path);
                    if (ignore) {
                        target = null;
                        content = null;
                    } else {
                        target = path;
                        content = new StringBuilder();
                        anyOpened = true;
                        // Opened for writing, so there from now on, and empty until closed.
                        put(path, new byte[0]);
                    }
                } else if (!ignore) {
                    if (!anyOpened) {
                        if (line.isEmpty()) {
                            continue;
                        }
                        throw new IllegalStateException("File marker \"" + MARKER
                                + "\" required in \"" + line + "\" (" + pack + ")");
                    }
                    content.append(line).append('\n');
                }
            }
            close(target, content);
        }

        private void close(String target, StringBuilder content) {
            if (target != null) {
                put(target, content.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        /** A name cannot be a file and a folder at once; ODK would stop with an OS error. */
        void checkNoFileIsAlsoAFolder() {
            for (String path : files.keySet()) {
                for (String other : files.keySet()) {
                    if (other.startsWith(path + "/")) {
                        throw new OdkConfigException("With this configuration ODK's templates "
                                + "give both a file '" + path + "' and a file inside it, '"
                                + other + "'. Check the ids and file names it gives.");
                    }
                }
            }
        }
    }
}
