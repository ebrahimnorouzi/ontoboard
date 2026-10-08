package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The imports a project's {@code import_group.products} declares, and where each comes from.
 *
 * <p><b>The measurement that motivates this.</b> <i>Project &gt; Refresh imports</i> found its
 * imports by listing {@code src/ontology/imports/} and read each one's upstream source from a
 * {@code # Source:} comment at the top of the term list. That comment is OntoBoard's own invention,
 * written by {@link ImportModules#writeTerms}. Across four real ODK projects - NFDIcore, MWO,
 * PMDCO and ECTO - there are 39 term lists and <b>none</b> of them carries it. So the rebuild
 * worked on projects OntoBoard had created and on no others, and every import of every real
 * repository reported "does not record where its terms came from".
 *
 * <p>The project said where they came from all along. Each product in {@code import_group.products}
 * names an {@code id}, and either a {@code mirror_from} URL or nothing - in which case ODK downloads
 * {@code http://purl.obolibrary.org/obo/<id>.owl}. Reading that takes all 35 products those four
 * projects declare from no known source to a known one.
 *
 * <p><b>Knowing the source is not the same as being able to rebuild.</b> {@code module_type} decides
 * how ODK turns the mirror into a module, and only one of its values is an extraction from the term
 * list. Of those 35 products, 15 are; the other 20 are named with the reason rather than rebuilt
 * wrongly. {@link Product#getRefusal()} is where that reason lives, and the most important one is
 * {@code custom}: ODK generates a rule for a custom module whose whole body is
 *
 * <pre>
 * &#64;echo "ERROR: You have configured {id} as a custom module;"
 * &#64;echo "       This rule needs to be overwritten in {project}.Makefile!"
 * &#64;false
 * </pre>
 *
 * <p>- so ODK itself declines, and the real command is hand-written in the project's own Makefile.
 * Fourteen of the 35 are custom. Rebuilding them generically would overwrite a module somebody
 * wrote a bespoke ROBOT pipeline for, and the user would have no way to tell.
 *
 * <p><b>Every default here is quoted from ODK, not guessed.</b> The source resolution is
 * {@code Makefile.jinja2}'s {@code download-mirror-} rule; the inherited {@code slme} defaults are
 * {@code ImportGroup}'s field initialisers in {@code odkcore/model.py} ({@code module_type =
 * "slme"}, {@code module_type_slme = "BOT"}, {@code slme_individuals = "include"}), and the
 * product-inherits-group rule is its {@code derive_fields}. The generated Makefile of a real ODK
 * project agrees: a bare {@code - id: iao} became {@code curl -L $(OBOBASE)/iao.owl} and
 * {@code extract … --individuals include --method BOT}.
 */
public final class ImportProducts {

    private ImportProducts() {
    }

    /** Where ODK downloads an import from when the product does not say. ODK's {@code OBOBASE}. */
    public static final String OBO_BASE = "http://purl.obolibrary.org/obo";

    /** {@code ImportGroup.module_type}'s default in odkcore's model. */
    public static final String DEFAULT_MODULE_TYPE = "slme";

    /** {@code ImportGroup.module_type_slme}'s default in odkcore's model. */
    public static final String DEFAULT_SLME_METHOD = "BOT";

    /** {@code ImportGroup.slme_individuals}'s default in odkcore's model. */
    public static final String DEFAULT_SLME_INDIVIDUALS = "include";

    /** {@code mirror_type} for a product with no upstream at all, so no source and no refresh. */
    public static final String NO_MIRROR = "no_mirror";

    private static final String PRODUCTS = "import_group.products";

    /**
     * One declared import, with every inherited default already resolved.
     *
     * <p>Resolved rather than raw, because the thing a user needs told is what the build will do -
     * and a product saying nothing at all still gets BOT with individuals included, from the group.
     */
    public static final class Product {

        private final String id;
        private final String mirrorFrom;
        private final String moduleType;
        private final String slmeMethod;
        private final String slmeIndividuals;
        private final String mirrorType;
        private final String useVariant;
        private final boolean gzipped;
        private final boolean large;

        Product(String id, String mirrorFrom, String moduleType, String slmeMethod,
                String slmeIndividuals, String mirrorType, String useVariant, boolean gzipped,
                boolean large) {
            this.id = id;
            this.mirrorFrom = mirrorFrom;
            this.moduleType = moduleType;
            this.slmeMethod = slmeMethod;
            this.slmeIndividuals = slmeIndividuals;
            this.mirrorType = mirrorType;
            this.useVariant = useVariant;
            this.gzipped = gzipped;
            this.large = large;
        }

        /** The product's {@code id}, which names both its module and its term list. */
        public String getId() {
            return id;
        }

        /** As the project wrote it, or null when it did not. */
        public String getMirrorFrom() {
            return mirrorFrom;
        }

        /** Resolved: the product's own, else the group's, else {@code slme}. */
        public String getModuleType() {
            return moduleType;
        }

        /** Resolved SLME method, or null when this is not an SLME module. */
        public String getSlmeMethod() {
            return slmeMethod;
        }

        /** Resolved individuals handling, or null when this is not an SLME module. */
        public String getSlmeIndividuals() {
            return slmeIndividuals;
        }

        /** Whether ODK mirrors this product at all. {@code no_mirror} means it does not. */
        public boolean isMirrored() {
            return !NO_MIRROR.equalsIgnoreCase(mirrorType);
        }

        /** True when a declared {@code mirror_from} gave the source, rather than the OBO PURL. */
        public boolean isSourceDeclared() {
            return mirrorFrom != null;
        }

        /**
         * Where ODK downloads this product from, or null when it mirrors nothing.
         *
         * <p>Exactly {@code Makefile.jinja2}'s {@code download-mirror-} rule: {@code mirror_from}
         * if given, else the {@code use_variant} path under the product's own directory, else
         * {@code $(OBOBASE)/<id>.owl} - and {@code .gz} on the end when {@code use_gzipped}.
         */
        public String getSource() {
            if (!isMirrored()) {
                return null;
            }
            if (mirrorFrom != null) {
                return mirrorFrom;
            }
            String path = useVariant != null
                    ? OBO_BASE + "/" + id + "/" + id + "-" + useVariant + ".owl"
                    : OBO_BASE + "/" + id + ".owl";
            return gzipped ? path + ".gz" : path;
        }

        /** ODK's {@code is_large}, which its own refresh target can be told to skip. */
        public boolean isLarge() {
            return large;
        }

        /**
         * The extraction OntoBoard would run, or null when it will not rebuild this product.
         *
         * <p>Null and {@link #getRefusal()} non-null are the same condition stated two ways: one
         * for the code, one for the user.
         */
        public TermExtract.Method getMethod() {
            if (!DEFAULT_MODULE_TYPE.equalsIgnoreCase(moduleType)) {
                return null;
            }
            for (TermExtract.Method method : TermExtract.Method.values()) {
                if (method.getLabel().equalsIgnoreCase(slmeMethod)) {
                    return method;
                }
            }
            return null;
        }

        /** Whether a rebuild here would produce the module ODK's extraction produces. */
        public boolean isRebuildable() {
            return getMethod() != null;
        }

        /**
         * Why OntoBoard will not rebuild this product, or null when it will.
         *
         * <p>Each reason is the ODK Makefile rule for that {@code module_type}, said in words.
         * Reporting beats guessing in every one of these cases, because the failure of a guess is
         * a module that looks right, differs from the one the repository holds, and says nothing.
         */
        public String getRefusal() {
            if (isRebuildable()) {
                return null;
            }
            String type = moduleType == null ? "" : moduleType.toLowerCase(Locale.ROOT);
            if ("custom".equals(type)) {
                return "module_type: custom - ODK's own generated rule for a custom module does "
                        + "nothing but print \"This rule needs to be overwritten in "
                        + "<project>.Makefile\" and fail, because the real command is hand-written "
                        + "there. ODK declines to build it generically and so does this: a "
                        + "generic rebuild would overwrite a module somebody wrote a bespoke "
                        + "pipeline for.";
            }
            if ("mirror".equals(type)) {
                return "module_type: mirror - the module is the mirrored ontology itself. ODK's "
                        + "rule for it does not depend on the term list at all, so there is "
                        + "nothing here to extract; refreshing the mirror is the whole job.";
            }
            if ("minimal".equals(type)) {
                return "module_type: minimal - ODK extracts BOT and then strips every external "
                        + "axiom and everything that is not a class, individual or annotation "
                        + "property. This runs the extraction and not the stripping, so the "
                        + "module would come out larger than the one in the repository.";
            }
            if ("filter".equals(type)) {
                return "module_type: filter - ODK extracts BOT and then removes the axioms "
                        + "outside the product's base IRIs. This runs the extraction and not the "
                        + "removal, so the module would come out larger than the one in the "
                        + "repository.";
            }
            if (DEFAULT_MODULE_TYPE.equals(type)) {
                return "module_type_slme: " + slmeMethod + " is not an extraction this can run. "
                        + "ODK documents BOT, TOP and STAR; ROBOT also has MIREOT, and all four "
                        + "work here. SUBSET is the one real projects use that does not: ROBOT "
                        + "reaches it by a different route, and rebuilding with BOT instead would "
                        + "quietly produce a different module.";
            }
            return "module_type: " + moduleType + " is not a type this knows. ODK's own list is "
                    + "slme, minimal, custom, mirror and filter.";
        }

        /** The {@code module_type}, with its SLME parameters when it has them. */
        public String describeModuleType() {
            if (!DEFAULT_MODULE_TYPE.equalsIgnoreCase(moduleType)) {
                return moduleType;
            }
            return moduleType + " " + slmeMethod + ", individuals " + slmeIndividuals;
        }

        @Override
        public String toString() {
            return id + " (" + describeModuleType() + ")";
        }
    }

    /**
     * The products this project declares, in the order the file declares them.
     *
     * <p>Empty on anything at all - no project, no file, unreadable YAML, no {@code import_group}.
     * A caller that finds nothing here falls back to listing the directory, which is what this
     * replaced and is still right for a project whose configuration does not mention an import
     * that is nonetheless on disk.
     */
    public static List<Product> declaredIn(File ontologyDirectory) {
        try {
            File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
            if (yaml == null) {
                return Collections.emptyList();
            }
            return declaredIn(new String(java.nio.file.Files.readAllBytes(yaml.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        } catch (java.io.IOException unreadable) {
            return Collections.emptyList();
        }
    }

    /** The same, from the file's text. */
    public static List<Product> declaredIn(String yamlText) {
        Map<String, String> group = new LinkedHashMap<String, String>();
        Map<Integer, Map<String, String>> products = new LinkedHashMap<Integer, Map<String,
                String>>();
        try {
            for (OdkYaml.Entry entry : OdkYaml.entriesIn(yamlText)) {
                String path = entry.getPath();
                if (path.startsWith(PRODUCTS + "[")) {
                    int close = path.indexOf(']');
                    int dot = path.indexOf('.', close);
                    if (close < 0 || dot < 0) {
                        continue;
                    }
                    Integer at = Integer.valueOf(path.substring(PRODUCTS.length() + 1, close));
                    Map<String, String> fields = products.get(at);
                    if (fields == null) {
                        fields = new LinkedHashMap<String, String>();
                        products.put(at, fields);
                    }
                    fields.put(path.substring(dot + 1), entry.getValue());
                } else if (path.startsWith("import_group.") && path.indexOf('[') < 0) {
                    group.put(path.substring("import_group.".length()), entry.getValue());
                }
            }
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        }

        String groupType = valueOr(group.get("module_type"), DEFAULT_MODULE_TYPE);
        String groupMethod = valueOr(group.get("module_type_slme"), DEFAULT_SLME_METHOD);
        String groupIndividuals = valueOr(group.get("slme_individuals"),
                DEFAULT_SLME_INDIVIDUALS);

        List<Product> declared = new ArrayList<Product>();
        for (Map<String, String> fields : products.values()) {
            String id = value(fields.get("id"));
            if (id == null) {
                // A product with no id names no module and no term list; ODK's own template
                // interpolates ont.id into both file names, so there is nothing to report about.
                continue;
            }
            String type = valueOr(fields.get("module_type"), groupType);
            if ("fast_slme".equalsIgnoreCase(type)) {
                // odkcore normalises this to slme for backwards compatibility, and so must this -
                // otherwise an older project's every import reads as an unknown type.
                type = DEFAULT_MODULE_TYPE;
            }
            boolean slme = DEFAULT_MODULE_TYPE.equalsIgnoreCase(type);
            declared.add(new Product(id,
                    value(fields.get("mirror_from")),
                    type,
                    slme ? valueOr(fields.get("module_type_slme"), groupMethod) : null,
                    slme ? valueOr(fields.get("slme_individuals"), groupIndividuals) : null,
                    value(fields.get("mirror_type")),
                    value(fields.get("use_variant")),
                    isTrue(fields.get("use_gzipped")),
                    isTrue(fields.get("is_large"))));
        }
        return declared;
    }

    /** What a refresh will do with one import, which is the thing the user reads. */
    public enum Verdict {
        /** Declared as an SLME module, with a term list and a source: a refresh rebuilds it. */
        REBUILD,

        /** Declared, but its {@code module_type} is not one this rebuilds. */
        REFUSED,

        /** Declared as a kind that needs a term list, and has none. Nor can ODK build it. */
        NO_TERM_LIST,

        /** Declared with {@code mirror_type: no_mirror}, so there is no upstream to extract. */
        NO_SOURCE,

        /**
         * On disk, and in no product.
         *
         * <p>ODK's {@code IMPORTS} list comes from {@code import_group.products}, so nothing
         * regenerates this file. It is still rebuilt when its term list records a source, because
         * that is what a refresh did before it read the declaration.
         */
        UNDECLARED
    }

    /** One import, as a refresh sees it: what the project says and what is on disk. */
    public static final class Row {
        private final String name;
        private final Product product;
        private final ImportModules.Module module;
        private final String source;
        private final Verdict verdict;

        Row(String name, Product product, ImportModules.Module module, String source,
                Verdict verdict) {
            this.name = name;
            this.product = product;
            this.module = module;
            this.source = source;
            this.verdict = verdict;
        }

        public String getName() {
            return name;
        }

        /** The declaration, or null when this import is on disk and in no product. */
        public Product getProduct() {
            return product;
        }

        public ImportModules.Module getModule() {
            return module;
        }

        /** Where a rebuild would read from, or null when there is nowhere. */
        public String getSource() {
            return source;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        /** Whether a refresh rebuilds this one. */
        public boolean willRebuild() {
            return verdict == Verdict.REBUILD
                    || (verdict == Verdict.UNDECLARED && source != null
                            && module.isRebuildable());
        }

        /** How to describe the declaration in a column. */
        public String describeDeclaration() {
            return product == null ? "NOT DECLARED" : product.describeModuleType();
        }

        /**
         * Whether anything reads this import's term list.
         *
         * <p>Which decides whether an absent one is missing or merely unused. A {@code mirror}
         * module's ODK rule depends only on the mirror, so NFDIcore's {@code bfo_terms.txt} is a
         * file its own build never opens, and calling that MISSING would be wrong.
         */
        public boolean termListIsRead() {
            return product == null || needsTermList(product);
        }

        /** The extraction to run, or null when this one is not rebuilt. */
        public TermExtract.Method getMethod() {
            if (!willRebuild()) {
                return null;
            }
            // An undeclared module has no module_type to read a method from, so it gets the ODK
            // default - which is what a refresh used for every module before this read the
            // declaration, so nothing about those rebuilds changes.
            return product == null ? TermExtract.Method.BOT : product.getMethod();
        }

        @Override
        public String toString() {
            return name + " " + verdict;
        }
    }

    /**
     * Every import of a project, the declaration merged with the directory.
     *
     * <p>Pure, and separate from the menu action, because the merge is where the judgement is: a
     * declared import can be absent, an import on disk can be undeclared, and only one of the two
     * sources of truth knows about each. Deciding that inside a Swing action would make it
     * untestable, and the first draft of it had a regression in the undeclared case.
     *
     * @param declared what {@code import_group.products} says, in the file's order
     * @param onDisk what {@link ImportModules#modulesIn} found
     * @param projectRoot where to look for each declared product's files
     */
    public static List<Row> audit(List<Product> declared, List<ImportModules.Module> onDisk,
            File projectRoot) {
        List<Row> rows = new ArrayList<Row>();
        java.util.Set<String> declaredNames = new java.util.LinkedHashSet<String>();

        for (Product product : declared) {
            declaredNames.add(product.getId());
            ImportModules.Module module = ImportModules.moduleFor(projectRoot, product.getId());
            String source = product.getSource();
            Verdict verdict;
            if (needsTermList(product) && !module.isRebuildable()) {
                verdict = Verdict.NO_TERM_LIST;
            } else if (!product.isRebuildable()) {
                verdict = Verdict.REFUSED;
            } else if (source == null) {
                verdict = Verdict.NO_SOURCE;
            } else {
                verdict = Verdict.REBUILD;
            }
            rows.add(new Row(product.getId(), product, module, source, verdict));
        }

        for (ImportModules.Module module : onDisk) {
            if (declaredNames.contains(module.getName())) {
                continue;
            }
            rows.add(new Row(module.getName(), null, module, recordedSourceIn(module),
                    Verdict.UNDECLARED));
        }
        return rows;
    }

    /**
     * Whether ODK's rule for this product reads the term list at all.
     *
     * <p>A {@code mirror} module does not: its rule depends only on the mirror, so NFDIcore's
     * {@code bfo_terms.txt} is a file its own build never opens. Reporting that one MISSING would
     * be wrong. A {@code custom} module's real rule is hand-written and may read anything, so its
     * term list is not demanded either.
     */
    private static boolean needsTermList(Product product) {
        String type = product.getModuleType() == null ? ""
                : product.getModuleType().toLowerCase(Locale.ROOT);
        return DEFAULT_MODULE_TYPE.equals(type) || "minimal".equals(type)
                || "filter".equals(type);
    }

    /** The {@code # Source:} line OntoBoard writes into a term list, or null. */
    private static String recordedSourceIn(ImportModules.Module module) {
        try {
            return ImportModules.sourceIn(module.getTermsFile());
        } catch (java.io.IOException cannotRead) {
            return null;
        }
    }

    /** The product with this id, or null. */
    public static Product named(List<Product> products, String id) {
        for (Product product : products) {
            if (product.getId().equals(id)) {
                return product;
            }
        }
        return null;
    }

    /**
     * A value, trimmed, with any surviving quotes off, or null when absent or empty.
     *
     * <p>snakeyaml has already unquoted a scalar by the time it reaches here, which matters
     * because these files quote inconsistently - PMDCO quotes the ChEBI {@code mirror_from} and
     * not the RO one. The unquoting below is therefore a no-op on a parsed scalar and is kept
     * only so that a value reaching this from anywhere else cannot arrive as a quoted URL.
     */
    private static String value(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.isEmpty() ? null : text;
    }

    private static String valueOr(String raw, String fallback) {
        String text = value(raw);
        return text == null ? fallback : text;
    }

    /** YAML's booleans, as ODK files actually write them: {@code TRUE}, {@code true}, {@code yes}. */
    private static boolean isTrue(String raw) {
        String text = value(raw);
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return "true".equals(lower) || "yes".equals(lower) || "on".equals(lower);
    }
}
