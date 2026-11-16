package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.git.GitHubUrl;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;

/**
 * The "new project" form and the ODK repository it produces.
 *
 * <p>The dialog is {@code JOptionPane}'s; the form in it is built by {@link #form}, which a test
 * lays out headlessly. Every decision it collects - validation, IRI normalisation, the file
 * tree - lives in {@link OdkProjectConfig} and {@link OdkCreate}, which are covered.
 */
public final class ProjectWizard {

    private ProjectWizard() {
    }

    /**
     * Shows the wizard and creates the project.
     *
     * <p>Static and parameterised so the Tools menu action and the canvas start panel run the
     * same code. Reaching it by subclassing the action would depend on Protege's action
     * lifecycle, which is not ours to rely on.
     */
    public static void show(Component parent, OWLModelManager modelManager) {
        JTextField id = new JTextField();
        JTextField title = new JTextField();
        JTextField description = new JTextField();
        final JTextField iri = new JTextField();
        // Filled in as the id is typed, and visible rather than implied.
        //
        // The default is OBO's namespace, which is right for an ontology being registered with
        // OBO Foundry and wrong for every other project - and it ends up in every term IRI,
        // permanently. The wizard used to say "leave Base IRI empty to use the OBO convention",
        // which is an invitation to mint terms under a namespace you do not own: MWO publishes
        // under purls.helmholtz-metadaten.de and NFDIcore under nfdi.fiz-karlsruhe.de, and
        // neither would notice until the IRIs failed to resolve for somebody else.
        //
        // So the same default is still there, but in the box where it can be read and
        // overtyped. It stops following the id as soon as anybody edits it.
        id.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private String suggested = "";

            private void follow() {
                if (!iri.getText().trim().isEmpty() && !iri.getText().equals(suggested)) {
                    return;
                }
                String typed = id.getText().trim().toLowerCase(java.util.Locale.ROOT);
                suggested = typed.isEmpty() ? ""
                        : "http://purl.obolibrary.org/obo/" + typed + ".owl";
                iri.setText(suggested);
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }
        });
        JTextField license = new JTextField("https://creativecommons.org/licenses/by/4.0/");
        final JTextField folder = new JTextField();
        folder.setEditable(false);

        // The GitHub owner (F2): ODK's links name it, and without it every one of them was
        // https://github.com//<id>/... The one given last is filled in; a folder inside a clone
        // fills in that clone's - unless somebody typed one, which is never overwritten.
        final java.util.prefs.Preferences remembered =
                java.util.prefs.Preferences.userNodeForPackage(ProjectWizard.class);
        final JTextField owner = new JTextField(remembered.get(OWNER_KEY, ""));
        final String[] filledIn = {owner.getText()};

        JButton browse = new JButton("Choose...");
        browse.addActionListener(a -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Where should the project folder be created?");
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
                if (owner.getText().trim().equals(filledIn[0].trim())) {
                    filledIn[0] = ownerFor(chooser.getSelectedFile(), ProcessRunner.real(),
                            owner.getText().trim());
                    owner.setText(filledIn[0]);
                }
            }
        });

        // Where a rejected value is explained, in the dialog rather than on top of it. Hidden
        // until there is something to say, so a first attempt looks no different from before.
        JLabel problem = new JLabel();
        problem.setVisible(false);

        // START FROM WHAT IS OPEN. The likeliest start - an ontology somebody already has - had no
        // way in: the guide said to make an empty project and merge the file into it by hand,
        // and the IRI rule then refused the IRI they already had.
        final org.semanticweb.owlapi.model.OWLOntology open = modelManager.getActiveOntology();
        final File openFile = adoptable(modelManager, open);
        final javax.swing.JCheckBox adopt = new javax.swing.JCheckBox(openFile == null ? ""
                : "Start from " + openFile.getName() + " - keep its address and everything in it");
        adopt.setVisible(openFile != null);
        adopt.addActionListener(a -> {
            if (adopt.isSelected()) {
                String address = open.getOntologyID().getOntologyIRI().get().toString();
                id.setText(idFrom(address));
                iri.setText(address);
                if (title.getText().trim().isEmpty()) {
                    title.setText(titleOf(open, openFile));
                }
            }
        });

        // The first commit, said before it is made and there to be declined: OntoBoard commits
        // nothing in anybody's project unseen. Ticked, because a project recorded from its first
        // file is what ODK's own seed leaves behind.
        final javax.swing.JCheckBox git = new javax.swing.JCheckBox(GIT_BOX, true);

        JPanel panel = form(id, title, description, iri, license, folder, owner, browse, problem,
                adopt, git);

        // Until it is accepted or abandoned. Every field this form collects is rejectable -
        // OdkProjectConfig.validate has eight rules, and two of them ("base IRI must end in the
        // ontology ID", "an SPDX identifier is not a licence IRI") are ones a careful person
        // trips on their first attempt. The form used to close on the way to the warning, so the
        // price of a typo in the last field was retyping the other five. The components are built
        // once above and reused here, so everything typed survives a rejection.
        //
        // Every rule is asked here, before anything is started, so a rejected attempt has
        // written nothing and there is nothing to undo before the next one. Writing the project
        // comes after, off this thread: ODK's container takes its time over a seed.
        while (true) {
            if (JOptionPane.showConfirmDialog(parent, panel, "New ODK project",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    != JOptionPane.OK_OPTION) {
                return;
            }

            final OdkProjectConfig config = new OdkProjectConfig(id.getText(), title.getText(),
                    description.getText(), iri.getText(), license.getText(),
                    folder.getText().trim().isEmpty() ? null : new File(folder.getText().trim()));
            if (adopt.isSelected()) {
                config.adopting();
            }
            config.setGithubOrg(owner.getText());
            try {
                config.validate();
                OdkConfigWriter.forNewProject(config);
            } catch (IllegalArgumentException invalid) {
                refuse(problem, invalid.getMessage());
                continue;
            }
            remembered.put(OWNER_KEY, config.getGithubOrg());
            create(parent, modelManager, config, open, config.isAdopted() ? openFile : null,
                    git.isSelected());
            return;
        }
    }

    /** What the form's last box says. */
    static final String GIT_BOX = "Make it a git repository, with these files as its first commit";

    /** The row that asks for the GitHub owner. */
    static final String OWNER_LABEL = "GitHub owner (optional):";

    /** Where the GitHub owner given last is remembered. */
    private static final String OWNER_KEY = "githubOwner";

    /**
     * The owner a GitHub remote names - {@code https://github.com/<owner>/<repo>(.git)} or
     * {@code git@github.com:<owner>/<repo>(.git)}; null for any other remote, and for none.
     */
    static String ownerFromRemote(String remote) {
        if (remote == null || !remote.toLowerCase(Locale.ROOT).contains("github.com")) {
            return null;
        }
        try {
            return GitHubUrl.parse(remote.trim()).getOwner();
        } catch (IllegalArgumentException notGitHub) {
            return null;
        }
    }

    /**
     * The owner to fill in for a project made in this folder: the GitHub owner of the clone the
     * folder is in, as git on this machine says (its {@code origin}); else the one before.
     *
     * @return never null; empty when there is none
     */
    static String ownerFor(File folder, ProcessRunner.Runner runner, String before) {
        String owner = null;
        if (folder != null) {
            try {
                ProcessRunner.Outcome outcome = runner.run(folder,
                        Arrays.asList("git", "remote", "get-url", "origin"), 1, null);
                if (outcome.isSuccess() && !outcome.getOutput().isEmpty()) {
                    owner = ownerFromRemote(outcome.getOutput().get(0));
                }
            } catch (IOException noGit) {
                // No git here, or no such folder: nothing to take an owner from.
            }
        }
        return owner != null ? owner : before == null ? "" : before;
    }

    /**
     * Writes the project off the event thread, then opens it.
     *
     * <p>Stopping it is safe: {@link OdkCreate} takes back what it wrote, so the same answers can
     * be given again.
     */
    private static void create(final Component parent, final OWLModelManager modelManager,
            final OdkProjectConfig config, final org.semanticweb.owlapi.model.OWLOntology open,
            final File adopted, final boolean recordInGit) {
        final java.util.concurrent.atomic.AtomicReference<OdkCreate.Created> made =
                new java.util.concurrent.atomic.AtomicReference<OdkCreate.Created>();
        de.fizkarlsruhe.ise.ontoboard.menu.BackgroundRun.execute(parent, "New ODK project",
                new java.util.concurrent.Callable<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public de.fizkarlsruhe.ise.ontoboard.menu.OperationResult call() {
                        made.set(OdkCreate.create(config, adopted,
                                de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.real(),
                                recordInGit));
                        return de.fizkarlsruhe.ise.ontoboard.menu.OperationResult
                                .of("New ODK project").summary("Created.").build();
                    }
                },
                new java.util.function.Consumer<
                        de.fizkarlsruhe.ise.ontoboard.menu.OperationResult>() {
                    @Override
                    public void accept(de.fizkarlsruhe.ise.ontoboard.menu.OperationResult result) {
                        OdkCreate.Created created = made.get();
                        if (created == null || !result.isSuccess()) {
                            JOptionPane.showMessageDialog(parent,
                                    wrapped(notCreatedMessage(result.getSummary())),
                                    "Not created", JOptionPane.ERROR_MESSAGE);
                        } else if (config.isAdopted()) {
                            adoptInto(parent, modelManager, open, adopted, config, created);
                        } else {
                            openGenerated(parent, modelManager, config, created);
                        }
                    }
                });
    }

    /** What is said when the project could not be written, or its writing was stopped. */
    static String notCreatedMessage(String why) {
        return "The project was not created, and nothing is left of the attempt.\n\n"
                + (why == null || why.trim().isEmpty() ? "No reason was given." : why.trim());
    }

    /** As wide as the form has always been. Only the width is fixed, not the height. */
    static final int FORM_WIDTH = 560;

    /**
     * Where the form's sentences wrap, in CSS pixels. Swing draws a CSS pixel 1.3 screen pixels
     * wide, so 420 is 546 px, inside the 560 px form - what {@code ResultDialog} found for a
     * build's message. The banner asked for 520, which is 676 px, and a tester read a refusal with
     * the end of each line cut off: "Replace the add ... in rather than typing after it".
     */
    static final int TEXT_WIDTH_CSS = 420;

    /**
     * The form the dialog shows: above the six rows, the banner that says why OK was refused and
     * the box that starts from the open ontology; below them, the hint.
     *
     * <p>Only its width is fixed. The whole panel used to be 560x260, and every pass of the loop
     * in {@link #show} packs a new dialog around it that cannot be resized, as no
     * {@code JOptionPane} dialog can - so a refusal took its banner's lines from the six fields. A
     * tester on Windows measured them at 8 px of the 22 they need, their text cut, after "The Base
     * IRI holds a second address". Now the dialog for the next attempt is packed around the
     * banner, and every row keeps its height.
     *
     * <p>Package-visible, so a test can lay it out without a display.
     */
    static JPanel form(JTextField id, JTextField title, JTextField description, JTextField iri,
            JTextField license, JTextField folder, JTextField owner, JButton browse,
            JLabel problem, Component adopt) {
        return form(id, title, description, iri, license, folder, owner, browse, problem, adopt,
                null);
    }

    /**
     * The same, with the box that says the project becomes a git repository.
     *
     * @param owner the GitHub owner's field, the last row, under the folder it is filled from
     * @param git   the box, under the fields it follows from; null for a form without it
     */
    static JPanel form(JTextField id, JTextField title, JTextField description, JTextField iri,
            JTextField license, JTextField folder, JTextField owner, JButton browse,
            JLabel problem, Component adopt, Component git) {
        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Ontology ID:"));
        fields.add(id);
        fields.add(new JLabel("Title:"));
        fields.add(title);
        fields.add(new JLabel("Description:"));
        fields.add(description);
        fields.add(new JLabel("Base IRI:"));
        fields.add(iri);
        fields.add(new JLabel("License URL:"));
        fields.add(license);
        fields.add(new JLabel("Create in folder:"));
        fields.add(folderRow);
        fields.add(new JLabel(OWNER_LABEL));
        fields.add(owner);

        // The rules said exactly, and the Base IRI said in a sentence: "one you own" meant nothing
        // to somebody who had never published an ontology, and the ID rule was learnt from the
        // error after the first try. With a width of its own, because an HTML label without one
        // reports its two unwrapped lines until it is first painted and six after: a form sized
        // from it before then comes out four lines short.
        JLabel hint = new JLabel("<html><body style='width:" + TEXT_WIDTH_CSS + "px'><i>"
                + "<b>ID</b>: a short name for file names - a lowercase "
                + "letter first, then lowercase letters, digits or underscores, e.g. <b>myont</b>"
                + " or <b>shop_ontology</b>.<br><b>Base IRI</b>: the web address your ontology "
                + "and its terms will be named under; it must end in the ID followed by .owl. "
                + "OBO's address is filled in, which is right only for an ontology registered "
                + "with the OBO Foundry. Otherwise use an address on a domain you or your "
                + "group control, e.g. <b>https://w3id.org/myont/myont.owl</b> - every term "
                + "is named under it, so it is hard to change later.<br><b>GitHub owner</b>: "
                + "the user or organisation the repository is under, e.g. <b>kitchen-lab</b> "
                + "for github.com/kitchen-lab/myont; ODK's README and pages link there.</i>"
                + "</body></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        problem.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel north = new JPanel(new BorderLayout());
        north.add(problem, BorderLayout.NORTH);
        north.add(adopt, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(FORM_WIDTH, super.getPreferredSize().height);
            }
        };
        panel.add(north, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);
        if (git == null) {
            panel.add(hint, BorderLayout.SOUTH);
        } else {
            JPanel south = new JPanel(new BorderLayout());
            south.add(git, BorderLayout.NORTH);
            south.add(hint, BorderLayout.CENTER);
            panel.add(south, BorderLayout.SOUTH);
        }
        return panel;
    }

    /** Says in the form why OK was refused. The dialog the loop makes next is packed around it. */
    static void refuse(JLabel problem, String message) {
        problem.setText(problemHtml(message));
        problem.setVisible(true);
    }

    /** The open ontology, if it has an IRI and a file and is not a project's edit file already. */
    static File adoptable(OWLModelManager modelManager,
            org.semanticweb.owlapi.model.OWLOntology open) {
        if (open == null || !open.getOntologyID().getOntologyIRI().isPresent()) {
            return null;
        }
        IRI document = modelManager.getOWLOntologyManager().getOntologyDocumentIRI(open);
        if (document == null || !"file".equals(document.getScheme())) {
            return null;
        }
        File file = new File(document.toURI());
        return file.getName().endsWith("-edit.owl") ? null : file;
    }

    /** "https://example.org/Shop-Ontology.owl" as an ID: shop_ontology. */
    static String idFrom(String address) {
        String name = address.replaceAll("[#/]+$", "");
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('#')) + 1)
                .replaceAll("\\.(owl|ttl|rdf|ofn|omn|owx)$", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name = "o" + name;
        }
        return name;
    }

    private static String titleOf(org.semanticweb.owlapi.model.OWLOntology open, File file) {
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : open.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString();
            if ((property.endsWith("/title") || property.endsWith("#label"))
                    && annotation.getValue() instanceof org.semanticweb.owlapi.model.OWLLiteral) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) annotation.getValue())
                        .getLiteral();
            }
        }
        return file.getName().replaceAll("\\.[^.]+$", "");
    }

    /**
     * The open ontology becomes the project's edit file: saved there, kept open, its IRI and
     * every axiom as they were. The file it came from is left as it is.
     */
    private static void adoptInto(Component parent, OWLModelManager modelManager,
            org.semanticweb.owlapi.model.OWLOntology open, File from, OdkProjectConfig config,
            OdkCreate.Created created) {
        try {
            modelManager.getOWLOntologyManager().setOntologyDocumentIRI(open,
                    IRI.create(config.getEditFile()));
            // The seed has put the ontology's file there, byte for byte. It is saved again only
            // when the window holds changes that file does not: a save writes the whole file
            // afresh, and the working copy would differ from the first commit in its layout
            // alone.
            if (modelManager.getDirtyOntologies().contains(open)) {
                modelManager.save(open);
            }
            JOptionPane.showMessageDialog(parent,
                    wrapped(adoptedMessage(created.written().size(), config.getProjectRoot(),
                            config.getEditFile().getName(), from.getName(), created.summary())),
                    "Project created", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception cannotSave) {
            JOptionPane.showMessageDialog(parent,
                    wrapped("The project was created in "
                            + config.getProjectRoot().getAbsolutePath()
                            + ", but your ontology could not be saved into it: "
                            + cannotSave.getMessage() + "\n\nSave it there yourself as "
                            + config.getEditFile().getName() + "."),
                    "Project created", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * A rejection message, as a banner the form itself can carry.
     *
     * <p>Wrapped at the dialog's width, because these are sentences rather than labels - the
     * base-IRI rule runs to sixty words and spends them explaining what a relative IRI does to a
     * colleague's copy, which is the part worth reading. An unwrapped {@code JLabel} lays that out
     * as a single line and widens the dialog past the edge of the screen.
     *
     * <p>Escaped, because every one of these messages quotes the user's own input back at them:
     * {@code Got: '<b>'}. Swing's HTML renderer would read that as markup and show an empty
     * string, hiding the very value that was rejected.
     */
    static String problemHtml(String message) {
        String text = message == null || message.trim().isEmpty()
                ? "Something about these values was rejected." : message;
        // Broken after a / once a word is longer than a line: the rejected address is quoted whole,
        // and a word wider than the width sets the width - shop_ontology, the hint's own example,
        // typed after the address filled in, is one word of 102 characters.
        return "<html><body style='width:" + TEXT_WIDTH_CSS + "px'><b>Not created.</b> "
                + breakable(escaped(text)) + "</body></html>";
    }

    /**
     * A message for a dialog, wrapped so the dialog comes out about 600 px wide: a blank line
     * between paragraphs, a line break where the text has one.
     *
     * <p>Unwrapped, an option pane lays each line out at its full length, and "Project created"
     * measured 1355 px wide on a Mac - its "Next: ..." line alone about 1200 px - so on a screen
     * 1280 px wide, 1920x1080 at 150%, its OK button was past the edge. Escaped, as
     * {@link #problemHtml} is: a folder's name may hold an {@code &}. 400px, because Swing reads
     * a CSS pixel as 1.3 of the screen's: the text is 520 px wide, measured, and the dialog 660.
     *
     * <p>A path is broken after a separator once it is longer than a line, because nothing else
     * breaks it: a word wider than the width sets the width, and every sentence then wrapped at
     * that instead - 1165 px for a project made in a deep folder.
     */
    static String wrapped(String message) {
        StringBuilder html = new StringBuilder("<html><body style='width:400px'>");
        String[] paragraphs = (message == null ? "" : message).split("\n\n");
        for (int at = 0; at < paragraphs.length; at++) {
            html.append(at == 0 ? "" : "<br><br>")
                    .append(breakable(escaped(paragraphs[at])).replace("\n", "<br>"));
        }
        return html.append("</body></html>").toString();
    }

    /** About as many characters of a path as fit on one line of {@link #wrapped}. */
    static final int LONGEST_WORD = 60;

    /** Every word longer than a line broken after a {@code /} or {@code \}, so it wraps. */
    private static String breakable(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (String word : text.split(" ", -1)) {
            out.append(out.length() == 0 ? "" : " ");
            int start = 0;
            while (word.length() - start > LONGEST_WORD) {
                int cut = Math.max(word.lastIndexOf('/', start + LONGEST_WORD),
                        word.lastIndexOf('\\', start + LONGEST_WORD));
                if (cut <= start) {
                    break;
                }
                out.append(word, start, cut + 1).append("<br>");
                start = cut + 1;
            }
            out.append(word.substring(start));
        }
        return out.toString();
    }

    private static String escaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                escaped.append("&amp;");
            } else if (c == '<') {
                escaped.append("&lt;");
            } else if (c == '>') {
                escaped.append("&gt;");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * What to do when Protégé would not open a new project's edit file. When an ontology with the
     * same IRI is open already - a project made again with the same ID, as starting over does -
     * opening it by hand would not work either: Protégé keeps the one it has, and a tester went on
     * editing the old project. Only a fresh start opens the new one.
     */
    static String howToOpen(Exception couldNotOpen, String editFile) {
        if (isAlreadyOpen(couldNotOpen)) {
            return "An ontology with the same IRI is already open, probably an earlier project with "
                    + "the same Ontology ID and Base IRI. To work in the new project, quit Protege, "
                    + "start it again, and choose OntoBoard > Project > Open existing ODK project... "
                    + "with the folder above.";
        }
        return "Open it manually: src/ontology/" + editFile;
    }

    /** Whether Protégé would not open it because an ontology with the same IRI is open. */
    static boolean isAlreadyOpen(Exception couldNotOpen) {
        String said = String.valueOf(couldNotOpen.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return couldNotOpen instanceof org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException
                || said.contains("already exists") || said.contains("already contains");
    }

    /**
     * Brings the OntoBoard tab forward, adding it if it is not there.
     *
     * <p>The dialog that follows says the file is on that tab. On a fresh Protege the tab has never
     * been opened, so that was not true for exactly the person reading it for the first time. A
     * failure here leaves the project as it is and the tab one menu away.
     *
     * <p>Package-visible for {@link ProjectOpener}, whose message points at the board too and which
     * left Active ontology in front.
     */
    static void showTheBoard(Component parent) {
        org.protege.editor.core.ui.workspace.TabbedWorkspace workspace =
                parent instanceof org.protege.editor.core.ui.workspace.TabbedWorkspace
                        ? (org.protege.editor.core.ui.workspace.TabbedWorkspace) parent
                        : (org.protege.editor.core.ui.workspace.TabbedWorkspace)
                                javax.swing.SwingUtilities.getAncestorOfClass(
                                        org.protege.editor.core.ui.workspace.TabbedWorkspace.class,
                                        parent);
        if (workspace == null) {
            return;
        }
        try {
            for (org.protege.editor.core.ui.workspace.WorkspaceTab open
                    : workspace.getWorkspaceTabs()) {
                if (open instanceof de.fizkarlsruhe.ise.ontoboard.views.OntoBoardTab) {
                    workspace.setSelectedTab(open);
                    return;
                }
            }
            for (org.protege.editor.core.ui.workspace.WorkspaceTabPlugin plugin
                    : workspace.getOrderedPlugins()) {
                if ("OntoBoard".equals(plugin.getLabel())) {
                    org.protege.editor.core.ui.workspace.WorkspaceTab added =
                            workspace.addTabForPlugin(plugin);
                    if (added != null) {
                        workspace.setSelectedTab(added);
                    }
                    return;
                }
            }
        } catch (RuntimeException notFatal) {
            org.slf4j.LoggerFactory.getLogger(ProjectWizard.class)
                    .warn("Could not bring the OntoBoard tab forward", notFatal);
        }
    }

    /**
     * What "Project created" says about a new project.
     *
     * <p>No "Next: OntoBoard &gt; Start here: the guide... walks you through the rest" any more.
     * A study participant, with a guide of their own in hand, read it as a second guide to follow
     * (egg A, 1.135.1). Nobody is left without one: the board this names is empty now, and its
     * first button is "New to this? Start the guide...".
     */
    static String createdMessage(int fileCount, File root, String editFile) {
        return createdMessage(fileCount, root, editFile, null);
    }

    /**
     * The same, with how the project was made: by ODK's container or from ODK's templates, and
     * whether git has it ({@link OdkCreate.Created#summary}). It ends the first paragraph, so
     * the message keeps its three.
     */
    static String createdMessage(int fileCount, File root, String editFile, String how) {
        return "Created " + fileCount + " files in\n" + root.getAbsolutePath() + made(how)
                + "\n\nEditing " + editFile + " - the file you work in. It is on the OntoBoard tab"
                + " now.\n\n" + CHECK_ANY_TIME;
    }

    /** What "Project created" says when the open ontology became the project's edit file. */
    static String adoptedMessage(int fileCount, File root, String editFile, String from) {
        return adoptedMessage(fileCount, root, editFile, from, null);
    }

    /** The same, with how the project was made. */
    static String adoptedMessage(int fileCount, File root, String editFile, String from,
            String how) {
        return "Created " + fileCount + " files in\n" + root.getAbsolutePath() + made(how)
                + "\n\nYour ontology is now the project's edit file, " + editFile
                + " - the file you work in from now on, with its address and every term as they"
                + " were. " + from + " is left as it was.\n\n" + CHECK_ANY_TIME;
    }

    private static String made(String how) {
        return how == null || how.trim().isEmpty() ? "" : "\n" + how.trim();
    }

    /** The last paragraph of both: how to check the ontology, and that nothing else is needed. */
    private static final String CHECK_ANY_TIME = "To check the ontology at any time,"
            + " OntoBoard > Build and release > Build... and choose test."
            + " Nothing else needs installing.";

    /**
     * Opens the generated edit file so the user lands in the ontology they just described.
     *
     * <p>If loading fails the project is still on disk and intact, so the message says where
     * it is rather than implying the whole operation failed.
     */
    private static void openGenerated(Component parent, OWLModelManager modelManager,
            OdkProjectConfig config, OdkCreate.Created created) {
        try {
            modelManager.getOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(config.getEditFile());
            modelManager.setActiveOntology(
                    modelManager.getOWLOntologyManager().getOntology(
                            IRI.create(config.getBaseIri())));
            showTheBoard(parent);
            JOptionPane.showMessageDialog(parent,
                    wrapped(createdMessage(created.written().size(), config.getProjectRoot(),
                            config.getEditFile().getName(), created.summary())),
                    "Project created", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception couldNotOpen) {
            JOptionPane.showMessageDialog(parent,
                    wrapped("The project was created at\n"
                            + config.getProjectRoot().getAbsolutePath()
                            + "\n\nbut Protege could not open the edit file automatically"
                            + (isAlreadyOpen(couldNotOpen) ? "." : ":\n" + couldNotOpen.getMessage())
                            + "\n\n" + howToOpen(couldNotOpen, config.getEditFile().getName())),
                    "Project created, not opened", JOptionPane.WARNING_MESSAGE);
        }
    }
}
