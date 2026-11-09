package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionPreset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps keys out of everything OntoBoard writes into a project, which is a git repository
 * (maintainer: "make sure it's not used in the github repo or not revealed").
 *
 * <p>Two nets. Every key OntoBoard knows - typed this session, remembered, or read from a key
 * variable - is replaced by "(your key)" wherever it appears; and anything shaped like a cloud
 * key (OpenAI's, Anthropic's and OpenRouter's {@code sk-…}, Google's {@code AIza…}) is replaced by
 * "(a key)", for a key OntoBoard was never told about that someone pasted into the chat.
 */
public final class KeyRedactor {

    static final String KNOWN = "(your key)";
    static final String SHAPED = "(a key)";

    /**
     * Shorter "keys" are not keys, and would eat ordinary words. Nor is a shorter value under a
     * key's name in a benchmark's models file, a model's "&lt;/s&gt;" (1.150.0, review 1.149.0
     * keys-03).
     */
    public static final int SHORTEST = 8;

    /**
     * A key's body holds a digit or a capital, checked on a match of the two shapes that allow
     * "-" in it: "sk-learn-compatible-estimator-interfaces", the signal-processing GLRT and an
     * ontology's hyphenated local names were taken for keys (1.146.0, review 1.145.0 keys-07);
     * words in lower case have neither. A digit alone let about one GitLab token in 30 through,
     * the ones with none in their body (1.147.0, review 1.146.0 keys-03): a base64 body has
     * capitals, and DeepSeek's and OpenRouter's lower-case hex keys have digits.
     *
     * <p>Checked on each match, and no longer by a lookahead after the prefix: from every prefix
     * in a run that lookahead scanned to the run's end, so a long run of "sk-" - a crafted paste,
     * a reply stuck in a loop - took seconds to minutes to redact, on every save of a
     * conversation, a role or an export (1.148.0, review 1.147.0 keys-06). A match takes its
     * whole run, so the pass is linear.
     */
    private static final Pattern DIGIT_OR_CAPITAL = Pattern.compile("[0-9A-Z]");

    /**
     * Where a key's shape or an address's scheme may begin: not right after an ASCII letter, a
     * digit or "_". It was \b, and on Java 8 to 18 - the Java of both Protégé versions - \b sees
     * no boundary between two letters of any script, so a key or a password written right after
     * a Chinese, Japanese, Thai or Korean word, with no space as those languages are written,
     * reached role files, conversations and exports in clear (1.149.0, review 1.148.0 keys-01).
     * Every shape and scheme begins with an ASCII letter, so after an ASCII character this is
     * the \b it replaces. The scheme takes this start too, and not the narrower one the review
     * offered, which refused one after "+", "." and "-" as well: with it the password in
     * "1.https://…", "see...https://…" and "(-https://…" stayed in clear (its checker).
     */
    private static final String START = "(?<![A-Za-z0-9_])";

    /**
     * Spaces of any kind and punctuation outside ASCII, where ordinary text goes on after an
     * address: a no-break, a narrow no-break or an ideographic space; the guillemets; General
     * Punctuation's dashes, curly quotes and ellipsis (U+2000 to U+206F); the Chinese and
     * Japanese commas, full stops and brackets of U+3000 to U+303F; the fullwidth forms of ASCII's
     * punctuation and the halfwidth Japanese ones (1.149.0, review 1.148.0 keys-02). Letters and
     * digits outside ASCII, and symbols such as § and €, stay in a password: "Straße99",
     * "€uro2026". So do the letters and numerals of U+3000 to U+303F - the iteration mark of
     * 佐々木, 〆, the kanji zero 〇: with the whole block, "https://佐々木:Passw0rd@…" left the
     * password in clear (1.149.0, the review of the keys-02 fix). See CREDENTIALS.
     */
    private static final String TEXT_ENDS = "\\p{Z}\\u00AB\\u00BB\\u2000-\\u206F"
            + "\\u3000-\\u3004\\u3008-\\u3020\\u3030\\u3036\\u3037\\u303D-\\u303F"
            + "\\uFF00-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65";

    /**
     * The letters of the scripts written without a space between words - Han, hiragana, katakana,
     * Hangul, Thai, Lao, Khmer, Myanmar - and the katakana middle dot and prolonged sound mark,
     * which belong to no script. After the digits that follow the ":" they end a port: see
     * CREDENTIALS (1.150.0, review 1.149.0 keys-01). Each \p{Is...} here names a script, which
     * Java 8's Pattern reads as well.
     */
    private static final String SPACELESS = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}"
            + "\\p{IsHangul}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}\\u30FB\\u30FC";

    /** The shapes whose body may hold "-", the sk- and GitLab ones: see DIGIT_OR_CAPITAL. */
    private static final String HYPHENATED = START + "sk-[A-Za-z0-9_\\-]{20,}"
            // GitLab's personal, deploy, trigger, runner and other tokens (1.145.0, review
            // 1.144.0 privacy-04).
            + "|" + START + "gl(?:pat|dt|ptt|rt|ft|cbt|soat|imt|oas|agent)-[A-Za-z0-9_\\-]{20,}";

    private static final String OTHER_SHAPES = START + "AIza[0-9A-Za-z_\\-]{30,}"
            // Groq, xAI and Hugging Face (1.143.0, review 1.141.0 I5).
            + "|" + START + "gsk_[A-Za-z0-9]{20,}|" + START + "xai-[A-Za-z0-9]{20,}"
            + "|" + START + "hf_[A-Za-z0-9]{20,}"
            // GitHub tokens - in a git repository the ones that matter most - and Perplexity
            // and Fireworks keys (1.144.0, review 1.143.0 M-4).
            + "|" + START + "gh[pousr]_[A-Za-z0-9]{30,}"
            + "|" + START + "github_pat_[A-Za-z0-9_]{30,}"
            + "|" + START + "pplx-[A-Za-z0-9]{20,}|" + START + "fw_[A-Za-z0-9]{20,}";

    /** Every shape; group 1 is a hyphenated one. */
    private static final Pattern SHAPES = Pattern.compile("(" + HYPHENATED + ")|" + OTHER_SHAPES);

    private static final Pattern OTHER = Pattern.compile(OTHER_SHAPES);

    /** The longest user name or password CREDENTIALS reads: see USER. */
    private static final int LONGEST = 4096;

    /**
     * Whatever stands before the host in an address - {@code http://user:password@host}, a token
     * as the user, an empty user, a password with "@" in it: up to the last "@" before the path,
     * query or fragment begins (1.145.0, review 1.144.0 privacy-04). An "@" in a path is left.
     *
     * <p>The user part ends at a character that cannot be in it in text - a quote, an apostrophe,
     * a comma, a semicolon, a parenthesis, a bar, a backslash, a space: in 1.145.0 it ran on to
     * the next "@" anywhere, so a CSV row, a Turtle literal's language tag, compact JSON-LD and a
     * Markdown link before a mention lost their text (1.146.0, review 1.145.0 keys-04).
     *
     * <p>That rule ended a password there too, so one holding ; , ( ) ' ^ | \ [ ] { } ` or a letter
     * outside ASCII - "Wint3r;2026", "Straße99" - reached the conversation and role files in
     * clear. After the first ":" the password takes anything but a space and / ? # " &lt; &gt;
     * up to the last "@" (1.147.0, review 1.146.0 keys-01). A quote, a space, "&lt;" or "&gt;"
     * still ends the address.
     *
     * <p>Before a password the user name may be an e-mail address, and takes a letter outside
     * ASCII and ' ( ): with the narrow class, "jürgen", "o'neil" and "kg(rw)" left even a plain
     * password in clear (1.147.0, the review of the keys-01 fix). It does not take , ; | [ ] { }
     * ` ^, and digits after the ":" followed by ' ) , ; | ] or } are a port. In 1.147.0 a bare
     * host, or a local server's address, then such a character and a later "@" was read as a name
     * and password, and ordinary text was cut up to that "@": the other fields of a CSV row,
     * single-quoted code, a Turtle literal's language tag, a Markdown link (1.148.0, review
     * 1.147.0 keys-05; with two or more "@" after the address too, and the backslash of a Windows
     * domain user, CORP\alice, kept in the user name: the review of that fix). The accepted
     * misses, as in 1.146.0: a password that begins with digits and then one of ' ) , ; | ] } -
     * "2026;Wint3r" - is read as a port and stays in clear, and so does a password after a user
     * name that holds one of , ; | [ ] { } ` ^. Ordinary text silently changed in committed role
     * files and conversations weighs more than those rare forms.
     *
     * <p>At most eight "x@" before the host: the repeated group recursed once per repetition, and
     * a crafted "a://" with 10,000 "b@" overflowed the stack - an Error out of every save (1.148.0,
     * found by the review of the keys-05 fix). A ninth "x@" before the user name leaves the
     * password in clear - "https://a@b@c@d@e@f@g@h@i@u:password@host" - which is accepted: no
     * real address has nine (a declined note of the review of 1.148.0).
     *
     * <p>The scheme has 64 characters at most. From every word start in a run of letters, digits
     * and + . - the scheme part scanned to the run's end for "://", so the run of "sk-" that
     * keys-06 found slow in the shape pass took 3 s for 40,000 characters here too, and a run of
     * "a." as long (1.148.0, review 1.147.0 keys-06). The longest registered schemes have about
     * 36 characters, and a longer one is still read from a word start in its last 64.
     *
     * <p>Before a password, an e-mail address as the user name takes what the user name takes:
     * read with the narrow class, "o'brien@example.org" or "jürgen@example.de" left the password
     * in clear, as in 1.146.0 (1.149.0, review 1.147.0 keys-03). Without a password the narrow
     * class stays: there an "x@" ends no password, and a single-quoted address before a language
     * tag, 'http://example.org'@en, would be taken for one with a user name.
     *
     * <p>Those ends were ASCII's alone, so ordinary text after a local server's address was still
     * cut up to a later "@" at a Chinese or Japanese comma, full stop, colon or bracket, a curly
     * quote, a dash, at a no-break or an ideographic space, and at ` [ { ^ after a port (1.149.0,
     * review 1.148.0 keys-02). What ends an address now: a space of any kind, / ? # " &lt; &gt;,
     * and punctuation outside ASCII (TEXT_ENDS); its user name, and an e-mail address before the
     * password, also : @ , ; | [ ] { } ` ^. Digits after the ":" are a port when one of
     * ' ( ) , ; | [ ] { } ` ^ follows them, or a range of ports and then one of those: ` [ { ^
     * were added, the user name's ends that were missing, and "(" and the range once an address
     * right after a Chinese or Japanese word was read - "サーバーhttp://localhost:7200(社内)の管理者は
     * alice@…" and "localhost:7200-7300,admin@…" were cut (1.149.0, the review of the keys-01
     * and keys-02 fixes). The accepted misses: a password that begins with up to five digits, or
     * a range, and then one of those - "2026;Wint3r", "2026`Wint3r", "2026(Wint3r" - is read as a
     * port and stays in clear, as with ' ) , ; | ] } before; so does a password with a space,
     * punctuation outside ASCII or a format character (a zero-width space, a direction mark) in
     * it, a password after a user name that holds one of , ; | [ ] { } ` ^, a curly apostrophe
     * (o’neil) or a no-break space, which end it. Ordinary text silently changed in committed role
     * files and conversations weighs more than those rare forms.
     *
     * <p>Digits after the ":", or a range, are a port also when a letter of a script written
     * without spaces follows them (SPACELESS). Chinese, Japanese, Korean and Thai put no space
     * between a port and the next word, and with an address right after such a word read since
     * 1.149.0, the port's digits and the words after them were taken for a password: ordinary text
     * was cut up to a later "@" and shown as (credentials) - "部署在http://localhost:7200由
     * alice@example.org负责", "サーバーhttp://localhost:7200の管理者は…" (1.150.0, review 1.149.0
     * keys-01). Not every letter outside ASCII, which the review offered first: a password that
     * begins with digits and then "ß" or "€" is still hidden (its checker). The accepted miss: a
     * password that begins with up to five digits, or a range, and then such a letter -
     * "1234密码Pw" - is read as a port and stays in clear, as "2026;Wint3r" does.
     *
     * <p>The user name, an e-mail address's part before its "@" and the password have LONGEST
     * characters at most. From each of up to 32 scheme starts that share one "://" they scanned
     * the whole tail through the wide classes of 1.149.0, so "a." 32 times, "a://" and a million
     * Chinese characters took about 9 s to redact, where 1.148.0 took about 1 s - on every save
     * of a conversation after an answer (1.150.0, review 1.149.0 keys-04). No real address has a
     * longer one; a longer password stays in clear.
     */
    /*
     * Possessive: neither class holds "@" or ":", so a shorter try can never be followed by one,
     * and giving back character by character only cost time. It changes no match.
     */
    private static final String USER = "[^\\s/?#\"<>:@,;|\\[\\]{}`^" + TEXT_ENDS + "]{0,"
            + LONGEST + "}+";

    /**
     * What follows an address's "://" when it holds a name and password, or a token - CREDENTIALS
     * as the comments above call it, read once for each "://" by {@link #addresses}. It begins
     * with one pass to the first "@": a match needs one before any end of an address, at most
     * twice LONGEST characters and a ":" in.
     */
    private static final Pattern CREDENTIALS = Pattern.compile(
            "(?i)(?=[^\\s/?#\"<>@" + TEXT_ENDS + "]{0," + (2 * LONGEST + 1) + "}+@)"
                    + "(?:(?:" + USER + "@){0,8}" + USER + ":"
                    + "(?!\\d{1,5}(?:-\\d{1,5})?(?:['(),;|\\[\\]{}`^]|[" + SPACELESS + "]))"
                    + "[^\\s/?#\"<>" + TEXT_ENDS + "]{0," + LONGEST + "}@"
                    + "|(?:[a-z0-9\\-._~%!$&*+=]{0," + LONGEST + "}+@){1,8})");

    /** The most characters of an address's scheme, its first letter with them. */
    private static final int SCHEME = 64;

    private KeyRedactor() {
    }

    /**
     * What may stand between two characters of a key in a text: whitespace, line breaks, and the
     * invisible characters ApiKeys.clean takes out of a pasted key (zero-width spaces, soft
     * hyphens, no-break spaces, controls).
     */
    private static final String GAP = "[\\s\\p{Cf}\\p{Cc}\\p{Z}]*";

    public static String redact(String text, Collection<String> keys) {
        if (text == null) {
            return "";
        }
        String safe = addresses(known(text, keys));
        return shapes(safe);
    }

    /**
     * The text with the name and password, or the token, of each address replaced by
     * "(credentials)", the scheme kept: http://(credentials)@host.
     *
     * <p>One try for each "://", from the leftmost place its scheme can begin. As one pattern,
     * scheme and all, it was tried from every place a scheme can begin - up to 32 for one "://"
     * in "a.a.a…a://" - and each try read what follows the "://" again, though what follows
     * decides alike for all of them: with "://" repeated every few thousand characters, a
     * million characters took seven seconds, on every save after an answer (1.150.0, the review
     * of the keys-04 fix). What is replaced is what that pattern replaced: the leftmost start
     * that reaches a "://" is the one a left-to-right search tries first, and a scheme's letters
     * hold no ":", so each start reaches one "://" only.
     */
    private static String addresses(String text) {
        StringBuilder out = null;
        Matcher after = null;
        int copied = 0;
        for (int colon = text.indexOf("://"); colon >= 0; colon = text.indexOf("://", colon + 1)) {
            if (schemeStart(text, colon, copied) < 0) {
                continue;
            }
            if (after == null) {
                after = CREDENTIALS.matcher(text);
            }
            after.region(colon + 3, text.length());
            if (!after.lookingAt()) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, colon + 3).append("(credentials)@");
            copied = after.end();
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * Where the scheme of the "://" at this index begins: the leftmost place, at or after from
     * and at most SCHEME characters before it, of an ASCII letter followed by letters, digits
     * and + . - up to the "://", and not right after an ASCII letter, a digit or "_" (START);
     * -1 when there is none.
     */
    private static int schemeStart(String text, int colon, int from) {
        int lowest = Math.max(from, colon - SCHEME);
        int run = colon;
        while (run > lowest && schemeChar(text.charAt(run - 1))) {
            run--;
        }
        for (int start = run; start < colon; start++) {
            if (asciiLetter(text.charAt(start))
                    && (start == 0 || !wordChar(text.charAt(start - 1)))) {
                return start;
            }
        }
        return -1;
    }

    private static boolean asciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean wordChar(char c) {
        return asciiLetter(c) || c >= '0' && c <= '9' || c == '_';
    }

    private static boolean schemeChar(char c) {
        return asciiLetter(c) || c >= '0' && c <= '9' || c == '+' || c == '.' || c == '-';
    }

    /** The text with everything shaped like a key replaced. */
    private static String shapes(String text) {
        Matcher shape = SHAPES.matcher(text);
        // A StringBuffer: Java 8's appendReplacement takes no StringBuilder.
        StringBuffer safe = new StringBuffer();
        while (shape.find()) {
            String found = shape.group();
            String replacement = SHAPED;
            if (shape.group(1) != null && !DIGIT_OR_CAPITAL.matcher(found).find()) {
                // Hyphenated words in lower case, kept - with any other shape in them replaced,
                // as the lookahead let the next prefix in the run be tried.
                replacement = OTHER.matcher(found).replaceAll(SHAPED);
            }
            shape.appendReplacement(safe, Matcher.quoteReplacement(replacement));
        }
        shape.appendTail(safe);
        return safe.toString();
    }

    /**
     * The known-key and shape passes of {@link #redact}, without the address one: for a line
     * whose fields were each redacted whole and then encoded, such as a replies.jsonl line. There
     * a field's end is no longer where its text ends, and the address pass took a bare-host URL
     * at the end of one field, and everything up to the "@" of the next, for a user name
     * (1.146.0, review 1.145.0 keys-04).
     */
    public static String redactKeysAndShapes(String text, Collection<String> keys) {
        if (text == null) {
            return "";
        }
        return shapes(known(text, keys));
    }

    /** The text with every known key in it replaced. */
    private static String known(String text, Collection<String> keys) {
        // Each key as the client sends it, without the invisible characters a paste can carry
        // (review 1.140.0 M10) - and matched with any of them, or a line break, between its
        // characters: a key pasted from a mail or wrapped in a text reached files whole when
        // only two exact forms were replaced (1.144.0, review 1.143.0 I-2).
        List<String> ordered = new ArrayList<String>();
        for (String key : keys) {
            String sent = de.fizkarlsruhe.ise.ontoboard.assistant.connect.ApiKeys.clean(key);
            if (sent.length() >= SHORTEST && !ordered.contains(sent)) {
                ordered.add(sent);
            }
        }
        // Longest first, so a key that contains another is replaced whole.
        Collections.sort(ordered, (a, b) -> b.length() - a.length());
        String safe = text;
        for (String key : ordered) {
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < key.length(); i++) {
                pattern.append(i == 0 ? "" : GAP)
                        .append(Pattern.quote(String.valueOf(key.charAt(i))));
            }
            safe = Pattern.compile(pattern.toString()).matcher(safe)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(KNOWN));
        }
        return safe;
    }

    /**
     * Every key OntoBoard could have used: per connection, the typed and the remembered one and
     * its key variable's value; and the presets' variables, set or not for any connection.
     */
    public static Collection<String> knownKeys(List<Connection> connections,
            Map<String, String> environment, Function<String, String> remembered,
            Function<String, String> typed) {
        Set<String> keys = new LinkedHashSet<String>();
        Set<String> variables = new LinkedHashSet<String>();
        for (ConnectionPreset preset : ConnectionPreset.values()) {
            if (!preset.getKeyVariable().isEmpty()) {
                variables.add(preset.getKeyVariable());
            }
        }
        for (Connection connection : connections) {
            keys.add(typed.apply(connection.getName()));
            keys.add(remembered.apply(connection.getName()));
            if (!connection.getKeyVariable().isEmpty()) {
                variables.add(connection.getKeyVariable());
            }
        }
        if (environment != null) {
            for (String variable : variables) {
                keys.add(environment.get(variable));
            }
        }
        keys.remove(null);
        keys.remove("");
        return keys;
    }

    /**
     * The keys known now, with every key known before in this Protégé session: what OntoBoard
     * writes is redacted against these, so a key replaced, forgotten or deleted in Settings stays
     * out of a conversation or a role that holds it (1.145.0, review 1.144.0 privacy-01).
     */
    public static Collection<String> everKnown(List<Connection> connections,
            Map<String, String> environment, Function<String, String> remembered) {
        return de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys.seen(knownKeys(
                connections, environment, remembered,
                de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys::get));
    }

    /** Keeps the keys known now known, before Settings replaces, forgets or deletes one. */
    public static void noteKnownKeys(List<Connection> connections,
            Map<String, String> environment, Function<String, String> remembered) {
        de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys.note(knownKeys(connections,
                environment, remembered,
                de.fizkarlsruhe.ise.ontoboard.assistant.session.SessionKeys::get));
    }
}
