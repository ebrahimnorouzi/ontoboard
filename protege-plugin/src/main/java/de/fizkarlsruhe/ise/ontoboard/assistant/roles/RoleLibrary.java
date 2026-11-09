package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The roles the assistant can take (spec D4): three shipped with OntoBoard, each editable and
 * resettable, and any the person or the team adds.
 *
 * <p>A role's text is capped at {@value #MAX_LENGTH} characters, because a Java preference value
 * holds at most 8,192 and an ontology without a file keeps its roles there. A role is
 * instructions, not a manual; 8,000 characters is about two thousand tokens.
 */
public final class RoleLibrary {

    public static final int MAX_LENGTH = 8000;

    public static final List<String> BUILT_IN = Collections.unmodifiableList(
            Arrays.asList("ontology-expert", "knowledge-engineer", "domain-expert"));

    /** What a domain-expert role says when the person has not named their field. */
    static final String NO_FIELD = "the person's field";

    private final RoleStore store;

    public RoleLibrary(RoleStore store) {
        this.store = store;
    }

    /** The shipped text of a built-in role, or null for any other id. */
    public static String shipped(String id) {
        if (!BUILT_IN.contains(id)) {
            return null;
        }
        String text = Resources.text("/assistant/roles/" + id + ".md");
        return text == null ? null : text.trim();
    }

    /** The role's text with {@code {field}} filled in. */
    public static String render(Role role, String field) {
        String named = field == null || field.trim().isEmpty() ? NO_FIELD : field.trim();
        return role.getText().replace("{field}", named);
    }

    /**
     * Built-ins first, in their order; then custom roles by name, and roles of one name by id.
     * Roles of one name came in the order the store read them - from a folder reviewer-2.md
     * before reviewer.md - and the list numbers them in its order, so 1 was reviewer-2.md
     * (1.151.0, review 1.150.0 ui-2).
     */
    public List<Role> all() throws IOException {
        Map<String, String> stored = store.load();
        List<Role> roles = new ArrayList<Role>();
        for (String id : BUILT_IN) {
            roles.add(builtIn(id, stored.get(id)));
        }
        List<Role> custom = new ArrayList<Role>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (!BUILT_IN.contains(entry.getKey())) {
                custom.add(new Role(entry.getKey(), nameOf(entry.getValue(), entry.getKey()),
                        entry.getValue(), false, false));
            }
        }
        Collections.sort(custom, new Comparator<Role>() {
            @Override
            public int compare(Role a, Role b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getId().compareTo(b.getId());
            }
        });
        roles.addAll(custom);
        return roles;
    }

    /**
     * The role with this id, or null; one whose id differs only in case when none has this very
     * id. A team's Reviewer.md is the role "reviewer" chosen elsewhere: Windows and macOS open
     * that file for reviewer.md, so a role looked up by its exact id was not found there and was
     * then written over it (1.149.0, review 1.148.0 ui-2). Only where the store says two such
     * names are one: on Linux, and in the preferences, they are two roles, and a text typed for
     * one went over the other (1.150.0, review 1.149.0 ui-2).
     */
    public Role get(String id) throws IOException {
        return find(all(), id, store.ignoresCase());
    }

    /** Whether a role in other capitals is the same one in this library's store. */
    public boolean ignoresCase() {
        return store.ignoresCase();
    }

    /**
     * The role with this id in {@code roles}: the same id first, else, when ignoring case, the
     * first whose id differs only in case; null when there is none.
     */
    public static Role find(List<Role> roles, String id, boolean ignoringCase) {
        Role sameButCase = null;
        for (Role role : roles) {
            if (role.getId().equals(id)) {
                return role;
            }
            if (ignoringCase && sameButCase == null && role.getId().equalsIgnoreCase(id)) {
                sameButCase = role;
            }
        }
        return sameButCase;
    }

    /** Whether two role ids name one role file on Windows and macOS: they differ only in case. */
    public static boolean sameId(String one, String other) {
        return one.equalsIgnoreCase(other);
    }

    /** @throws IllegalArgumentException when the text is over {@value #MAX_LENGTH} characters */
    public void edit(String id, String text) throws IOException {
        checkLength(text);
        store.save(id, text);
    }

    /** A built-in role back to its shipped text. */
    public void reset(String id) throws IOException {
        if (BUILT_IN.contains(id)) {
            store.remove(id);
        }
    }

    /**
     * A new role named {@code name}; its text gets the name as its heading when it has none.
     *
     * @throws IllegalArgumentException for a blank name, a built-in's name, a name in use as a
     *     role's name or its file's in any capitals, or a text over {@value #MAX_LENGTH}
     *     characters
     */
    public Role create(String name, String text) throws IOException {
        String id = slug(name);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A role needs a name.");
        }
        if (BUILT_IN.contains(id)) {
            throw new IllegalArgumentException("\"" + name.trim() + "\" is a built-in role;"
                    + " edit it instead.");
        }
        for (Role role : all()) {
            // The name a person sees in the list and types is the role's heading. Only file
            // names were compared, so "Reviewer" was accepted beside rev.md headed "# Reviewer",
            // or a built-in whose heading was edited, and the list held two roles of one name
            // (1.151.0, review 1.150.0 ui-3).
            if (role.getName().trim().equalsIgnoreCase(name.trim())) {
                throw new IllegalArgumentException("There is already a role called \""
                        + role.getName().trim() + "\".");
            }
            // A file whose name differs only in case is the same file on Windows and macOS: the
            // team's Reviewer.md was written over by a new "Reviewer" (1.149.0, review 1.148.0
            // ui-2).
            if (sameId(role.getId(), id)) {
                throw new IllegalArgumentException("There is already a role called \""
                        + name.trim() + "\".");
            }
        }
        String body = text == null ? "" : text.trim();
        String full = body.startsWith("# ") ? body : "# " + name.trim() + "\n\n" + body;
        checkLength(full);
        store.save(id, full);
        return new Role(id, nameOf(full, id), full, false, false);
    }

    /** @throws IllegalArgumentException for a built-in role, which can only be reset */
    public void delete(String id) throws IOException {
        if (BUILT_IN.contains(id)) {
            throw new IllegalArgumentException("A built-in role can be reset, not deleted.");
        }
        store.remove(id);
    }

    private Role builtIn(String id, String storedText) {
        String shipped = shipped(id);
        String text = storedText == null ? shipped : storedText;
        boolean edited = storedText != null && !storedText.trim().equals(shipped);
        return new Role(id, nameOf(text, id), text, true, edited);
    }

    private static void checkLength(String text) {
        int length = text == null ? 0 : text.length();
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "A role can be at most %,d characters; this one is %,d.", MAX_LENGTH, length));
        }
    }

    /** The text's first "# " heading, or the id when there is none. */
    static String nameOf(String text, String id) {
        if (text != null) {
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    return trimmed.substring(2).trim();
                }
            }
        }
        return id;
    }

    /** "Materials expert" to "materials-expert". */
    static String slug(String name) {
        if (name == null) {
            return "";
        }
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }
}
