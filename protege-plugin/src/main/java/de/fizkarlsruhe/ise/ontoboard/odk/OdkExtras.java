package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import java.util.LinkedHashMap;

/**
 * What OntoBoard adds to a project on top of ODK's seed.
 *
 * <p>A new project is the seed, every file of it as ODK writes it, and then these - none of
 * which is a file the seed writes, so none of them changes one:
 *
 * <ul>
 *   <li>{@code .gitattributes}, so the project is the same bytes on every machine.
 *   <li>{@code mkdocs.yaml} and three pages under {@code docs/}. ODK's seed writes the workflow
 *       that publishes a documentation site ({@code .github/workflows/docs.yml}) and, without a
 *       {@code documentation:} block in the configuration, nothing for it to publish.
 *   <li>{@code src/sparql/check_labels-violation.sparql}: OntoBoard's label check, as one of
 *       ODK's checks. The configuration names it ({@link #LABEL_CHECK}), so ODK's own
 *       {@code make test} runs it and fails on a class without a label.
 * </ul>
 */
public final class OdkExtras {

    /** The label check's name, as the configuration's {@code custom_sparql_checks} lists it. */
    public static final String LABEL_CHECK = "check_labels";

    /** Where ODK looks for a check of that name. */
    public static final String LABEL_CHECK_FILE = "src/sparql/" + LABEL_CHECK
            + "-violation.sparql";

    private OdkExtras() {
    }

    /** What makes a repository's workflows able to write, for its owner and name to fill. */
    private static final String WORKFLOW_COMMAND = "gh api -X PUT repos/<owner>/<repo>/actions/"
            + "permissions/workflow -f default_workflow_permissions=write";

    /**
     * F3: why ODK's documentation workflow cannot publish on a new GitHub repository, and the
     * one setting that lets it. ODK's {@code docs.yml} declares no {@code permissions}, and a new
     * repository gives its workflows a token that can only read, so the push to
     * {@code gh-pages} fails. Said where OntoBoard says how to publish; ODK's file stays ODK's.
     * {@code <owner>/<repo>} is filled by {@link #workflowPermissions}.
     */
    public static final String WORKFLOW_PERMISSIONS = "ODK's documentation workflow pushes to "
            + "the gh-pages branch, and a new GitHub repository lets workflows only read: allow "
            + "writing once in Settings > Actions > General > Workflow permissions (or: "
            + WORKFLOW_COMMAND + ").";

    /**
     * {@link #WORKFLOW_PERMISSIONS} for a repository: its owner where it is known, else
     * {@code <owner>}.
     */
    public static String workflowPermissions(String owner, String repo) {
        return filled(WORKFLOW_PERMISSIONS, owner, repo);
    }

    /**
     * The same for a page: its command in code, where Markdown shows {@code <owner>} as it is
     * rather than taking it for a tag.
     */
    static String workflowPermissionsOnAPage(String owner, String repo) {
        return filled(WORKFLOW_PERMISSIONS.replace(WORKFLOW_COMMAND, "`" + WORKFLOW_COMMAND + "`"),
                owner, repo);
    }

    private static String filled(String text, String owner, String repo) {
        return text.replace("<owner>/<repo>", (owner == null || owner.trim().isEmpty()
                ? "<owner>" : owner.trim()) + "/" + repo);
    }

    /** The extra files of a new project: path from the project's root, with {@code /}, to text. */
    public static LinkedHashMap<String, String> files(OdkProjectConfig config) {
        LinkedHashMap<String, String> files = new LinkedHashMap<String, String>();
        files.put(".gitattributes", gitattributes());
        files.put("mkdocs.yaml", mkdocs(config));
        files.put("docs/index.md", docsIndex(config));
        files.put("docs/editing.md", docsEditing(config));
        files.put("docs/release.md", docsRelease(config));
        files.put(LABEL_CHECK_FILE, checkLabels());
        return files;
    }

    /**
     * The label check, in the form ODK runs: a query whose every row is a violation.
     *
     * <p>ODK runs its checks on the edit file with the imports taken out. A class of an import
     * that the edit file refers to is still declared there, without the label the import gives
     * it, so "every class has a label" would fail on every imported parent. The scope is
     * therefore that of ROBOT's own {@code missing_label} rule: a class counts when the edit
     * file says something about it besides what kind of thing it is. OWL's own vocabulary and
     * deprecated classes are left out, as they were in 1.135.x.
     */
    public static String checkLabels() {
        return "# Every class this ontology describes carries an rdfs:label.\n"
                + "#\n"
                + "# An ODK check: make sparql_test (part of make test) runs it on the edit file\n"
                + "# without its imports, and every row it returns fails the test. A class counts\n"
                + "# when the edit file says something about it besides its type - a parent, a\n"
                + "# definition - so a class that is only referred to, and labelled in an import,\n"
                + "# is not reported. OWL's own vocabulary and deprecated classes are left out.\n"
                + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
                + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                + "\n"
                + "SELECT DISTINCT ?entity WHERE {\n"
                + "  ?entity a owl:Class .\n"
                + "  FILTER (!isBlank(?entity))\n"
                + "  FILTER (!STRSTARTS(STR(?entity), \"http://www.w3.org/2002/07/owl#\"))\n"
                + "  FILTER NOT EXISTS {\n"
                + "    ?entity rdfs:label ?label .\n"
                + "    FILTER (STR(?label) != \"\")\n"
                + "  }\n"
                + "  FILTER NOT EXISTS { ?entity owl:deprecated true }\n"
                + "  FILTER EXISTS {\n"
                + "    ?entity ?property ?object .\n"
                + "    FILTER (?property != rdf:type)\n"
                + "    FILTER (?property != owl:equivalentClass)\n"
                + "    FILTER (?property != owl:disjointWith)\n"
                + "  }\n"
                + "}\n"
                + "ORDER BY ?entity\n";
    }

    /**
     * Pins line endings.
     *
     * <p>Git is often set to {@code core.autocrlf=true}, which rewrites text files to CRLF on
     * checkout. Everything ODK and OntoBoard write is LF, and ODK's Makefile compares the
     * configuration's hash, runs shell scripts and reads term lists: a Windows clone with CRLF
     * in them is a different project from the one that was committed.
     */
    private static String gitattributes() {
        return "# Line endings are pinned so this project is the same bytes on every machine.\n"
                + "#\n"
                + "# Git is often configured with core.autocrlf=true, which rewrites text files to\n"
                + "# CRLF on checkout. Everything ODK and OntoBoard write is LF, so without this a\n"
                + "# Windows clone gets CRLF in the working tree - in the scripts ODK's container\n"
                + "# runs, too.\n"
                + "* text=auto eol=lf\n"
                + "\n"
                + "# Not text: these are binary or effectively so.\n"
                + "*.png binary\n"
                + "*.jpg binary\n"
                + "*.zip binary\n"
                + "*.gz binary\n";
    }

    private static String mkdocs(OdkProjectConfig c) {
        return "site_name: " + OdkConfigWriter.quoted(c.getTitle()) + "\n"
                + "theme: readthedocs\n"
                + "\n"
                + "# Add a page by putting it in docs/ and naming it here. Anything not listed\n"
                + "# is still published, but is reachable only by its URL.\n"
                + "nav:\n"
                + "  - Home: index.md\n"
                + "  - Editing: editing.md\n"
                + "  - Releases: release.md\n";
    }

    private static String docsIndex(OdkProjectConfig c) {
        String id = c.getOntologyId();
        String title = OdkConfigWriter.oneLine(c.getTitle());
        return "# " + title + "\n\n"
                + (c.getDescription().isEmpty() ? "" : c.getDescription() + "\n\n")
                + "This site is published from the `docs/` folder of this repository by\n"
                + "GitHub Actions, every time `main` changes.\n\n"
                + "## Getting the ontology\n\n"
                + "| What | Where |\n"
                + "|---|---|\n"
                + "| Ontology IRI | `" + c.getBaseIri() + "` |\n"
                + "| Edit file | `src/ontology/" + id + "-edit.owl` |\n"
                + "| Released files | `" + id + ".owl` and its variants at the top of the "
                + "repository; every release under `releases/` |\n\n"
                + "Edit the **edit file**, never a released one: the released files are made by\n"
                + "a release and overwritten by the next.\n\n"
                + "## Turning this site on\n\n"
                + workflowPermissionsOnAPage(c.getGithubOrg(), id) + "\n\n"
                + "GitHub does not publish Pages until it is asked. Once, in the repository's\n"
                + "**Settings > Pages**, set the source to **Deploy from a branch** and choose\n"
                + "the `gh-pages` branch - the workflow creates that branch the first time it\n"
                + "runs. The site then appears at `https://<owner>.github.io/<repository>/`.\n";
    }

    private static String docsEditing(OdkProjectConfig c) {
        String id = c.getOntologyId();
        return "# Editing " + OdkConfigWriter.oneLine(c.getTitle()) + "\n\n"
                + "This is an [ODK](https://github.com/INCATools/ontology-development-kit)\n"
                + "project, set up as ODK " + OdkPin.VERSION + " sets one up.\n\n"
                + "## With Protege and OntoBoard\n\n"
                + "1. Open `src/ontology/" + id + "-edit.owl`.\n"
                + "2. Work on the OntoBoard tab, or in Protege's own views.\n"
                + "3. Run **OntoBoard > Build and release > Build...** and choose `test` before you\n"
                + "   commit. It runs the checks the repository's CI runs.\n\n"
                + "From a terminal the same is `sh run.sh make test` in `src/ontology`, which\n"
                + "needs Docker.\n\n"
                + "## Identifiers\n\n"
                + (c.isReadableNames()
                        ? "New terms are named from what is typed - \"boiled egg\" a class\n"
                                + "`BoiledEgg`, \"has part\" a property `hasPart` - with what is "
                                + "typed\nas the label, as `ontoboard_term_names: readable` in\n"
                                + "`src/ontology/" + id + "-odk.yaml` says. Protege's own *Add\n"
                                + "subclass* still numbers a term from your range in\n"
                                + "`src/ontology/" + id + "-idranges.owl`. "
                        : "New terms are numbered from the range allocated to you in\n"
                                + "`src/ontology/" + id + "-idranges.owl`. ")
                + "ODK writes that file with two\n"
                + "placeholder ranges, `ONTOLOGY-CREATOR` and `ADDITIONAL EDITOR`; OntoBoard\n"
                + "gives the first to the person who made the project, or asks whose it is.\n"
                + "Give every further editor a range of their own, with\n"
                + "**OntoBoard > Project > ID ranges...**, so two people never give the same\n"
                + "identifier to different things.\n\n"
                + "## What the checks mean\n\n"
                + "| Target | What it does |\n"
                + "|---|---|\n"
                + "| `reason_test` | Runs the reasoner: fails when the ontology is inconsistent or a "
                + "class can have no instances |\n"
                + "| `sparql_test` | The checks in `src/sparql/` that `src/ontology/" + id
                + "-odk.yaml` names |\n"
                + "| `robot_reports` | ROBOT's quality report with the rules of "
                + "`src/ontology/profile.txt`, failing on anything at ERROR |\n"
                + "| `validate_idranges` | That the ID ranges are well-formed and do not overlap |\n"
                + "| `test` | All of these, and that the ontology is in OWL 2 DL - after bringing "
                + "the imports up to date, which needs the network |\n"
                + "| `test_fast` | The same, with the imports as they are |\n";
    }

    /**
     * The path under {@code docs/} that GitHub Pages serves an IRI on {@code github.io} from, or
     * null when it has no path. A project site, {@code https://<user>.github.io/<repo>/},
     * publishes {@code docs/X} at {@code .../<repo>/X}, so it is the path after the repository; a
     * user site's IRI, {@code https://<user>.github.io/x.owl}, has only the one segment.
     */
    static String underDocs(String iri) {
        String path;
        try {
            path = new java.net.URI(iri).getPath();
        } catch (java.net.URISyntaxException notAnIri) {
            return null;
        }
        if (path == null) {
            return null;
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return null;
        }
        int repository = path.indexOf('/');
        return repository < 0 ? path : path.substring(repository + 1);
    }

    /**
     * The project's release page: numbered releases, committed and tagged, as Release... makes
     * them. On GitHub Pages it also says what to copy into {@code docs/} for the IRIs to answer:
     * Release... writes only the project's own folders.
     */
    private static String docsRelease(OdkProjectConfig c) {
        String id = c.getOntologyId();
        String base = OdkConfigWriter.ontBase(c);
        boolean pages = c.getBaseIri().contains(".github.io/");
        // Where GitHub Pages serves the two IRIs from: the path after the repository's name.
        String file = pages ? underDocs(c.getBaseIri()) : null;
        String versions = pages ? underDocs(base) : null;
        boolean served = file != null && versions != null;
        return "# Releasing " + OdkConfigWriter.oneLine(c.getTitle()) + "\n\n"
                + "A release is a frozen, numbered copy - 1.0.0, then 1.1.0 and so on - that other\n"
                + "ontologies can import and that will not change under them.\n\n"
                + "1. Make sure `test` passes.\n"
                + "2. **OntoBoard > Build and release > Release...** proposes the next number and\n"
                + "   the version IRI, `" + base + "/releases/1.0.0/" + id + ".owl` unless you\n"
                + "   choose another shape. It runs ODK's `prepare_release`, which builds the\n"
                + "   release files and copies them to the top of the repository, `" + id
                + ".owl`\n"
                + "   among them, and it copies them to `releases/1.0.0/` as well. From the second\n"
                + "   release on, `releases/<version>/CHANGES.md` says what changed.\n"
                + "3. It then shows the git commands it is about to run, and runs them when you\n"
                + "   agree: the release files are committed - ODK keeps them out of git until\n"
                + "   then - and the commit is tagged `v1.0.0`.\n"
                + "4. Push the commit and its tag: `git push --follow-tags`.\n"
                + (served
                        ? "5. GitHub Pages serves `docs/`, so copy the release there for its IRIs "
                                + "to\n   answer: `" + id + ".owl` to `docs/" + file + "`, and "
                                + "`releases/1.0.0/" + id + ".owl` to\n   `docs/" + versions
                                + "/releases/1.0.0/" + id + ".owl`. Commit and push that too.\n"
                        : "")
                + "\n"
                + workflowPermissionsOnAPage(c.getGithubOrg(), id) + "\n\n"
                + "From a terminal, the build alone is `sh run.sh make prepare_release` in\n"
                + "`src/ontology`, which needs Docker.\n\n"
                + "A version IRI cannot be taken back once somebody has imported it, so a version\n"
                + "that has been released is not released again: the next release gets the next\n"
                + "number.\n";
    }
}
