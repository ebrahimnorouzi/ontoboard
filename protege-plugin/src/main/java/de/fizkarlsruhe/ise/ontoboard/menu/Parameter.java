package de.fizkarlsruhe.ise.ontoboard.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing a user can set before an operation runs, described well enough to build a form from.
 *
 * <p>The point of describing a parameter as data rather than writing a dialog per operation is the
 * help text. ROBOT has twenty-odd operations with a hundred options between them, and every one of
 * them needs a sentence explaining what it does in terms a domain expert recognises - not a
 * restatement of its own name. A hand-written dialog gets that sentence right for the first two
 * operations and then stops; a required field on a descriptor does not let you skip it.
 *
 * <p>{@link #getHelp()} is therefore mandatory and validated: a help text that merely repeats the
 * label is rejected at construction, because "the reasoner to use" for a parameter called
 * "Reasoner" is exactly the non-explanation this exists to prevent.
 */
public final class Parameter {

    /** What kind of control to draw, and how to read what was typed. */
    public enum Kind {
        /** One of a fixed set. Rendered as a dropdown. */
        CHOICE,
        /** On or off. Rendered as a checkbox. */
        FLAG,
        /** Free text. */
        TEXT,
        /**
         * Free text over several lines, where each line means something.
         *
         * <p>A term list is the case: forty IRIs, one per line, pasted out of a spreadsheet. A
         * single-line field would turn that into one unusable line, and asking a user to save a
         * file first to import ten terms is a worse tool than the command line they are trying to
         * avoid.
         */
        MULTILINE,
        /** A whole number, validated. */
        NUMBER,
        /** A file to read or write, with a chooser. */
        FILE
    }

    private final String key;
    private final String label;
    private final Kind kind;
    private final String help;
    private final String defaultValue;
    private final List<String> choices;
    private final boolean required;

    private Parameter(Builder builder) {
        this.key = builder.key;
        this.label = builder.label;
        this.kind = builder.kind;
        this.help = builder.help;
        this.defaultValue = builder.defaultValue;
        this.choices = Collections.unmodifiableList(new ArrayList<String>(builder.choices));
        this.required = builder.required;
    }

    public static Builder of(String key, String label, Kind kind) {
        return new Builder(key, label, kind);
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public Kind getKind() {
        return kind;
    }

    /** The sentence behind the "?". Never blank, never a restatement of the label. */
    public String getHelp() {
        return help;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    /** The permitted values for a {@link Kind#CHOICE}; empty otherwise. */
    public List<String> getChoices() {
        return choices;
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Why {@code value} is not acceptable, or null when it is.
     *
     * <p>Returns a sentence naming this parameter, because a validation message that says only
     * "invalid input" leaves a user with six fields and no idea which one.
     */
    public String reject(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return required ? label + " is required." : null;
        }
        switch (kind) {
            case CHOICE:
                return choices.contains(trimmed) ? null
                        : label + " must be one of " + choices + ", not '" + trimmed + "'.";
            case NUMBER:
                try {
                    Long.parseLong(trimmed);
                    return null;
                } catch (NumberFormatException notANumber) {
                    return label + " must be a whole number, not '" + trimmed + "'.";
                }
            case FLAG:
                return "true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)
                        ? null : label + " must be true or false.";
            case FILE:
            case TEXT:
            case MULTILINE:
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return key + " (" + kind + ")";
    }

    /** Builds a parameter. */
    public static final class Builder {
        private final String key;
        private final String label;
        private final Kind kind;
        private final List<String> choices = new ArrayList<String>();
        private String help = "";
        private String defaultValue = "";
        private boolean required;

        private Builder(String key, String label, Kind kind) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("a parameter needs a key");
            }
            if (label == null || label.trim().isEmpty()) {
                throw new IllegalArgumentException("a parameter needs a label: " + key);
            }
            this.key = key.trim();
            this.label = label.trim();
            this.kind = kind;
        }

        /**
         * The sentence a user reads when they press "?".
         *
         * @throws IllegalArgumentException if it is missing, trivially short, or a restatement of
         *     the label - all three are the non-explanation this class exists to prevent
         */
        public Builder help(String text) {
            String trimmed = text == null ? "" : text.trim();
            if (trimmed.length() < 25) {
                throw new IllegalArgumentException("the help for '" + label
                        + "' must actually explain the parameter; got: '" + trimmed + "'");
            }
            if (trimmed.toLowerCase().replace("the ", "").startsWith(label.toLowerCase())
                    && trimmed.length() < label.length() + 30) {
                throw new IllegalArgumentException("the help for '" + label
                        + "' just restates its label; say what it does and when to change it");
            }
            this.help = trimmed;
            return this;
        }

        public Builder defaultValue(String value) {
            this.defaultValue = value == null ? "" : value;
            return this;
        }

        public Builder choices(String... values) {
            choices.clear();
            Collections.addAll(choices, values);
            return this;
        }

        public Builder required() {
            this.required = true;
            return this;
        }

        public Parameter build() {
            if (help.isEmpty()) {
                throw new IllegalArgumentException("parameter '" + label
                        + "' has no help text, and a form built from it would offer a control "
                        + "nobody can interpret");
            }
            if (kind == Kind.CHOICE && choices.isEmpty()) {
                throw new IllegalArgumentException("choice parameter '" + label
                        + "' offers nothing to choose from");
            }
            if (kind == Kind.CHOICE && !defaultValue.isEmpty()
                    && !choices.contains(defaultValue)) {
                throw new IllegalArgumentException("the default for '" + label + "' ("
                        + defaultValue + ") is not one of its choices " + choices);
            }
            return new Parameter(this);
        }
    }

    /** Defaults for a whole set, as the starting point for a form. */
    public static Map<String, String> defaultsOf(List<Parameter> parameters) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Parameter parameter : parameters) {
            values.put(parameter.getKey(), parameter.getDefaultValue());
        }
        return values;
    }

    /**
     * Every reason the given values are unacceptable, in parameter order.
     *
     * <p>All of them, not the first: a form that reports one problem per attempt makes a user
     * discover six mistakes in six rounds.
     */
    public static List<String> rejections(List<Parameter> parameters, Map<String, String> values) {
        List<String> problems = new ArrayList<String>();
        for (Parameter parameter : parameters) {
            String problem = parameter.reject(
                    values == null ? null : values.get(parameter.getKey()));
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }
}
