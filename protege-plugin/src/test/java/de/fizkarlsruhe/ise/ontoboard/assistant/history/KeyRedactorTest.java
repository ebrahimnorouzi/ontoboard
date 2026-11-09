package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The maintainer: "if user have an api key, make sure it's not used in the github repo or not
 * revealed." Whatever OntoBoard writes into the project goes through this first.
 */
class KeyRedactorTest {

    /**
     * Review 1.143.0 I-2: a known key with one invisible or whitespace character inside - a
     * paste from a mail, a wrapped line - reached role and conversation files whole, since only
     * two exact forms of each key were replaced.
     */
    @org.junit.jupiter.api.Test
    void aKnownKeyWithAnInvisibleOrWhitespaceCharacterInsideIsRedacted() {
        String key = "Mx7pLq2Rt9Vw4Yz8Ab3Cd6Ef1Gh5Jk0";
        String[] forms = {
            key.substring(0, 10) + "\u200B" + key.substring(10),
            key.substring(0, 16) + "\n" + key.substring(16),
            key.substring(0, 20) + "\r\n" + key.substring(20),
            key.substring(0, 8) + " " + key.substring(8),
            key.substring(0, 12) + "\u00AD" + key.substring(12),
            key.substring(0, 5) + "\u00A0" + key.substring(5, 25) + "\t" + key.substring(25),
        };
        for (String form : forms) {
            String redacted = KeyRedactor.redact("before " + form + " after",
                    java.util.Collections.singletonList(key));

            org.junit.jupiter.api.Assertions.assertEquals("before (your key) after", redacted,
                    "form " + form.replace("\n", "\\n").replace("\r", "\\r"));
        }
    }

    /**
     * Review 1.143.0 M-4 (raised to Important under the key rule): GitHub tokens - in a git
     * repository the ones that matter most - and Perplexity and Fireworks keys had no shape.
     */
    @org.junit.jupiter.api.Test
    void githubPerplexityAndFireworksKeysAreKeyShaped() {
        // Made-up keys, each split after its prefix so that no secret scanner takes this source
        // for a leak: the text redacted is the same.
        String text = "a ghp_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 b gho_"
                + "AbCdEfGhIjKlMnOpQrStUvWxYz0123"
                + " c github_pat_" + "11ABCDEFG0abcdefghijkl_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
                + " d pplx-" + "AbCdEfGhIjKlMnOpQrStUvWx12 e fw_"
                + "AbCdEfGhIjKlMnOpQrStUvWx12 end";

        String redacted = KeyRedactor.redact(text, java.util.Collections.<String>emptyList());

        org.junit.jupiter.api.Assertions.assertEquals(
                "a (a key) b (a key) c (a key) d (a key) e (a key) end", redacted);
    }

    /** Review 1.143.0 M-4: a password in a server's address is never written. */
    @org.junit.jupiter.api.Test
    void credentialsInAnAddressAreRedacted() {
        String redacted = KeyRedactor.redact(
                "Nothing answered at http://ontouser:s3cretPassw0rd@127.0.0.1:11434/v1 today",
                java.util.Collections.<String>emptyList());

        org.junit.jupiter.api.Assertions.assertEquals(
                "Nothing answered at http://(credentials)@127.0.0.1:11434/v1 today", redacted);
    }

    /**
     * The verifier's case for review 1.143.0 I-2: a key copied across a line break is stored by
     * the key field with a space (it turns line breaks into spaces), and pasted into a role or the
     * chat with the line break.
     */
    @org.junit.jupiter.api.Test
    void aKeyStoredWithASpaceIsRedactedWhereItIsWrittenWithALineBreak() {
        String stored = "7y9IdkO2mvjFMDAV hvdz4mT2Vizumsae";

        String redacted = KeyRedactor.redact("You are careful.\n7y9IdkO2mvjFMDAV\nhvdz4mT2Vizumsae\n",
                java.util.Collections.singletonList(stored));

        org.junit.jupiter.api.Assertions.assertEquals("You are careful.\n(your key)\n", redacted);
    }

    /**
     * Review 1.144.0 privacy-04: an empty user name, a token as the user name, and a password
     * holding "@" came through. An address with "@" in its path is left alone.
     */
    @org.junit.jupiter.api.Test
    void credentialsInEveryFormOfAnAddressAreRedacted() {
        String[] forms = {
            "https://:Hunter2Hunter2@triples.example.org/sparql",
            "https://alice:p@ss-Hunter2@triples.example.org/sparql",
            "https://AbCdEfGhIjKlMnOpQrStUvWxYz0123456789@dev.azure.com/org/p/_git/onto",
        };
        for (String form : forms) {
            String redacted = KeyRedactor.redact("see " + form + " now",
                    java.util.Collections.<String>emptyList());

            org.junit.jupiter.api.Assertions.assertFalse(redacted.contains("Hunter2")
                    || redacted.contains("AbCdEfGh"), redacted);
            org.junit.jupiter.api.Assertions.assertTrue(redacted.contains("(credentials)@"),
                    redacted);
        }
        org.junit.jupiter.api.Assertions.assertEquals("see https://medium.com/@user/a-post now",
                KeyRedactor.redact("see https://medium.com/@user/a-post now",
                        java.util.Collections.<String>emptyList()));
    }

    /** Review 1.144.0 privacy-04: this team works on GitLab, and its tokens had no shape. */
    @org.junit.jupiter.api.Test
    void gitlabTokensAreKeyShaped() {
        org.junit.jupiter.api.Assertions.assertEquals("token (a key) in CI", KeyRedactor.redact(
                "token glpat-" + "Ab12Cd34Ef56Gh78Ij90 in CI",
                java.util.Collections.<String>emptyList()));
    }

    /** Review 1.141.0 I5: Groq, xAI and Hugging Face keys have shapes too. */
    @org.junit.jupiter.api.Test
    void groqXaiAndHuggingFaceKeysAreKeyShaped() {
        // Made-up keys, split after their prefixes for secret scanners.
        String text = "groq gsk_" + "AbCdEfGhIjKlMnOpQrStUvWx12 xai xai-"
                + "AbCdEfGhIjKlMnOpQrStUvWx12"
                + " hf hf_" + "AbCdEfGhIjKlMnOpQrStUvWx12 end";

        String redacted = KeyRedactor.redact(text, java.util.Collections.<String>emptyList());

        org.junit.jupiter.api.Assertions.assertEquals("groq (a key) xai (a key) hf (a key) end",
                redacted);
    }

    @Test
    void aKnownKeyIsReplaced() {
        String text = "I pasted my key sk-test-1234567890abcdef by mistake.";

        String safe = KeyRedactor.redact(text, Arrays.asList("sk-test-1234567890abcdef"));

        assertEquals("I pasted my key (your key) by mistake.", safe);
    }

    /**
     * Review 1.140.0 M10: a key pasted with a zero-width space is sent cleaned; a server or proxy
     * that echoes the cleaned key must not get it into a file.
     */
    @Test
    void aKeyIsRedactedAlsoAsTheClientSendsIt() {
        String pasted = "gsk_test​1234567890abcdef";
        String echoed = "The server said: invalid key gsk_" + "test1234567890abcdef.";

        String safe = KeyRedactor.redact(echoed, Arrays.asList(pasted));

        assertEquals("The server said: invalid key (your key).", safe);
    }

    /** A key OntoBoard was never told about still looks like one. */
    @Test
    void keysByTheirShapeAreReplacedToo() {
        // Made-up keys, split after their prefixes for secret scanners, as above.
        String text = "openai sk-proj-ABCDEFGHIJKLMNOPQRSTUV12345, anthropic"
                + " sk-ant-" + "api03-abcdefghijklmnopqrstuvwxyz, gemini"
                + " AIza" + "SyA1234567890abcdefghijklmnopqrstu.";

        String safe = KeyRedactor.redact(text, Collections.<String>emptyList());

        assertFalse(safe.contains("sk-proj-"), safe);
        assertFalse(safe.contains("sk-ant-"), safe);
        assertFalse(safe.contains("AIzaSy"), safe);
        assertEquals(3, safe.split("\\(a key\\)", -1).length - 1, safe);
    }

    @Test
    void shortOrEmptyKeysDoNotEatOrdinaryWords() {
        String text = "a pizza has a base";

        assertEquals(text, KeyRedactor.redact(text, Arrays.asList("", "a", " ", null)));
    }

    @Test
    void theKeysOntoBoardKnowsAreTheTypedRememberedAndEnvironmentOnes() {
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("GEMINI_API_KEY", "gemini-env-key-123456");
        environment.put("PATH", "C:\\Windows");
        Map<String, String> remembered = new HashMap<String, String>();
        remembered.put("Claude", "remembered-claude-key-1234");
        Map<String, String> typed = new HashMap<String, String>();
        typed.put("Claude", "typed-claude-key-12345");

        Collection<String> keys = KeyRedactor.knownKeys(Arrays.asList(
                Connection.of("Claude", ConnectionKind.ANTHROPIC),
                Connection.of("Gemini", ConnectionKind.OPENAI_COMPATIBLE)
                        .withKeyVariable("GEMINI_API_KEY")),
                environment, remembered::get, typed::get);

        assertTrue(keys.contains("gemini-env-key-123456"), keys.toString());
        assertTrue(keys.contains("remembered-claude-key-1234"), keys.toString());
        assertTrue(keys.contains("typed-claude-key-12345"), keys.toString());
        assertFalse(keys.contains("C:\\Windows"), "only key variables are read");
    }

    /**
     * Review 1.145.0 keys-04 (a 1.145.0 regression): the user part of an address ran on through
     * quotes, commas and parentheses to the next "@" in the text, so a CSV row, a Turtle literal
     * with a language tag, compact JSON-LD and a Markdown link before a mention lost their text.
     */
    @Test
    void anAddressEndsWhereTextGoesOn() {
        String[] texts = {
            "Alice,https://alice.example,alice@example.org",
            "ex:Pizza rdfs:seeAlso \"https://www.wikidata.org\"@en ;",
            "Annotations: rdfs:comment \"Defined at http://dbpedia.org\"@en",
            "{\"@context\":\"https://schema.org\",\"@type\":\"Person\"}",
            "See [home](https://example.org),@bob",
            "'https://example.org';@bob and https://example.org\\@bob",
        };
        for (String text : texts) {
            assertEquals(text, KeyRedactor.redact(text, Collections.<String>emptyList()));
        }
        assertEquals("https://a.example|https://(credentials)@c",
                KeyRedactor.redact("https://a.example|https://b.example@c",
                        Collections.<String>emptyList()));
    }

    /**
     * Review 1.145.0 keys-04: a replies.jsonl line is redacted field by field before it is
     * encoded; the pass over the encoded line replaces keys and shapes only, since there the end
     * of an address is no longer where its field ends.
     */
    @Test
    void theEncodedLinePassReplacesKeysAndShapesOnly() {
        String key = "Zq7vR2mX9pLa4wT8nB3kY6hJ1cD5fG0s";
        String line = "{\"sent\":\"ask http://u:p@h.example\",\"reply\":\"" + key
                + " and sk-proj-ABCDEFGHIJKLMNOPQRSTUV12345\"}";

        assertEquals("{\"sent\":\"ask http://u:p@h.example\",\"reply\":\"(your key) and (a key)\"}",
                KeyRedactor.redactKeysAndShapes(line, Collections.singletonList(key)));
    }

    /**
     * Review 1.145.0 keys-07 (a 1.145.0 regression): the sk- and GitLab shapes took hyphenated
     * words for keys - a scikit-learn term, the signal-processing GLRT, an ontology's local name.
     * A key's body holds a digit or a capital (1.147.0, review 1.146.0 keys-03); words in lower
     * case have neither, and DeepSeek's and OpenRouter's lower-case hex keys have digits. Every
     * key here is made up, and split after its prefix for secret scanners.
     */
    @Test
    void hyphenatedWordsAreNotKeyShaped() {
        String[] words = {
            "Use the sk-learn-compatible-estimator-interfaces here.",
            "Detection uses the glrt-" + "based-detector-for-moving-targets class.",
            "ex:gldt-" + "derived-thermal-model-component-a a owl:Class .",
        };
        for (String text : words) {
            assertEquals(text, KeyRedactor.redact(text, Collections.<String>emptyList()));
        }
        assertEquals("a (a key) b (a key) c (a key) end", KeyRedactor.redact(
                "a glpat-" + "Ab12Cd34Ef56Gh78Ij90 b sk-" + "3f9a2b7c1d4e5f60718293a4b5c6d7e8"
                        + " c sk-or-v1-0123456789abcdef0123456789abcdef0123456789abcdef end",
                Collections.<String>emptyList()));
    }

    /**
     * Review 1.146.0 keys-01 (a 1.146.0 regression): the rule that ends an address's user part
     * where text goes on also ended a password at ; , ( ) ' ^ | \ [ ] { } ` or a letter outside
     * ASCII, and the password reached the conversation and role files in clear. 1.145.0 caught
     * every one of them. All made up.
     */
    @Test
    void aPasswordWithPunctuationOrALetterOutsideAsciiIsRedacted() {
        String[] passwords = {"Wint3r;2026", "pa,ss77", "p(a)ss77", "it's77x", "Xy^7kLq2",
            "ab|cd77", "ab\\cd77", "ab[cd]77", "ab{cd}77", "ab`cd77", "Pässwort7",
            "Geheim§7x", "Passß77x", "Sommer;24x", "Ge(heim)42", "Müller2024!",
            "a,b,c123", "O'Neil77", "€uro2026", "Straße99", "p@ss;77"};
        for (String password : passwords) {
            String text = "Our endpoint is postgresql://kg_admin:" + password
                    + "@db.example.org:5432/kg - map the table";

            assertEquals("Our endpoint is postgresql://(credentials)@db.example.org:5432/kg"
                    + " - map the table", KeyRedactor.redact(text,
                            Collections.<String>emptyList()), password);
        }
        // The checker's case: an e-mail address as the user name, before such a password.
        assertEquals("see https://(credentials)@db.example.org/ now", KeyRedactor.redact(
                "see https://alice@example.org:Wint3r;2026@db.example.org/ now",
                Collections.<String>emptyList()));
    }

    /**
     * The guard for keys-01: a quote, a space, "<" or ">" still ends an address after a port, so
     * JSON, JSON-LD and Turtle with a local server's address keep their text.
     */
    @Test
    void anAddressWithAPortStillEndsAtAQuoteOrASpace() {
        String[] texts = {
            "{\"@context\":\"http://localhost:8080\",\"@type\":\"Person\"}",
            "{\"baseUrl\":\"http://localhost:11434\",\"model\":\"llama@x\"}",
            "ex:a rdfs:seeAlso \"http://example.org:7200\"@en .",
            "Fuseki is at http://localhost:3030 @bob",
            "<http://localhost:7200>@en",
        };
        for (String text : texts) {
            assertEquals(text, KeyRedactor.redact(text, Collections.<String>emptyList()));
        }
    }

    /**
     * Review 1.146.0 keys-03 (a 1.146.0 regression): the sk- and GitLab shapes asked the body for
     * a digit, and about one GitLab token in 30 has none. All made up.
     */
    @Test
    void keysWithNoDigitInTheirBodyAreStillKeyShaped() {
        assertEquals("a (a key) b (a key) c (a key) d (a key) e (a key) f (a key) end",
                // Made-up keys, split after their prefixes for secret scanners.
                KeyRedactor.redact("a glpat-" + "HqVmWnBxKcTrYsZpLdFg b gldt-"
                        + "Ab_cD-efGhJkMnPqRsTu"
                        + " c glrt-" + "ZuMhPbXwVqRtNcYkJsEa d glpat-" + "abcdefghijKLMNOPqrst"
                        + " e sk-QwErTyUiOpAsDfGhJkLzXcVbNmQwErTyUiOpAsDfGhJkLz"
                        + " f glft-" + "NpQrStUvWxYzAbCdEfGh end",
                        Collections.<String>emptyList()));
    }

    /**
     * Review of the 1.147.0 keys-01 fix: the password branch still read the user name with the
     * narrow class, so a user name with a letter outside ASCII or one of ' ( ) , ; left even a
     * plain password in clear. Every value here is made up.
     */
    @org.junit.jupiter.api.Test
    void aUserNameWithPunctuationOrALetterOutsideAsciiStillHidesThePassword() {
        String[] forms = {
            "postgresql://j\u00fcrgen:Geheim7x@db.example.org:5432/kg",
            "https://o'neil:Secret77x@h.example.org/",
            "https://m\u00fcller.k:Sommer;24x@triples.example.org/ds/sparql",
            "https://kg(rw):Secret77x@h.example.org/",
            "https://\u00e9quipe:Wint3r;2026@db.example.org",
        };
        for (String form : forms) {
            String redacted = KeyRedactor.redact("see " + form + " now",
                    java.util.Collections.<String>emptyList());

            org.junit.jupiter.api.Assertions.assertFalse(redacted.contains("Geheim7x")
                    || redacted.contains("Secret77x") || redacted.contains("Sommer;24x")
                    || redacted.contains("Wint3r;2026"), redacted);
            org.junit.jupiter.api.Assertions.assertTrue(redacted.contains("(credentials)@"),
                    redacted);
        }
    }

    /**
     * Review 1.147.0 keys-05 (a 1.147.0 regression): the address rule read a local server's
     * address, then a "," ";" "|" "'" ")" or "]" and a later "@", as a name and password, and cut
     * ordinary text up to that "@": other fields of a CSV row, single-quoted code, a Turtle
     * literal's language tag, a Markdown link. 1.146.0 kept every one of these whole. The
     * reviewer's 19 texts.
     */
    @Test
    void ordinaryTextWithALocalAddressBeforeAnAtStaysWhole() {
        String[] texts = {
            "kg,http://localhost:7200,alice@example.org",
            "name,endpoint,contact\nfuseki,http://localhost:3030,admin@example.org",
            "http://localhost:8080,alice@example.org",
            "https://example.org,mailto:info@example.org",
            "1;Fuseki;http://localhost:3030;ops@example.org;active",
            "{'endpoint':'http://localhost:3030','admin':'root@example.org'}",
            "fetch('http://localhost:3030',{headers:{'From':'me@example.org'}})",
            "endpoint='http://localhost:7200';user='alice@example.org'",
            "driver=GraphDatabase.driver('bolt://localhost:7687',auth=('neo4j@local','x'))",
            "SPARQLWrapper('http://localhost:3030').setCredentials('user@example.org','pw')",
            "['http://localhost:8080','admin@example.org']",
            "{endpoint:http://localhost:8080,email:a@b.org}",
            "ex:site rdfs:label 'http://localhost:8080'@en .",
            "FILTER(?u = 'http://localhost:8080'@en)",
            "[http://localhost:8080](mailto:alice@example.org)",
            "(http://localhost:8080)@bob",
            "[[http://localhost:8080][alice@example.org]]",
            "Endpoints: http://localhost:8080;http://localhost:9090;ops@example.org",
            "http://host:8080|admin@example.org",
            // The review of the 1.148.0 fix: two or more "@" after the address were still cut.
            "kg,http://localhost:7200,alice@example.org,bob@example.org",
            "{'endpoint':'http://localhost:3030','admin':'root@example.org','backup':"
                    + "'ops@example.org'}",
            "SPARQLWrapper('http://localhost:3030').setCredentials('user@example.org','p@ss')",
            "Endpoints: http://localhost:8080;ops@example.org;dev@example.org",
            "['http://localhost:8080','admin@example.org','ops@example.org']",
            "1;Fuseki;http://localhost:3030;ops@example.org;dev@example.org;active",
        };
        for (String text : texts) {
            assertEquals(text, KeyRedactor.redact(text, Collections.<String>emptyList()));
        }
    }

    /**
     * The guard for keys-05: digits after the ":" are read as a port only when a port could end
     * there, so a password that begins with digits is still hidden when a letter, a "!" or two
     * more "@" follow them. Every value here is made up.
     */
    @Test
    void aPasswordThatBeginsWithDigitsIsHiddenWhereNoPortEnds() {
        for (String password : new String[] {"2026Wint3r", "2026!Wint3r"}) {
            assertEquals("see https://(credentials)@db.example.org/kg now", KeyRedactor.redact(
                    "see https://kg_rw:" + password + "@db.example.org/kg now",
                    Collections.<String>emptyList()), password);
        }
    }

    /**
     * Review 1.147.0 keys-06: the sk- and GitLab shapes looked ahead from every prefix to the end
     * of its run, so a long run of prefixes - a crafted paste, a reply stuck in a loop - took
     * seconds to minutes, on every save of a conversation, a role or an export.
     */
    @Test
    void aLongRunOfKeyPrefixesIsRedactedQuickly() {
        KeyRedactor.redact("warm up: sk-a-sk-b glrt-c http://h:1@x", Collections.<String>emptyList());
        for (String prefix : new String[] {"sk-", "glrt-", "sk-model-"}) {
            StringBuilder run = new StringBuilder();
            while (run.length() < 100000) {
                run.append(prefix);
            }
            String text = run.toString();

            String redacted = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(1),
                    () -> KeyRedactor.redact(text, Collections.<String>emptyList()),
                    () -> "a run of " + text.length() + " characters of " + prefix);

            assertEquals(text, redacted, "lower case and hyphens only: no key");
        }
    }

    /**
     * The guard for keys-06: a hyphenated run in lower case is kept, and a key of another shape
     * inside it is still replaced, as it was when the lookahead failed and the next prefix in the
     * run was tried. Made up.
     */
    @Test
    void aKeyOfAnotherShapeInsideAHyphenatedRunIsStillReplaced() {
        assertEquals("see sk-learn-(a key) now", KeyRedactor.redact(
                "see sk-learn-xai-" + "abcdefghijklmnopqrstuvwxyz now",
                Collections.<String>emptyList()));
        assertEquals("see glrt-based-(a key) now", KeyRedactor.redactKeysAndShapes(
                "see glrt-" + "based-hf_" + "abcdefghijklmnopqrstuvwxyz now",
                Collections.<String>emptyList()));
    }

    /**
     * The review of the 1.148.0 fix: with the backslash kept out of the user name, a Windows
     * domain user in a proxy address - CORP\\alice - left the password in clear, as 1.146.0
     * did; 1.147.0 hid it. Every value here is made up.
     */
    @Test
    void aWindowsDomainUserInAProxyAddressStillHidesThePassword() {
        for (String text : new String[] {
            "set HTTPS_PROXY=http://CORP\\alice:Secr3tPw@proxy.corp:8080",
            "proxy=http://CORP\\\\alice:Secr3tPw@proxy.corp:8080"}) {
            String redacted = KeyRedactor.redact(text, Collections.<String>emptyList());

            org.junit.jupiter.api.Assertions.assertFalse(redacted.contains("Secr3tPw"), redacted);
            org.junit.jupiter.api.Assertions.assertTrue(
                    redacted.contains("(credentials)@proxy.corp:8080"), redacted);
        }
    }

    /**
     * Review 1.147.0 keys-03, deferred in 1.148.0: before the password, an e-mail address as the
     * user name was read with the narrow class, so one with an apostrophe or a letter outside
     * ASCII left the password in clear, as 1.146.0 did. Every value here is made up.
     */
    @Test
    void anEmailUserNameWithAnApostropheOrALetterOutsideAsciiStillHidesThePassword() {
        String[] forms = {
            "https://o'brien@example.org:Secr3tPw@db.example.org/kg",
            "https://j\u00fcrgen@example.de:Secr3tPw@db.example.org/kg",
            "postgresql://ren\u00e9e.d'arc@corp.example.fr:Secr3tPw@db.example.org/kg",
            "https://o'brien@mail.example.org@corp:Secr3tPw@db.example.org/kg",
        };
        for (String form : forms) {
            String redacted = KeyRedactor.redact("see " + form + " now",
                    Collections.<String>emptyList());

            org.junit.jupiter.api.Assertions.assertFalse(redacted.contains("Secr3tPw"), redacted);
            assertEquals("see https://(credentials)@db.example.org/kg now".replace("https",
                    form.substring(0, form.indexOf(':'))), redacted);
        }
    }

    /**
     * Older than 1.148.0, found by its review: an address's repeated "x@" group recursed once per
     * repetition, so a crafted "a://" and 10,000 times "b@" overflowed the stack - an Error out
     * of every save of a conversation, a role or an export.
     */
    @Test
    void aLongRunOfAtSignsAfterASchemeDoesNotOverflow() {
        StringBuilder text = new StringBuilder("see a://");
        for (int i = 0; i < 10000; i++) {
            text.append("b@");
        }
        String crafted = text.append(" now").toString();

        String redacted = KeyRedactor.redact(crafted, Collections.<String>emptyList());

        org.junit.jupiter.api.Assertions.assertTrue(redacted.endsWith(" now"), "redacted");
    }

    private static String redact(String text) {
        return KeyRedactor.redact(text, Collections.<String>emptyList());
    }

    /**
     * Review 1.148.0 keys-01: a key or a password written right after a letter outside ASCII -
     * Chinese and Japanese are written without spaces - reached role files, conversations and
     * exports in clear, since on Java 8 to 18, the Java of both Protégé versions, there is no
     * word boundary between two letters. Every value here is made up.
     */
    @Test
    void aKeyOrAPasswordRightAfterALetterOutsideAsciiIsHidden() {
        String[][] secrets = {
            {"sk-proj-Fk3Lm9Qr2Vx8Zb4Hj6Np1Tw5Yc7Ge0Ad", "(a key)"},
            // Made-up, split after the prefix for secret scanners.
            {"AIza" + "FAKEb4Hj6Np1Tw5Yc7Ge0AdFk3Lm9Qr2Vx8Z", "(a key)"},
            {"ghp_" + "FAKEb4Hj6Np1Tw5Yc7Ge0AdFk3Lm9Qr2Vx8Zb", "(a key)"},
            {"postgresql://kg_admin:Wint3r7x@db.example.org:5432/kg",
                "postgresql://(credentials)@db.example.org:5432/kg"},
            {"https://bot:Sommer2026x@triples.example.org/sparql",
                "https://(credentials)@triples.example.org/sparql"},
        };
        // U+662F "is", and the Japanese particles U+306F and U+306E.
        for (String letter : new String[] {"是", "は", "の"}) {
            for (String[] secret : secrets) {
                assertEquals(letter + secret[1], redact(letter + secret[0]), secret[0]);
            }
        }
        // The reviewers' sentences: Chinese, Japanese, Thai and Korean.
        String[][] sentences = {
            {"我的密钥是sk-proj-Fk3Lm9Qr2Vx8Zb4Hj6Np1Tw5Yc7Ge0Ad",
                "我的密钥是(a key)"},
            {"キーはsk-proj-Fk3Lm9Qr2Vx8Zb4Hj6Np1Tw5Yc7Ge0Adです",
                "キーは(a key)です"},
            {"GoogleのキーはAIza" + "FAKEb4Hj6Np1Tw5Yc7Ge0AdFk3Lm9Qr2Vx8Z",
                "Googleのキーは(a key)"},
            {"令牌ghp_" + "FAKEb4Hj6Np1Tw5Yc7Ge0AdFk3Lm9Qr2Vx8Zb", "令牌(a key)"},
            {"トークンghs_" + "Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qrを使っ"
                + "て", "トークン(a key)を使って"},
            {"部署令牌glpat-" + "Ab12Cd34Ef56Gh78Ij90", "部署令牌(a key)"},
            {"คีย์คือsk-or-v1-0a1b2c3d4e5f60718293a4b5c6d7e8f9",
                "คีย์คือ(a key)"},
            {"키는hf_" + "FAKEb4Hj6Np1Tw5Yc7Ge0AdFk3Lm9", "키는(a key)"},
            {"地址是postgresql://kg_admin:Wint3r7x@db.example.org:5432/kg",
                "地址是postgresql://(credentials)@db.example.org:5432/kg"},
            {"接続先はhttps://bot:Sommer2026x@triples.example.org/sparql",
                "接続先はhttps://(credentials)@triples.example.org/sparql"},
            {"数据库连接mysql://root:Fr0stb1te9@10.0.0.5:3306/kg",
                "数据库连接mysql://(credentials)@10.0.0.5:3306/kg"},
        };
        for (String[] sentence : sentences) {
            assertEquals(sentence[1], redact(sentence[0]));
        }
    }

    /**
     * The guard for keys-01: after an ASCII letter no key begins, as before; after punctuation
     * or a space, and a scheme after "1." or "...", a key and a password are hidden, as before.
     * The checker found that the narrower start the reviewer offered for the scheme left the
     * password of "1.https://" in clear. Every value here is made up.
     */
    @Test
    void aKeyOrAnAddressAfterAnAsciiLetterOrAfterPunctuationBehavesAsBefore() {
        String sk = "sk-proj-Fk3Lm9Qr2Vx8Zb4Hj6Np1Tw5Yc7Ge0Ad";
        assertEquals("Schlüssel" + sk, redact("Schlüssel" + sk));
        assertEquals("task-Qw7Er5Ty3Ui9Op1As8Df2Gh4", redact("task-Qw7Er5Ty3Ui9Op1As8Df2Gh4"));
        assertEquals("我的密钥是 (a key)",
                redact("我的密钥是 " + sk));
        assertEquals("APIキー：(a key)", redact("APIキー：" + sk));
        String address = "https://svc:Kirsch3nbl@graphdb.example.net/kg";
        String hidden = "https://(credentials)@graphdb.example.net/kg";
        for (String before : new String[] {"1.", "see...", "(-", "第一步 1.", "x "}) {
            assertEquals(before + hidden, redact(before + address), before);
        }
    }

    /**
     * Review 1.148.0 keys-02: the keys-05 fix covered ASCII only, so ordinary text after a local
     * server's address was still cut up to a later "@" at punctuation outside ASCII - Chinese and
     * Japanese commas, full stops, brackets and colons, curly quotes - at a no-break or an
     * ideographic space, and at ` [ { ^ after a port. 1.146.0 kept every one of these whole. Ten
     * of the reviewer's eleven texts, and the checker's: the eleventh, a port range then "," and
     * an e-mail address, is still cut, as by 1.147.0 and 1.148.0 (see KeyRedactor.CREDENTIALS).
     */
    @Test
    void ordinaryTextWithAnAddressBeforePunctuationOutsideAsciiOrAUnicodeSpaceStaysWhole() {
        String[] texts = {
            "地址：http://localhost:7200，联系人：alice@example.org",
            "「http://localhost:7200」の管理者はalice@example.org"
                    + "です",
            "エンドポイント：http://localhost:7200。問"
                    + "い合わせ：alice@example.org",
            "数据库（http://localhost:5432）由@alice维护",
            "地址：http://example.org，账号:alice@example.org",
            "Server: http://localhost:7200 contact alice@example.org",
            "サーバー　http://localhost:7200　担当　"
                    + "alice@example.org",
            "`http://localhost:7200`,`alice@example.org`",
            "|`http://localhost:7200`|`alice@example.org`|",
            "“http://localhost:7200”,“alice@example.org”",
            "结果请发到：http://localhost:8080，并抄送"
                    + "bob@example.com。",
            "GraphDB（http://localhost:7200）の管理者は"
                    + "suzuki@example.jp",
            "端口改成 http://localhost:9999；详情见@张三",
            "管理画面：http://localhost:8443、support@example.co.jp",
            "请使用“http://localhost:7200”而不是"
                    + "“admin@example.org”",
            "Fuseki: http://localhost:3030 (maintainer: carol@example.net)",
            "Fuseki http://localhost:3030 ops@example.org",
            "端点　http://localhost:7200　tanaka@example.jp",
            "|Fuseki|`http://localhost:3030`|`ops@example.org`|",
            "http://localhost:8080—bob@example.org",
            "«http://h:1»x@y",
            "http://localhost:8080[ops@example.org",
            "{http://localhost:8080{ops@example.org",
            "^http://localhost:8080^ops@example.org",
            // An address right after a letter outside ASCII, which keys-01 now reads.
            "请访问http://localhost:7200，管理员是"
                    + "alice@example.org",
        };
        for (String text : texts) {
            assertEquals(text, redact(text));
        }
    }

    /**
     * Review 1.148.0 keys-02: the reviewer's sweep - six addresses, a separator, six texts with
     * an "@" - for the separators 1.148.0 still cut at and the ones it already kept whole.
     */
    @Test
    void textAfterAnAddressAndAWideOrClosingSeparatorStaysWhole() {
        String[] heads = {"http://localhost:8080", "https://example.org", "http://127.0.0.1:7200",
            "bolt://localhost:7687", "https://example.org:8443", "http://fuseki:3030"};
        String[] tails = {"alice@example.org", "'@en", "mailto:bob@example.org", "@bob",
            "ex:Thing'@en", "contact:ops@example.org"};
        String separators = ",;]|}[{^`，。、：；）」】 "
                + "　”—…»’ ";
        for (char separator : separators.toCharArray()) {
            for (String head : heads) {
                for (String tail : tails) {
                    String text = "x " + head + separator + tail + " y";
                    assertEquals(text, redact(text));
                }
            }
        }
    }

    /**
     * The guard for keys-02: a password in an address inside Chinese text, and one that holds
     * letters outside ASCII, is still hidden. Every value here is made up.
     */
    @Test
    void aPasswordInAnAddressInsideTextOutsideAsciiIsStillHidden() {
        assertEquals("连接串：postgresql://(credentials)@db.example.cn:5432/kg"
                + "，请勿外传", redact("连接串：postgresql://"
                        + "kg_admin:Xq7Lm2Pz@db.example.cn:5432/kg，请勿外传"));
        assertEquals("地址：postgresql://(credentials)@db.example.cn/kg", redact(
                "地址：postgresql://用户:密码Pw77@db.example.cn/kg"));
    }

    /**
     * The review of the keys-02 fix: the block of CJK symbols and punctuation (U+3000 to U+303F)
     * also holds letters and numerals - the iteration mark of 佐々木 (Sasaki, a common Japanese
     * surname), 〆 and the kanji zero 〇 - and taken whole as an end of text, it left these
     * passwords in clear. Every value here is made up.
     */
    @Test
    void aPasswordAfterAnIterationMarkOrAKanjiNumeralIsStillHidden() {
        String[][] cases = {
            {"https://佐々木:Passw0rd@db.example.jp/kg", "https://(credentials)@db.example.jp/kg"},
            {"postgresql://sasaki:時々Pw2026@db.example.jp/kg",
                "postgresql://(credentials)@db.example.jp/kg"},
            {"postgresql://wang:二〇二六Pw@db.example.cn/kg",
                "postgresql://(credentials)@db.example.cn/kg"},
            {"mysql://〆切:Fr0stb1te9@10.0.0.5:3306/kg", "mysql://(credentials)@10.0.0.5:3306/kg"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], redact(c[0]), c[0]);
        }
    }

    /**
     * The review of the keys-01 fix: an address straight after a Chinese or Japanese word is now
     * read, so a port followed by "(" - common in Japanese technical writing - and a port range
     * cut the text up to a later "@". Digits after the ":" and then "(" or a range are a port.
     * The guard: a password that begins with digits and a "-" is still hidden. Made up.
     */
    @Test
    void aPortBeforeABracketOrAPortRangeEndsTheAddress() {
        for (String text : new String[] {
            "サーバーhttp://localhost:7200(社内)の管理者はalice@example.orgです",
            "部署在http://localhost:7200(测试)由alice@example.org负责",
            "http://localhost:7200-7300,admin@example.org",
            "kg,http://localhost:7200(test),alice@example.org"}) {
            assertEquals(text, redact(text));
        }
        assertEquals("https://(credentials)@db.example.org/kg", redact(
                "https://kg_rw:2024-Wint3r@db.example.org/kg"));
    }

    /**
     * Review 1.149.0 keys-01: Chinese, Japanese, Korean and Thai put no space between a port and
     * the next word, and an address right after such a word is read since 1.149.0. The port's
     * digits and the word after them were read as a password, and the text was cut up to a later
     * "@" and shown as (credentials). 1.148.0 kept each of these whole. The checker's seven texts,
     * the reviewer's thirteen, and a letter of each script the port now ends at. Made up.
     */
    @Test
    void aPortRightBeforeAWordOfAScriptWrittenWithoutSpacesEndsTheAddress() {
        String[] texts = {
            "请把查询发到http://localhost:7200或联系ops@example.cn",
            "ラベルはhttp://localhost:7200にあるrdfs:labelの@ja付きのものを使って",
            "服务器http://localhost:7200上的文件以@prefix开头",
            "Neo4jはbolt://localhost:7687ポートで@adminが管理",
            "ติดตั้งที่http://localhost:7200โดยผู้ดูแลsomchai@example.co.th",
            "GraphDB http://localhost:7200・管理者alice@example.org",
            // With a space before the scheme: cut by 1.148.0 too.
            "请把数据导入 http://localhost:7200然后通知bob@example.com",
            "部署在http://localhost:7200由alice@example.org负责",
            "サーバーhttp://localhost:7200の管理者はalice@example.orgです",
            "请访问http://localhost:8080或发邮件至admin@example.com",
            "用http://127.0.0.1:7200登录admin@example.org",
            "管理画面http://localhost:8080にadmin@example.comでログインしてください",
            "GraphDBはhttp://localhost:7200で動いています@tanaka",
            "生产环境http://10.0.0.6:8080由@运维组负责",
            "เปิดhttp://localhost:8080แล้วติดต่อadmin@example.com",
            "서버http://localhost:8080관리자admin@example.com",
            "访问http://例子.中国:8080由alice@example.org负责",
            "服务器http://localhost:8080电话010-12345678邮箱ops@example.cn",
            "詳細はhttp://wiki.example.org:8090のページを参照@suzuki",
            "会议链接http://meet.example.org:443今天10点@全体成员",
        };
        for (String text : texts) {
            assertEquals(text, redact(text));
        }
        // Han, hiragana, katakana, Hangul, Thai, Lao, Khmer, Myanmar, the katakana middle dot
        // and the prolonged sound mark: each \p{Is...} works on Java 8, Protege 5.5.0's.
        for (String letter : new String[] {"由", "の", "ポ", "관", "แ", "\u0EA5", "\u1780",
            "\u1000", "\u30FB", "\u30FC"}) {
            String text = "部署在http://localhost:7200" + letter + "alice@example.org负责";
            assertEquals(text, redact(text), letter);
        }
    }

    /**
     * The guard for keys-01: digits and then a letter or a symbol outside those scripts are still
     * read as the start of a password. Every value here is made up.
     */
    @Test
    void aPasswordThatBeginsWithDigitsAndALetterOutsideTheseScriptsIsStillHidden() {
        assertEquals("postgresql://(credentials)@db.example.de/kg",
                redact("postgresql://kg:2026ßtraße@db.example.de/kg"));
        assertEquals("mysql://(credentials)@10.0.0.5:3306/kg",
                redact("mysql://root:2026€uro@10.0.0.5:3306/kg"));
        assertEquals("https://(credentials)@host.example.org/x",
                redact("https://user:7200abc@host.example.org/x"));
        assertEquals("地址：postgresql://(credentials)@db.example.cn/kg",
                redact("地址：postgresql://用户:密码Pw77@db.example.cn/kg"));
    }

    /**
     * Review 1.149.0 keys-04: each of up to 32 scheme starts that share one "://" scanned the
     * whole tail through the wide classes of 1.149.0, so a crafted or looping run of a million
     * characters took about 9 s to redact, on every save of a conversation, against about 1 s in
     * 1.148.0.
     */
    @Test
    void aLongRunAfterManySchemeStartsIsRedactedQuickly() {
        StringBuilder starts = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            starts.append("a.");
        }
        starts.append("a://");
        StringBuilder run = new StringBuilder(starts);
        for (int i = 0; i < 1000000; i++) {
            run.append('中');
        }
        String text = run.toString();
        String shorter = text.substring(0, 50000);
        for (int i = 0; i < 5; i++) {
            assertEquals(shorter, KeyRedactor.redact(shorter, Collections.<String>emptyList()));
        }

        // 3 s, not 1: about 1 s is the healthy time here (1.148.0's), and a busy machine took it
        // past 1 s in two full runs of 1.151.0; the defect took 7 to 9 s.
        String redacted = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                () -> KeyRedactor.redact(text, Collections.<String>emptyList()),
                () -> "32 scheme starts and a run of " + text.length() + " characters");

        assertEquals(text, redacted, "no address with a name or password");
    }

    /**
     * The review of the keys-04 fix: the bound capped one long run, but with "://" repeated every
     * 4,096 characters or fewer each of the 32 scheme starts still scanned its whole chunk
     * several times through the wide classes - seven seconds for a million characters, on every
     * save after an answer.
     */
    @Test
    void manySchemeStartsRepeatedAreRedactedQuickly() {
        StringBuilder starts = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            starts.append("a.");
        }
        starts.append("a://");
        for (String fill : new String[] {"中", "b"}) {
            int length = fill.equals("中") ? 1000 : 4096;
            StringBuilder chunk = new StringBuilder(starts);
            for (int i = 0; i < length; i++) {
                chunk.append(fill);
            }
            StringBuilder run = new StringBuilder();
            while (run.length() < 1000000) {
                run.append(chunk);
            }
            String text = run.toString();
            String shorter = text.substring(0, 50000);
            for (int i = 0; i < 5; i++) {
                assertEquals(shorter, KeyRedactor.redact(shorter, Collections.<String>emptyList()));
            }

            // 3 s, as above: the defect took seven seconds.
            String redacted = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(3),
                    () -> KeyRedactor.redact(text, Collections.<String>emptyList()),
                    () -> "32 scheme starts every " + length + " characters of " + fill);

            assertEquals(text, redacted, "no address with a name or password");
        }
    }
}
