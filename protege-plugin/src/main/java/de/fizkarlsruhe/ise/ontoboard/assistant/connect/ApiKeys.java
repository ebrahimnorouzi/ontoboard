package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.util.Map;

/**
 * Where the key for one call comes from (spec D2), in this order:
 *
 * <ol>
 *   <li>the key typed in this Protégé session, held in memory only - the default;
 *   <li>the environment variable the connection names ({@code ANTHROPIC_API_KEY},
 *       {@code OPENAI_API_KEY}, or one the person entered);
 *   <li>a key remembered on this computer, which exists only if the person ticked the box that
 *       says where it is stored.
 * </ol>
 *
 * <p>Every value is trimmed: a key pasted from a web page arrives with a newline more often than
 * not, and the server's answer to that is a 401 that names nothing.
 */
public final class ApiKeys {

    /** Which of the three places the key came from, or none. */
    public enum Source {
        SESSION,
        ENVIRONMENT,
        REMEMBERED,
        NONE
    }

    /** A key and where it came from. */
    public static final class Resolved {
        private final String key;
        private final Source source;
        private final String variable;

        Resolved(String key, Source source, String variable) {
            this.key = key;
            this.source = source;
            this.variable = variable;
        }

        /** Empty when there is none. */
        public String getKey() {
            return key;
        }

        public Source getSource() {
            return source;
        }

        /** The environment variable read, when the key came from one; otherwise empty. */
        public String getVariable() {
            return variable;
        }
    }

    private ApiKeys() {
    }

    public static Resolved resolve(Connection connection, String sessionKey,
            Map<String, String> environment, String rememberedKey) {
        String typed = trimmed(sessionKey);
        if (!typed.isEmpty()) {
            return new Resolved(typed, Source.SESSION, "");
        }
        String variable = connection == null ? "" : connection.getKeyVariable();
        if (!variable.isEmpty() && environment != null) {
            String fromEnvironment = trimmed(environment.get(variable));
            if (!fromEnvironment.isEmpty()) {
                return new Resolved(fromEnvironment, Source.ENVIRONMENT, variable);
            }
        }
        String remembered = trimmed(rememberedKey);
        if (!remembered.isEmpty()) {
            return new Resolved(remembered, Source.REMEMBERED, "");
        }
        return new Resolved("", Source.NONE, "");
    }

    private static String trimmed(String value) {
        return clean(value);
    }

    /**
     * The key with every whitespace, control and invisible formatting character taken out. No API
     * key contains one, and a pasted key often does: a newline from a terminal, a no-break or
     * zero-width space from a web page. A line break also matters for safety - the JDK refuses
     * such a header value with an exception that quotes the whole value, which is the key.
     */
    public static String clean(String key) {
        if (key == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            int type = Character.getType(c);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || Character.isSpaceChar(c) || type == Character.FORMAT) {
                continue;
            }
            kept.append(c);
        }
        return kept.toString();
    }
}
