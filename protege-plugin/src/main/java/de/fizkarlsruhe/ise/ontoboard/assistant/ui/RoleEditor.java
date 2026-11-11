package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import de.fizkarlsruhe.ise.ontoboard.assistant.history.KeyRedactor;
import de.fizkarlsruhe.ise.ontoboard.assistant.project.ProjectFolder;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.Role;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleLibrary;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.SettingsStore;
import de.fizkarlsruhe.ise.ontoboard.canvas.WrapLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The role (spec D4): which one the assistant takes, its text to read and edit, the field a
 * domain expert works in, and where an edit is kept - next to the ontology, so the team shares
 * it, or in Protégé's preferences when the ontology has no file yet.
 */
public final class RoleEditor extends JPanel {

    private static final long serialVersionUID = 1L;

    private final SettingsStore store;
    private final Supplier<ProjectFolder> project;
    /** For the keys OntoBoard knows, redacted from a role saved in the project (1.143.0). */
    private final java.util.Map<String, String> environment;
    private final Runnable onChange;
    private final JComboBox<Role> roles = new JComboBox<Role>();
    private final JTextArea text = new JTextArea(10, 40);
    /** A note, not a label: it wraps at the column's width (1.144.0, review 1.143.0 I-4). */
    private final Note count = new Note(" ", Color.GRAY);
    private final Note where = new Note("", java.awt.Color.GRAY);
    private final JTextField field = new JTextField(20);
    private final JButton save = new JButton("Save");
    /**
     * Drops the text typed and shows the role's stored text: a text typed and abandoned came back
     * whenever its role was shown, and only Save, Reset or Delete - each changing the role's
     * shared file - showed the stored text again (1.150.0, review 1.149.0 ui-1).
     */
    private final JButton discard = new JButton("Discard");
    private final JButton reset = new JButton("Reset");
    private final JButton delete = new JButton("Delete");
    private final JButton create = new JButton("New role…");
    /** Asks the new role's name; a dialog, replaced in tests. Null is Cancel. */
    private Supplier<String> nameAsker = () -> JOptionPane.showInputDialog(this,
            "A name for the new role:", "New role", JOptionPane.QUESTION_MESSAGE);
    private boolean loading;
    /**
     * Whether a role in other capitals is the same one where edits go: on Windows and macOS, not
     * on Linux nor in the preferences (1.150.0, review 1.149.0 ui-2). Read with the roles.
     */
    private boolean ignoresCase;
    /** The role whose stored text was put in the editor last: a text unlike it was typed. */
    private Role shown;
    /** Where the line says edits go: the project's key, or "" for Protégé's preferences. */
    private String shownFor = "";
    /**
     * Texts typed and not saved, put aside for the project they were typed for and their role:
     * one waits there while another ontology is active (1.147.0, review 1.146.0 ui-1). One waits
     * per project and role, so a text put aside never replaces another: with one per project the
     * next text put aside replaced the last, and picking another role threw the text typed away
     * (1.149.0, review 1.147.0 ui-3). A role's text comes back whenever the role is shown, so two
     * for one role arise only when Save As takes an ontology into the folder of another whose
     * own text waits on the same role; a list keeps both then, and the later comes back first.
     */
    private final List<Draft> drafts = new ArrayList<Draft>();

    /** A text typed and not saved, put aside for its project and role. */
    private static final class Draft {
        /** The key of the project it was typed for, or "" for Protégé's preferences. */
        final String project;
        /**
         * The role it was typed on, not only its id: a role the project does not hold - one of
         * your own carried by the first save or Save As, or removed by a pull - was not in the
         * list when the project was active again, so the text was never shown again after the
         * line said it is kept (1.148.0, review 1.147.0 ui-1).
         */
        final Role role;
        final String text;
        /**
         * Whether it was typed while the line named another place. Without it the line lost
         * "Where edits go has changed" at the switch, and Save wrote a text written as a private
         * preference edit over the team's committed role (1.148.0, review 1.147.0 ui-4).
         */
        final boolean carried;

        Draft(String project, Role role, String text, boolean carried) {
            this.project = project;
            this.role = role;
            this.text = text;
            this.carried = carried;
        }
    }
    /**
     * Whether the text shown was typed while the line named another place - before the
     * ontology's first save or Save As - and is not saved yet (1.147.0, review 1.146.0 ui-1).
     */
    private boolean carried;
    /**
     * The roles in the list that this place does not hold: one carried here with a text typed,
     * and every one a text put aside here waits on. Save adds the role shown.
     */
    private final List<Role> notHere = new ArrayList<Role>();
    /** What the line adds to where edits go after the last read: the texts typed and kept. */
    private String notes = "";
    /**
     * Whether the last read of the roles failed: Settings reads them again when it is shown,
     * where it read them again only for another project (1.149.0, review 1.147.0 ui-3).
     */
    private boolean unread;

    public RoleEditor(SettingsStore store, Supplier<ProjectFolder> project, Runnable onChange,
            java.util.Map<String, String> environment) {
        // As wide as the column, with "where" wrapped at it (1.142.0).
        super(new StackLayout(4));
        this.store = store;
        this.project = project;
        this.onChange = onChange;
        this.environment = environment;
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        // The list names a role as the line does: with its file when another has its name
        // (1.150.0, review 1.149.0 ui-3).
        roles.setRenderer(naming(roles.getRenderer()));

        roles.addActionListener(event -> {
            if (!loading && roles.getSelectedItem() != null) {
                Role role = (Role) roles.getSelectedItem();
                if (role == shown && typed()) {
                    // The role shown, picked again: a click on its row in the open list, or its
                    // first letter typed on the list. JComboBox fires an action for every pick,
                    // and the text typed - or one just brought back, with its "Where edits go
                    // has changed" - was replaced without a word (1.149.0, review 1.148.0 ui-1).
                    return;
                }
                // Another role picked: the text typed waits for its role here, and the line says
                // so. It was replaced by the role picked, without a word (1.149.0, review
                // 1.147.0 ui-3).
                putAside();
                store.setRoleId(role.getId());
                show(role);
                // A text typed for this role here and put aside: picked to go on with it.
                restoreDraft();
                where.setText(line() + waitingHere());
                onChange.run();
            }
        });
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                counted();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                counted();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                counted();
            }
        });
        save.addActionListener(event -> saveText());
        discard.addActionListener(event -> discardText());
        reset.addActionListener(event -> resetRole());
        delete.addActionListener(event -> deleteRole());
        create.addActionListener(event -> createRole());
        field.setText(store.field());
        field.setToolTipText("The domain expert role works in this field, e.g. materials"
                + " science.");
        field.addActionListener(event -> saveField());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                saveField();
            }
        });

        JPanel top = new JPanel(new LabelledRowLayout(new int[] {0}));
        top.add(new JLabel("Role"));
        top.add(roles);
        JPanel newRole = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        newRole.add(create);
        JPanel bottom = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        bottom.add(save);
        bottom.add(discard);
        bottom.add(reset);
        bottom.add(delete);
        add(top);
        add(newRole);
        // A row of its own: in the wrapping row above, the field (20 columns) was wider than a
        // 300 px column with a larger font (1.144.0, review 1.143.0 I-4).
        JPanel yourField = new JPanel(new LabelledRowLayout(new int[] {0}));
        yourField.add(new JLabel("Your field"));
        yourField.add(field);
        add(yourField);
        add(new JScrollPane(text));
        add(bottom);
        add(count);
        add(where);
        reload();
    }

    private RoleLibrary library(ProjectFolder folder) {
        return new RoleLibrary(folder != null ? folder.roles(() -> KeyRedactor.everKnown(
                store.connections(), environment, store::rememberedKey))
                : store.roles());
    }

    private static String keyOf(ProjectFolder folder) {
        return folder == null ? "" : folder.key();
    }

    /**
     * Where edits go for a project's key, naming the ontology's folder: every project's line
     * read "assistant/roles/ next to the ontology", so another ontology becoming active changed
     * nothing on it (1.147.0, review 1.146.0 ui-1).
     */
    private static String whereLine(String key) {
        if (key.isEmpty()) {
            return "Edits are saved for you in Protégé's preferences: the ontology has no file"
                    + " yet.";
        }
        File folder = new File(key);
        return "Edits are saved in " + folder.getName() + "/roles/ next to the ontology, in "
                + folder.getParent() + ", and shared through git.";
    }

    /** The line: where edits go, and what that means for a text carried here. */
    private String line() {
        return (carried ? "Where edits go has changed. " : "") + whereLine(shownFor)
                + (shown != null && notHere.contains(shown) ? " " + named(shown)
                        + " is not there yet: Save adds it." : "");
    }

    /**
     * A role as the list and the line name it: with its file - its id while the ontology has no
     * file, as a role in Protégé's preferences has none - when another listed role has the very
     * same name. Named alone, a text waiting on one of two roles named Reviewer was said to wait
     * on "Reviewer" while the other one was shown, and picking the role shown changed nothing
     * (1.150.0, review 1.149.0 ui-3). Names that differ in case already read differently.
     */
    private String named(Role role) {
        for (int i = 0; i < roles.getItemCount(); i++) {
            Role other = roles.getItemAt(i);
            if (!other.getId().equals(role.getId()) && other.getName().equals(role.getName())) {
                return withFile(role);
            }
        }
        return role.getName();
    }

    /** A role with its file, or its id while the ontology has no file: "Reviewer (reviewer.md)". */
    private String withFile(Role role) {
        return role.getName() + " (" + role.getId() + (shownFor.isEmpty() ? "" : ".md") + ")";
    }

    /**
     * What the list's tooltips say of a role: its file, or its id while the ontology has no file
     * (1.151.0, review 1.150.0 ui-2). Never the heading first: a tooltip starting "<html>" is
     * shown as HTML, and a role's heading is whatever anyone committed.
     */
    private String fileOf(Role role) {
        return shownFor.isEmpty() ? "Id: " + role.getId() + ", in Protégé's preferences"
                : "File: " + role.getId() + ".md";
    }

    /**
     * A role as its row in the list reads: as {@link #named} has it, and, when another listed
     * role has its name, with its place among them first - "2 · Reviewer (reviewer-2.md)". The
     * file after the name was all that told two apart, and a narrow column cuts the end first:
     * at 300 px both read "Materials science reviewer (mat..." (1.151.0, review 1.150.0 ui-2).
     * A number survives any cut; the tooltips name the file, and the line names it as before.
     */
    private String rowName(Role role) {
        int alike = 0;
        int place = 0;
        for (int i = 0; i < roles.getItemCount(); i++) {
            Role other = roles.getItemAt(i);
            if (other.getName().equals(role.getName())) {
                alike++;
                if (other.getId().equals(role.getId())) {
                    place = alike;
                }
            }
        }
        return alike > 1 && place > 0 ? place + " · " + named(role) : named(role);
    }

    /**
     * The list's renderer: the look and feel's own, given each role as {@link #rowName} has it.
     * Each row of the open list names its role's file when pointed at, as the list's own tooltip
     * names the selected one's: the open list is as narrow as the closed one (1.151.0, review
     * 1.150.0 ui-2).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private javax.swing.ListCellRenderer<Role> naming(final javax.swing.ListCellRenderer plain) {
        // A heading is whatever anyone committed: one starting "<html>" was rendered as HTML, in
        // the closed list and in its row, where it may load an image from anywhere (the review of
        // the 1.151.0 ui fix). Set before the label takes any text, as a view is made then.
        if (plain instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) plain).putClientProperty("html.disable", Boolean.TRUE);
        }
        return (list, role, index, selected, focused) -> {
            String name = role == null ? null : rowName(role);
            java.awt.Component row = plain.getListCellRendererComponent(list, name, index,
                    selected, focused);
            if (row instanceof javax.swing.JComponent) {
                javax.swing.JComponent shown = (javax.swing.JComponent) row;
                // "html" is where the look and feel keeps the view it made for a text starting
                // <html>: dropped, the label paints the text as it is. Named, not taken from
                // javax.swing.plaf.basic.BasicHTML: that was a new mandatory OSGi import.
                if (shown.getClientProperty("html.disable") != Boolean.TRUE
                        || shown.getClientProperty("html") != null) {
                    // A component other than the renderer, which took the text unguarded.
                    shown.putClientProperty("html.disable", Boolean.TRUE);
                    shown.putClientProperty("html", null);
                }
                shown.setToolTipText(role == null ? null : fileOf(role));
            }
            return row;
        };
    }

    /** Whether the editor holds a text typed and not saved: unlike the shown role's. */
    private boolean typed() {
        return shown != null && !text.getText().equals(textOf(shown));
    }

    /** Where the line says edits go: the project's key, or "" for Protégé's preferences. */
    String shownFor() {
        return shownFor;
    }

    /** Whether the last read of the roles failed, so that Settings reads them again. */
    boolean readFailed() {
        return unread;
    }

    /**
     * Puts the text typed aside for the role shown here, when one was typed.
     *
     * @return whether one was
     */
    private boolean putAside() {
        if (!typed()) {
            return false;
        }
        drafts.add(new Draft(shownFor, shown, text.getText(), carried));
        return true;
    }

    /**
     * Reads the roles again: the same ontology's file may have changed, by its first save or
     * Save As. A text typed and not saved stays, on the same role, with Save enabled: it was
     * replaced by the role's stored text at the next show or hide after the ontology's first save
     * (1.146.0, review 1.145.0 ui-4). The line then says that where edits go has changed, so the
     * next Save is made knowing where it writes (1.147.0, review 1.146.0 ui-1). The texts put
     * aside for the ontology move with it (1.149.0, review 1.148.0, the ui reviewer's R4).
     * Another ontology becoming active is {@link #reloadForActiveOntology()}.
     */
    public void reload() {
        reload(true);
    }

    /**
     * Reads the roles of the ontology now active (AssistantView.showCurrent). A text typed for
     * another project is not carried here: Save wrote it over this project's role, and Reset and
     * Delete removed this project's file, while the line read the same for both (1.147.0, review
     * 1.146.0 ui-1). It waits for its project and is shown again when that is active again.
     */
    public void reloadForActiveOntology() {
        if (keyOf(project.get()).equals(shownFor)) {
            // The same project - the ontology reloaded, or another one in its folder: edits go
            // where they went, and the text typed stays.
            reload(true);
            return;
        }
        boolean parked = putAside();
        // When the roles here cannot be read, nothing is shown, so a later Save or switch can put
        // nothing of the other ontology's text on this project's role (1.147.0, the review of the
        // ui-1 fix); the line then says the texts typed are kept.
        if (reload(false) && parked) {
            where.setText(where.getText() + " The text you typed for the other ontology is"
                    + " kept until it is active again.");
        }
    }

    /**
     * Shows the text put aside here for the role shown, if one waits: the later of two. The
     * line says again that where edits go has changed when it was carried, so the next Save is
     * still made knowing where it writes (1.148.0, review 1.147.0 ui-4).
     *
     * @return whether a text came back
     */
    private boolean restoreDraft() {
        for (int i = drafts.size() - 1; shown != null && i >= 0; i--) {
            Draft waiting = drafts.get(i);
            if (waiting.project.equals(shownFor) && isShown(waiting.role)) {
                drafts.remove(i);
                text.setText(waiting.text);
                text.setCaretPosition(0);
                carried = waiting.carried && typed();
                where.setText(line());
                return true;
            }
        }
        return false;
    }

    /**
     * What the line adds when a text put aside comes back without its role being picked: after a
     * read of the roles, or after Discard.
     */
    private String backNote() {
        return " The text you typed for " + named(shown) + " is shown again: it is not saved"
                + " yet.";
    }

    /**
     * What the line adds for the texts typed here and put aside: every role one waits on, so
     * that it can be picked (1.149.0, review 1.147.0 ui-3). One on the role shown is the second
     * of two for it, which comes back once the one shown is saved or discarded. The line names
     * Discard: without it a text typed and abandoned could not be dropped (1.150.0, review
     * 1.149.0 ui-1). Texts are counted per role, each role named as the list names it: counted
     * by name, two texts on two roles named Reviewer read as one text, on the role shown (1.150.0,
     * review 1.149.0 ui-3).
     */
    private String waitingHere() {
        List<Role> waitingOn = new ArrayList<Role>();
        int texts = 0;
        boolean another = false;
        for (Draft waiting : drafts) {
            if (!waiting.project.equals(shownFor)) {
                continue;
            }
            if (shown != null && isShown(waiting.role)) {
                another = true;
                continue;
            }
            texts++;
            Role role = listed(waiting.role);
            if (!hasId(waitingOn, role.getId())) {
                waitingOn.add(role);
            }
        }
        String note = another ? " Another text you typed for " + named(shown) + " is kept: it is"
                + " shown once you Save or Discard this one." : "";
        List<String> names = new ArrayList<String>();
        for (Role role : waitingOn) {
            names.add(named(role));
        }
        if (texts == 1) {
            note += " The text you typed for " + names.get(0) + " is kept: pick it to go on or"
                    + " to Discard it.";
        } else if (names.size() == 1) {
            note += " The texts you typed for " + names.get(0) + " are kept: pick it to go on or"
                    + " to Discard them.";
        } else if (names.size() > 1) {
            note += " The texts you typed for " + joined(names) + " are kept: pick one to go on"
                    + " or to Discard its text.";
        }
        return note;
    }

    /** Whether one of these roles has this very id: not one differing only in case. */
    private static boolean hasId(List<Role> roles, String id) {
        for (Role role : roles) {
            if (role.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The role in the list a text put aside for this role belongs on, as the list names it: the
     * one with its very id, else one whose id differs only in case, else the role itself. Matched
     * by case alone, a text typed for "reviewer" came back on "Reviewer" where the place holds
     * both - the preferences, a folder on Linux - and Save wrote it into the other's file
     * (1.149.0, the review of the ui-2 fix).
     */
    private Role listed(Role role) {
        List<Role> items = new ArrayList<Role>();
        for (int i = 0; i < roles.getItemCount(); i++) {
            items.add(roles.getItemAt(i));
        }
        Role found = RoleLibrary.find(items, role.getId(), ignoresCase);
        return found == null ? role : found;
    }

    /** Whether a text put aside for this role belongs on the role shown: see {@link #listed}. */
    private boolean isShown(Role role) {
        return shown != null && listed(role).getId().equals(shown.getId());
    }

    /** "A", "A and B", "A, B and C". */
    private static String joined(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            out.append(i == 0 ? "" : i == names.size() - 1 ? " and " : ", ").append(names.get(i));
        }
        return out.toString();
    }

    /** What the line says of the texts put aside while no role can be shown. */
    private String kept() {
        return drafts.isEmpty() ? "" : drafts.size() == 1 ? " The text you typed is kept."
                : " The texts you typed are kept.";
    }

    /**
     * @param keepTyping true when the same ontology's roles are read again - its file may have
     *     changed, by its first save or Save As: the text typed stays on screen, and the texts
     *     put aside for it move with it; false after Save, Reset, Delete and New role, which show
     *     what is stored, and for another ontology
     * @return whether the roles could be read
     */
    private boolean reload(boolean keepTyping) {
        Role typedOn = keepTyping && typed() ? shown : null;
        ProjectFolder folder = project.get();
        String key = keyOf(folder);
        // Typed while the line named one place, and shown where it names another, until it is
        // saved or replaced: the line says so (1.147.0, review 1.146.0 ui-1).
        carried = typedOn != null && (carried || !key.equals(shownFor));
        if (keepTyping && !key.equals(shownFor)) {
            // The same ontology, saved elsewhere: the texts put aside for it go with it, as the
            // text typed does, and say that where edits go has changed. They stayed with the old
            // folder, their roles left the list, and nothing said so (1.149.0, review 1.148.0,
            // the ui reviewer's R4).
            for (int i = 0; i < drafts.size(); i++) {
                Draft waiting = drafts.get(i);
                if (waiting.project.equals(shownFor)) {
                    drafts.set(i, new Draft(key, waiting.role, waiting.text, true));
                }
            }
        }
        shownFor = key;
        notHere.clear();
        notes = "";
        where.setText(line());
        loading = true;
        try {
            roles.removeAllItems();
            RoleLibrary library = library(folder);
            List<Role> all = library.all();
            ignoresCase = library.ignoresCase();
            for (Role role : all) {
                roles.addItem(role);
            }
            // Ids are matched ignoring case: a team's Reviewer.md is the file Windows and macOS
            // open for reviewer.md. Matched exactly, the role chosen or typed on was not found
            // here, the line said "Reviewer is not there yet: Save adds it", and Save wrote over
            // the team's file (1.149.0, review 1.148.0 ui-2).
            // Where the file system ignores case (1.150.0, review 1.149.0 ui-2).
            Role chosen = RoleLibrary.find(all, store.roleId(), ignoresCase);
            Role same = typedOn == null ? null
                    : RoleLibrary.find(all, typedOn.getId(), ignoresCase);
            if (typedOn != null && same == null) {
                // A role of your own kept in the preferences has no file here yet: it stays in
                // the list, and Save writes it here, where the line now says. Nothing here is
                // deleted by Delete, which set the role choice back: it is off, and the line says
                // Save adds the role (1.147.0, review 1.146.0 ui-2).
                same = typedOn;
                roles.addItem(same);
                notHere.add(same);
            }
            // A text put aside here waits on a role this place does not hold: it stays in the
            // list, as when it was carried here, so the text comes back on it and Save adds the
            // role (1.148.0, review 1.147.0 ui-1) - every such role, not only one's.
            for (Draft waiting : drafts) {
                if (waiting.project.equals(key) && !holds(all, waiting.role.getId())
                        && !holds(notHere, waiting.role.getId())) {
                    roles.addItem(waiting.role);
                    notHere.add(waiting.role);
                }
            }
            unread = false;
            if (typedOn == null) {
                if (chosen == null) {
                    chosen = RoleLibrary.find(notHere, store.roleId(), ignoresCase);
                }
                if (chosen == null && !all.isEmpty()) {
                    chosen = all.get(0);
                }
                roles.setSelectedItem(chosen);
                show(chosen);
                // After every read, on every path - the same project read again, Save, Reset,
                // Delete, New role - the text put aside for the role shown comes back, and the
                // line says so. Only another ontology becoming active brought it back, so it
                // waited unseen until the next text put aside replaced it (1.149.0, review
                // 1.147.0 ui-3).
                notes = (restoreDraft() ? backNote() : "") + waitingHere();
            } else {
                // The text area is left alone: the text, the caret, what is selected in it.
                roles.setSelectedItem(same);
                shown = same;
                buttonsFor(same);
                notes = waitingHere();
            }
            where.setText(line() + notes);
            return true;
        } catch (IOException unreadable) {
            // Nothing is shown while the roles cannot be read: no text can be typed then, nor
            // saved. A text typed in the emptied area was gone at the next switch, and the line
            // did not say the texts typed are kept. A text typed here before waits for its role,
            // and the next read brings it back (1.149.0, review 1.147.0 ui-3).
            if (typedOn != null) {
                drafts.add(new Draft(key, typedOn, text.getText(), carried));
            }
            unread = true;
            shown = null;
            carried = false;
            text.setText("");
            buttonsFor(null);
            // The system's message ends without a full stop, and the note follows it.
            String said = String.valueOf(unreadable.getMessage()).trim();
            where.setText("The roles could not be read: " + said + (said.endsWith(".") ? "" : ".")
                    + kept());
            return false;
        } finally {
            loading = false;
        }
    }

    /** Whether the place holds a role with this id, in any case (1.149.0, review 1.148.0 ui-2). */
    private boolean holds(List<Role> all, String id) {
        return RoleLibrary.find(all, id, ignoresCase) != null;
    }

    private void show(Role role) {
        shown = role;
        carried = false;
        text.setText(textOf(role));
        text.setCaretPosition(0);
        buttonsFor(role);
        // What the line said about a text now gone - "Press Save again" - goes with it
        // (1.147.0, review 1.146.0 ui-5).
        where.setText(line());
    }

    /** What the editor shows for a role: a built-in's text is null when its file is missing. */
    private static String textOf(Role role) {
        return role == null || role.getText() == null ? "" : role.getText();
    }

    /**
     * Reset and Delete as this role allows, and Save as the text does. With no role shown - the
     * roles could not be read - nothing can be typed: a text typed in the emptied area was gone
     * at the next switch (1.149.0, review 1.147.0 ui-3). The list's tooltip names the role's
     * file, which the column may cut from its row (1.151.0, review 1.150.0 ui-2).
     */
    private void buttonsFor(Role role) {
        roles.setToolTipText(role == null ? null : fileOf(role));
        text.setEnabled(role != null);
        reset.setEnabled(role != null && role.isBuiltIn() && role.isEdited());
        delete.setEnabled(role != null && !role.isBuiltIn() && !notHere.contains(role));
        counted();
    }

    /**
     * Whether the line still says where edits go. The ontology may have got its first file, or
     * another one, since it was read: Save then wrote the role next to the ontology, where git
     * sees it, while the line said Protégé's preferences (1.146.0, review 1.145.0 ui-2). Then
     * nothing is written: the section follows, the text typed stays, and the line says so.
     *
     * @param undone what did not happen, as the line starts, e.g. "Not saved"
     * @param again what to do now, or "" when the button may no longer apply
     */
    private boolean showsWhereEditsGo(ProjectFolder folder, String undone, String again) {
        if (keyOf(folder).equals(shownFor)) {
            return true;
        }
        if (reload(true)) {
            // With what the read said of the texts typed and kept: one may be back on screen.
            where.setText(undone + ": where edits go has changed. " + whereLine(shownFor)
                    + (again.isEmpty() ? "" : " " + again) + notes);
        }
        return false;
    }

    private void counted() {
        int length = text.getText().length();
        boolean tooLong = length > RoleLibrary.MAX_LENGTH;
        count.setText(String.format(Locale.ROOT, "%,d / %,d characters", length,
                RoleLibrary.MAX_LENGTH));
        count.setForeground(tooLong ? Color.RED : Color.GRAY);
        save.setEnabled(!tooLong && shown != null && roles.getSelectedItem() != null);
        // Only a text unlike the role's stored one can be dropped (1.150.0, review 1.149.0 ui-1).
        discard.setEnabled(typed());
    }

    private Role selected() {
        return (Role) roles.getSelectedItem();
    }

    private void saveText() {
        Role role = selected();
        if (role == null) {
            return;
        }
        ProjectFolder folder = project.get();
        if (!showsWhereEditsGo(folder, "Not saved", "Press Save again to save there.")) {
            return;
        }
        try {
            library(folder).edit(role.getId(), text.getText());
            reloadKeeping(role.getId());
            onChange.run();
        } catch (IOException | IllegalArgumentException refused) {
            JOptionPane.showMessageDialog(this, refused.getMessage(), "Role not saved",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Drops the text shown, typed and not saved, without putting it aside, and shows the role's
     * stored text; then the next text waiting for the role here, if one does, comes back, and the
     * line says so. Nothing is written, so where edits go need not be checked as Save, Reset and
     * Delete check it. Without it a text typed and abandoned came back whenever its role was
     * shown, and the only ways to the stored text were Save, which writes the unwanted text into
     * the shared role file, and Reset or Delete, which remove that file (1.150.0, review 1.149.0
     * ui-1).
     */
    private void discardText() {
        if (!typed()) {
            return;
        }
        show(shown);
        // The text waiting comes back before the line is built, as on every other path. Built
        // first, the line was read while show() had just cleared "carried": a text typed for
        // another place came back without "Where edits go has changed", and the next Save wrote
        // it over the team's committed role (1.151.0, review 1.150.0 ui-1).
        boolean back = restoreDraft();
        where.setText(line() + (back ? backNote() : "") + waitingHere());
    }

    private void resetRole() {
        Role role = selected();
        if (role == null) {
            return;
        }
        // Reset, Delete and New role change files as Save does (1.146.0, review 1.145.0 ui-2).
        ProjectFolder folder = project.get();
        if (!showsWhereEditsGo(folder, "Not reset", "")) {
            return;
        }
        try {
            library(folder).reset(role.getId());
            reloadKeeping(role.getId());
            onChange.run();
        } catch (IOException unwritable) {
            JOptionPane.showMessageDialog(this, unwritable.getMessage(), "Role not reset",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void deleteRole() {
        Role role = selected();
        if (role == null || role.isBuiltIn() || notHere.contains(role)) {
            return;
        }
        ProjectFolder folder = project.get();
        if (!showsWhereEditsGo(folder, "Not deleted", "")) {
            return;
        }
        try {
            library(folder).delete(role.getId());
            store.setRoleId("ontology-expert");
            reload(false);
            onChange.run();
        } catch (IOException | IllegalArgumentException refused) {
            JOptionPane.showMessageDialog(this, refused.getMessage(), "Role not deleted",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void createRole() {
        // Asked before the name, so the name is typed for the place the line says.
        ProjectFolder folder = project.get();
        if (!showsWhereEditsGo(folder, "No role made", "")) {
            return;
        }
        String chosen = nameAsker.get();
        if (chosen == null || chosen.trim().isEmpty()) {
            return;
        }
        try {
            Role role = library(folder).create(chosen, "Describe who the assistant is and how it"
                    + " should help.");
            // The text typed waits for its role, as when another role is picked: New role threw
            // it away. Named as a role a text waits on, the new role shows that text, and the
            // line says so (1.149.0, review 1.147.0 ui-3; the ui reviewer's R5 of 1.148.0).
            putAside();
            store.setRoleId(role.getId());
            reloadKeeping(role.getId());
            onChange.run();
        } catch (IOException | IllegalArgumentException refused) {
            JOptionPane.showMessageDialog(this, refused.getMessage(), "Role not created",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void reloadKeeping(String id) {
        store.setRoleId(id);
        reload(false);
    }

    private void saveField() {
        if (!field.getText().trim().equals(store.field())) {
            store.setField(field.getText());
            onChange.run();
        }
    }

    // ------------------------------------------------------------------ for tests

    void setText(String value) {
        text.setText(value);
    }

    JButton saveButton() {
        return save;
    }

    javax.swing.JComponent countLabel() {
        return count;
    }

    String whereText() {
        return where.getText();
    }

    String typedText() {
        return text.getText();
    }

    JComboBox<Role> roleBox() {
        return roles;
    }

    /** Whether the role's text can be typed in. */
    boolean textEnabled() {
        return text.isEnabled();
    }

    JButton createButton() {
        return create;
    }

    void setNameAsker(Supplier<String> asker) {
        this.nameAsker = asker;
    }
}
