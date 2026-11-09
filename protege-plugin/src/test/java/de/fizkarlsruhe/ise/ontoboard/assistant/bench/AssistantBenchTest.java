package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.FakeModelServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The benchmark as a command: generate, run against a model, re-score without calling it. */
class AssistantBenchTest {

    @TempDir
    File dir;

    private FakeModelServer server;
    private File ontology;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        server = FakeModelServer.start();
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"VegetarianTopping and MeatTopping are disjoint.\"},"
                        + "\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        ontology = new File(dir, "mini-pizza.ofn");
        try (InputStream in = getClass().getResourceAsStream("/assistant/mini-pizza.ofn")) {
            Files.copy(in, ontology.toPath());
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private int bench(Map<String, String> environment, String... args) {
        return AssistantBench.run(args, new PrintStream(out, true), new PrintStream(err, true),
                environment);
    }

    private File models(String keyVariable) throws IOException {
        File models = new File(dir, "models.json");
        Files.write(models.toPath(), ("[{\"name\":\"fake\",\"kind\":\"ollama\",\"baseUrl\":\""
                + server.url() + "\",\"model\":\"m\",\"keyVariable\":\"" + keyVariable + "\"}]")
                .getBytes(StandardCharsets.UTF_8));
        return models;
    }

    private File generate(String name) {
        File cases = new File(dir, name);
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                ontology.getPath(), "--out", cases.getPath(), "--seed", "3", "--explain", "1",
                "--context", "1", "--controls", "0", "--definition", "0", "--repair", "0",
                "--text-to-axiom", "0"),
                err.toString());
        return cases;
    }

    /**
     * The fake server explains but never proposes a removal: the loop asks three times, stops
     * unsolved, and records every round so "score" can replay them without the server.
     */
    /**
     * Review 1.141.0 I2: in run and in score, a repair answer the reasoner cannot handle is that
     * row's error; the answer is kept, and the files are written.
     */
    @Test
    void aRepairAnswerTheReasonerCannotHandleIsThatRowsError() throws IOException {
        File clash = new File(dir, "datatype-clash.ofn");
        Files.copy(new File("../benchmarks/assistant/ontologies/fixtures/datatype-clash.ofn")
                .toPath(), clash.toPath());
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"```ontoboard-proposal\\naction: add-axiom\\naxiom: "
                        + "'has melting point in degrees Celsius' Range xsd:string[>= 5]\\n```\"},\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        File cases = new File(dir, "clash.jsonl");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                clash.getPath(), "--out", cases.getPath(), "--seed", "1", "--explain", "0",
                "--context", "0", "--controls", "0", "--definition", "0", "--repair", "1",
                "--text-to-axiom", "0"), err.toString());
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());

        List<String> csv = Files.readAllLines(new File(results, "results.csv").toPath());
        assertEquals(2, csv.size(), csv.toString());
        assertTrue(csv.get(1).contains("could not check"), csv.get(1));
        String replies = new String(Files.readAllBytes(new File(results, "replies.jsonl")
                .toPath()), StandardCharsets.UTF_8);
        assertEquals(1, BenchCases.JSON.readTree(replies.trim()).path("rounds").size());

        File rescored = new File(dir, "rescored");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", new File(results, "replies.jsonl").getPath(),
                "--out", rescored.getPath()), err.toString());
        assertEquals(csv, Files.readAllLines(new File(rescored, "results.csv").toPath()));
    }

    /**
     * Review 1.141.0 M4 and review 1.143.0 I-3: a run into a folder that holds earlier runs keeps
     * every one of them whole, in previous-1/, previous-2/, ..., and replaces nothing. In 1.143.0
     * each file was moved alone over the one before: a paid run, then a retry with the server
     * down, then a good run lost the paid answers.
     */
    @Test
    void everyEarlierRunIsKeptWhole() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());
        String paid = read(new File(results, "replies.jsonl"));
        String paidResults = read(new File(results, "results.csv"));
        server.close();
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());
        String failed = read(new File(results, "replies.jsonl"));
        server = FakeModelServer.start();
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"VegetarianTopping and MeatTopping are disjoint.\"},"
                        + "\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());

        assertTrue(paid.contains("disjoint"), paid);
        assertEquals(paid, read(new File(results, "previous-1/replies.jsonl")));
        assertEquals(paidResults, read(new File(results, "previous-1/results.csv")));
        assertTrue(new File(results, "previous-1/summary.md").isFile());
        assertEquals(failed, read(new File(results, "previous-2/replies.jsonl")));
        assertTrue(read(new File(results, "replies.jsonl")).contains("disjoint"));
        assertTrue(err.toString().contains("previous-2"), err.toString());
    }

    /** Review 1.143.0 M-8: a killed run's last line, cut short, does not stop score. */
    @Test
    void scoreSkipsALineCutShortAndSaysSo() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());
        String whole = read(new File(results, "replies.jsonl"));
        String firstLine = whole.substring(0, whole.indexOf('\n') + 1);
        File cut = new File(dir, "cut.jsonl");
        write(cut, firstLine + firstLine.substring(0, firstLine.length() / 2));
        File rescored = new File(dir, "rescored");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", cut.getPath(), "--out", rescored.getPath()),
                err.toString());

        assertEquals(2, java.nio.file.Files.readAllLines(new File(rescored, "results.csv")
                .toPath()).size(), "the header and the one whole reply");
        assertTrue(err.toString().contains("line 2"), err.toString());
    }

    /** Review 1.143.0 M-8: scoring into a run's folder keeps that run's results. */
    @Test
    void scoringIntoARunsFolderKeepsItsResults() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());
        String original = read(new File(results, "results.csv"));

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", new File(results, "replies.jsonl").getPath(),
                "--out", results.getPath()), err.toString());

        assertEquals(original, read(new File(results, "previous-1/results.csv")));
        assertTrue(new File(results, "replies.jsonl").isFile(), "score never moves the replies");
    }

    /** Review 1.143.0 M-4: a models file with a password in an address is refused. */
    @Test
    void aModelsFileWithAPasswordInAnAddressIsRefused() throws IOException {
        File cases = generate("cases.jsonl");
        File models = new File(dir, "models-pw.json");
        write(models, "[{\"name\":\"proxy\",\"kind\":\"ollama\",\"baseUrl\":"
                + "\"http://ontouser:s3cretPassw0rd@127.0.0.1:1\",\"model\":\"m\"}]");

        int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                models.getPath(), "--cases", cases.getPath(), "--out",
                new File(dir, "results").getPath(), "--repeats", "1");

        assertEquals(1, status, err.toString());
        assertTrue(err.toString().contains("name and password"), err.toString());
        assertFalse(err.toString().contains("s3cretPassw0rd"), err.toString());
        assertFalse(new File(dir, "results").exists(), "nothing was run");
    }

    /**
     * Review 1.144.0 bench-6: a key pasted into the models file - a habit from other tools - was
     * accepted without a word, in a file the README put inside the clone, where git add -A
     * commits it. Such a file is refused, and the key is not repeated.
     */
    @Test
    void aModelsFileHoldingAKeyIsRefused() throws IOException {
        File cases = generate("cases.jsonl");
        String key = "Mx7pLq" + "2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0";
        String start = "[{\"name\":\"cloud\",\"kind\":\"anthropic\",\"model\":\"m\",";
        String[] files = {
            start + "\"keyVariable\":\"ANTHROPIC_API_KEY\",\"apiKey\":\"" + key + "\"}]",
            start + "\"keyVariable\":\"sk-ant-api03-" + key + "\"}]",
            start + "\"keyVariable\":\"" + key.substring(0, 10) + "-" + key + "\"}]",
            start + "\"baseUrl\":\"https://api.example.org/v1?key=" + key + "\"}]",
        };
        for (String json : files) {
            err.reset();
            File models = new File(dir, "models-key.json");
            write(models, json);

            int status = bench(Collections.singletonMap("ANTHROPIC_API_KEY", "x"), "run",
                    "--models", models.getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "results").getPath(), "--repeats", "1");

            assertEquals(1, status, json + "\n" + err);
            assertTrue(err.toString().contains("holds a key"), err.toString());
            assertFalse(err.toString().contains(key.substring(0, 12)), err.toString());
            assertFalse(new File(dir, "results").exists(), "nothing was run: " + json);
        }
    }

    /**
     * Review 1.145.0 keys-02: a models file with a JSON syntax error was printed whole, the key in
     * it too, in the parser's message - before keyIn could refuse it. A trailing comma, a missing
     * one and a key without quotes, all made up.
     */
    @Test
    void aModelsFileThatIsNotJsonIsNotPrinted() throws IOException {
        File cases = generate("cases.jsonl");
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String start = "[ {\n  \"name\": \"mistral\",\n  \"kind\": \"openai-compatible\",\n"
                + "  \"baseUrl\": \"https://api.mistral.ai/v1\",\n";
        String[] files = {
            start + "  \"apiKey\": \"" + key + "\",\n} ]",
            start + "  \"model\": \"mistral-small-latest\"\n  \"apiKey\": \"" + key + "\"\n} ]",
            start + "  \"apiKey\": " + key + "\n} ]",
        };
        for (String json : files) {
            err.reset();
            File models = new File(dir, "models-broken.json");
            write(models, json);

            int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                    models.getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "results").getPath(), "--repeats", "1");

            assertEquals(1, status, err.toString());
            assertFalse(err.toString().contains(key.substring(0, 12)), err.toString());
            assertTrue(err.toString().contains("not valid JSON"), err.toString());
            assertTrue(err.toString().contains("line "), err.toString());
            assertFalse(new File(dir, "results").exists(), "nothing was run: " + json);
        }
    }

    /**
     * Review 1.145.0 keys-03 and bench-A: a key with no known shape - Mistral's, Cohere's, an
     * Azure hex key - pasted where the variable's name belongs passed keyIn, and the "skipped"
     * line printed it in full. All made up.
     */
    @Test
    void aKeyInPlaceOfTheVariablesNameIsRefusedAndNotPrinted() throws IOException {
        File cases = generate("cases.jsonl");
        String[] keys = {
            "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u",
            "e4b9c2" + "f7a1d83e6b0c5f9a2d7e1b4c8f",
            "Mx7pLq" + "2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0",
            "Hk3Lm9Np2Qr8St4Uv7Wx1Yz5Ab6Cd0Ef3Gh7Ij2Kl",
            // A UUID, as Scaleway's secret keys are, and a base64url token split by - and _
            // (1.148.0, review 1.147.0 keys-02).
            "d3f1a7b2-5c84-4e19-9b6a-27c0e8f4a1d5",
            "Tq8vHz2-Rk5nW9xLp3Vb_7Cm1Jd4Fs6Gy0AeKu2N",
        };
        for (String key : keys) {
            err.reset();
            File models = new File(dir, "models-variable.json");
            write(models, "[{\"name\":\"mistral\",\"kind\":\"openai-compatible\",\"baseUrl\":"
                    + "\"https://api.mistral.ai/v1\",\"model\":\"mistral-small-latest\","
                    + "\"keyVariable\":\"" + key + "\"}]");

            int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                    models.getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "results").getPath(), "--repeats", "1");

            assertEquals(1, status, err.toString());
            assertTrue(err.toString().contains("holds a key"), err.toString());
            assertFalse(err.toString().contains(key.substring(0, 12)), err.toString());
            assertFalse(new File(dir, "results").exists(), "nothing was run: " + key);
        }
    }

    /**
     * The checker's guard for bench-A, and review 1.145.0 keys-03: a variable's name - lower
     * case, or with "-" as Windows allows - is not refused; and the "skipped" line shows $NAME
     * only for an upper-case one, so a key the rule misses is never printed.
     */
    @Test
    void aVariablesNameIsSkippedNotRefused() throws IOException {
        File cases = generate("cases.jsonl");
        for (String variable : new String[] {"my_mistral_key", "MY-LLM-KEY"}) {
            err.reset();

            int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                    models(variable).getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r").getPath());

            assertEquals(1, status, err.toString());
            assertFalse(err.toString().contains("holds a key"), err.toString());
            assertTrue(err.toString().contains(
                    "skipped fake: the variable its keyVariable names is not set."),
                    err.toString());
            assertFalse(err.toString().contains(variable), err.toString());
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.145.0 keys-06 and bench-D: a key under another tool's field name - apiToken,
     * mistralApiKey, a nested Authorization header - was accepted without a word.
     */
    @Test
    void aKeyUnderAnyKeyishNameAtAnyDepthIsFound() {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\",";
        String[] held = {
            start + "\"api_token\":\"" + key + "\"}]",
            start + "\"apiToken\":\"" + key + "\"}]",
            start + "\"mistralApiKey\":\"" + key + "\"}]",
            start + "\"MISTRAL_API_KEY\":\"" + key + "\"}]",
            start + "\"headers\":{\"Authorization\":\"Bearer " + key + "\"}}]",
            start + "\"extra\":[{\"clientCredential\":\"" + key + "\"}]}]",
            start + "\"x-passwd\":\"" + key + "\"}]",
        };
        for (String json : held) {
            String where = AssistantBench.keyIn(json);

            assertNotNull(where, json);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }
        assertNull(AssistantBench.keyIn(start + "\"keyVariable\":\"MISTRAL_API_KEY\","
                + "\"maxOutputTokens\":2048,\"_note\":\"set the variable\"}]"));
    }

    /**
     * Deferred since 1.146.0: a field whose name only holds a key word inside another word -
     * "author", "max_tokens", "tokenizer" - or that holds a number or a yes or no, was refused
     * as holding a key, with the advice to take it out of git. Unknown fields are otherwise
     * ignored. Keys under the names other tools use are still found. All made up.
     */
    @Test
    void aFieldWhoseNameOnlyContainsAKeyWordHoldsNoKey() {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        for (String field : new String[] {"\"author\":\"Ada Lovelace\"",
            "\"authors\":[\"Ada Lovelace\",\"Alan Turing\"]", "\"authorship\":\"FIZ\"",
            "\"max_tokens\":1024", "\"maxTokens\":1024", "\"tokenizer\":\"cl100k_base\"",
            "\"keywords\":[\"ontology\",\"bfo\"]", "\"token_limit\":4096",
            "\"useAuth\":false", "\"apiKey\":\"\"", "\"secret\":null"}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        for (String field : new String[] {"apikey", "mistralapikey", "Passwort", "authToken",
            "authentication", "oauth", "access-token", "secretKeys"}) {
            String where = AssistantBench.keyIn(start + "\"" + field + "\":\"" + key + "\"}]");

            assertNotNull(where, field);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }
    }

    /**
     * Review 1.149.0 keys-02: the narrower field rule of 1.149.0 took a plural only of key,
     * secret and credential, and no word that begins with a key word, so an unshaped key under
     * "tokens", "apiTokens" or "keyvalue" was no longer reported; and the tree keeps the last of
     * two fields with one name, so a key followed by an empty "apiKey" went unseen. 1.148.0
     * reported each. The refusal of a field given twice says so, without the value. Made up.
     */
    @Test
    void aKeyUnderAPluralOrAJoinedKeyWordOrInAFieldGivenTwiceIsFound() throws IOException {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        String twice = start + "\"apiKey\":\"" + key + "\",\"apiKey\":\"\"}]";
        String[] held = {
            start + "\"tokens\":{\"mistral\":\"" + key + "\"}}]",
            start + "\"apiTokens\":\"" + key + "\"}]",
            start + "\"accessTokens\":[\"" + key + "\"]}]",
            start + "\"passwords\":[\"" + key + "\"]}]",
            start + "\"headers\":{\"X-Tokens\":\"" + key + "\"}}]",
            start + "\"keyvalue\":\"" + key + "\"}]",
            start + "\"secretvalue\":\"" + key + "\"}]",
            twice,
            start + "\"db_passwds\":[\"" + key + "\"]}]",
            start + "\"pwds\":[\"" + key + "\"]}]",
            start + "\"tokenvalue\":\"" + key + "\"}]",
            start + "\"passwordhash\":\"" + key + "\"}]",
        };
        for (String json : held) {
            String where = AssistantBench.keyIn(json);

            assertNotNull(where, json);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }

        File cases = generate("cases.jsonl");
        File models = new File(dir, "models-twice.json");
        write(models, twice);

        int status = bench(Collections.singletonMap("MISTRAL_API_KEY", "x"), "run", "--models",
                models.getPath(), "--cases", cases.getPath(), "--out",
                new File(dir, "r").getPath(), "--repeats", "1");

        assertEquals(1, status, err.toString());
        assertTrue(err.toString().contains("\"apiKey\" is given twice in entry 1"),
                err.toString());
        assertFalse(err.toString().contains(key.substring(0, 12)), err.toString());
        assertFalse(new File(dir, "r").exists(), "nothing was run");
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.149.0 keys-03: a variable's reference - ${NAME}, LiteLLM's os.environ/NAME - or a
     * model's special token under a key's name holds no key, yet the file was refused as holding
     * one, with the advice to take it out of git. A value counts as a possible key with 8 or more
     * characters, no space, and no reference; a reference is refused with the advice to name the
     * variable in keyVariable. A real key under each of those names is still found. Made up.
     */
    @Test
    void aReferenceOrASpecialTokenUnderAKeysNameHoldsNoKey() throws IOException {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        String[][] fields = {
            {"apiKey", "\"${MISTRAL_API_KEY}\"", "\"" + key + "\""},
            {"api_key", "\"os.environ/MISTRAL_API_KEY\"", "\"" + key + "\""},
            {"eos_token", "\"</s>\"", "\"" + key + "\""},
            {"stop_token", "\"<|end|>\"", "\"" + key + "\""},
            {"authentication", "{\"type\":\"bearer\"}", "{\"type\":\"" + key + "\"}"},
            {"Authorization", "\"Bearer ${MISTRAL_API_KEY}\"", "\"Bearer " + key + "\""},
        };
        for (String[] field : fields) {
            assertNull(AssistantBench.keyIn(start + "\"" + field[0] + "\":" + field[1] + "}]"),
                    field[0] + ": " + field[1]);

            String where = AssistantBench.keyIn(start + "\"" + field[0] + "\":" + field[2] + "}]");

            assertNotNull(where, field[0] + ": " + field[2]);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }

        File cases = generate("cases.jsonl");
        for (String reference : new String[] {"${MISTRAL_API_KEY}",
            "os.environ/MISTRAL_API_KEY"}) {
            err.reset();
            File models = new File(dir, "models-reference.json");
            write(models, start + "\"apiKey\":\"" + reference + "\"}]");

            int status = bench(Collections.singletonMap("MISTRAL_API_KEY", "x"), "run",
                    "--models", models.getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r").getPath(), "--repeats", "1");

            assertEquals(1, status, err.toString());
            assertFalse(err.toString().contains("holds a key"), err.toString());
            assertFalse(err.toString().contains("out of git"), err.toString());
            assertTrue(err.toString().contains(
                    "\"apiKey\" in entry 1 refers to the variable MISTRAL_API_KEY"),
                    err.toString());
            assertTrue(err.toString().contains("keyVariable"), err.toString());
            assertFalse(new File(dir, "r").exists(), "nothing was run: " + reference);
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * The review of the keys-03 fix: a key in upper case written inside a reference under a
     * key's name was printed whole as "the variable ...", where every other message hides a name
     * that looks like a key. Made up.
     */
    @Test
    void aReferenceToARandomNameUnderAKeysNameDoesNotShowIt() {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        for (String field : new String[] {"\"apiKey\":\"${QM7RKT" + "2VXP9LWN4SJD8HFB3CZY6GAE1U}\"",
            "\"api_key\":\"os.environ/QM7RKT" + "2VXP9LWN4SJD8HFB3CZY6GAE1U\""}) {
            String said = AssistantBench.referenceIn(start + field + "}]");

            assertNotNull(said, field);
            assertFalse(said.contains("QM7RKT2VXP9L"), said);
            assertTrue(said.contains("keyVariable"), said);
        }
        assertTrue(AssistantBench.referenceIn(start + "\"apiKey\":\"${MISTRAL_API_KEY}\"}]")
                .contains("the variable MISTRAL_API_KEY"));
    }

    /**
     * The review of the keys-03 fix: a text with a space in it was no key at all, so a key after
     * a scheme other than Bearer, Basic, Token or Api-Key - fal.ai's "Key", "OAuth", "Bot",
     * DeepL's, Okta's "SSWS" - or beside a comment or another key was not reported, where 1.149.0
     * reported it. A part that looks like a key is one. Made up.
     */
    @Test
    void aKeyAfterAnySchemeOrBesideOtherWordsIsFound() {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String other = "Zx4Pq8" + "Ws2Er6Ty1Ui9Op3As7Df5Gh0Jk";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        for (String field : new String[] {"\"headers\":{\"Authorization\":\"Key " + key + "\"}",
            "\"headers\":{\"Authorization\":\"OAuth " + key + "\"}",
            "\"headers\":{\"Authorization\":\"Bot " + key + "\"}",
            "\"headers\":{\"Authorization\":\"DeepL-Auth-Key " + key + "\"}",
            "\"headers\":{\"Authorization\":\"SSWS " + key + "\"}",
            "\"apiKey\":\"" + key + " # mistral\"", "\"apiKeys\":\"" + key + " " + other + "\"",
            "\"apiKeys\":\"" + key + "\\n" + other + "\""}) {
            String where = AssistantBench.keyIn(start + field + "}]");

            assertNotNull(where, field);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }
        assertNull(AssistantBench.keyIn(start + "\"apiKey\":\"set MISTRAL_API_KEY first\"}]"));
    }

    /**
     * The review of the keys-03 fix: the special tokens of today's models are longer than eight
     * characters, and were still refused as keys, with the advice to take them out of git.
     */
    @Test
    void aSpecialTokenOfAnyLengthIsNoKey() {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MISTRAL_API_KEY\",";
        for (String field : new String[] {"\"eos_token\":\"<|im_end|>\"",
            "\"eos_token\":\"<|eot_id|>\"", "\"eos_token\":\"<|endoftext|>\"",
            "\"eos_token\":\"<end_of_turn>\"",
            "\"stop_tokens\":[\"<|im_end|>\",\"<|endoftext|>\"]"}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }
        assertNotNull(AssistantBench.keyIn(start
                + "\"eos_token\":\"Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u\"}]"));
    }

    /**
     * Review 1.150.0 keys-01 (a 1.150.0 regression): files 1.149.0 refused ran without a word - a
     * key wrapped by a line break or broken by a space, a key left in &lt;...&gt;, a password
     * under 8 characters or with spaces, a passphrase, a key with a space whose parts do not look
     * random, a no-break or an ideographic space around a key. Each is reported, never its value;
     * a note, a placeholder and a model's special token under a key's name still are not. The
     * reviewer's cases, with the checker's; all made up.
     */
    @Test
    void aKeyBrokenOrInBracketsOrAPasswordOfAnyShapeIsFound() throws IOException {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String letters = "QwErTy" + "UiOpAsDfGhJkLzXcVbNmQwErTy";
        String endDigits = "QwErTy" + "UiOpAsDfGhJkLzXcVbNm012345";
        String start = "[{\"name\":\"fake\",\"kind\":\"ollama\",\"baseUrl\":\"" + server.url()
                + "\",\"model\":\"m\",\"keyVariable\":\"MISTRAL_API_KEY\",";
        // Each field, and a piece of its value that no message may hold.
        String[][] held = {
            {"\"apiKey\":\"" + key.substring(0, 16) + "\\n" + key.substring(16) + "\"",
                "Qm7RkT2vXp9L"},
            {"\"apiKey\":\"" + key.substring(0, 16) + " " + key.substring(16) + "\"",
                "Qm7RkT2vXp9L"},
            {"\"apiKey\":\"<" + key + ">\"", "Qm7RkT2vXp9L"},
            {"\"headers\":{\"Authorization\":\"Bearer <" + key + ">\"}", "Qm7RkT2vXp9L"},
            {"\"apiKey\":\"" + key.substring(0, 11) + " " + key.substring(11, 22) + " "
                + key.substring(22) + "\"", "Qm7RkT2vXp9"},
            {"\"password\":\"hunter2\"", "hunter2"},
            {"\"password\":\"Wint3r2\"", "Wint3r2"},
            {"\"password\":\"correct horse battery staple\"", "horse"},
            {"\"password\":\"Tr0ub4dor &3\"", "Tr0ub4dor"},
            {"\"secret\":\"my super secret phrase\"", "super secret"},
            {"\"passphrase\":\"" + key + "\"", "Qm7RkT2vXp9L"},
            {"\"password\":\"<Wint3r2026>\"", "Wint3r2026"},
            {"\"apiKey\":\"Key " + letters + "\"", "QwErTyUiOp"},
            {"\"apiKey\":\"" + endDigits + " # prod key\"", "QwErTyUiOp"},
            {"\"apiKey\":\"Key 7f3a9c2e1b4d8a6f\"", "7f3a9c2e"},
            {"\"apiKey\":\"" + endDigits + "\\u00a0\"", "QwErTyUiOp"},
            {"\"apiKey\":\"\\u3000" + letters + "\"", "QwErTyUiOp"},
            {"\"headers\":{\"Authorization\":\"Bearer " + letters + " (prod)\"}", "QwErTyUiOp"},
            {"\"apiKey\":\"Qw3rTy\\u00a0UiOpAsDfGhJkLzXcVbNm\"", "UiOpAsDfGh"},
            {"\"apiKeys\":\"" + letters + " " + endDigits + "\"", "QwErTyUiOp"},
        };
        for (String[] field : held) {
            String where = AssistantBench.keyIn(start + field[0] + "}]");

            assertNotNull(where, field[0]);
            assertFalse(where.contains(field[1]), where);
        }
        for (String field : new String[] {"\"apiKey\":\"set MISTRAL_API_KEY first\"",
            "\"apiKey\":\"<YOUR_API_KEY>\"", "\"apiKey\":\"<your-api-key>\"",
            "\"eos_token\":\"<|reserved_special_token_250|>\"",
            "\"stop_token\":\"<|im_start|> <|im_end|>\"",
            "\"apiKey\":\"rotated every 90 days by the ops team\"",
            "\"apiKey\":\"Use the key from 1Password vault Engineering2024\"",
            "\"apiKey\":\"see https://console.mistral.ai/api-keys for one\"",
            "\"apiKey\":\"Mixtral 8x22B Instruct v0.1 key\"",
            "\"headers\":{\"Authorization\":\"Bearer token for the gpt4o2024 gateway\"}",
            "\"apiKey\":\"set it in MyTeamGatewayApiKey2 first\"",
            "\"apiKey\":\"see the AzureOpenAIGateway2024 resource\"",
            "\"password\":\"\"", "\"password\":\"${DB_PASSWORD}\"",
            "\"password\":\"<YOUR_PASSWORD>\""}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }

        File cases = generate("cases.jsonl");
        for (String[] field : new String[][] {held[0], held[5], held[7], held[14]}) {
            err.reset();
            File models = new File(dir, "models-held.json");
            write(models, start + field[0] + "}]");

            int status = bench(Collections.singletonMap("MISTRAL_API_KEY", "x"), "run",
                    "--models", models.getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r").getPath(), "--repeats", "1");

            assertEquals(1, status, field[0] + "\n" + err);
            assertTrue(err.toString().contains("holds a key"), err.toString());
            assertFalse(err.toString().contains(field[1]), err.toString());
            assertFalse(new File(dir, "r").exists(), "nothing was run: " + field[0]);
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.150.0 keys-02: the plural and joined key words of 1.150.0 refused files that hold
     * no key - another tool's stop strings, rate limit, tokenizer, keystore or secretary, and
     * keyVariable misspelt - with the advice to take the key out of git. A name with a word such
     * as stop, eos, max, per or type holds a key only when its text looks like one. Ruled:
     * "passwords" is a password's name, and "not-needed" is as good a password as any, so it is
     * reported. Made up.
     */
    @Test
    void aSettingOrAWordThatOnlyBeginsWithAKeyWordHoldsNoKey() {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MY_LLM_KEY\",";
        for (String field : new String[] {"\"stopTokens\":[\"Observation:\"]",
            "\"stop_tokens\":[\"\\n\\nObservation:\",\"</s>\"]",
            "\"tokensPerMinute\":\"unlimited\"", "\"tokenization\":\"sentencepiece\"",
            "\"tokeniser\":\"bert-base-uncased\"",
            "\"keystore\":\"certs/client.jks\"", "\"secretary\":\"alice.example\"",
            "\"keyvariable\":\"MY_LLM_KEY\"", "\"KeyVariable\":\"MY_LLM_KEY\"",
            "\"key_variable\":\"MY_LLM_KEY\"", "\"maxtokens\":\"unlimited\"",
            "\"tokenType\":\"Bearer\"", "\"authType\":\"api-key-header\"",
            "\"auth_type\":\"azure_ad\"", "\"credentials\":{\"type\":\"service_account\"}",
            "\"special_tokens\":{\"eos\":\"<|endoftext|>\","
                + "\"pad\":\"<|finetune_right_pad_id|>\"}"}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }
        assertNotNull(AssistantBench.keyIn(start + "\"passwords\":\"not-needed\"}]"));
        for (String field : new String[] {"\"stopTokens\":[\"" + key + "\"]",
            "\"tokensPerMinute\":\"" + key + "\"", "\"keyvariable\":\"" + key + "\"",
            "\"KeyVariable\":\"" + key + "\"", "\"eos_token\":\"" + key + "\"",
            "\"special_tokens\":{\"eos\":\"" + key + "\"}",
            "\"credentials\":{\"type\":\"bearer\",\"value\":\"hunter2\"}"}) {
            String where = AssistantBench.keyIn(start + field + "}]");

            assertNotNull(where, field);
            assertFalse(where.contains(key.substring(0, 12)), where);
            assertFalse(where.contains("hunter2"), where);
        }
    }

    /**
     * Review of the 1.151.0 keys fix: under a password's name a text in brackets with no digit
     * was taken for a placeholder however much it looked like a key, so a secret of letters left
     * in them was accepted without a word - as it is now reported under "apiKey". Made up.
     */
    @Test
    void aKeyLeftInBracketsUnderAPasswordsNameIsFound() {
        String letters = "QwErTy" + "UiOpAsDfGhJkLzXcVbNmQwErTy";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MY_LLM_KEY\",";
        for (String field : new String[] {"\"secret\":\"<" + letters + ">\"",
            "\"client_secret\":\"<" + letters.substring(0, 26) + ">\"",
            "\"password\":\"<" + letters + ">\""}) {
            String where = AssistantBench.keyIn(start + field + "}]");

            assertNotNull(where, field);
            assertFalse(where.contains("QwErTyUiOp"), where);
        }
        for (String field : new String[] {"\"password\":\"<YOUR_PASSWORD>\"",
            "\"secret\":\"<your-secret>\""}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }
    }

    /**
     * Review of the 1.151.0 keys fix: any text counted under every name with a password's word
     * in it, so names about a password rather than of one - another tool's "secretName",
     * "passwordPolicy", LiteLLM's "litellm_credential_name" - refused files that hold none, with
     * the advice to take the key out of git; so did a key's URL, header or variable named under
     * a key's word, and a yes or no written as text. A word such as name, source, policy, url or
     * header after the key's or password's word makes the name a setting's, which holds one only
     * in a text that looks like a key; a number or a word that only says which key - "_2",
     * "Prod" - does not. Made up.
     */
    @Test
    void aNameAboutAKeyRatherThanOfOneHoldsOneOnlyWhenItsTextLooksLikeOne() {
        String key = "Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u";
        String letters = "QwErTy" + "UiOpAsDfGhJkLzXcVbNmQwErTy";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MY_LLM_KEY\",";
        for (String field : new String[] {"\"secretName\":\"llm\"",
            "\"secretName\":\"my-azure-secret-name\"", "\"credentialSource\":\"env\"",
            "\"passwordPolicy\":\"none\"", "\"usePassword\":\"false\"",
            "\"passphraseRequired\":\"no\"", "\"litellm_credential_name\":\"azure\"",
            "\"litellm_credential_name\":\"azure-openai-credentials\"",
            "\"secretNameProd\":\"prod-secret-name\"",
            "\"tokenUrl\":\"https://login.example.com/oauth2/v2.0/token\"",
            "\"tokenEndpoint\":\"https://auth.example.com/oauth/token\"",
            "\"apiKeyName\":\"team-gateway-key\"", "\"apiKeyEnv\":\"MISTRAL_API_KEY\"",
            "\"apiKeyHeader\":\"X-API-Key\"", "\"auth\":\"disabled\"",
            "\"apiKey\":\"required\"", "\"password\":\"false\""}) {
            assertNull(AssistantBench.keyIn(start + field + "}]"), field);
        }
        for (String[] field : new String[][] {{"\"secretName\":\"" + key + "\"", "Qm7RkT2vXp9L"},
            {"\"litellm_credential_name\":\"" + key + "\"", "Qm7RkT2vXp9L"},
            {"\"apiKeyName\":\"" + letters + "\"", "QwErTyUiOp"},
            {"\"usePassword\":\"hunter2\"", "hunter2"},
            {"\"passwordHash\":\"abc123\"", "abc123"},
            {"\"secret_key\":\"short1\"", "short1"},
            {"\"clientSecret\":\"pw-1\"", "pw-1"},
            {"\"api_key_2\":\"hunter2hunter2\"", "hunter2"},
            {"\"apiKeyProd\":\"hunter2hunter2\"", "hunter2"}}) {
            String where = AssistantBench.keyIn(start + field[0] + "}]");

            assertNotNull(where, field[0]);
            assertFalse(where.contains(field[1]), where);
        }
    }

    /**
     * Review of the 1.151.0 keys fix: a field given twice is named only when no key can be in its
     * name, and that took looksRandom alone, so a key of letters pasted twice as a field's name
     * was printed. Made up.
     */
    @Test
    void aKeyGivenTwiceAsAFieldsNameIsNeverShown() {
        String letters = "QwErTy" + "UiOpAsDfGhJkLzXcVbNmQwErTy";
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"model\":\"x\","
                + "\"keyVariable\":\"MY_LLM_KEY\",";

        String twice = AssistantBench.twiceIn(start + "\"" + letters + "\":\"a\",\"" + letters
                + "\":\"b\"}]");

        assertEquals("a field is given twice in entry 1", twice);
        assertEquals("\"apiKey\" is given twice in entry 1", AssistantBench.twiceIn(start
                + "\"apiKey\":\"a\",\"apiKey\":\"b\"}]"));
    }

    /**
     * Review 1.150.0 keys-03: a reference under a key's name printed the name inside it, so a key
     * in upper case shorter than 20 characters, or with no digit followed by a letter, reached
     * the error stream. The variable is named only when it is a preset's, the entry's own
     * keyVariable, or set in the environment. Made up.
     */
    @Test
    void aReferenceUnderAKeysNameNamesOnlyAKnownVariable() throws IOException {
        String start = "[{\"name\":\"fake\",\"kind\":\"ollama\",\"baseUrl\":\"" + server.url()
                + "\",\"model\":\"m\",\"keyVariable\":\"MY_LLM_KEY\",";
        String[] keys = {"AB12CD34EF56GH78", "QWERTY" + "UIOPASDFGHJKLZXCVBNM012345"};
        for (String name : keys) {
            String said = AssistantBench.referenceIn(start + "\"apiKey\":\"${" + name + "}\"}]");

            assertNotNull(said, name);
            assertFalse(said.contains(name.substring(0, 10)), said);
            assertTrue(said.contains("refers to a variable"), said);
        }
        assertTrue(AssistantBench.referenceIn(start + "\"apiKey\":\"${MISTRAL_API_KEY}\"}]")
                .contains("refers to the variable MISTRAL_API_KEY"), "a preset's variable");
        assertTrue(AssistantBench.referenceIn(start + "\"apiKey\":\"${MY_LLM_KEY}\"}]")
                .contains("refers to the variable MY_LLM_KEY"), "the entry's own keyVariable");
        assertTrue(AssistantBench.referenceIn(start + "\"apiKey\":\"${TEAM_LLM_KEY}\"}]")
                .contains("refers to a variable,"), "a name nothing says is a variable's");

        File cases = generate("cases.jsonl");
        Map<String, String> environment = new java.util.HashMap<String, String>();
        environment.put("MY_LLM_KEY", "x");
        environment.put("TEAM_LLM_KEY", "x");
        String[][] runs = {{keys[0], "refers to a variable,"}, {keys[1], "refers to a variable,"},
            {"TEAM_LLM_KEY", "refers to the variable TEAM_LLM_KEY"},
            {"MISTRAL_API_KEY", "refers to the variable MISTRAL_API_KEY"}};
        for (String[] run : runs) {
            err.reset();
            File models = new File(dir, "models-reference.json");
            write(models, start + "\"apiKey\":\"${" + run[0] + "}\"}]");

            int status = bench(environment, "run", "--models", models.getPath(), "--cases",
                    cases.getPath(), "--out", new File(dir, "r").getPath(), "--repeats", "1");

            assertEquals(1, status, err.toString());
            assertTrue(err.toString().contains(run[1]), err.toString());
            assertFalse(err.toString().contains("holds a key"), err.toString());
            if (run[1].endsWith(",")) {
                assertFalse(err.toString().contains(run[0].substring(0, 10)), err.toString());
            }
            assertFalse(new File(dir, "r").exists(), "nothing was run: " + run[0]);
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.147.0 keys-04, deferred in 1.148.0: a key written as the default in
     * ${NAME:-...} was refused only for the way the name is written, so the person fixed that
     * and never heard that the file - perhaps committed - held a key. Made up.
     */
    @Test
    void aKeyAsTheDefaultOfAVariableIsAKey() throws IOException {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"baseUrl\":"
                + "\"https://llm.example.org/v1\",\"model\":\"x\",\"keyVariable\":\"";
        for (String key : new String[] {"Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u",
            "d3f1a7b2-5c84-4e19-9b6a-27c0e8f4a1d5"}) {
            String where = AssistantBench.keyIn(start + "${MISTRAL_API_KEY:-" + key + "}\"}]");

            assertNotNull(where, key);
            assertTrue(where.contains("default"), where);
            assertFalse(where.contains(key.substring(0, 12)), where);
        }
        for (String placeholder : new String[] {"", "none", "changeme", "your-key-here"}) {
            assertNull(AssistantBench.keyIn(start + "${MISTRAL_API_KEY:-" + placeholder
                    + "}\"}]"), placeholder);
        }

        File cases = generate("cases.jsonl");
        int status = bench(Collections.singletonMap("OPENAI_API_KEY", "x"), "run", "--models",
                models("${OPENAI_API_KEY:-Qm7RkT2vXp9LwN4sJd8HfB3cZy6GaE1u}").getPath(),
                "--cases", cases.getPath(), "--out", new File(dir, "r").getPath());

        assertEquals(1, status, err.toString());
        assertTrue(err.toString().contains("holds a key"), err.toString());
        assertTrue(err.toString().contains("out of git"), err.toString());
        assertFalse(err.toString().contains("Qm7RkT2vXp9L"), err.toString());
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.145.0 bench-D: the address rule ran over the whole one-line file, from a bare-host
     * baseUrl to the "@" of a model id, and refused a file that holds no key. It runs on each
     * address.
     */
    @Test
    void aModelIdWithAnAtAfterABareHostAddressIsNoKey() {
        assertNull(AssistantBench.keyIn("[{\"name\":\"a\",\"kind\":\"anthropic\",\"baseUrl\":"
                + "\"https://api.anthropic.com\",\"model\":\"claude-sonnet-4@20250514\","
                + "\"keyVariable\":\"ANTHROPIC_API_KEY\"}]"));
        assertNotNull(AssistantBench.keyIn("[{\"name\":\"a\",\"kind\":\"ollama\",\"baseUrl\":"
                + "\"http://u:Hunter2Hunter2@127.0.0.1:1\",\"model\":\"m\"}]"));
    }

    /** Review 1.146.0 keys-03: a GitLab token with no digit in its body passed the check. */
    @Test
    void aGitLabTokenWithNoDigitIsFound() {
        assertNotNull(AssistantBench.keyIn("[{\"_note\":\"token glpat-" + "abcdefghijKLMNOPqrst\","
                + "\"name\":\"m\",\"kind\":\"ollama\"}]"));
    }

    /**
     * Review 1.146.0 keys-05 (a 1.146.0 regression): files that hold no key were refused as
     * holding one, with the advice to take it out of git - for a name in lower case with a
     * number, a CamelCase name, and a variable written as $NAME, ${NAME}, %NAME% or env:NAME.
     * Keys in its place are still found, whatever is around them. All made up.
     */
    @Test
    void aVariablesNameWithANumberOrInCamelCaseHoldsNoKey() {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"baseUrl\":"
                + "\"https://llm.example.org/v1\",\"model\":\"x\",\"keyVariable\":\"";
        for (String variable : new String[] {"mistral_api_key_2024", "azure_openai_api_key_2",
            "MyTeamGatewayApiKey2", "OPENROUTER_API_KEY_2025", "$OPENAI_API_KEY",
            "${OPENAI_API_KEY}", "%OPENAI_API_KEY%", "env:OPENAI_API_KEY",
            "$env:OPENAI_API_KEY", "${env:OPENAI_API_KEY}", "$(OPENAI_API_KEY)",
            "{{OPENAI_API_KEY}}", "${OPENAI_API_KEY:-}"}) {
            assertNull(AssistantBench.keyIn(start + variable + "\"}]"), variable);
        }
        for (String key : new String[] {"Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u",
            "${Qm7RkT" + "2vXp9LwN4sJd8HfB3cZy6GaE1u}", "e4b9c2" + "f7a1d83e6b0c5f9a2d7e1b4c8f",
            "%e4b9c2" + "f7a1d83e6b0c5f9a2d7e1b4c8f%", "r8_Hk3Lm9Np2Qr8St4Uv7Wx1Yz5Ab6Cd0Ef3Gh7"}) {
            assertNotNull(AssistantBench.keyIn(start + key + "\"}]"), key);
        }
    }

    /**
     * Review 1.147.0 keys-02 (a 1.147.0 regression): the models check took a UUID that begins
     * with a letter - Scaleway's secret keys are UUIDs - and a base64url token split by - or _
     * for a variable's name, since no run between the separators was 20 long. The file passed
     * with no word about a key. The names of 1.147.0's tests and the reviewer's stay names. All
     * made up.
     */
    @Test
    void aUuidOrASeparatedTokenInPlaceOfTheVariablesNameIsAKey() {
        String start = "[{\"name\":\"m\",\"kind\":\"openai-compatible\",\"baseUrl\":"
                + "\"https://llm.example.org/v1\",\"model\":\"x\",\"keyVariable\":\"";
        for (String key : new String[] {"d3f1a7b2-5c84-4e19-9b6a-27c0e8f4a1d5",
            "c9bf9e57-1685-4c89-bafb-ff5af830be8a", "D3F1A7B2-5C84-4E19-9B6A-27C0E8F4A1D5",
            "$d3f1a7b2-5c84-4e19-9b6a-27c0e8f4a1d5", "${c9bf9e57-1685-4c89-bafb-ff5af830be8a}",
            "Xq3-vB8_kL2mN9pQ4rS7t-U1wY6zA0cD5eF8gH2j",
            "Tq8vHz2-Rk5nW9xLp3Vb_7Cm1Jd4Fs6Gy0AeKu2N"}) {
            String where = AssistantBench.keyIn(start + key + "\"}]");

            assertNotNull(where, key);
            assertTrue(where.contains("looks like a key"), where);
        }
        for (String variable : new String[] {"mistral_api_key_2024", "azure_openai_api_key_2",
            "MyTeamGatewayApiKey2", "OPENROUTER_API_KEY_2025", "MIXTRAL_8X7B_INSTRUCT_KEY",
            "LLAMA3B_70B_INSTRUCT_KEY", "PHI3_5_MINI_4K_INSTRUCT_KEY",
            "GPT4O_MINI_2024_07_18_KEY", "QWEN2_5_72B_INSTRUCT_KEY",
            "LLAMA_3_1_8B_INSTRUCT_FP8_KEY", "SCW_SECRET_KEY", "$MIXTRAL_8X7B_INSTRUCT_KEY",
            "${GPT4O_MINI_2024_07_18_KEY}", "%LLAMA3B_70B_INSTRUCT_KEY%"}) {
            assertNull(AssistantBench.keyIn(start + variable + "\"}]"), variable);
        }
    }

    /** keys-05: a variable written as $NAME is refused for what it is, and not as a key. */
    @Test
    void aVariableWrittenWithADollarIsRefusedButNotAsAKey() throws IOException {
        File cases = generate("cases.jsonl");
        for (String variable : new String[] {"$OPENAI_API_KEY", "${OPENAI_API_KEY}",
            "%OPENAI_API_KEY%", "env:OPENAI_API_KEY", "$env:OPENAI_API_KEY",
            "${env:OPENAI_API_KEY}", "$(OPENAI_API_KEY)", "{{OPENAI_API_KEY}}",
            "${OPENAI_API_KEY:-}"}) {
            err.reset();

            int status = bench(Collections.singletonMap("OPENAI_API_KEY", "x"), "run",
                    "--models", models(variable).getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r").getPath());

            assertEquals(1, status, err.toString());
            assertFalse(err.toString().contains("holds a key"), err.toString());
            assertFalse(err.toString().contains("out of git"), err.toString());
            assertTrue(err.toString().contains("keyVariable of entry 1 is written"),
                    err.toString());
            assertTrue(err.toString().contains("bare name, OPENAI_API_KEY"), err.toString());
            assertFalse(new File(dir, "r").exists(), "nothing was run: " + variable);
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.145.0 keys-04: the pass over the encoded replies.jsonl line ran the address rule,
     * which took everything from a bare-host URL at the end of "sent" to the "@" of a reply that
     * begins "@prefix" for a user name: the line kept "sent" alone, and the reply was gone.
     */
    @Test
    void aReplyStartingWithAtPrefixAfterABareHostUrlIsKeptWhole() throws IOException {
        String turtle = "@prefix ex: <http://example.org/> .\nex:A a owl:Class .";
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"@prefix ex: <http://example.org/> .\\nex:A a"
                        + " owl:Class .\"},\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        File cases = generate("cases.jsonl");
        StringBuilder edited = new StringBuilder();
        for (String line : Files.readAllLines(cases.toPath(), StandardCharsets.UTF_8)) {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) BenchCases.JSON.readTree(line);
            node.put("message", node.path("message").asText() + " See http://example.org");
            edited.append(BenchCases.JSON.writeValueAsString(node)).append('\n');
        }
        write(cases, edited.toString());
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());

        List<String> lines = Files.readAllLines(new File(results, "replies.jsonl").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), lines.toString());
        for (String line : lines) {
            com.fasterxml.jackson.databind.JsonNode node = BenchCases.JSON.readTree(line);
            assertTrue(node.path("sent").asText().endsWith("See http://example.org\n"), line);
            assertEquals(turtle, node.path("reply").asText(null), line);
        }
    }

    /** Review 1.144.0 bench-6: the README's models file and results stay out of git. */
    @Test
    void theReadmesModelsFileAndResultsAreIgnoredByGit() throws IOException {
        List<String> rules = Files.readAllLines(new File("../.gitignore").toPath(),
                StandardCharsets.UTF_8);

        assertTrue(rules.contains("/my-models.json"), rules.toString());
        assertTrue(rules.contains("/results/"), rules.toString());
    }

    /**
     * Review 1.144.0 privacy-03: replies.jsonl was redacted after JSON encoding, where a line
     * break inside a key is the two characters \\n, so both halves of the key were written.
     */
    @Test
    void aKnownKeySplitByALineBreakIsNotWrittenToReplies() throws IOException {
        String key = "Zq7vR2" + "mX9pLa4wT8nB3kY6hJ1cD5fG0s";
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"Your key Zq7vR2mX9pLa4wT8\\nnB3kY6hJ1cD5fG0s is wrong.\"},"
                        + "\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.singletonMap("BENCH_TEST_KEY", key), "run", "--models",
                models("BENCH_TEST_KEY").getPath(), "--cases", cases.getPath(), "--out",
                results.getPath(), "--repeats", "1"), err.toString());

        String replies = read(new File(results, "replies.jsonl"));
        assertFalse(replies.contains("Zq7vR2mX9pLa4wT8"), replies);
        assertFalse(replies.contains("nB3kY6hJ1cD5fG0s"), replies);
    }

    /** No model can run (its key variable is unset): the folder is left as it was. */
    @Test
    void aRunThatCanRunNoModelTouchesNothing() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");
        results.mkdirs();
        write(new File(results, "results.csv"), "old results\n");

        bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("NO_SUCH_KEY_VARIABLE_FOR_THIS_TEST").getPath(), "--cases",
                cases.getPath(), "--out", results.getPath(), "--repeats", "1");

        assertEquals("old results\n", read(new File(results, "results.csv")));
        assertFalse(new File(results, "previous-1").exists());
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void repairRunsAsALoopAndReplaysFromTheRecord() throws IOException {
        File cases = new File(dir, "repair.jsonl");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                ontology.getPath(), "--out", cases.getPath(), "--seed", "3", "--explain", "0",
                "--context", "0", "--controls", "0", "--definition", "0", "--repair", "1",
                "--text-to-axiom", "0"),
                err.toString());
        File results = new File(dir, "results");

        int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1");

        assertEquals(0, status, err.toString());
        List<String> csv = Files.readAllLines(new File(results, "results.csv").toPath());
        assertEquals(2, csv.size(), csv.toString());
        java.util.List<String> header = Arrays.asList(csv.get(0).split(","));
        String[] row = csv.get(1).split(",");
        assertEquals("repair", row[header.indexOf("task")]);
        assertEquals("0", row[header.indexOf("solved")]);
        assertEquals("3", row[header.indexOf("rounds")]);
        String replies = new String(Files.readAllBytes(new File(results, "replies.jsonl")
                .toPath()), StandardCharsets.UTF_8);
        assertEquals(3, BenchCases.JSON.readTree(replies.trim()).path("rounds").size());

        File rescored = new File(dir, "rescored");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", new File(results, "replies.jsonl").getPath(),
                "--out", rescored.getPath()), err.toString());
        assertEquals(csv, Files.readAllLines(new File(rescored, "results.csv").toPath()));
    }

    @Test
    void definitionCasesAreGeneratedAndScored() throws IOException {
        File cases = new File(dir, "definition.jsonl");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                ontology.getPath(), "--out", cases.getPath(), "--seed", "3", "--explain", "0",
                "--context", "0", "--controls", "0", "--definition", "1", "--repair", "0",
                "--text-to-axiom", "0"),
                err.toString());
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());

        List<String> csv = Files.readAllLines(new File(results, "results.csv").toPath());
        java.util.List<String> header = Arrays.asList(csv.get(0).split(","));
        String[] row = csv.get(1).split(",");
        assertEquals("definition", row[header.indexOf("task")]);
        assertEquals("0", row[header.indexOf("validProposal")], "prose is not a proposal");
    }

    /**
     * Text to axiom (1.140.0): the model writes Margherita's held-back axiom for both cases. The
     * reasoner passes it where it was held back and not for vegetarian pizza's "only", and
     * "score" replays the same verdicts without the server.
     */
    /** 1.142.0 (L1): the warning names every reason an axiom gives no case. */
    @Test
    void theWarningSaysAnAxiomThatEmptiesAClassGivesNoCase() {
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                "../benchmarks/assistant/ontologies/fixtures/unsatisfiable.ofn", "--out",
                new File(dir, "unsat.jsonl").getPath(), "--seed", "1", "--explain", "0",
                "--context", "0", "--controls", "0", "--definition", "0", "--repair", "0",
                "--text-to-axiom", "5"), err.toString());

        assertTrue(err.toString().contains("asked for 5 text-to-axiom cases and made 0"),
                err.toString());
        assertTrue(err.toString().contains("makes a class unsatisfiable"), err.toString());
        // 1.144.0: not only some and only any more.
        assertTrue(err.toString().contains("min, max, exactly"), err.toString());
    }

    @Test
    void textToAxiomCasesAreJudgedByTheReasonerAndReScored() throws IOException {
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"```ontoboard-proposal\\naction: add-axiom\\naxiom:"
                        + " Margherita SubClassOf hasTopping some 'cheese topping'\\n```\"},"
                        + "\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":50,"
                        + "\"eval_count\":9}\n");
        File cases = new File(dir, "text.jsonl");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "generate", "--ontology",
                ontology.getPath(), "--out", cases.getPath(), "--seed", "3", "--explain", "0",
                "--context", "0", "--controls", "0", "--definition", "0", "--repair", "0",
                "--text-to-axiom", "5"), err.toString());
        assertTrue(err.toString().contains("asked for 5 text-to-axiom cases and made 2"),
                err.toString());
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());

        List<String> csv = Files.readAllLines(new File(results, "results.csv").toPath());
        assertEquals(3, csv.size(), csv.toString());
        List<String> header = Arrays.asList(csv.get(0).split(","));
        int passes = 0;
        for (String line : csv.subList(1, csv.size())) {
            String[] row = line.split(",");
            assertEquals("text-to-axiom", row[header.indexOf("task")]);
            passes += Integer.parseInt(row[header.indexOf("pass")]);
        }
        assertEquals(1, passes, csv.toString());
        File rescored = new File(dir, "rescored");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", new File(results, "replies.jsonl").getPath(),
                "--out", rescored.getPath()), err.toString());
        assertEquals(csv, Files.readAllLines(new File(rescored, "results.csv").toPath()));
    }

    @Test
    void generateIsDeterministic() throws IOException {
        File first = generate("a.jsonl");
        File second = generate("b.jsonl");

        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()));
        assertEquals(2, Files.readAllLines(first.toPath()).size());
    }

    @Test
    void runWritesTheThreeFilesWithoutTheKey() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");

        int status = bench(Collections.singletonMap("BENCH_KEY", "sk-test-secret"), "run",
                "--models", models("BENCH_KEY").getPath(), "--cases", cases.getPath(), "--out",
                results.getPath(), "--repeats", "2");

        assertEquals(0, status, err.toString());
        List<String> csv = Files.readAllLines(new File(results, "results.csv").toPath());
        assertEquals(5, csv.size(), "a header and 2 cases x 2 repeats: " + csv);
        assertTrue(csv.get(0).startsWith("case,task,model,repeat,pass,"), csv.get(0));
        assertTrue(new File(results, "summary.md").isFile());
        assertTrue(new File(results, "replies.jsonl").isFile());
        assertEquals("Bearer sk-test-secret", server.lastHeaders().get("authorization"));
        for (File written : results.listFiles()) {
            String text = new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
            assertFalse(text.contains("sk-test-secret"), written.getName());
        }
        String summary = new String(Files.readAllBytes(new File(results, "summary.md").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(summary.contains("| fake |"), summary);
    }

    /** Review (1.137.0): the benchmark wrote model text as it came, a pasted key included. */
    @Test
    void aKeyEchoedByTheModelIsNotWritten() throws IOException {
        server.respond("/api/chat", 200, "application/x-ndjson",
                "{\"message\":{\"content\":\"Your key is sk-test-secret.\"},\"done\":false}\n",
                "{\"message\":{\"content\":\"\"},\"done\":true}\n");
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");

        assertEquals(0, bench(Collections.singletonMap("BENCH_KEY", "sk-test-secret"), "run",
                "--models", models("BENCH_KEY").getPath(), "--cases", cases.getPath(), "--out",
                results.getPath(), "--repeats", "1"), err.toString());

        String replies = new String(Files.readAllBytes(new File(results, "replies.jsonl")
                .toPath()), StandardCharsets.UTF_8);
        assertFalse(replies.contains("sk-test-secret"), replies);
        assertTrue(replies.contains("(your key)"), replies);
    }

    @Test
    void scoreReScoresRecordedRepliesWithoutCallingTheServer() throws IOException {
        File cases = generate("cases.jsonl");
        File results = new File(dir, "results");
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("").getPath(), "--cases", cases.getPath(), "--out", results.getPath(),
                "--repeats", "1"), err.toString());
        List<String> before = Files.readAllLines(new File(results, "results.csv").toPath());
        server.close();
        int requests = server.requestCount();
        File rescored = new File(dir, "rescored");

        int status = bench(Collections.<String, String>emptyMap(), "score", "--cases",
                cases.getPath(), "--replies", new File(results, "replies.jsonl").getPath(),
                "--out", rescored.getPath());

        assertEquals(0, status, err.toString());
        assertEquals(requests, server.requestCount());
        assertEquals(before, Files.readAllLines(new File(rescored, "results.csv").toPath()));
    }

    @Test
    void aModelWithoutItsKeyIsSkippedAndSaidSo() throws IOException {
        File cases = generate("cases.jsonl");

        int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                models("MISSING_KEY").getPath(), "--cases", cases.getPath(), "--out",
                new File(dir, "r").getPath());

        assertEquals(1, status);
        assertTrue(err.toString().contains("skipped fake: $MISSING_KEY is not set"),
                err.toString());
        assertEquals(0, server.requestCount());
    }

    /**
     * Review 1.148.0 keys-03: about one random 40-character base64url key in 55 has fewer than
     * three digit-then-letter pairs and passed as a variable's name. The run said only that the
     * variable is not set, and the key stayed in a file the README puts inside the clone. Such a
     * value names no variable that is set, so the "skipped" line now says to take it out, and
     * never shows it. Made up.
     */
    @Test
    void aLongValueThatNamesNoSetVariableIsSkippedWithAWordAboutAKey() throws IOException {
        File cases = generate("cases.jsonl");
        for (String value : new String[] {"eJPLoxhGVeLDOcUPwbzvpNz_cDzJiFT6tqHCXGAh",
            "QQmGE9rnnCzmbjm9_rSyEazcqdRCEnPZQxSioDNtzxl9"}) {
            err.reset();

            int status = bench(Collections.<String, String>emptyMap(), "run", "--models",
                    models(value).getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r").getPath());

            assertEquals(1, status, err.toString());
            assertTrue(err.toString().contains("skipped fake: keyVariable of entry 1 names no"
                    + " variable that is set; if it holds a key, take it out of the file and out"
                    + " of git."), err.toString());
            assertFalse(err.toString().contains(value.substring(0, 12)), err.toString());
            assertFalse(err.toString().contains(value.substring(value.length() - 12)),
                    err.toString());
        }
        assertEquals(0, server.requestCount());
    }

    /**
     * The declined note of review 1.148.0, re-graded with keys-03: a variable's name with three
     * model numbers in it - MIXTRAL_8X7B_Q4K_M_KEY - was refused as a key even when it was set.
     * A name that is set is a variable's name. Unset, it is still refused, and the refusal says
     * a variable's name must be set to run. Made up.
     */
    @Test
    void aNameWithModelNumbersRunsWhenItsVariableIsSet() throws IOException {
        File cases = generate("cases.jsonl");
        for (String variable : new String[] {"MIXTRAL_8X7B_Q4K_M_KEY",
            "QWEN2_5_7B_Q4K_M_Q8K_KEY", "ONTOBENCH_KEY_2B_4K_8K", "MIXTRAL_8X22B_Q4K_KEY"}) {
            err.reset();

            int status = bench(Collections.singletonMap(variable, "sk-test-made-up-9"), "run",
                    "--models", models(variable).getPath(), "--cases", cases.getPath(), "--out",
                    new File(dir, "r-" + variable).getPath(), "--repeats", "1");

            assertEquals(0, status, err.toString());
            assertFalse(err.toString().contains("holds a key"), err.toString());
            assertEquals("Bearer sk-test-made-up-9", server.lastHeaders().get("authorization"));
        }
        int before = server.requestCount();
        err.reset();

        int status = bench(Collections.singletonMap("OTHER_KEY", "x"), "run", "--models",
                models("MIXTRAL_8X7B_Q4K_M_KEY").getPath(), "--cases", cases.getPath(), "--out",
                new File(dir, "r-unset").getPath());

        assertEquals(1, status, err.toString());
        assertTrue(err.toString().contains("holds a key"), err.toString());
        assertTrue(err.toString().contains("if it is a variable's name, set the variable to run"),
                err.toString());
        assertFalse(new File(dir, "r-unset").exists());
        assertEquals(before, server.requestCount());
    }

    @Test
    void versionAndHelpWork() {
        assertEquals(0, bench(Collections.<String, String>emptyMap(), "version"));
        assertEquals(2, bench(Collections.<String, String>emptyMap()));
        assertTrue(out.toString().contains("generate"), out.toString());
    }
}
