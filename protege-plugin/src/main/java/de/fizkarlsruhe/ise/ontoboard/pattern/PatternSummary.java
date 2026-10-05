package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.List;
import org.semanticweb.owlapi.model.IRI;

/**
 * What to say about a pattern once its file has been read.
 *
 * <p>Separate from the dialog so that what it claims can be tested. The claims are the point: a
 * chooser that states a pattern's size is making an assertion, and the harvested metadata gets
 * that assertion wrong for 41 of the 123 patterns, so every number here comes from the file.
 */
public final class PatternSummary {

    /** Beyond this many imports, listing them stops being reading and starts being scrolling. */
    static final int MOST_IMPORTS_SHOWN = 6;

    private PatternSummary() {
    }

    /** The details pane, as HTML. */
    public static String asHtml(DesignPattern pattern, PatternLibrary.Contents contents) {
        StringBuilder html = new StringBuilder("<html><body style='font-family:sans-serif'>");
        html.append("<h2 style='margin-bottom:2px'>").append(escape(pattern.getName()))
            .append("</h2>");
        html.append("<div style='color:#777777;margin-bottom:8px'>")
            .append(escape(pattern.getPublisher())).append(" &middot; ")
            .append(escape(pattern.getCategory())).append(" &middot; ")
            .append(escape(pattern.getDomain())).append("</div>");

        if (pattern.isDuplicate()) {
            html.append(note("This is <b>" + escape(pattern.getSameAs()) + "</b> under a second "
                    + "name. Importing both would import the same terms twice."));
        }

        if (!pattern.getDescription().isEmpty()) {
            html.append("<p>").append(escape(pattern.getDescription())).append("</p>");
        }
        if (!pattern.getCompetencyQuestions().isEmpty()) {
            html.append("<p><b>Answers:</b> ").append(escape(pattern.getCompetencyQuestions()))
                .append("</p>");
        }

        html.append("<p><b>Contains:</b> ").append(contents.describe());
        if (contents.getOntologyIri() != null) {
            html.append("<br><span style='color:#777777'>")
                .append(escape(contents.getOntologyIri().toString())).append("</span>");
        }
        html.append("</p>");

        List<IRI> imports = contents.getImports();
        if (!imports.isEmpty()) {
            StringBuilder listed = new StringBuilder(importsSentence(imports));
            listed.append("<ul style='margin-top:2px'>");
            int shown = 0;
            for (IRI one : imports) {
                if (shown++ >= MOST_IMPORTS_SHOWN) {
                    listed.append("<li>and ").append(imports.size() - MOST_IMPORTS_SHOWN)
                          .append(" more</li>");
                    break;
                }
                listed.append("<li>").append(escape(one.toString())).append("</li>");
            }
            html.append(note(listed.append("</ul>").toString()));
        }
        return html.append("</body></html>").toString();
    }

    /**
     * What the pattern's own imports mean for the import about to happen.
     *
     * <p>Worth a sentence rather than a silent omission: 101 of the 123 patterns declare
     * {@code owl:imports}, four of them pulling in DUL, and somebody who expected a whole
     * pattern file to arrive should know why what arrives is smaller. Extracting a module is
     * the right behaviour - it is what ODK does - but it is not the obvious one.
     */
    static String importsSentence(List<IRI> imports) {
        return "This pattern imports " + imports.size()
                + (imports.size() == 1 ? " other ontology" : " other ontologies")
                + ". Extracting a module takes the terms you choose and the axioms that give "
                + "them meaning; it does not bring these in, which is what keeps an upper "
                + "ontology out of your project.";
    }

    private static String note(String text) {
        return "<div style='background:#F3F3F3;padding:6px;margin:6px 0'>" + text + "</div>";
    }

    static String escape(String text) {
        return text == null ? ""
                : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
