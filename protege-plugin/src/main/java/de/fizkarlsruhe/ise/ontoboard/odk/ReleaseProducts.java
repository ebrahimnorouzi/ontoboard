package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Whether a file is one of an ODK project's release files, and which edit file it is made from.
 *
 * <p>Found by the user (1.155.0): with the egg project's root {@code egg.owl} open - a file ODK's
 * release writes, and the next release writes again - a pattern's import put its module, mirror
 * and catalog beside it, at the project's root, while its term list went to
 * {@code src/ontology/imports/}; and <i>Release...</i> released it as an ontology of its own into
 * the project's own {@code releases/}. Work done in a release file is lost at the next release,
 * or changes what was published. So OntoBoard says what the file is, and where to work instead.
 *
 * <p>A release file is, in a folder whose {@code src/ontology/} holds the project's
 * configuration ({@code <id>-odk.yaml}) and its edit file ({@code <id>-edit.*}): one of the
 * files ODK's own {@code .gitignore} names at the top - {@code <id>.owl}, {@code .obo},
 * {@code .json}, {@code .db}, and {@code <id>-base}, {@code -basic}, {@code -full},
 * {@code -simple}, {@code -simple-non-classified} in any format - or {@code <id>.ttl} and
 * {@code <id>.ofn}, which projects export too; or anything under {@code releases/<version>/},
 * the copies of published releases OntoBoard keeps.
 */
public final class ReleaseProducts {

    /** Where the release file lies. */
    public enum Where {
        /** At the top of the project, where ODK's release writes it. */
        TOP,
        /** Under releases/&lt;version&gt;/, a copy of a published release. */
        RELEASES
    }

    private static final List<String> FORMATS = Arrays.asList("owl", "obo", "json", "db",
            "ttl", "ofn");

    private static final List<String> VARIANTS = Arrays.asList("base", "basic", "full",
            "simple", "simple-non-classified");

    private static final List<String> EDIT_FORMATS = Arrays.asList("owl", "obo", "ofn", "ttl",
            "omn", "owx");

    /** A release file, and the project it belongs to. */
    public static final class Product {
        private final File file;
        private final File projectRoot;
        private final String id;
        private final File editFile;
        private final Where where;
        private final String version;

        Product(File file, File projectRoot, String id, File editFile, Where where,
                String version) {
            this.file = file;
            this.projectRoot = projectRoot;
            this.id = id;
            this.editFile = editFile;
            this.where = where;
            this.version = version;
        }

        public File file() {
            return file;
        }

        public File projectRoot() {
            return projectRoot;
        }

        /** The project's id, as its configuration's name has it. */
        public String id() {
            return id;
        }

        /** The file the project is edited in, which the release is made from. */
        public File editFile() {
            return editFile;
        }

        public Where where() {
            return where;
        }

        /** The release's version for a copy under releases/, else null. */
        public String version() {
            return version;
        }

        /** The edit file, as a path from the project's top. */
        public String editFileShown() {
            return relative(projectRoot, editFile);
        }

        /** The release file, as a path from the project's top. */
        public String fileShown() {
            return relative(projectRoot, file);
        }
    }

    private ReleaseProducts() {
    }

    /** The project {@code file} is a release file of, or null when it is none. */
    public static Product of(File file) {
        if (file == null) {
            return null;
        }
        File absolute = file.getAbsoluteFile();
        File folder = absolute.getParentFile();
        if (folder == null) {
            return null;
        }
        // At the top of a project.
        Product top = atTop(absolute, folder);
        if (top != null) {
            return top;
        }
        // Under releases/<version>/, at any depth below the version's folder.
        for (File inside = folder; inside != null && inside.getParentFile() != null;
                inside = inside.getParentFile()) {
            File releases = inside.getParentFile();
            if ("releases".equals(releases.getName())
                    && OntologyVersion.parseOrNull(inside.getName()) != null) {
                File root = releases.getParentFile();
                Project project = root == null ? null : projectAt(root);
                if (project != null) {
                    return new Product(absolute, root, project.id, project.editFile,
                            Where.RELEASES, inside.getName());
                }
                return null;
            }
        }
        return null;
    }

    private static Product atTop(File file, File root) {
        Project project = projectAt(root);
        if (project == null) {
            return null;
        }
        String name = file.getName();
        return isProductName(name, project.id)
                ? new Product(file, root, project.id, project.editFile, Where.TOP, null) : null;
    }

    /**
     * Whether a file of this name at a project's top is one of ODK's release files of the
     * ontology {@code id}: {@code <id>.owl} and its other formats, {@code <id>-base.owl}, ...
     */
    static boolean isProductName(String name, String id) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String stem = name.substring(0, dot);
        String format = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        boolean product = stem.equals(id) && FORMATS.contains(format);
        for (String variant : VARIANTS) {
            if (stem.equals(id + "-" + variant)) {
                product = true;
            }
        }
        return product;
    }

    /** An ODK project's id and edit file, when {@code root} is the top of one. */
    private static final class Project {
        final String id;
        final File editFile;

        Project(String id, File editFile) {
            this.id = id;
            this.editFile = editFile;
        }
    }

    private static Project projectAt(File root) {
        File ontology = new File(new File(root, "src"), "ontology");
        File yaml = OdkBuildSettings.yamlIn(ontology);
        if (yaml == null) {
            return null;
        }
        String id = yaml.getName().substring(0,
                yaml.getName().length() - "-odk.yaml".length());
        for (String format : EDIT_FORMATS) {
            File edit = new File(ontology, id + "-edit." + format);
            if (edit.isFile()) {
                return new Project(id, edit);
            }
        }
        return null;
    }

    /**
     * Why {@code what} is not done to {@code file}, in a sentence that says where to do it; null
     * when the file is no release file.
     *
     * @param what what would be lost or would change a release, as "the pattern's import"
     */
    public static String refusal(File file, String what) {
        Product product = of(file);
        if (product == null) {
            return null;
        }
        if (product.where() == Where.RELEASES) {
            return "This is " + product.fileShown() + ", the " + product.version()
                    + " release of the ODK project " + product.id() + ", kept as it was "
                    + "published: " + what + " here would change a published release. Open the "
                    + "edit file, " + product.editFileShown() + ", and make it there.";
        }
        return "This is " + product.fileShown() + ", a release file of the ODK project "
                + product.id() + ", made from " + product.editFileShown() + ": the next "
                + "release writes it again, so " + what + " would be lost. Open the edit file, "
                + product.editFileShown() + ", and make it there.";
    }

    /**
     * Why a release file is not released as an ontology of its own, in a sentence that says where
     * to release from; null when the file is no release file. The egg project's root egg.owl was
     * released into its own project's releases/ (1.155.0).
     */
    public static String releaseRefusal(File file) {
        Product product = of(file);
        if (product == null) {
            return null;
        }
        if (product.where() == Where.RELEASES) {
            return "This is " + product.fileShown() + ", the " + product.version()
                    + " release of the ODK project " + product.id() + ", kept as it was "
                    + "published. A release is made from the edit file, "
                    + product.editFileShown() + ": open it and release from there.";
        }
        return "This is " + product.fileShown() + ", a release file of the ODK project "
                + product.id() + ", made from " + product.editFileShown() + " - the next release "
                + "writes it again. A release is made from the edit file: open it and release "
                + "from there.";
    }

    private static String relative(File root, File file) {
        try {
            return root.getCanonicalFile().toPath()
                    .relativize(file.getCanonicalFile().toPath()).toString().replace('\\', '/');
        } catch (java.io.IOException | IllegalArgumentException elsewhere) {
            return file.getAbsolutePath();
        }
    }
}
