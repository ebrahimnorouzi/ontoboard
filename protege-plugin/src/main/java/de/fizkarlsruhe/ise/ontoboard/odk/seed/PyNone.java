package de.fizkarlsruhe.ise.ontoboard.odk.seed;

/**
 * Python's {@code None}: a value, which Java's {@code null} is not.
 *
 * <p>The difference is in the bytes of every seeded Makefile. A config that names no components
 * leaves {@code project.components} holding {@code None} - and {@code None} <em>is defined</em>,
 * so the template's {@code {% if project.components is defined %}} branch is taken and its lines
 * are written. With {@code null} standing for both "holds None" and "no such attribute" that
 * branch cannot be told from the other one. So None is this singleton ({@link Py#NONE}), absence
 * is {@link PyUndefined}, and {@code null} means a bug.
 */
public final class PyNone {

    static final PyNone INSTANCE = new PyNone();

    private PyNone() {
    }

    @Override
    public String toString() {
        return "None";
    }
}
