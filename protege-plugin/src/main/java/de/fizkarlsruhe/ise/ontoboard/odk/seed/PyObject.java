package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An instance of one of ODK's dataclasses - {@code OntologyProject}, {@code ImportGroup},
 * {@code ImportProduct} and the rest - as the templates see it.
 *
 * <p>Fields in declaration order, each holding a Python value ({@link Py}); a field may hold
 * {@link Py#NONE}, which is not the same as the field not being there. ODK also hangs a few
 * attributes on its objects after the fact ({@code config_hash}, {@code special_products},
 * {@code released_products}); those are {@linkplain #setDynamic dynamic}: a template reads them
 * like any other, but {@code odk.py export-project} does not dump them, and neither does
 * {@code OdkProjectModel.toPlain}.
 */
public final class PyObject {

    private final String type;
    private final Map<String, Object> fields = new LinkedHashMap<String, Object>();
    private final Map<String, Object> dynamic = new LinkedHashMap<String, Object>();

    public PyObject(String type) {
        this.type = type;
    }

    /** The dataclass's name, for messages. */
    public String type() {
        return type;
    }

    /** Sets a declared field. */
    public PyObject set(String name, Object value) {
        fields.put(name, checked(name, value));
        return this;
    }

    /** Sets an attribute ODK adds to the object outside its declaration. */
    public PyObject setDynamic(String name, Object value) {
        dynamic.put(name, checked(name, value));
        return this;
    }

    /** Whether the object has this attribute at all, declared or dynamic. */
    public boolean has(String name) {
        return fields.containsKey(name) || dynamic.containsKey(name);
    }

    /** The attribute's value, or {@code null} when the object has no such attribute. */
    public Object get(String name) {
        return fields.containsKey(name) ? fields.get(name) : dynamic.get(name);
    }

    /** The declared fields, in declaration order. */
    public Set<String> fields() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    private Object checked(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(type + "." + name + " was given Java's null; a "
                    + "field that holds nothing holds Py.NONE.");
        }
        return value;
    }

    @Override
    public String toString() {
        return type + fields.keySet();
    }
}
