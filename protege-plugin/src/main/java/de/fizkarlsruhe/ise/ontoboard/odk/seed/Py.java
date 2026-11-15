package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Python's rules for the values ODK's templates work on, in one place.
 *
 * <p>The values: {@code String} (str), {@code Long} (int), {@code Double} (float),
 * {@code Boolean} (bool), {@code List<Object>} (list), {@code Map<String, Object>} (dict),
 * {@link PyObject} (a dataclass instance), {@link #NONE} and {@link PyUndefined}. Java's
 * {@code null} is never one of them.
 *
 * <p>Every rule here is one Jinja2 3.1.6 applies when ODK renders, and none is a matter of taste:
 * each decides bytes of a seeded file. They are checked one by one in {@code PyTest} and all
 * together by comparing rendered projects with ODK's own.
 *
 * <p>What Python would refuse with a TypeError, and Jinja2 with an UndefinedError, is refused
 * here with an {@code IllegalStateException}; the interpreter adds the template and the line.
 */
public final class Py {

    /** Python's {@code None}. */
    public static final PyNone NONE = PyNone.INSTANCE;

    private Py() {
    }

    /** A value that is not there, remembering what was asked for. */
    public static PyUndefined undefined(String hint) {
        return new PyUndefined(hint);
    }

    /** {@code bool(value)}. */
    public static boolean truthy(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof Long) {
            return (Long) value != 0L;
        }
        if (value instanceof Double) {
            return (Double) value != 0.0;
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof PyNone || value instanceof PyUndefined) {
            return false;
        }
        if (value instanceof PyObject) {
            return true;
        }
        throw notAValue(value);
    }

    /** {@code str(value)}: what {@code {{ value }}} writes. */
    public static String str(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof PyUndefined) {
            return "";
        }
        return repr(value, false);
    }

    /** {@code repr(value)} for what a list or dict holds, {@code str} for the rest. */
    private static String repr(Object value, boolean nested) {
        if (value instanceof String) {
            String text = (String) value;
            if (!nested) {
                return text;
            }
            // Python prefers single quotes, and switches to double when the text has a single
            // quote and no double one.
            boolean doubleQuoted = text.indexOf('\'') >= 0 && text.indexOf('"') < 0;
            char quote = doubleQuoted ? '"' : '\'';
            StringBuilder out = new StringBuilder().append(quote);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' || c == quote) {
                    out.append('\\').append(c);
                } else if (c == '\n') {
                    out.append("\\n");
                } else if (c == '\t') {
                    out.append("\\t");
                } else if (c == '\r') {
                    out.append("\\r");
                } else {
                    out.append(c);
                }
            }
            return out.append(quote).toString();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "True" : "False";
        }
        if (value instanceof Long) {
            return Long.toString((Long) value);
        }
        if (value instanceof Double) {
            double number = (Double) value;
            if (number == Math.rint(number) && !Double.isInfinite(number)
                    && Math.abs(number) < 1e16) {
                return Long.toString((long) number) + ".0";
            }
            return Double.toString(number);
        }
        if (value instanceof PyNone) {
            return "None";
        }
        if (value instanceof PyUndefined) {
            return "Undefined";
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (List<?>) value) {
                out.append(out.length() == 1 ? "" : ", ").append(repr(item, true));
            }
            return out.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.append(out.length() == 1 ? "" : ", ").append(repr(entry.getKey(), true))
                        .append(": ").append(repr(entry.getValue(), true));
            }
            return out.append('}').toString();
        }
        if (value instanceof PyObject) {
            // A dataclass's repr lists its fields; nothing ODK renders prints one, and guessing
            // at it would be writing bytes nobody has compared.
            throw new IllegalStateException("An object of " + ((PyObject) value).type()
                    + " would be printed; ODK's templates never print one.");
        }
        throw notAValue(value);
    }

    /** {@code a == b}. */
    public static boolean eq(Object a, Object b) {
        if (a instanceof PyUndefined || b instanceof PyUndefined) {
            // Jinja2: an Undefined equals only another Undefined.
            return a instanceof PyUndefined && b instanceof PyUndefined;
        }
        if (a instanceof PyNone || b instanceof PyNone) {
            return a instanceof PyNone && b instanceof PyNone;
        }
        if (isNumber(a) && isNumber(b)) {
            // bool is an int in Python: True == 1.
            return number(a) == number(b);
        }
        if (a instanceof String || b instanceof String) {
            return a instanceof String && a.equals(b);
        }
        if (a instanceof List && b instanceof List) {
            List<?> left = (List<?>) a;
            List<?> right = (List<?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!eq(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> left = (Map<?, ?>) a;
            Map<?, ?> right = (Map<?, ?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey())
                        || !eq(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a == b;
    }

    private static boolean isNumber(Object value) {
        return value instanceof Long || value instanceof Double || value instanceof Boolean;
    }

    private static double number(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value ? 1.0 : 0.0;
        }
        return ((Number) value).doubleValue();
    }

    /** {@code item in container}. */
    public static boolean contains(Object container, Object item) {
        if (container instanceof String) {
            if (!(item instanceof String)) {
                throw new IllegalStateException("'in <string>' needs a string on the left, got "
                        + describe(item));
            }
            return ((String) container).contains((String) item);
        }
        if (container instanceof List) {
            for (Object candidate : (List<?>) container) {
                if (eq(candidate, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof Map) {
            for (Object key : ((Map<?, ?>) container).keySet()) {
                if (eq(key, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof PyUndefined) {
            // Iterates as empty, so nothing is in it.
            return false;
        }
        throw new IllegalStateException("'in' needs a list, a string or a dict on the right, got "
                + describe(container));
    }

    /**
     * {@code base.name}, as Jinja2 resolves it: the attribute, else the item, else Undefined.
     *
     * <p>So a dict answers to a dot, and an attribute of {@code None}, of a string or of a number
     * is Undefined rather than an error - but an attribute of Undefined raises.
     */
    public static Object attr(Object base, String name) {
        if (base instanceof PyUndefined) {
            throw new IllegalStateException("'" + ((PyUndefined) base).hint() + "' is undefined, so "
                    + "it has no attribute '" + name + "'");
        }
        if (base instanceof PyObject) {
            Object value = ((PyObject) base).get(name);
            return value != null ? value : undefined(((PyObject) base).type() + "." + name);
        }
        if (base instanceof Map) {
            Object value = ((Map<?, ?>) base).get(name);
            return value != null ? value : undefined(name);
        }
        if (base == null) {
            throw notAValue(null);
        }
        return undefined(describe(base) + "." + name);
    }

    /** {@code base[key]}, as Jinja2 resolves it: the item, else the attribute, else Undefined. */
    public static Object item(Object base, Object key) {
        if (base instanceof PyUndefined) {
            throw new IllegalStateException("'" + ((PyUndefined) base).hint() + "' is undefined, so "
                    + "it has no item " + str(key));
        }
        if (base instanceof Map && key instanceof String) {
            Object value = ((Map<?, ?>) base).get(key);
            return value != null ? value : undefined(String.valueOf(key));
        }
        if (base instanceof List && key instanceof Long) {
            List<?> list = (List<?>) base;
            long index = (Long) key < 0 ? list.size() + (Long) key : (Long) key;
            return index >= 0 && index < list.size() ? list.get((int) index)
                    : undefined("[" + key + "]");
        }
        if (key instanceof String) {
            return attr(base, (String) key);
        }
        return undefined(describe(base) + "[" + str(key) + "]");
    }

    /** What {@code for x in value} goes through. */
    @SuppressWarnings("unchecked")
    public static Iterable<Object> iterate(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        if (value instanceof PyUndefined) {
            return Collections.emptyList();
        }
        if (value instanceof Map) {
            return new ArrayList<Object>(((Map<String, Object>) value).keySet());
        }
        if (value instanceof String) {
            List<Object> characters = new ArrayList<Object>();
            String text = (String) value;
            for (int i = 0; i < text.length(); ) {
                int point = text.codePointAt(i);
                characters.add(new String(Character.toChars(point)));
                i += Character.charCount(point);
            }
            return characters;
        }
        throw new IllegalStateException(describe(value) + " cannot be iterated over");
    }

    /** What a value is, for a message: "None", "a string", "an ImportGroup". */
    public static String describe(Object value) {
        if (value instanceof PyNone) {
            return "None";
        }
        if (value instanceof PyUndefined) {
            return "undefined";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof Long || value instanceof Double) {
            return "a number";
        }
        if (value instanceof List) {
            return "a list";
        }
        if (value instanceof Map) {
            return "a dict";
        }
        if (value instanceof PyObject) {
            return "an object of " + ((PyObject) value).type();
        }
        return String.valueOf(value);
    }

    private static IllegalStateException notAValue(Object value) {
        return new IllegalStateException((value == null ? "Java's null"
                : "A " + value.getClass().getName()) + " is not one of the template values; "
                + "None is Py.NONE, an integer a Long.");
    }
}
