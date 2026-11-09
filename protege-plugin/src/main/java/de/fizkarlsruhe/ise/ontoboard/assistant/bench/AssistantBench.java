package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Assembled;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RequestAssembler;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionPreset;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connections;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClients;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.FixedRules;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.Role;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleLibrary;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleStore;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The assistant benchmark (spec D8): compares models on the same cases with one command, using
 * the plugin's own code - the roles and fixed rules, request assembly, the three clients - so a
 * score is what a user of the plugin would get.
 *
 * <pre>
 * AssistantBench generate --ontology pizza.owl --out cases/pizza.jsonl [--seed 1]
 *                         [--explain 10] [--context 10] [--controls 5] [--reasoner HermiT]
 * AssistantBench run      --models models.json --cases cases/ --out results/ [--repeats 3]
 *                         [--role ontology-expert] [--field ""]
 * AssistantBench score    --cases cases/ --replies results/replies.jsonl --out rescored/
 * AssistantBench version
 * </pre>
 *
 * <p>Run from a checkout the way {@code bench/Bench} is (docs/measurements.md): the OWL API is
 * Protégé's and not in the jar. Keys come only from the environment variables the models file
 * names, and no key reaches any file this writes. No timestamps in any output.
 */
public final class AssistantBench {

    private AssistantBench() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, System.getenv());
    }

    static int run(String[] args, PrintStream out, PrintStream err,
            Map<String, String> environment) {
        // Every error line goes through KeyRedactor, against the keys of the presets' variables
        // and, once the models file is read, of its own: a parser's message, a name or a path
        // read from a file can carry a key to a terminal and from there to a CI log (1.146.0,
        // review 1.145.0 keys-02).
        List<String> known = new java.util.concurrent.CopyOnWriteArrayList<String>(
                KeyRedactor.knownKeys(Collections.<Connection>emptyList(), environment,
                        name -> null, name -> null));
        PrintStream safe = redacting(err, known);
        try {
            return command(args, out, safe, environment, known);
        } finally {
            safe.flush();
        }
    }

    /** A stream that writes each whole line to the given one through KeyRedactor. */
    private static PrintStream redacting(final PrintStream target, final List<String> keys) {
        java.io.OutputStream lines = new java.io.OutputStream() {
            private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                line.write(b);
                if (b == '\n') {
                    flush();
                }
            }

            @Override
            public synchronized void flush() {
                if (line.size() > 0) {
                    target.print(KeyRedactor.redact(new String(line.toByteArray(),
                            StandardCharsets.UTF_8), keys));
                    line.reset();
                }
                target.flush();
            }
        };
        try {
            return new PrintStream(lines, false, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException everyJvmHasUtf8) {
            throw new IllegalStateException(everyJvmHasUtf8);
        }
    }

    private static int command(String[] args, PrintStream out, PrintStream err,
            Map<String, String> environment, List<String> known) {
        if (args == null || args.length == 0 || "--help".equals(args[0])
                || "-h".equals(args[0]) || "help".equals(args[0])) {
            usage(out);
            return args == null || args.length == 0 ? 2 : 0;
        }
        String command = args[0];
        Map<String, String> options = options(Arrays.copyOfRange(args, 1, args.length));
        try {
            if ("version".equals(command)) {
                out.println(version());
                return 0;
            }
            if ("generate".equals(command)) {
                return generate(options, out, err);
            }
            if ("run".equals(command)) {
                return runModels(options, out, err, environment, known);
            }
            if ("score".equals(command)) {
                return score(options, out, err);
            }
            err.println("assistant bench: no command called " + command + ".");
            usage(err);
            return 2;
        } catch (IllegalArgumentException | IOException failed) {
            err.println("assistant bench: " + failed.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("OntoBoard assistant benchmark " + version());
        out.println();
        out.println("  generate --ontology <owl> --out <cases.jsonl> [--seed 1] [--explain 10]");
        out.println("           [--context 10] [--controls 5] [--definition 5] [--repair 5]");
        out.println("           [--text-to-axiom 5] [--reasoner HermiT]");
        out.println("  run      --models <models.json> --cases <file|dir> --out <dir>");
        out.println("           [--repeats 3] [--role ontology-expert] [--field \"\"]");
        out.println("  score    --cases <file|dir> --replies <replies.jsonl> --out <dir>");
        out.println("  version");
        out.println();
        out.println("Keys come from the environment variable each model names in the models"
                + " file; no key is written anywhere.");
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String name = args[i].substring(2);
            String value = "";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            options.put(name, value);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("--" + name + " is required.");
        }
        return value.trim();
    }

    private static int number(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("--" + name + " needs a number, not " + value);
        }
    }

    // ------------------------------------------------------------------ generate

    private static int generate(Map<String, String> options, PrintStream out, PrintStream err)
            throws IOException {
        File ontologyFile = new File(required(options, "ontology"));
        File casesFile = new File(required(options, "out"));
        if (!ontologyFile.isFile()) {
            throw new IllegalArgumentException("no such ontology file: "
                    + ontologyFile.getAbsolutePath());
        }
        int explain = number(options, "explain", 10);
        int context = number(options, "context", 10);
        int controls = number(options, "controls", 5);
        int definitions = number(options, "definition", 5);
        int repairs = number(options, "repair", 5);
        int texts = number(options, "text-to-axiom", 5);
        long seed = number(options, "seed", 1);
        String reasonerName = options.containsKey("reasoner") ? options.get("reasoner")
                : "HermiT";
        Reasoners.Choice reasoner = Reasoners.byLabel(reasonerName);
        OWLOntology ontology = load(ontologyFile);
        CaseGenerator generator = new CaseGenerator(ontology,
                relative(casesFile, ontologyFile), CaseGenerator.sha256Lf(ontologyFile),
                reasoner.newFactory(), reasoner.getLabel(), seed);
        List<BenchCase> cases = new ArrayList<BenchCase>(generator.explainProblem(explain));
        int explained = cases.size();
        List<BenchCase> requests = generator.contextRequest(context, controls);
        cases.addAll(requests);
        List<BenchCase> defined = generator.definition(definitions);
        cases.addAll(defined);
        List<BenchCase> repaired = generator.repair(repairs);
        cases.addAll(repaired);
        List<BenchCase> said = generator.textToAxiom(texts);
        cases.addAll(said);
        BenchCases.write(cases, casesFile);
        // An inconsistent ontology gives exactly one problem case: its own. Say so, rather than
        // blame its disjoint classes.
        String fewProblems = !repaired.isEmpty() && "inconsistent".equals(
                repaired.get(0).getProblem()) && repaired.get(0).getFault().isEmpty()
                        ? ": the ontology is already inconsistent, and that is its one case."
                        : ": the ontology has too few asserted disjoint classes.";
        if (defined.size() < definitions) {
            err.println("asked for " + definitions + " definition cases and made "
                    + defined.size() + ": too few classes with one definition and a parent.");
        }
        if (repaired.size() < repairs) {
            err.println("asked for " + repairs + " repair cases and made " + repaired.size()
                    + fewProblems);
        }
        if (said.size() < texts) {
            err.println("asked for " + texts + " text-to-axiom cases and made " + said.size()
                    + ": too few superclass restrictions (some, only, min, max, exactly, a value or"
                    + " a datatype) and definitions, with readable names, of its own classes that"
                    + " the rest does not already entail and"
                    + " whose return neither makes a class unsatisfiable nor the ontology"
                    + " inconsistent (an inconsistent ontology entails everything and gives"
                    + " none).");
        }
        if (explained < explain) {
            err.println("asked for " + explain + " explain-problem cases and made " + explained
                    + fewProblems);
        }
        if (requests.size() < context + controls) {
            err.println("asked for " + (context + controls) + " context-request cases and made "
                    + requests.size() + ": too few restrictions to a class with subclasses.");
        }
        out.println("wrote " + cases.size() + " cases to " + casesFile.getPath());
        return 0;
    }

    /** The ontology's path from the cases file's folder, with forward slashes. */
    private static String relative(File casesFile, File ontologyFile) {
        File folder = casesFile.getAbsoluteFile().getParentFile();
        try {
            return folder.toPath().relativize(ontologyFile.getAbsoluteFile().toPath()).toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException otherDrive) {
            return ontologyFile.getAbsolutePath().replace('\\', '/');
        }
    }

    static OWLOntology load(File file) throws IOException {
        try {
            OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                    new FileDocumentSource(file), configuration);
        } catch (OWLOntologyCreationException unreadable) {
            throw new IOException("could not read " + file.getPath() + ": "
                    + unreadable.getMessage(), unreadable);
        }
    }

    // ------------------------------------------------------------------ run and score

    /** Cases with the folder each was read from, for finding its ontology. */
    private static Map<BenchCase, File> readCases(File source) throws IOException {
        List<File> files = new ArrayList<File>();
        if (source.isDirectory()) {
            File[] found = source.listFiles((folder, name) -> name.endsWith(".jsonl"));
            if (found != null) {
                files.addAll(Arrays.asList(found));
            }
            Collections.sort(files);
        } else if (source.isFile()) {
            files.add(source);
        } else {
            throw new IllegalArgumentException("no such cases file or folder: "
                    + source.getAbsolutePath());
        }
        Map<BenchCase, File> cases = new LinkedHashMap<BenchCase, File>();
        List<BenchCase> all = new ArrayList<BenchCase>();
        Map<BenchCase, File> folders = new HashMap<BenchCase, File>();
        for (File file : files) {
            for (BenchCase c : BenchCases.read(file)) {
                all.add(c);
                folders.put(c, file.getAbsoluteFile().getParentFile());
            }
        }
        Collections.sort(all, (a, b) -> a.getId().compareTo(b.getId()));
        for (BenchCase c : all) {
            cases.put(c, folders.get(c));
        }
        return cases;
    }

    private static int runModels(Map<String, String> options, PrintStream out, PrintStream err,
            Map<String, String> environment, List<String> errorKeys) throws IOException {
        File modelsFile = new File(required(options, "models"));
        Map<BenchCase, File> cases = readCases(new File(required(options, "cases")));
        File outFolder = new File(required(options, "out"));
        int repeats = number(options, "repeats", 3);
        String roleText = roleText(options.containsKey("role") ? options.get("role")
                : "ontology-expert", options.containsKey("field") ? options.get("field") : "");
        String json = new String(Files.readAllBytes(modelsFile.toPath()),
                StandardCharsets.UTF_8);
        // Before the file is read as connections, whose parser can quote it (1.146.0, review
        // 1.145.0 keys-02).
        String held = keyIn(json, environment);
        if (held != null && held.equals(twiceIn(json))) {
            // A field given twice was read for its last value alone, so a key in an earlier one
            // went unseen. Refused for what it is, with neither value (1.150.0, review 1.149.0
            // keys-02).
            throw new IllegalArgumentException(modelsFile.getName() + ": " + held + ". Only its"
                    + " last value would be read, and a key in an earlier one would go unseen:"
                    + " give each field once. Keys are read only from the environment, from the"
                    + " variable keyVariable names.");
        }
        if (held != null) {
            throw new IllegalArgumentException(modelsFile.getName() + " holds a key (" + held
                    + "). Keys are read only from the environment: name the variable in"
                    + " keyVariable, set it, and take the key out of the file - and out of git,"
                    + " if the file was ever committed.");
        }
        String reference = referenceIn(json, environment);
        if (reference != null) {
            // A name written as $NAME holds no key, and the refusal does not say it does
            // (1.147.0, review 1.146.0 keys-05).
            throw new IllegalArgumentException(modelsFile.getName() + ": " + reference);
        }
        List<Connection> models = Connections.fromJson(json);
        errorKeys.addAll(KeyRedactor.knownKeys(models, environment, name -> null, name -> null));
        for (Connection model : models) {
            if (model.hasCredentials()) {
                // Java never sends them, and they would reach replies.jsonl and results.csv in
                // errors (1.144.0, review 1.143.0 M-4).
                throw new IllegalArgumentException(modelsFile.getName() + ": the address of "
                        + model.getName() + " holds a name and password, which OntoBoard cannot"
                        + " sign in with. Remove them, and give the key in an environment"
                        + " variable named by keyVariable.");
            }
        }
        Map<Connection, String> keys = new LinkedHashMap<Connection, String>();
        int number = 0;
        for (Connection model : models) {
            // Connections.fromJson keeps every entry, in the file's order, or refuses the file.
            number++;
            String variable = model.getKeyVariable();
            String key = variable.isEmpty() || environment == null ? ""
                    : environment.get(variable);
            if (!variable.isEmpty() && (key == null || key.trim().isEmpty())) {
                if (variable.length() >= 20 && !SHOWN_VARIABLE.matcher(variable).matches()) {
                    // A key that keyIn's rule misses - about one random 40-character base64url
                    // key in 55 - stayed in the file without a word, perhaps committed. A value
                    // that names no variable that is set runs nothing anyway, so this refuses no
                    // name; the value is never shown (1.149.0, review 1.148.0 keys-03).
                    err.println("skipped " + model.getName() + ": keyVariable of entry " + number
                            + " names no variable that is set; if it holds a key, take it out of"
                            + " the file and out of git.");
                    continue;
                }
                // $NAME only for a name in the presets' form: a key that keyIn's rule misses is
                // never printed (1.146.0, review 1.145.0 bench-A).
                err.println("skipped " + model.getName() + ": "
                        + (SHOWN_VARIABLE.matcher(variable).matches() ? "$" + variable
                                : "the variable its keyVariable names")
                        + " is not set.");
                continue;
            }
            keys.put(model, key == null ? "" : key.trim());
        }
        if (keys.isEmpty()) {
            err.println("no model could be run.");
            return 1;
        }
        List<ResultsWriter.Row> rows = new ArrayList<ResultsWriter.Row>();
        // Each answer is written as it comes, before it is scored: a run that ends early keeps
        // every answer paid for so far (review 1.140.0 I2).
        Files.createDirectories(outFolder.toPath());
        keepEarlier(outFolder, new String[] {"replies.jsonl", "results.csv", "summary.md"}, err);
        File replies = new File(outFolder, "replies.jsonl");
        Files.write(replies.toPath(), new byte[0]);
        Map<String, Source> known = new HashMap<String, Source>();
        // A model can echo a key it was never sent - pasted into a case, or into its training -
        // and a server can quote one in an error: neither is written (1.138.0).
        final List<String> secrets = new ArrayList<String>(keys.values());
        for (Map.Entry<BenchCase, File> entry : cases.entrySet()) {
            BenchCase c = entry.getKey();
            Source source = source(c, entry.getValue(), known, err);
            Set<String> iris = source.iris;
            OWLOntology without = without(c, source);
            for (Map.Entry<Connection, String> model : keys.entrySet()) {
                for (int repeat = 1; repeat <= repeats; repeat++) {
                    if (BenchCase.REPAIR.equals(c.getTask())) {
                        final Connection connection = model.getKey();
                        final String key = model.getValue();
                        RepairLoop.Outcome outcome = repair(c, source, roleText,
                                connection.getMaxOutputTokens(), request -> ModelClients
                                        .create(connection, key).chat(request, delta -> { }));
                        appendLine(replies, KeyRedactor.redactKeysAndShapes(repairReply(c,
                                connection, repeat, outcome, secrets), secrets));
                        Score score = Scorers.repair(outcome, iris);
                        rows.add(new ResultsWriter.Row(c.getId(), c.getTask(), c.isControl(),
                                connection.getName(), repeat, score, outcome.millis,
                                outcome.inputTokens, outcome.outputTokens,
                                redact(outcome.error, secrets)));
                        out.println(c.getId() + " " + connection.getName() + " #" + repeat
                                + ": " + (outcome.error != null ? "error"
                                        : score.isPass() ? "pass" : "fail") + " (" + outcome.rounds
                                + (outcome.rounds == 1 ? " round)" : " rounds)"));
                        continue;
                    }
                    Assembled assembled = RequestAssembler.assemble(roleText, FixedRules.text(),
                            c.getHeader(), c.getContext(), Collections.<Turn>emptyList(),
                            c.getMessage(), model.getKey().getMaxOutputTokens());
                    String text = "";
                    String error = null;
                    long millis = 0;
                    int in = Reply.UNKNOWN;
                    int outTokens = Reply.UNKNOWN;
                    try {
                        Reply reply = ModelClients.create(model.getKey(), model.getValue())
                                .chat(assembled.getRequest(), delta -> { });
                        text = reply.getText();
                        millis = reply.getMillis();
                        in = reply.getInputTokens();
                        outTokens = reply.getOutputTokens();
                    } catch (ModelCallException failed) {
                        error = failed.getMessage();
                    }
                    // Each field was redacted whole: the encoded line gets the key and shape
                    // passes only (1.146.0, review 1.145.0 keys-04).
                    appendLine(replies, KeyRedactor.redactKeysAndShapes(reply(c, model.getKey(),
                            repeat, assembled.describe(model.getKey()), text, millis, in,
                            outTokens, error, secrets), secrets));
                    Score score;
                    try {
                        score = scoreOne(c, text, iris, without);
                    } catch (RuntimeException unscorable) {
                        // One answer the scorer cannot handle is that row's error, not the
                        // end of the run (review 1.140.0 I2).
                        score = new Score(new LinkedHashMap<String, Double>(), false);
                        error = "could not be scored: " + unscorable;
                    }
                    if (error == null) {
                        error = score.getError();
                    }
                    rows.add(new ResultsWriter.Row(c.getId(), c.getTask(), c.isControl(),
                            model.getKey().getName(), repeat, score, millis, in, outTokens,
                            redact(error, secrets)));
                    out.println(c.getId() + " " + model.getKey().getName() + " #" + repeat + ": "
                            + (error != null ? "error" : score.isPass() ? "pass" : "fail"));
                }
            }
        }
        ResultsWriter.write(rows, outFolder, version());
        out.println("wrote " + rows.size() + " results to " + outFolder.getPath());
        return 0;
    }

    private static final java.util.regex.Pattern PREVIOUS =
            java.util.regex.Pattern.compile("previous-(\\d{1,9})");

    /**
     * What an earlier run left in the folder moves, together, into previous-n/ - n one higher than
     * any there - and nothing is ever replaced (1.144.0, review 1.143.0 I-3). In 1.143.0 each file
     * was moved alone over the one before it: a paid run, a retry with the server down and a good
     * run lost the paid answers, and the kept files came from different runs.
     */
    private static void keepEarlier(File outFolder, String[] names, PrintStream err)
            throws IOException {
        List<File> found = new ArrayList<File>();
        for (String name : names) {
            File file = new File(outFolder, name);
            if (file.isFile()) {
                found.add(file);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        int next = 1;
        File[] present = outFolder.listFiles();
        if (present != null) {
            for (File each : present) {
                java.util.regex.Matcher numbered = PREVIOUS.matcher(each.getName());
                if (each.isDirectory() && numbered.matches()) {
                    next = Math.max(next, Integer.parseInt(numbered.group(1)) + 1);
                }
            }
        }
        File previous = new File(outFolder, "previous-" + next);
        Files.createDirectories(previous.toPath());
        List<String> moved = new ArrayList<String>();
        for (File file : found) {
            Files.move(file.toPath(), new File(previous, file.getName()).toPath());
            moved.add(file.getName());
        }
        err.println("kept the earlier " + String.join(", ", moved) + " in " + previous.getName()
                + "/.");
    }

    private static void appendLine(File file, String line) throws IOException {
        Files.write(file.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * The words that may follow a key's or a password's word, joined to it in one word, in a
     * name that holds one: keyvalue, tokenid, secretvalue, passwordhash. See {@link #KEY_WORD}.
     */
    private static final String JOINED = "values?|ids?|string|text|data|hash|file";

    /**
     * The words of a setting about keys or tokens rather than of a key: see {@link #SETTING_WORD}.
     */
    private static final String SETTINGS = "stops?|eos|bos|unk|sep|cls|pads?|masks?|special|max"
            + "|min|per|limits?|counts?|types?";

    /**
     * Fields other tools keep a key in (1.145.0, review 1.144.0 bench-6), by the words of their
     * names: apiToken, mistralApiKey, a header's Authorization were accepted when the whole name
     * had to be one of a list (1.146.0, review 1.145.0 keys-06). A word that ends with a key word
     * - apikey written as one word too - or begins with "auth" and is no author's. Until 1.149.0
     * any name with a key word inside it counted, so "author", "max_tokens", "tokenizer" and
     * "keywords" refused a file that holds no key, with the advice to take it out of git
     * (deferred since 1.146.0).
     *
     * <p>Every key word takes its plural (1.150.0, review 1.149.0 keys-02). Key or token with
     * more after it in one word counts only when that is a word of {@link #JOINED}: with any
     * word after it, "tokenization", "tokeniser", "keystore" and "keyvariable" refused files that
     * hold no key (1.151.0, review 1.150.0 keys-02). A password's words are
     * {@link #PASSWORD_WORD}'s; a name with a word of {@link #SETTING_WORD} is a setting's. A
     * number under such a name, token_limit's, holds no key: see {@link #holdsKey}.
     */
    private static final java.util.regex.Pattern KEY_WORD = java.util.regex.Pattern.compile(
            "(?i).*(?:keys?|tokens?|bearer|auth)|.*(?:key|token)s?(?:" + JOINED + ")"
                    + "|auth(?!or(?:s|ed|ing|ship|ity|ities)?$).*");

    /**
     * A password's words - password, passwd, pwd, passwort, passphrase, secret, credential and
     * their plurals, and those joined to a word of {@link #JOINED}. Under such a name any text is
     * a password but a reference or a placeholder, whatever its length or spaces: "hunter2",
     * "Tr0ub4dor &amp;3" and a passphrase were accepted without a word, and "passphrase" was no
     * key's name at all (1.151.0, review 1.150.0 keys-01). See {@link #possiblePassword}.
     */
    private static final java.util.regex.Pattern PASSWORD_WORD = java.util.regex.Pattern.compile(
            "(?i).*(?:passwords?|passwds?|passwort|pwds?|passphrases?|secrets?|credentials?)"
                    + "|.*(?:password|passwd|pwd|passphrase|secret|credential)s?(?:" + JOINED
                    + ")");

    /**
     * A setting's words - stop, eos, bos, pad, special, max, min, per, limit, count, type and the
     * like - alone, or joined to a key's word: stopTokens, eos_token, maxtokens, tokensPerMinute,
     * tokenType. Under such a name a text holds a key only when it looks like one: other tools'
     * "Observation:", "unlimited" and "api-key-header" refused the file, with the advice to take
     * the key out of git (1.151.0, review 1.150.0 keys-02).
     */
    private static final java.util.regex.Pattern SETTING_WORD = java.util.regex.Pattern.compile(
            "(?i)(?:" + SETTINGS + ")(?:keys?|tokens?|bearer|auth|passwords?|secrets?)?"
                    + "|(?:key|token|auth|secret|password)s?(?:" + SETTINGS + ").*");

    /**
     * Words that, after a key's or a password's word, make a name one about a key rather than of
     * one: secretName, credentialSource, passwordPolicy, LiteLLM's litellm_credential_name,
     * tokenUrl, apiKeyHeader. Under 1.151.0's rule any text under a name with a password's word
     * in it was one, and such names refused files that hold none, with the advice to take the key
     * out of git (the review of the 1.151.0 keys fix). A word that only says which key -
     * apiKeyProd, api_key_2 - is none of them; nor is "file", which {@link #JOINED} has: keyFile
     * is read as keyfile is.
     */
    private static final java.util.regex.Pattern ABOUT = java.util.regex.Pattern.compile(
            "(?i)names?|sources?|polic(?:y|ies)|required|urls?|uris?|endpoints?|headers?|envs?"
                    + "|vars?|variables?|refs?|references?|paths?|locations?|providers?"
                    + "|methods?|modes?|formats?|prefix(?:es)?|lengths?"
                    + "|expir(?:y|ies|es|ation)|rotation|scopes?|fields?|params?|parameters?"
                    + "|vaults?|stores?|managers?|helpers?|commands?|cmds?|enabled|disabled"
                    + "|usage|issuers?|audiences?|algorithms?|kinds?|schemes?");

    /**
     * A yes or no written as text, which no key or password is: "usePassword": "false" and
     * "auth": "disabled" refused files that hold none (the review of the 1.151.0 keys fix).
     */
    private static final java.util.regex.Pattern FLAG = java.util.regex.Pattern.compile(
            "(?i)true|false|null|none|yes|no|on|off|disabled|enabled|required|optional");

    /** The words of a field's name: apiKey, api_key, API-KEY and APIKey each give api and key. */
    private static final java.util.regex.Pattern NAME_WORD = java.util.regex.Pattern.compile(
            "[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");

    /** The default in ${NAME:-default}: see {@link #keyIn}. */
    private static final java.util.regex.Pattern DEFAULT = java.util.regex.Pattern.compile(
            "\\$\\{(?i:env:)?[^}:]+:-([^}]*)\\}");

    /**
     * The models file's own fields whose names hold one of those words, in lower case and without
     * separators. keyVariable as written is checked by {@link #keyIn}'s own rule. Any other
     * spelling of it - keyvariable, KeyVariable, key_variable - is not read, and holds a key only
     * when its text looks like one: such a field was refused as holding one whatever its text
     * (1.151.0, review 1.150.0 keys-02).
     */
    private static final Set<String> NOT_KEY_FIELDS = new HashSet<String>(Arrays.asList(
            "keyvariable", "maxoutputtokens"));

    /** What a field's name says its text can hold: see AssistantBench.says. */
    private enum Holds {
        /** Nothing: no key's name, or keyVariable, which keyIn checks by its own rule. */
        NOTHING,
        /** A setting's: a text holds a key only when it looks like one. */
        SETTING,
        /** A key's or a token's: see AssistantBench.possibleKey. */
        KEY,
        /** A password's: see AssistantBench.possiblePassword. */
        PASSWORD
    }

    /** The presets' key variables, which a message may name: see {@link #referenceIn}. */
    private static final Set<String> PRESET_VARIABLES = presetVariables();

    /** A variable's name, on Windows too: MY-LLM-KEY is one (1.146.0, review 1.145.0 keys-03). */
    private static final java.util.regex.Pattern VARIABLE =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /** A digit followed by a letter: see {@link #looksRandom}. */
    private static final java.util.regex.Pattern DIGIT_THEN_LETTER =
            java.util.regex.Pattern.compile("[0-9][A-Za-z]");

    /** A UUID, in either case: see {@link #looksRandom}. */
    private static final java.util.regex.Pattern UUID_FORM = java.util.regex.Pattern.compile(
            "(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    /**
     * A variable written the way a shell or a settings file refers to it - $NAME, ${NAME},
     * %NAME%, env:NAME - with the name in one of the groups. Also $env:NAME (PowerShell),
     * ${env:NAME} (VS Code), $(NAME), {{NAME}} and ${NAME:-default}, which were refused as
     * holding a key (1.147.0, the review of the keys-05 fix).
     */
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile(
            "\\$\\{(?i:env:)?([^}:]+)(?::-[^}]*)?\\}|\\$\\((.+)\\)|\\$(?i:env:)?(.+)|%(.+)%"
                    + "|(?i:env:)(.+)|\\{\\{\\s*(.+?)\\s*\\}\\}");

    /** A name the "skipped" line may show as $NAME: the presets' form. */
    private static final java.util.regex.Pattern SHOWN_VARIABLE =
            java.util.regex.Pattern.compile("[A-Z_][A-Z0-9_]*");

    /** LiteLLM's way to name a variable under a key's name: os.environ/NAME. */
    private static final java.util.regex.Pattern ENVIRON =
            java.util.regex.Pattern.compile("os\\.environ/(.+)");

    /** An Authorization header's scheme, then its credentials: "Bearer KEY", "Basic KEY". */
    private static final java.util.regex.Pattern SCHEME = java.util.regex.Pattern.compile(
            "(?i)(?:bearer|basic|token|api-?key)[\\s\\p{Z}]+(.*)");

    /** A space of any kind, which no key holds: see {@link #possibleKey}. */
    private static final java.util.regex.Pattern SPACE =
            java.util.regex.Pattern.compile("[\\s\\p{Z}]");

    /** A model's special token: &lt;/s&gt;, &lt;|im_end|&gt;, &lt;end_of_turn&gt;. */
    private static final java.util.regex.Pattern SPECIAL_TOKEN =
            java.util.regex.Pattern.compile("</?\\|?[A-Za-z0-9_.-]{1,40}\\|?>");

    /**
     * A placeholder in brackets under a password's name - &lt;YOUR_PASSWORD&gt; - with no digit
     * in it: a password left in them, &lt;Wint3r2026&gt;, is still one (1.151.0, review 1.150.0
     * keys-01).
     */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("</?\\|?[A-Za-z_.-]{1,40}\\|?>");

    /** The shortest run of letters and digits that {@link #unnamed} can take for a key. */
    private static final int UNNAMED_RUN = 16;

    /** The models file read as {@link #entries} reads it, with a field given twice an error. */
    private static final com.fasterxml.jackson.databind.ObjectReader STRICT =
            BenchCases.JSON.reader().with(
                    com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /**
     * A key rather than a name: a run of 20 or more letters and digits, with no "_", "." or "-"
     * in it, where a digit is followed by a letter. Mistral's, Cohere's and Azure's keys have no
     * shape, and their digits fall anywhere in them. A name's words are separated, or begin with a
     * capital, and a number in it ends it: under 1.146.0's rule - 20 or more characters with a
     * lower-case letter and a digit (review 1.145.0 bench-A) - mistral_api_key_2024,
     * azure_openai_api_key_2 and MyTeamGatewayApiKey2 were refused as keys (1.147.0, review
     * 1.146.0 keys-05).
     *
     * <p>Also a UUID, and a value of 20 or more characters with three or more digit-then-letter
     * pairs across its runs. A UUID's runs are 12 long at most, and a base64url token is split
     * by "-" and "_": both were taken for a name, and the file passed with no word about a key -
     * Scaleway's secret keys are UUIDs, and 1.146.0 caught every one (1.148.0, review 1.147.0
     * keys-02). A name's numbers are few: MIXTRAL_8X7B_INSTRUCT_KEY has two such pairs,
     * GPT4O_MINI_2024_07_18_KEY one.
     */
    private static boolean looksRandom(String name) {
        if (UUID_FORM.matcher(name).matches()) {
            return true;
        }
        for (String run : name.split("[_.\\-]")) {
            if (run.length() >= 20 && DIGIT_THEN_LETTER.matcher(run).find()) {
                return true;
            }
        }
        int pairs = 0;
        java.util.regex.Matcher pair = DIGIT_THEN_LETTER.matcher(name);
        while (pair.find()) {
            pairs++;
        }
        return name.length() >= 20 && pairs >= 3;
    }

    /** Whether a text with no space looks like a key: {@link #looksRandom} or {@link #unnamed}. */
    private static boolean looksLikeKey(String text) {
        return looksRandom(text) || unnamed(text);
    }

    /**
     * Whether a run of 16 or more letters and digits in the text reads as no name: its words, as
     * {@link #NAME_WORD} splits it, are under three characters long on average. A key's case or
     * digits change every two characters or so - QwErTy..., 7f3a9c2e1b4d8a6f - a name's with
     * each word: MyTeamGatewayApiKey2, internationalization. looksRandom needs a digit followed by
     * a letter, so a key of letters, or with its digits at the end, written with a space - after
     * "Key", beside a comment, beside a second key - was accepted without a word (1.151.0, review
     * 1.150.0 keys-01).
     */
    private static boolean unnamed(String text) {
        for (String run : text.split("[^A-Za-z0-9]+")) {
            if (run.length() >= UNNAMED_RUN) {
                int words = 0;
                java.util.regex.Matcher word = NAME_WORD.matcher(run);
                while (word.find()) {
                    words++;
                }
                if (run.length() < 3 * words) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> presetVariables() {
        Set<String> variables = new HashSet<String>();
        for (ConnectionPreset preset : ConnectionPreset.values()) {
            if (!preset.getKeyVariable().isEmpty()) {
                variables.add(preset.getKeyVariable());
            }
        }
        return variables;
    }

    /** The variable's name in $NAME, ${NAME}, %NAME% or env:NAME; null for any other value. */
    private static String referenced(String variable) {
        java.util.regex.Matcher reference = REFERENCE.matcher(variable);
        if (!reference.matches()) {
            return null;
        }
        for (int group = 1; group <= reference.groupCount(); group++) {
            String inside = reference.group(group);
            if (inside != null) {
                return VARIABLE.matcher(inside).matches() ? inside : null;
            }
        }
        return null;
    }

    /** The models file's entries; none when it is not JSON. */
    private static List<JsonNode> entries(String json) {
        List<JsonNode> entries = new ArrayList<JsonNode>();
        JsonNode root;
        try {
            root = BenchCases.JSON.readTree(json);
        } catch (IOException notJson) {
            return entries;
        }
        if (root == null) {
            return entries;
        }
        if (root.isArray()) {
            for (JsonNode entry : root) {
                entries.add(entry);
            }
        } else {
            entries.add(root);
        }
        return entries;
    }

    /**
     * Where a models file names its variable the way a shell refers to it, and what to write
     * instead; null when none does. Such a file holds no key: it was refused as holding one, and
     * told to take the key out of git (1.147.0, review 1.146.0 keys-05). The name is shown only
     * in the presets' form, as on the "skipped" line. The file alone: every variable unset.
     */
    static String referenceIn(String json) {
        return referenceIn(json, Collections.<String, String>emptyMap());
    }

    /**
     * {@link #referenceIn(String)}, where a field under a key's name names the variable it refers
     * to only when that is known to be a variable's name: a preset's, the entry's own keyVariable
     * in the presets' form, or one set in the environment. Under a key's name the text inside
     * ${...} is what a person who pasted a key over a template's name has there, and a key in
     * upper case shorter than 20 characters, or with no digit followed by a letter -
     * "${AB12CD34EF56GH78}" - was printed whole (1.151.0, review 1.150.0 keys-03).
     */
    static String referenceIn(String json, Map<String, String> environment) {
        int number = 0;
        for (JsonNode entry : entries(json)) {
            number++;
            String variable = entry.path("keyVariable").asText("");
            String inside = referenced(variable);
            if (inside != null) {
                String form = variable.startsWith("${") ? "as ${...}"
                        : variable.startsWith("$(") ? "as $(...)"
                                : variable.startsWith("$") ? "with $"
                                        : variable.startsWith("%") ? "as %...%"
                                                : variable.startsWith("{{") ? "as {{...}}"
                                                        : "with env:";
                return "keyVariable of entry " + number + " is written " + form + "; write the "
                        + (SHOWN_VARIABLE.matcher(inside).matches() ? "bare name, " + inside
                                : "variable's bare name") + ".";
            }
            // A key's field that names a variable - "apiKey": "${MISTRAL_API_KEY}", LiteLLM's
            // os.environ/NAME - holds no key, and was refused as holding one, with the advice
            // to take it out of git (1.150.0, review 1.149.0 keys-03).
            String[] field = referenceField(entry);
            if (field != null) {
                // Only a name known to be a variable's is shown: hiding one that looks like a key
                // still printed an upper-case key shorter than 20 characters (1.151.0, review
                // 1.150.0 keys-03).
                boolean known = PRESET_VARIABLES.contains(field[1]) || isSet(environment, field[1])
                        || field[1].equals(variable) && SHOWN_VARIABLE.matcher(variable).matches();
                return "\"" + field[0] + "\" in entry " + number + " refers to "
                        + (known ? "the variable " + field[1] : "a variable")
                        + ", but a key is read only from the variable keyVariable names: name the"
                        + " variable there, and take \"" + field[0] + "\" out.";
            }
        }
        return null;
    }

    /**
     * The first field at any depth whose name says it holds a key or a password and whose text
     * refers to a variable, as its name and the variable's; null when none does. A setting's
     * name is no such name: a chat template's "{{user}}" in stop_tokens is no key's variable.
     */
    private static String[] referenceField(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Holds says = says(field.getKey(), Holds.NOTHING);
                String variable = says == Holds.KEY || says == Holds.PASSWORD
                        ? variableIn(field.getValue()) : null;
                if (variable != null) {
                    return new String[] {field.getKey(), variable};
                }
                String[] deeper = referenceField(field.getValue());
                if (deeper != null) {
                    return deeper;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String[] deeper = referenceField(item);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /** The variable a text at any depth of the value refers to; null when none does. */
    private static String variableIn(JsonNode value) {
        if (value.isTextual()) {
            return variableIn(credentials(value.asText().trim()));
        }
        for (JsonNode item : value) {
            String variable = variableIn(item);
            if (variable != null) {
                return variable;
            }
        }
        return null;
    }

    /**
     * The variable's name in a reference - $NAME, ${NAME}, %NAME%, env:NAME and the others of
     * {@link #REFERENCE}, or os.environ/NAME - and null for any other text.
     */
    private static String variableIn(String text) {
        String inside = referenced(text);
        if (inside != null) {
            return inside;
        }
        java.util.regex.Matcher environ = ENVIRON.matcher(text);
        return environ.matches() && VARIABLE.matcher(environ.group(1)).matches()
                ? environ.group(1) : null;
    }

    /** A header's credentials without its scheme - "Bearer KEY" gives KEY - or the text. */
    private static String credentials(String text) {
        java.util.regex.Matcher scheme = SCHEME.matcher(text);
        return scheme.matches() ? scheme.group(1).trim() : text;
    }

    /**
     * Where a models file gives a field twice, never its values; null when it gives none twice,
     * or is not JSON, which Connections.fromJson then says where. Read as a tree, the last of two
     * fields with one name was kept, so a key followed by an empty "apiKey" went unseen; read
     * with Jackson's strict duplicate detection as well, such a file is refused (1.150.0, review
     * 1.149.0 keys-02). The parser's message quotes the file, and is never shown.
     */
    static String twiceIn(String json) {
        try {
            BenchCases.JSON.readTree(json);
        } catch (IOException notJson) {
            return null;
        }
        try {
            STRICT.readTree(json);
            return null;
        } catch (IOException twice) {
            // Read without the check the file was JSON, so a field is given twice.
            Object parser = twice instanceof com.fasterxml.jackson.core.JsonProcessingException
                    ? ((com.fasterxml.jackson.core.JsonProcessingException) twice).getProcessor()
                    : null;
            return parser instanceof com.fasterxml.jackson.core.JsonParser
                    ? twice(((com.fasterxml.jackson.core.JsonParser) parser).getParsingContext())
                    : "a field is given twice";
        }
    }

    /** The field given twice - by its name, when that is a plain one - and its entry. */
    private static String twice(com.fasterxml.jackson.core.JsonStreamContext context) {
        if (context == null) {
            return "a field is given twice";
        }
        com.fasterxml.jackson.core.JsonStreamContext top = context;
        while (top.getParent() != null && !top.getParent().inRoot()) {
            top = top.getParent();
        }
        int entry = top.inArray() ? top.getCurrentIndex() + 1 : 1;
        String name = context.getCurrentName();
        // The name only when no key can be in it: looksRandom alone printed a key of letters
        // pasted twice as a field's name (the review of the 1.151.0 keys fix).
        boolean plain = name != null && name.length() <= 64 && VARIABLE.matcher(name).matches()
                && !looksLikeKey(name);
        return (plain ? "\"" + name + "\"" : "a field") + " is given twice in entry " + entry;
    }

    /**
     * Where a models file holds a key, never the key itself; null when it holds none. A key
     * pasted into it - a habit from other tools - was accepted without a word, in a file the
     * README put inside the clone (1.145.0, review 1.144.0 bench-6). Run before the file is read
     * as connections (1.146.0, review 1.145.0 keys-02). The file alone: every variable unset.
     */
    static String keyIn(String json) {
        return keyIn(json, Collections.<String, String>emptyMap());
    }

    /**
     * {@link #keyIn(String)}, where a keyVariable that names a variable set in the environment is
     * a variable's name, whatever it looks like: MIXTRAL_8X7B_Q4K_M_KEY, QWEN2_5_7B_Q4K_M_Q8K_KEY
     * and ONTOBENCH_KEY_2B_4K_8K, with three digit-then-letter pairs, were refused as keys even
     * when set (1.149.0, review 1.148.0 keys-03 and its declined note).
     */
    static String keyIn(String json, Map<String, String> environment) {
        // Shapes only on the whole text: the address rule there ran from a bare-host baseUrl to
        // the "@" of a model id such as claude-sonnet-4@20250514. It runs on each address below
        // (1.146.0, review 1.145.0 bench-D).
        if (!KeyRedactor.redactKeysAndShapes(json, Collections.<String>emptyList())
                .equals(json)) {
            return "something shaped like a key";
        }
        String twice = twiceIn(json);
        if (twice != null) {
            return twice;
        }
        int number = 0;
        for (JsonNode entry : entries(json)) {
            number++;
            String field = keyField(entry);
            if (field != null) {
                return "\"" + field + "\" in entry " + number;
            }
            String address = entry.path("baseUrl").asText("");
            if (address.contains("?")) {
                return "a query, which can carry one, in the address of entry " + number;
            }
            if (!KeyRedactor.redact(address, Collections.<String>emptyList()).equals(address)) {
                return "a name and password, or a token, in the address of entry " + number;
            }
            String variable = entry.path("keyVariable").asText("");
            // $NAME and the like are checked by the name in them: no key, though no name either
            // as written, and referenceIn says so (1.147.0, review 1.146.0 keys-05).
            String inside = referenced(variable);
            String name = inside == null ? variable : inside;
            boolean set = isSet(environment, name);
            if (!set && !name.isEmpty() && !VARIABLE.matcher(name).matches()) {
                return "keyVariable of entry " + number + ", which names no environment variable";
            }
            if (!set && looksRandom(name)) {
                return "keyVariable of entry " + number + ", which looks like a key, not a"
                        + " variable's name; if it is a variable's name, set the variable to run";
            }
            // A key as the default in ${NAME:-...} was refused only for the way the name is
            // written, and the person never heard that the file held a key (1.149.0, review
            // 1.147.0 keys-04).
            java.util.regex.Matcher fallback = DEFAULT.matcher(variable);
            if (fallback.matches() && looksRandom(fallback.group(1))) {
                return "keyVariable of entry " + number + ", whose default looks like a key";
            }
        }
        return null;
    }

    /** Whether the variable has a value that is not blank: what runModels takes for set. */
    private static boolean isSet(Map<String, String> environment, String name) {
        String value = environment == null || name.isEmpty() ? null : environment.get(name);
        return value != null && !value.trim().isEmpty();
    }

    /**
     * What a field's name says its text can hold, by the words of the name (see
     * {@link #KEY_WORD}); what the name of the field around it says when its own says nothing. A
     * setting's word comes first, then a password's, then a key's: "credentials": {"type":
     * "bearer"} holds no key, and "secretKey" holds a password. One rule for all three was too
     * strict for other tools' settings and too lax for passwords (1.151.0, review 1.150.0 keys-01
     * and keys-02). A word of {@link #ABOUT} after the last key's or password's word makes the
     * name a setting's too: secretName, tokenUrl (the review of the 1.151.0 keys fix).
     */
    private static Holds says(String name, Holds outer) {
        if ("keyVariable".equals(name)) {
            return Holds.NOTHING;
        }
        if (NOT_KEY_FIELDS.contains(name.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(java.util.Locale.ROOT))) {
            return Holds.SETTING;
        }
        boolean setting = false;
        boolean password = false;
        boolean key = false;
        // Whether the last word that says what the field is - a key's, a password's or one of
        // ABOUT - is one of ABOUT after a key's or a password's: secretName is a name.
        boolean about = false;
        java.util.regex.Matcher word = NAME_WORD.matcher(name);
        while (word.find()) {
            String each = word.group();
            boolean itsPassword = PASSWORD_WORD.matcher(each).matches();
            boolean itsKey = KEY_WORD.matcher(each).matches();
            setting |= SETTING_WORD.matcher(each).matches();
            if (itsPassword || itsKey) {
                about = false;
            } else if ((password || key) && ABOUT.matcher(each).matches()) {
                about = true;
            }
            password |= itsPassword;
            key |= itsKey;
        }
        if (setting || about) {
            return password || key || outer != Holds.NOTHING ? Holds.SETTING : Holds.NOTHING;
        }
        return password ? Holds.PASSWORD : key ? Holds.KEY : outer;
    }

    /**
     * Whether a value holds a key as its name says: a text at any depth that
     * {@link #possiblePassword} or {@link #possibleKey} takes, each field in it read by its own
     * name, or by the name around it when its own says nothing. A number, a yes or no, or an
     * empty text under a key's name - token_limit, useAuth, an empty apiKey - holds none
     * (1.149.0).
     */
    private static boolean holdsKey(JsonNode value, Holds says) {
        if (value.isTextual()) {
            return says == Holds.PASSWORD ? possiblePassword(value.asText())
                    : says != Holds.NOTHING && possibleKey(value.asText(), says == Holds.SETTING);
        }
        if (value.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (holdsKey(field.getValue(), says(field.getKey(), says))) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode item : value) {
            if (holdsKey(item, says)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a text under a password's name can be one: any text but a reference to a variable
     * - after a scheme, "Bearer" - or a placeholder in brackets, whatever its length or spaces.
     * The rule for keys took "hunter2", "Tr0ub4dor &amp;3" and "correct horse battery staple" for
     * no password (1.151.0, review 1.150.0 keys-01). A password as the default in ${NAME:-...}
     * is still one.
     */
    private static boolean possiblePassword(String text) {
        String value = credentials(stripped(text));
        java.util.regex.Matcher fallback = DEFAULT.matcher(value);
        if (fallback.matches()) {
            return possiblePassword(fallback.group(1));
        }
        // A placeholder only when what is inside the brackets does not look like a key: a secret
        // of letters left in them was accepted without a word (the review of the 1.151.0 keys
        // fix), as it is reported under a key's name.
        return !value.isEmpty() && variableIn(value) == null && !FLAG.matcher(value).matches()
                && !(PLACEHOLDER.matcher(value).matches()
                        && !looksLikeKey(value.replaceAll("[<>|/]", "")));
    }

    /**
     * Whether a text under a key's name can be a key: 8 or more characters (KeyRedactor's
     * SHORTEST), no space, and no reference to a variable - after an Authorization header's
     * scheme, "Bearer". "${MISTRAL_API_KEY}", LiteLLM's "os.environ/MISTRAL_API_KEY", a model's
     * "&lt;/s&gt;" or "&lt;|end|&gt;" and {"type": "bearer"} were refused as keys, with the advice
     * to take the key out of git (1.150.0, review 1.149.0 keys-03); {@link #referenceIn} refuses
     * a reference with the advice to name the variable in keyVariable. A key as the default in
     * ${NAME:-...} is still one. Under a setting's name ({@code strict}) the text must also look
     * like a key: {@link #looksLikeKey}.
     *
     * <p>A text with a space holds a key when a part of it looks like one: after a scheme other
     * than those four - "Key", "OAuth", "Bot", DeepL's, Okta's "SSWS" - beside a comment or a
     * second key, a key was no longer reported (1.150.0, the review of the keys-03 fix). So do two
     * parts side by side that are one key broken in two: see {@link #brokenKey}. A model's
     * special token, or a placeholder, is none when what is inside the brackets does not look
     * like a key: a key left in them, as in "&lt;Qm7R...&gt;", was taken for a special token
     * (1.151.0, review 1.150.0 keys-01). Spaces of any kind around a text are no part of it:
     * trim() left a no-break or an ideographic space, and the key beside it was taken for a part
     * that looks like no key.
     */
    private static boolean possibleKey(String text, boolean strict) {
        String value = credentials(stripped(text));
        java.util.regex.Matcher fallback = DEFAULT.matcher(value);
        if (fallback.matches()) {
            return possibleKey(fallback.group(1), strict);
        }
        if (FLAG.matcher(value).matches()) {
            return false;
        }
        if (SPECIAL_TOKEN.matcher(value).matches()
                && !looksLikeKey(value.replaceAll("[<>|/]", ""))) {
            return false;
        }
        if (SPACE.matcher(value).find()) {
            String[] parts = value.split("[\\s\\p{Z}]+");
            for (int i = 0; i < parts.length; i++) {
                if (possibleKey(parts[i], true)
                        || i + 1 < parts.length && brokenKey(parts[i], parts[i + 1])) {
                    return true;
                }
            }
            return false;
        }
        return value.length() >= KeyRedactor.SHORTEST && variableIn(value) == null
                && (!strict || looksLikeKey(value));
    }

    /**
     * Whether two parts of a text side by side are one key broken in two, by a space or by a
     * line break where a mail or a terminal wrapped it: each half of a 32-character key is too
     * short to look like a key, and such a key was accepted without a word (1.151.0, review
     * 1.150.0 keys-01). Joined at a mark, so that a number ending one word and a letter beginning
     * the next - "MyTeamGatewayApiKey2 first" - are no digit followed by a letter.
     */
    private static boolean brokenKey(String first, String second) {
        String joined = first + second;
        return joined.length() >= KeyRedactor.SHORTEST && variableIn(joined) == null
                && (unnamed(joined) || looksRandom(first + "-" + second));
    }

    /** The text without spaces of any kind at either end, which trim() leaves. */
    private static String stripped(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** Whether a character is a space of any kind, as {@link #SPACE} has it. */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** The first field at any depth whose name says it holds a key; null when none does. */
    private static String keyField(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Holds says = says(field.getKey(), Holds.NOTHING);
                if (says != Holds.NOTHING && holdsKey(field.getValue(), says)) {
                    return field.getKey();
                }
                String deeper = keyField(field.getValue());
                if (deeper != null) {
                    return deeper;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String deeper = keyField(item);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    private static String redact(String text, List<String> secrets) {
        return text == null ? null : KeyRedactor.redact(text, secrets);
    }

    /**
     * One answer's score. A definition, and an axiom written from a sentence, are judged against
     * the ontology as the model saw it: without the definition, or without the axiom.
     */
    private static Score scoreOne(BenchCase c, String text, Set<String> iris,
            OWLOntology without) {
        boolean defined = BenchCase.DEFINITION.equals(c.getTask());
        if (defined || BenchCase.TEXT_TO_AXIOM.equals(c.getTask())) {
            if (without == null) {
                LinkedHashMap<String, Double> none = new LinkedHashMap<String, Double>();
                none.put("validProposal", 0.0);
                return new Score(none, false);
            }
            return defined ? Scorers.definition(c, text, iris, without)
                    : Scorers.textToAxiom(c, text, iris, without,
                            Reasoners.Choice.HERMIT.newFactory());
        }
        return Scorers.score(c, text, iris);
    }

    /** The ontology as a definition or text-to-axiom case showed it; null for the others. */
    private static OWLOntology without(BenchCase c, Source source) {
        if (source.ontology == null) {
            return null;
        }
        if (BenchCase.DEFINITION.equals(c.getTask())) {
            return CaseGenerator.withoutDefinition(source.ontology,
                    IRI.create(c.getSubjectIri()));
        }
        if (BenchCase.TEXT_TO_AXIOM.equals(c.getTask())) {
            return CaseGenerator.without(source.ontology, BenchCases.goldAxiom(c));
        }
        return null;
    }

    /** The repair loop with HermiT, the reasoner the cases were made with. */
    private static RepairLoop.Outcome repair(BenchCase c, Source source, String roleText,
            int maxOutput, RepairLoop.Model model) {
        if (source.ontology == null) {
            RepairLoop.Outcome unreadable = new RepairLoop.Outcome();
            unreadable.error = "the case's ontology could not be read";
            return unreadable;
        }
        Reasoners.Choice hermit = Reasoners.Choice.HERMIT;
        return RepairLoop.run(c, source.ontology, hermit.newFactory(), hermit.getLabel(),
                roleText, maxOutput, model);
    }

    /**
     * A repair's replies.jsonl line: every round's reply, so "score" can replay it. Each text is
     * redacted before it is encoded: in JSON a line break is two characters, and a key split by
     * one was written in two halves (1.145.0, review 1.144.0 privacy-03). The line then gets the
     * key and shape passes again, never the address one, which in an encoded line ran from a URL
     * at the end of one field to an "@" in the next (1.146.0, review 1.145.0 keys-04).
     */
    private static String repairReply(BenchCase c, Connection model, int repeat,
            RepairLoop.Outcome outcome, List<String> secrets) throws IOException {
        ObjectNode node = BenchCases.JSON.createObjectNode();
        node.put("case", c.getId());
        node.put("model", model.getName());
        node.put("repeat", repeat);
        node.put("sent", redact(String.join("\n\n--- next round ---\n\n", outcome.sent),
                secrets));
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < outcome.replies.size(); i++) {
            all.append(i == 0 ? "" : "\n\n").append("--- round ").append(i + 1)
                    .append(" ---\n\n").append(outcome.replies.get(i));
        }
        node.put("reply", redact(all.toString(), secrets));
        com.fasterxml.jackson.databind.node.ArrayNode rounds = node.putArray("rounds");
        for (String text : outcome.replies) {
            rounds.add(redact(text, secrets));
        }
        node.put("millis", outcome.millis);
        node.put("inputTokens", outcome.inputTokens);
        node.put("outputTokens", outcome.outputTokens);
        if (outcome.error == null) {
            node.putNull("error");
        } else {
            node.put("error", redact(outcome.error, secrets));
        }
        return BenchCases.JSON.writeValueAsString(node);
    }

    /** A replies.jsonl line, each text redacted before it is encoded (privacy-03). */
    private static String reply(BenchCase c, Connection model, int repeat, String sent,
            String text, long millis, int in, int out, String error, List<String> secrets)
            throws IOException {
        ObjectNode node = BenchCases.JSON.createObjectNode();
        node.put("case", c.getId());
        node.put("model", model.getName());
        node.put("repeat", repeat);
        node.put("sent", redact(sent, secrets));
        node.put("reply", redact(text, secrets));
        node.put("millis", millis);
        node.put("inputTokens", in);
        node.put("outputTokens", out);
        if (error == null) {
            node.putNull("error");
        } else {
            node.put("error", redact(error, secrets));
        }
        return BenchCases.JSON.writeValueAsString(node);
    }

    private static int score(Map<String, String> options, PrintStream out, PrintStream err)
            throws IOException {
        Map<BenchCase, File> cases = readCases(new File(required(options, "cases")));
        File repliesFile = new File(required(options, "replies"));
        File outFolder = new File(required(options, "out"));
        Map<String, BenchCase> byId = new HashMap<String, BenchCase>();
        for (BenchCase c : cases.keySet()) {
            byId.put(c.getId(), c);
        }
        Map<String, Source> known = new HashMap<String, Source>();
        Map<String, OWLOntology> withouts = new HashMap<String, OWLOntology>();
        List<ResultsWriter.Row> rows = new ArrayList<ResultsWriter.Row>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(repliesFile.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = BenchCases.JSON.readTree(line);
            } catch (IOException unreadable) {
                // A run stopped while it wrote this line: the replies before it still count
                // (1.144.0, review 1.143.0 M-8).
                err.println("line " + lineNumber + " of " + repliesFile.getName() + " is not a"
                        + " whole reply - a run stopped while writing it? - and is skipped.");
                continue;
            }
            BenchCase c = byId.get(node.path("case").asText());
            if (c == null) {
                err.println("no case " + node.path("case").asText() + "; its reply is skipped.");
                continue;
            }
            String error = node.path("error").isTextual() ? node.path("error").asText() : null;
            Source source = source(c, cases.get(c), known, err);
            Score score;
            if (BenchCase.REPAIR.equals(c.getTask())) {
                // Replayed: the recorded replies, in order, through the same loop.
                final List<String> recorded = new ArrayList<String>();
                for (JsonNode round : node.path("rounds")) {
                    recorded.add(round.asText(""));
                }
                RepairLoop.Outcome outcome = repair(c, source, "", 0, request -> {
                    if (recorded.isEmpty()) {
                        throw new ModelCallException(ModelCallException.Kind.BAD_REPLY,
                                "the recorded replies ran out", null);
                    }
                    return new Reply(recorded.remove(0), Reply.UNKNOWN, Reply.UNKNOWN, "", 0);
                });
                if (error != null) {
                    outcome.error = error;
                }
                score = Scorers.repair(outcome, source.iris);
            } else {
                OWLOntology without = withouts.get(c.getId());
                if (without == null && !withouts.containsKey(c.getId())) {
                    without = without(c, source);
                    withouts.put(c.getId(), without);
                }
                try {
                    score = scoreOne(c, node.path("reply").asText(""), source.iris, without);
                } catch (RuntimeException unscorable) {
                    score = new Score(new LinkedHashMap<String, Double>(), false);
                    error = "could not be scored: " + unscorable;
                }
                if (error == null) {
                    error = score.getError();
                }
            }
            rows.add(new ResultsWriter.Row(c.getId(), c.getTask(), c.isControl(),
                    node.path("model").asText(),
                    node.path("repeat").asInt(), score, node.path("millis").asLong(),
                    node.path("inputTokens").asInt(Reply.UNKNOWN),
                    node.path("outputTokens").asInt(Reply.UNKNOWN), error));
        }
        // Results already in that folder - a run's own, when its folder is scored again - are kept
        // with the earlier runs; the replies are never moved (1.144.0, review 1.143.0 M-8).
        Files.createDirectories(outFolder.toPath());
        keepEarlier(outFolder, new String[] {"results.csv", "summary.md"}, err);
        ResultsWriter.write(rows, outFolder, version());
        out.println("re-scored " + rows.size() + " replies into " + outFolder.getPath());
        return 0;
    }

    /** A case's ontology as read once per file: its IRIs, and itself for the round-2 tasks. */
    private static final class Source {
        final Set<String> iris = new HashSet<String>();
        OWLOntology ontology;
    }

    private static Source source(BenchCase c, File folder, Map<String, Source> cache,
            PrintStream err) {
        File file = new File(c.getOntology());
        if (!file.isAbsolute()) {
            file = new File(folder, c.getOntology());
        }
        String key = file.getAbsolutePath();
        Source source = cache.get(key);
        if (source != null) {
            return source;
        }
        source = new Source();
        try {
            if (!c.getOntologySha256().equals(CaseGenerator.sha256Lf(file))) {
                err.println("warning: " + file.getPath() + " is not the file the cases were"
                        + " made from (its SHA-256 differs).");
            }
            source.ontology = load(file);
            for (OWLEntity entity : source.ontology.getSignature(Imports.INCLUDED)) {
                source.iris.add(entity.getIRI().toString());
            }
        } catch (IOException unreadable) {
            err.println("warning: could not read " + file.getPath() + "; every IRI in an answer"
                    + " will count as invented, and its definition, repair and text-to-axiom"
                    + " cases fail.");
        }
        cache.put(key, source);
        return source;
    }

    private static String roleText(String id, String field) throws IOException {
        RoleLibrary library = new RoleLibrary(new RoleStore() {
            @Override
            public Map<String, String> load() {
                return Collections.emptyMap();
            }

            @Override
            public void save(String roleId, String text) {
            }

            @Override
            public void remove(String roleId) {
            }
        });
        Role role = library.get(id);
        if (role == null) {
            throw new IllegalArgumentException("no built-in role called " + id + "; the roles"
                    + " are " + RoleLibrary.BUILT_IN);
        }
        return RoleLibrary.render(role, field);
    }

    static String version() {
        return de.fizkarlsruhe.ise.ontoboard.assistant.BuildInfo.version();
    }
}
