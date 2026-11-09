package de.fizkarlsruhe.ise.ontoboard.assistant.roles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three roles, editable, and the rules OntoBoard adds to every one of them (spec D4). */
class RoleLibraryTest {

    /** In memory, as the project folder and the preferences both behave. */
    static final class MemoryStore implements RoleStore {
        final Map<String, String> texts = new LinkedHashMap<String, String>();

        @Override
        public Map<String, String> load() {
            return new LinkedHashMap<String, String>(texts);
        }

        @Override
        public void save(String id, String text) {
            texts.put(id, text);
        }

        @Override
        public void remove(String id) {
            texts.remove(id);
        }

        /** As a folder on Windows and macOS when true; as the preferences or Linux when false. */
        boolean ignoresCase;

        @Override
        public boolean ignoresCase() {
            return ignoresCase;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final RoleLibrary library = new RoleLibrary(store);

    @Test
    void threeBuiltInsComeFirst() throws IOException {
        store.save("aaa-custom", "# Aaa custom\nBe brief.");

        List<Role> roles = library.all();

        assertEquals(Arrays.asList("ontology-expert", "knowledge-engineer", "domain-expert"),
                Arrays.asList(roles.get(0).getId(), roles.get(1).getId(), roles.get(2).getId()));
        assertEquals("Ontology expert", roles.get(0).getName());
        assertTrue(roles.get(0).isBuiltIn());
        assertEquals("aaa-custom", roles.get(3).getId());
        assertFalse(roles.get(3).isBuiltIn());
    }

    @Test
    void anEditIsStoredAndResetRestoresTheShippedText() throws IOException {
        library.edit("ontology-expert", "# Ontology expert\nOnly BFO, please.");

        Role edited = library.get("ontology-expert");
        assertEquals("# Ontology expert\nOnly BFO, please.", edited.getText());
        assertTrue(edited.isEdited());

        library.reset("ontology-expert");

        Role reset = library.get("ontology-expert");
        assertEquals(RoleLibrary.shipped("ontology-expert"), reset.getText());
        assertFalse(reset.isEdited());
    }

    @Test
    void aRoleOver8000CharactersIsRefusedWithItsLength() {
        StringBuilder long8001 = new StringBuilder("# Long\n");
        while (long8001.length() < 8001) {
            long8001.append('x');
        }

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> library.edit("ontology-expert", long8001.toString()));

        assertEquals("A role can be at most 8,000 characters; this one is 8,001.",
                refused.getMessage());
    }

    @Test
    void customRolesAreSlugged() throws IOException {
        Role created = library.create("Materials expert", "You know steel.");

        assertEquals("materials-expert", created.getId());
        assertEquals("Materials expert", created.getName());
        assertTrue(store.texts.get("materials-expert").startsWith("# Materials expert\n"));
        assertThrows(IllegalArgumentException.class,
                () -> library.create("Ontology expert", "a built-in's id"));
        assertThrows(IllegalArgumentException.class, () -> library.delete("domain-expert"));
    }

    /**
     * Review 1.148.0 ui-2: "New role…" named Reviewer, where the team committed Reviewer.md, was
     * not refused: on Windows and macOS the new reviewer.md was the team's file, written over.
     */
    @Test
    void aNewRoleNamedAsARoleFileThatDiffersOnlyInCaseIsRefused(@TempDir File dir)
            throws IOException {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza.owl"));
        File roles = new File(project.getDirectory(), "roles");
        assertTrue(roles.mkdirs());
        File team = new File(roles, "Reviewer.md");
        String committed = "# Reviewer\n\nThe team's reviewer, committed.";
        Files.write(team.toPath(), committed.getBytes(StandardCharsets.UTF_8));
        RoleLibrary folder = new RoleLibrary(project.roles(Collections::<String>emptyList));

        assertThrows(IllegalArgumentException.class, () -> folder.create("Reviewer",
                "Describe who the assistant is and how it should help."));

        assertEquals(committed, new String(Files.readAllBytes(team.toPath()),
                StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("Reviewer.md"), Arrays.asList(roles.list()));
    }

    /**
     * Review 1.150.0 ui-3: only file names were compared, so New role "Reviewer" was accepted
     * where rev.md, headed "# Reviewer", already showed a Reviewer in the list, and the list then
     * held two roles of one name. A name any role already has is refused, in any capitals: a
     * role in a file named by hand, and a built-in whose heading was edited.
     */
    @Test
    void aNewRoleNamedAsARoleInAFileNamedOtherwiseIsRefused(@TempDir File dir)
            throws IOException {
        ProjectFolder project = ProjectFolder.of(new File(dir, "pizza.owl"));
        File roles = new File(project.getDirectory(), "roles");
        assertTrue(roles.mkdirs());
        File rev = new File(roles, "rev.md");
        String committed = "# Reviewer\n\nThe team's reviewer, in a file named by hand.";
        Files.write(rev.toPath(), committed.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(roles, "ontology-expert.md").toPath(),
                "# BFO expert\n\nThe built-in, its heading edited.".getBytes(
                        StandardCharsets.UTF_8));
        RoleLibrary folder = new RoleLibrary(project.roles(Collections::<String>emptyList));
        String text = "Describe who the assistant is and how it should help.";

        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> folder.create("Reviewer", text));
        assertThrows(IllegalArgumentException.class, () -> folder.create(" REVIEWER ", text));
        assertThrows(IllegalArgumentException.class, () -> folder.create("bfo Expert", text));

        assertEquals("There is already a role called \"Reviewer\".", same.getMessage());
        String[] left = roles.list();
        Arrays.sort(left);
        assertEquals(Arrays.asList("ontology-expert.md", "rev.md"), Arrays.asList(left));
        assertEquals(committed, new String(Files.readAllBytes(rev.toPath()),
                StandardCharsets.UTF_8));
        assertEquals("reviewer-2", folder.create("Reviewer 2", text).getId(), "another name");
    }

    /**
     * Review 1.150.0 ui-2: two roles of one name came in the order the store read them - from a
     * folder reviewer-2.md before reviewer.md - and the list numbers the roles of one name in
     * its order. They come in the order of their ids: 1 is reviewer.md, 2 is reviewer-2.md.
     */
    @Test
    void rolesOfOneNameAreListedInTheOrderOfTheirIds() throws IOException {
        store.save("reviewer-2", "# Reviewer\n\nThe second.");
        store.save("reviewer", "# Reviewer\n\nThe first.");
        store.save("aaa", "# Aaa\n\nBefore both.");

        List<Role> roles = library.all();

        assertEquals(Arrays.asList("aaa", "reviewer", "reviewer-2"), Arrays.asList(
                roles.get(3).getId(), roles.get(4).getId(), roles.get(5).getId()));
    }

    /** Review 1.148.0 ui-2: a role is found by its id whatever the case of its file's name. */
    @Test
    void aRoleIsFoundByItsIdInAnyCase() throws IOException {
        store.ignoresCase = true;
        store.save("Reviewer", "# Reviewer\n\nThe team's reviewer.");

        Role found = library.get("reviewer");

        assertNotNull(found);
        assertEquals("Reviewer", found.getId());
    }

    /**
     * Review 1.149.0 ui-2: on Linux, and in the preferences, Reviewer.md and reviewer.md are two
     * roles, and a text typed for one was saved over the other - a team's committed role. A role
     * in other capitals is the same one only where the store says two such names are one.
     */
    @Test
    void aRoleInOtherCapitalsIsAnotherRoleWhereTheStoreKeepsCase() throws IOException {
        store.ignoresCase = false;
        store.save("Reviewer", "# Reviewer\n\nThe team's reviewer.");

        assertEquals(null, library.get("reviewer"));
        assertEquals("Reviewer", library.get("Reviewer").getId());
    }

    @Test
    void theDomainExpertNamesTheField() throws IOException {
        Role domain = library.get("domain-expert");

        assertTrue(RoleLibrary.render(domain, "materials science").contains("materials science"));
        assertTrue(RoleLibrary.render(domain, "").contains("the person's field"));
        assertFalse(RoleLibrary.render(domain, "x").contains("{field}"));
    }

    @Test
    void noShippedTextContainsAMavenPlaceholder() {
        for (String id : RoleLibrary.BUILT_IN) {
            String text = RoleLibrary.shipped(id);
            assertNotNull(text, id);
            assertTrue(text.length() < 2500, id + " is " + text.length() + " characters");
            assertFalse(text.contains("${"), id);
        }
        assertFalse(FixedRules.text().contains("${"));
    }

    @Test
    void theFixedRulesNameTheNeedFence() {
        String rules = FixedRules.text();

        assertTrue(rules.contains("```ontoboard-need"), rules);
        assertTrue(rules.contains("Never write an IRI you did not see in the context."), rules);
        assertTrue(rules.contains("I don't know"), rules);
    }
}
