package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import de.fizkarlsruhe.ise.ontoboard.odk.TermMinter;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; ID ranges - who may mint which identifiers, and giving a block to somebody new.
 *
 * <p>The commonest question in a shared ODK project is "why can I not create a term", and the
 * answer is almost always that no range is allocated to the name this editor is using. This
 * answers that in one click, and - since a view that can only diagnose a problem it cannot fix is
 * half a tool - it can also allocate the block that resolves it.
 *
 * <p>Allocation matters more than it looks. Without per-editor ranges two collaborators mint
 * {@code MWO_0000001} independently and both are right, so the same identifier names two different
 * concepts in two working copies and the collision surfaces at merge time as a conflict nobody can
 * resolve without deciding which meaning to throw away. ODK's answer is a block per person, and
 * the mechanism is worth nothing if adding a person means hand-editing an OWL file.
 */
public class IdRangesAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_WHAT = "what";
    private static final String OPTION_EDITOR = "editor";
    private static final String OPTION_SIZE = "size";

    private static final String JUST_SHOW = "Just show me who has what";
    private static final String ALLOCATE = "Allocate a block to an editor";

    /** ODK's own default. Big enough that nobody exhausts one, small enough to allocate freely. */
    private static final long DEFAULT_BLOCK = 1000;

    private volatile boolean allocating;
    private volatile String editor = "";
    private volatile long blockSize = DEFAULT_BLOCK;

    @Override
    protected String operationName() {
        return "ID ranges";
    }

    @Override
    protected boolean configure() {
        File rangesFile = rangesFile();
        if (rangesFile == null) {
            // Nothing to allocate into. run() explains why in full; asking first would be asking
            // about a file that is not there.
            allocating = false;
            return true;
        }

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_WHAT, "Do what", Parameter.Kind.CHOICE)
                        .choices(JUST_SHOW, ALLOCATE)
                        .defaultValue(JUST_SHOW)
                        .required()
                        .help("Showing is read-only. Allocating writes one new range into "
                                + rangesFile.getName() + " and changes nothing else - it is the "
                                + "answer to 'why can I not create a term', which is nearly "
                                + "always that the name you are editing under has no block.")
                        .build(),
                Parameter.of(OPTION_EDITOR, "Editor", Parameter.Kind.TEXT)
                        .defaultValue(defaultEditorName())
                        .help("Who the block belongs to. ODK conventionally uses a person's name "
                                + "or their email address, and it has to match the name this "
                                + "plugin mints under - which is the provenance agent if you have "
                                + "set one, and your login name otherwise. A block allocated to a "
                                + "name nobody edits under helps nobody.")
                        .build(),
                Parameter.of(OPTION_SIZE, "How many identifiers", Parameter.Kind.NUMBER)
                        .defaultValue(String.valueOf(suggestedBlockSize(rangesFile)))
                        .help("The size of the block. The default matches the blocks already in "
                                + "this project, so everybody gets the same allowance; where "
                                + "there are none it is 1000, which is ODK's own default. Ranges "
                                + "never overlap, so this only decides how long it is before "
                                + "somebody needs a second block.")
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "ID ranges",
                "Which editor may mint which identifiers in this project. Read from "
                        + rangesFile.getName() + ".",
                parameters);
        if (chosen == null) {
            return false;
        }
        allocating = ALLOCATE.equals(chosen.get(OPTION_WHAT));
        editor = chosen.get(OPTION_EDITOR) == null ? "" : chosen.get(OPTION_EDITOR).trim();
        try {
            blockSize = Long.parseLong(chosen.get(OPTION_SIZE).trim());
        } catch (NumberFormatException notANumber) {
            blockSize = DEFAULT_BLOCK;
        }
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File ontologyFile = fileOf(ontology);
        File rangesFile = TermMinter.findRangesFile(ontologyFile);
        if (rangesFile == null) {
            return OperationResult.of(operationName())
                    .summary("This project has no -idranges.owl, so new terms are named from "
                            + "what you type rather than from an allocated block. That is fine "
                            + "for one editor and unsafe for several.")
                    .note(ontologyFile == null
                            ? "The ontology has not been saved, so there is nowhere to look."
                            : "Looked beside " + ontologyFile.getAbsolutePath())
                    .build();
        }

        IdRanges ranges;
        try {
            ranges = IdRanges.parse(new String(Files.readAllBytes(rangesFile.toPath()),
                    Charset.forName("UTF-8")));
        } catch (IOException cannotRead) {
            return OperationResult.failed(operationName(),
                    "Could not read " + rangesFile.getAbsolutePath() + ": "
                            + cannotRead.getMessage());
        } catch (RuntimeException notRanges) {
            return OperationResult.of(operationName())
                    .failed("Found " + rangesFile.getName()
                            + " but could not read it as an ID ranges file.")
                    .build();
        }

        if (allocating) {
            return allocate(ranges, rangesFile);
        }
        return describe(ranges, rangesFile);
    }

    /** The lost lines, quoted, for a refusal a user can act on. */
    private static String join(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (joined.length() > 0) {
                joined.append("; ");
            }
            joined.append('"').append(line).append('"');
        }
        return joined.toString();
    }

    /** Adds a block and writes the file back. */
    private OperationResult allocate(IdRanges ranges, File rangesFile) {
        OperationResult.Builder result = OperationResult.of(operationName());
        String original;
        try {
            original = new String(Files.readAllBytes(rangesFile.toPath()), "UTF-8");
        } catch (IOException cannotRead) {
            return result.failed("Could not re-read " + rangesFile.getAbsolutePath()
                    + " to check that nothing would be lost: " + cannotRead.getMessage()).build();
        }
        if (editor.isEmpty()) {
            return result.failed("A block has to belong to somebody. Give the name the editor "
                    + "mints under.").build();
        }
        IdRanges.Range existing = ranges.rangeFor(editor);
        if (existing != null) {
            // Not refused outright - a second block for somebody who has exhausted the first is
            // legitimate - but not done by accident either, since the usual cause is a name typed
            // slightly differently from the one already there.
            return result.failed("'" + editor + "' already has " + existing
                    + ". If that block is exhausted, allocate a second one by editing "
                    + rangesFile.getName() + " directly; if you meant a different person, check "
                    + "the spelling - the existing owners are " + ranges.owners() + ".").build();
        }

        IdRanges updated;
        try {
            updated = ranges.withRangeFor(editor, blockSize);
        } catch (IllegalArgumentException overlaps) {
            return result.failed(overlaps.getMessage()).build();
        }
        // Nothing is overwritten until the replacement is shown to contain everything the
        // original did. IdRanges renders from a fixed template, so a declaration it has no field
        // for would simply not be in the output - and allocating a range is not an operation
        // anybody expects to delete a line from their file. Measured on a real project before
        // this existed: one lost declaration, silently.
        String replacement = updated.toManchester();
        List<String> lost = IdRanges.whatWouldBeLost(original, replacement);
        if (!lost.isEmpty()) {
            return result.failed("Allocating this range would drop " + lost.size()
                    + (lost.size() == 1 ? " line" : " lines") + " from "
                    + rangesFile.getName() + " that OntoBoard cannot reproduce: "
                    + join(lost) + ". Nothing has been written. Add the range by hand, or "
                    + "remove the line if it is no longer needed.").build();
        }
        try {
            Files.write(rangesFile.toPath(), replacement.getBytes("UTF-8"));
        } catch (IOException cannotWrite) {
            return result.failed("Could not write " + rangesFile.getAbsolutePath() + ": "
                    + cannotWrite.getMessage()).build();
        }
        result.wrote(rangesFile);

        IdRanges.Range allocated = updated.rangeFor(editor);
        result.note("Prefix: " + updated.getIdPrefix());
        result.note("First identifier: " + updated.iriFor(allocated.getLower()));
        result.warn("Commit " + rangesFile.getName() + " and make sure everyone pulls it. Two "
                + "editors working from different versions of this file will mint the same "
                + "identifiers, which is the one thing it exists to prevent.");
        return withTable(result, updated)
                .summary("Allocated " + (allocated.getUpper() - allocated.getLower() + 1)
                        + " identifiers to " + editor + ": " + allocated.getLower() + " to "
                        + allocated.getUpper() + ".").build();
    }

    /** The read-only view. */
    private OperationResult describe(IdRanges ranges, File rangesFile) {
        OperationResult.Builder result = withTable(OperationResult.of(operationName()), ranges)
                .summary(ranges.getRanges().size() + " range"
                        + (ranges.getRanges().size() == 1 ? "" : "s") + " under prefix "
                        + ranges.getIdPrefix() + ", padded to " + ranges.getIdDigits()
                        + " digits");
        result.note("Read from " + rangesFile.getAbsolutePath());

        String me = defaultEditorName();
        boolean mine = false;
        for (IdRanges.Range range : ranges.getRanges()) {
            mine |= range.getAllocatedTo().equalsIgnoreCase(me);
        }
        if (!mine) {
            result.warn("Nothing is allocated to '" + me + "', which is the name this plugin "
                    + "would mint under - so creating a term will be refused. Run this again and "
                    + "choose '" + ALLOCATE + "'.");
        }
        if (ranges.getRanges().isEmpty()) {
            result.warn("No ranges are allocated, so nobody can mint an identifier.");
        }
        return result.build();
    }

    private OperationResult.Builder withTable(OperationResult.Builder result, IdRanges ranges) {
        result.columns("Range", "Allocated to", "From", "To", "Identifiers");
        for (IdRanges.Range range : ranges.getRanges()) {
            result.row(String.valueOf(range.getNumber()), range.getAllocatedTo(),
                    String.valueOf(range.getLower()), String.valueOf(range.getUpper()),
                    String.valueOf(range.getUpper() - range.getLower() + 1));
        }
        return result;
    }

    /**
     * The name this plugin would mint under.
     *
     * <p>The same name {@code TermMinter} uses, because a block allocated to anything else is a
     * block that does not answer the question the user came here with.
     */
    private String defaultEditorName() {
        String agent = ProvenanceSettings.load().canonicalAgent();
        if (agent != null && !agent.trim().isEmpty() && !agent.startsWith("http")) {
            return agent.trim();
        }
        return System.getProperty("user.name", "");
    }

    /** The size of the blocks this project already uses, so everybody gets the same allowance. */
    static long suggestedBlockSize(File rangesFile) {
        try {
            IdRanges ranges = IdRanges.parse(new String(
                    Files.readAllBytes(rangesFile.toPath()), Charset.forName("UTF-8")));
            for (IdRanges.Range range : ranges.getRanges()) {
                long size = range.getUpper() - range.getLower() + 1;
                if (size > 0) {
                    return size;
                }
            }
        } catch (IOException cannotRead) {
            return DEFAULT_BLOCK;
        } catch (RuntimeException notRanges) {
            return DEFAULT_BLOCK;
        }
        return DEFAULT_BLOCK;
    }

    private File rangesFile() {
        return TermMinter.findRangesFile(fileOf(getOWLModelManager().getActiveOntology()));
    }

    /** The ontology's own file, or null when it has never been saved. */
}
