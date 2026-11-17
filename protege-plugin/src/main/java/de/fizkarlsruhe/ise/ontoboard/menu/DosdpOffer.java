package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.DosdpTools;
import java.awt.Component;
import java.util.Map;
import javax.swing.JOptionPane;

/**
 * The offer Build and Release make when OntoBoard's own engine refused for want of DOSDP-Tools:
 * download it and go on - and only then, as the download cures nothing else.
 *
 * <p>When to offer is decided here without a screen ({@link #forBuild}, {@link #forRelease});
 * the dialog is Swing, and the download is {@link GetToolDialog#installDosdpTools}'s.
 */
final class DosdpOffer {

    /** The button that downloads it. */
    static final String DOWNLOAD = "Download DOSDP-Tools " + DosdpTools.VERSION + " ("
            + DosdpTools.DOWNLOAD_SIZE + ") and go on";

    private static final String TEST = "test";

    private DosdpOffer() {
    }

    /**
     * What Build offers the download on, or null: the refusal when nothing runs for want of it,
     * else test's when test - the target the form starts with - is not run for want of it.
     *
     * @param nothingRuns why nothing runs, or null when something does
     * @param refused     the targets the engine does not run, each with its reason
     */
    static String forBuild(String nothingRuns, Map<String, String> refused) {
        if (nothingRuns != null) {
            return DosdpTools.isMissingIn(nothingRuns) ? nothingRuns : null;
        }
        String test = refused.get(TEST);
        return DosdpTools.isMissingIn(test) ? test : null;
    }

    /** What Release offers the download on, or null: its refusal, when that is the want of it. */
    static String forRelease(String refusal) {
        return DosdpTools.isMissingIn(refusal) ? refusal : null;
    }

    /**
     * Says the refusal and offers the download; true when DOSDP-Tools is installed now, and the
     * engine is to be asked again.
     *
     * @param goesOnWithout whether there is something to go on with without it: the other
     *                      button is then "Not now", else "Cancel"
     */
    static boolean ask(Component parent, String refusal, String title, boolean goesOnWithout) {
        Object[] options = {DOWNLOAD, goesOnWithout ? "Not now" : "Cancel"};
        int answer = JOptionPane.showOptionDialog(parent, ReleaseAction.wrapped(refusal), title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options,
                options[0]);
        return answer == 0 && GetToolDialog.installDosdpTools(parent);
    }
}
