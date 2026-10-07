package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * OntoBoard &gt; Project &gt; Term lists... - viewing and editing {@code <import>_terms.txt}.
 *
 * <p>The list an ODK import is rebuilt from, and the file that makes {@code refresh-imports}
 * mean anything: the module is an artefact, the term list is the source. OntoBoard could read
 * one since 1.77.0 and write a fresh one since 1.38.0, and nothing could edit an existing one.
 *
 * <p><b>Why this does not go through {@code ImportModules.writeTerms}.</b> That writes a file
 * from a template, which is right for a module just extracted and wrong for one somebody
 * maintains. Measured on a real repository's {@code iao_terms.txt} - 18 lines, 13 terms - a
 * read-then-write round trip leaves 15 of those lines gone: every trailing {@code # label}
 * comment and both headers, re-sorted into a different order. So edits are spliced through
 * {@link TermFile}, and every comment survives.
 *
 * <p>What this offers over a text editor is the part Prot&eacute;g&eacute; can do and a text
 * editor cannot: each term shown with the label the open ontology gives it, so a list of OBO
 * numbers reads; a malformed line named rather than skipped; and a term added from the entity
 * selected in Prot&eacute;g&eacute;, with its label written as the trailing comment the file's
 * own convention asks for.
 */
public class TermListAction extends ProtegeOWLAction {

    private static final long serialVersionUID = 1L;

    @Override
    public void initialise() {
    }

    @Override
    public void dispose() {
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        File projectRoot;
        try {
            projectRoot = projectRoot();
        } catch (RuntimeException notAProject) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), notAProject.getMessage(),
                    "No project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<ImportModules.Module> modules = ImportModules.modulesIn(projectRoot);
        List<ImportModules.Module> withLists = new ArrayList<ImportModules.Module>();
        for (ImportModules.Module module : modules) {
            if (module.getTermsFile() != null && module.getTermsFile().isFile()) {
                withLists.add(module);
            }
        }
        if (withLists.isEmpty()) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "This project has no term lists.\n\nODK keeps them beside the modules they "
                            + "build, as src/ontology/imports/<import>_terms.txt.\n"
                            + "ROBOT > Import terms... writes one for each module it extracts.",
                    "No term lists", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        ImportModules.Module chosen = ask(withLists);
        if (chosen == null) {
            return;
        }
        edit(chosen);
    }

    /** Which list, with its term count so the choice is informed. */
    private ImportModules.Module ask(List<ImportModules.Module> modules) {
        String[] labels = new String[modules.size()];
        for (int at = 0; at < modules.size(); at++) {
            ImportModules.Module module = modules.get(at);
            int terms = 0;
            try {
                terms = TermFile.termsIn(read(module.getTermsFile())).size();
            } catch (IOException unreadable) {
                // Shown as zero; opening it will report the real reason.
            }
            labels[at] = module.getName() + "   (" + terms
                    + (terms == 1 ? " term)" : " terms)");
        }
        JComboBox<String> choices = new JComboBox<String>(labels);
        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), choices, "Which term list?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return null;
        }
        return modules.get(Math.max(0, choices.getSelectedIndex()));
    }

    private void edit(ImportModules.Module module) {
        File file = module.getTermsFile();
        String original;
        try {
            original = read(file);
        } catch (IOException unreadable) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Could not read " + file.getAbsolutePath() + ": " + unreadable.getMessage(),
                    "Cannot open the term list", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String[] text = {original};
        DefaultTableModel model = new DefaultTableModel(
                new Object[] {"Line", "Term", "Label or comment"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        final JTable table = new JTable(model);
        fill(model, text[0]);

        JButton add = new JButton("Add the selected term");
        add.setToolTipText("Adds whatever is selected in Protege's class or property tree");
        add.addActionListener(a -> {
            OWLEntity selected = getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
            if (selected == null) {
                JOptionPane.showMessageDialog(table, "Select a term in Protege first.",
                        "Nothing selected", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            try {
                text[0] = TermFile.with(text[0], selected.getIRI(), labelFor(selected));
                fill(model, text[0]);
            } catch (IllegalArgumentException refused) {
                JOptionPane.showMessageDialog(table, refused.getMessage(),
                        "Not added", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JButton remove = new JButton("Remove");
        remove.addActionListener(a -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(table, "Select a line to remove.",
                        "Nothing selected", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int line = Integer.parseInt(String.valueOf(model.getValueAt(row, 0)));
            text[0] = TermFile.without(text[0], line);
            fill(model, text[0]);
        });

        JPanel buttons = new JPanel();
        buttons.add(add);
        buttons.add(remove);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(new JLabel("<html><b>" + module.getName() + "</b> &mdash; "
                + file.getAbsolutePath() + "<br>Comments are preserved: only the lines you add "
                + "or remove change.</html>"), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(680, 340));
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        if (JOptionPane.showConfirmDialog(getOWLWorkspace(), content,
                "Term list - " + module.getName(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (text[0].equals(original)) {
            JOptionPane.showMessageDialog(getOWLWorkspace(), "Nothing was changed.",
                    "Term list", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        save(file, original, text[0], module.getName());
    }

    /** Rebuilds the table from the current text, so the view is never a guess. */
    private void fill(DefaultTableModel model, String text) {
        model.setRowCount(0);
        OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        for (TermFile.Line line : TermFile.linesIn(text)) {
            if (line.getKind() == TermFile.Kind.BLANK) {
                continue;
            }
            String shown;
            String note;
            if (line.getKind() == TermFile.Kind.TERM) {
                shown = line.getTerm().toString();
                // The ontology's own label beats the file's comment: the comment was written
                // when the term was added and the label is what the term is called now.
                String label = labelOf(ontology, line.getTerm());
                note = label != null && !label.isEmpty() ? label : line.getComment();
            } else if (line.getKind() == TermFile.Kind.COMMENT) {
                shown = "(comment)";
                note = line.getComment();
            } else {
                shown = line.getText().trim();
                note = "not a readable term - the module is missing it";
            }
            model.addRow(new Object[] {line.getNumber(), shown, note});
        }
    }

    private String labelFor(OWLEntity entity) {
        OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        String label = ontology == null ? null : DisplayLabels.forEntity(ontology, entity);
        return label == null || label.isEmpty() ? null : label;
    }

    /**
     * What the ontology calls a term, found from its IRI alone.
     *
     * <p>A term list holds IRIs, and {@code DisplayLabels} labels entities, so the IRI has to be
     * resolved first. Over the imports closure, explicitly: a term list names terms from
     * <em>other</em> ontologies, which is the whole point of an import, so the one place they
     * are declared is inside an import. {@code getEntitiesInSignature} defaults to
     * {@code Imports.EXCLUDED}, which would find none of them and leave every row reading as a
     * bare OBO number.
     */
    private static String labelOf(OWLOntology ontology, IRI iri) {
        if (ontology == null || iri == null) {
            return null;
        }
        for (OWLEntity entity : ontology.getEntitiesInSignature(iri,
                org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            String label = DisplayLabels.forEntity(ontology, entity);
            if (label != null && !label.isEmpty() && !label.equals(iri.toString())) {
                return label;
            }
        }
        return null;
    }

    /**
     * Writes it, refusing if the file changed underneath.
     *
     * <p>The same two guards the configuration editor uses, and for the same reason: the new
     * text was spliced from the old one, so writing it over a file somebody else has edited
     * discards their change wholesale rather than merging with it.
     */
    private void save(File file, String expected, String updated, String name) {
        try {
            String now = read(file);
            if (!now.equals(expected)) {
                JOptionPane.showMessageDialog(getOWLWorkspace(),
                        file.getName() + " changed while it was open here - a text editor, a git "
                                + "pull, or a build.\n\nNothing has been written, because this "
                                + "edit was built from the older text and saving it would "
                                + "discard that change.",
                        "The file changed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            File temporary = File.createTempFile(file.getName(), ".tmp",
                    file.getAbsoluteFile().getParentFile());
            Files.write(temporary.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    temporary.deleteOnExit();
                }
            }
        } catch (IOException cannotWrite) {
            JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "Could not write " + file.getAbsolutePath() + ": " + cannotWrite.getMessage(),
                    "Nothing was written", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(getOWLWorkspace(),
                "Saved " + file.getName() + ".\n\nThe module itself has not changed. Run "
                        + "Project > Refresh imports... to rebuild " + name
                        + "_import.owl from this list.",
                "Term list saved", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The project root, two levels above the open ontology's own directory. */
    private File projectRoot() {
        OWLOntology ontology = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        if (ontology == null) {
            throw new IllegalStateException("No ontology is open.");
        }
        IRI document = getOWLModelManager().getOWLOntologyManager()
                .getOntologyDocumentIRI(ontology);
        java.net.URI uri = document == null ? null : document.toURI();
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("This ontology is not saved to a file, so OntoBoard "
                    + "cannot find a project around it.");
        }
        File ontologyDirectory = new File(uri).getAbsoluteFile().getParentFile();
        File src = ontologyDirectory == null ? null : ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        if (root == null) {
            throw new IllegalStateException("Expected <project>/src/ontology, got "
                    + ontologyDirectory);
        }
        return root;
    }
}
