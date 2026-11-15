package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * What an ODK configuration means once ODK has read it: {@code odk.py}'s {@code OntologyProject}.
 *
 * <p>ODK's templates never see the YAML. They see the object {@code Generator.load_config} builds
 * from it, and three things happen on the way that decide what a seeded file says:
 *
 * <ul>
 *   <li><b>Defaults.</b> Every key the config leaves out takes its dataclass default - and the
 *       defaults are not all what one would guess: {@code description} is the string "None",
 *       {@code repo} is "noname", {@code release_artefacts} is full and base.
 *   <li><b>{@code robot_report} is a plain dict.</b> A config with that block gets exactly the
 *       block; none of {@code ReportConfig}'s defaults are merged in. A config without it gets
 *       all of them. That is why one Makefile runs four SPARQL checks and another five.
 *   <li><b>Derived fields</b> ({@code fill_missing}): an import product takes its group's module
 *       type, a component its base IRI, and the products that differ from their group are
 *       collected as {@code special_products}, each of which gets a Makefile rule of its own.
 * </ul>
 *
 * <p>This is that code, ported from ODK v1.6.1 ({@code odk/odk.py} lines 41 to 930), with dacite's
 * rules for reading a dict into a dataclass: an unknown key is ignored, a value of the wrong type
 * is refused, a missing one defaults. It is held to ODK's own dump of the object
 * ({@code odk.py export-project}) for every config the tests have - which is the only reason to
 * believe a line of it.
 */
public final class OdkProjectModel {

    private OdkProjectModel() {
    }

    // ===================================================================== the schema

    private enum Kind { STR, BOOL, INT, LIST_STR, DICT, OBJECT, LIST_OBJECT }

    /** Marks a field with no default: dacite refuses a dict that lacks it. */
    private static final Object REQUIRED = new Object();

    private static final class Field {
        final String name;
        final Kind kind;
        final boolean optional;
        final String of;
        final Object dflt;

        Field(String name, Kind kind, boolean optional, String of, Object dflt) {
            this.name = name;
            this.kind = kind;
            this.optional = optional;
            this.of = of;
            this.dflt = dflt;
        }
    }

    private static final Map<String, List<Field>> CLASSES = new LinkedHashMap<String, List<Field>>();

    private static Field str(String name, String dflt) {
        return new Field(name, Kind.STR, false, null, dflt);
    }

    /** {@code name : str = None} - annotated as a string, defaulting to None all the same. */
    private static Field strNone(String name) {
        return new Field(name, Kind.STR, false, null, Py.NONE);
    }

    private static Field optStr(String name) {
        return new Field(name, Kind.STR, true, null, Py.NONE);
    }

    private static Field optStr(String name, String dflt) {
        return new Field(name, Kind.STR, true, null, dflt);
    }

    private static Field required(String name) {
        return new Field(name, Kind.STR, false, null, REQUIRED);
    }

    private static Field bool(String name, boolean dflt) {
        return new Field(name, Kind.BOOL, false, null, Boolean.valueOf(dflt));
    }

    private static Field integer(String name, long dflt) {
        return new Field(name, Kind.INT, false, null, Long.valueOf(dflt));
    }

    private static Field optInt(String name) {
        return new Field(name, Kind.INT, true, null, Py.NONE);
    }

    private static Field list(String name, String... dflt) {
        return new Field(name, Kind.LIST_STR, false, null, Arrays.asList(dflt));
    }

    /** {@code name : List[str] = None}. */
    private static Field listNone(String name) {
        return new Field(name, Kind.LIST_STR, false, null, Py.NONE);
    }

    private static Field optList(String name) {
        return new Field(name, Kind.LIST_STR, true, null, Py.NONE);
    }

    private static Field optList(String name, String... dflt) {
        return new Field(name, Kind.LIST_STR, true, null, Arrays.asList(dflt));
    }

    private static Field obj(String name, String of) {
        return new Field(name, Kind.OBJECT, true, of, Py.NONE);
    }

    private static Field objs(String name, String of) {
        return new Field(name, Kind.LIST_OBJECT, true, of, Py.NONE);
    }

    private static void define(String type, List<Field> base, Field... own) {
        List<Field> fields = new ArrayList<Field>(base);
        fields.addAll(Arrays.asList(own));
        CLASSES.put(type, fields);
    }

    private static final List<Field> NOTHING = new ArrayList<Field>();

    /** {@code Product}: what every kind of product starts with. */
    private static final List<Field> PRODUCT = Arrays.asList(
            required("id"),
            optStr("description"),
            str("maintenance", "manual"),
            bool("rebuild_if_source_changes", true),
            obj("robot_settings", "CommandSettings"));

    /** {@code ProductGroup}: what every group of products starts with. */
    private static final List<Field> GROUP = Arrays.asList(
            optList("ids"),
            bool("disabled", false),
            bool("rebuild_if_source_changes", true));

    private static final String[] IMPORT_ANNOTATIONS = {"rdfs:label", "IAO:0000115", "OMO:0002000"};

    static {
        define("CommandSettings", NOTHING,
                optInt("memory_gb"));
        define("SubsetProduct", PRODUCT,
                optList("creators"));
        define("ComponentProduct", NOTHING,
                optStr("filename"),
                optStr("source"),
                bool("use_template", false),
                bool("use_mappings", false),
                optStr("template_options"),
                optStr("sssom_tool_options", ""),
                optList("templates"),
                optList("mappings"),
                optList("base_iris"),
                bool("make_base", false));
        define("ImportProduct", PRODUCT,
                optStr("mirror_from"),
                optList("base_iris"),
                bool("is_large", false),
                optStr("module_type"),
                optStr("module_type_slme"),
                list("annotation_properties", IMPORT_ANNOTATIONS),
                optStr("slme_individuals"),
                bool("use_base", false),
                bool("make_base", false),
                bool("use_gzipped", false),
                optStr("mirror_type"));
        define("PatternPipelineProduct", PRODUCT,
                str("dosdp_tools_options", "--obo-prefixes=true"),
                str("ontology", "$(SRC)"));
        define("SSSOMMappingSetProduct", PRODUCT,
                optStr("mirror_from"),
                optStr("source_file"),
                optStr("sssom_tool_options", ""),
                bool("release_mappings", false),
                optList("source_mappings"));
        define("BabelonTranslationProduct", PRODUCT,
                optStr("mirror_babelon_from"),
                optStr("mirror_synonyms_from"),
                bool("include_robot_template_synonyms", false),
                optStr("babelon_tool_options", ""),
                str("language", "en"),
                bool("include_not_translated", false),
                bool("update_translation_status", true),
                bool("drop_unknown_columns", true),
                bool("auto_translate", false));
        define("SubsetGroup", GROUP,
                objs("products", "SubsetProduct"));
        define("ImportGroup", GROUP,
                objs("products", "ImportProduct"),
                str("module_type", "slme"),
                str("module_type_slme", "BOT"),
                str("slme_individuals", "include"),
                integer("mirror_retry_download", 4),
                integer("mirror_max_time_download", 200),
                bool("release_imports", false),
                bool("use_base_merging", false),
                bool("base_merge_drop_equivalent_class_axioms", false),
                optList("exclude_iri_patterns"),
                bool("export_obo", false),
                list("annotation_properties", IMPORT_ANNOTATIONS),
                bool("strip_annotation_properties", true),
                bool("annotate_defined_by", false),
                bool("scan_signature", true));
        define("DocumentationGroup", NOTHING,
                optStr("documentation_system", "mkdocs"));
        define("ComponentGroup", GROUP,
                objs("products", "ComponentProduct"));
        define("PatternPipelineGroup", GROUP,
                objs("products", "PatternPipelineProduct"),
                objs("matches", "PatternPipelineProduct"));
        define("SSSOMMappingSetGroup", NOTHING,
                bool("release_mappings", false),
                str("mapping_extractor", "sssom-py"),
                objs("products", "SSSOMMappingSetProduct"));
        define("BabelonTranslationSetGroup", NOTHING,
                bool("release_merged_translations", false),
                optList("predicates", "IAO:0000115", "rdfs:label"),
                str("oak_adapter", "pronto:$(ONT).obo"),
                str("translate_ontology", "$(ONT).obo"),
                objs("products", "BabelonTranslationProduct"));
        define("RobotPlugin", NOTHING,
                str("name", ""),
                optStr("mirror_from"));
        define("PluginsGroup", NOTHING,
                objs("plugins", "RobotPlugin"));
        define("OntologyProject", NOTHING,
                str("id", ""),
                str("title", ""),
                str("git_user", ""),
                str("repo", "noname"),
                str("repo_url", ""),
                str("github_org", ""),
                str("git_main_branch", "main"),
                str("edit_format", "owl"),
                bool("run_as_root", false),
                optStr("robot_version"),
                obj("robot_settings", "CommandSettings"),
                optStr("robot_java_args", ""),
                optStr("owltools_memory", ""),
                bool("use_external_date", false),
                bool("remove_owl_nothing", false),
                bool("export_project_yaml", false),
                str("reasoner", "ELK"),
                str("exclude_tautologies", "structural"),
                str("primary_release", "full"),
                str("license", "https://creativecommons.org/licenses/unspecified"),
                str("description", "None"),
                bool("use_dosdps", false),
                bool("use_templates", false),
                bool("use_mappings", false),
                bool("use_translations", false),
                bool("use_env_file_docker", false),
                bool("use_custom_import_module", false),
                bool("manage_import_declarations", true),
                str("custom_makefile_header", "\n# ----------------------------------------\n"
                        + "# More information: https://github.com/INCATools/ontology-development-kit/\n"),
                bool("use_context", false),
                str("public_release", "none"),
                optList("public_release_assets"),
                bool("release_date", false),
                str("allow_equivalents", "asserted-only"),
                optList("ci", "github_actions"),
                optList("workflows", "docs", "qc"),
                bool("import_pattern_ontology", false),
                str("import_component_format", "ofn"),
                bool("create_obo_metadata", true),
                bool("gzip_main", false),
                list("release_artefacts", "full", "base"),
                bool("release_use_reasoner", true),
                bool("release_annotate_inferred_axioms", false),
                listNone("release_materialize_object_properties"),
                list("export_formats", "owl", "obo"),
                optList("namespaces"),
                bool("use_edit_file_imports", true),
                str("dosdp_tools_options", "--obo-prefixes=true"),
                optList("travis_emails"),
                str("obo_format_options", ""),
                str("robot_relax_options", "--include-subclass-of true"),
                str("robot_reduce_options", "--include-subproperties true"),
                str("catalog_file", "catalog-v001.xml"),
                str("uribase", "http://purl.obolibrary.org/obo"),
                strNone("uribase_suffix"),
                optStr("contact"),
                optList("creators"),
                optList("contributors"),
                new Field("robot_report", Kind.DICT, false, null, null),
                bool("ensure_valid_rdfxml", true),
                bool("extra_rdfxml_checks", false),
                obj("robot_plugins", "PluginsGroup"),
                obj("import_group", "ImportGroup"),
                obj("components", "ComponentGroup"),
                obj("documentation", "DocumentationGroup"),
                obj("subset_group", "SubsetGroup"),
                obj("pattern_pipelines_group", "PatternPipelineGroup"),
                obj("sssom_mappingset_group", "SSSOMMappingSetGroup"),
                obj("babelon_translation_group", "BabelonTranslationSetGroup"),
                bool("release_diff", false));
    }

    /** {@code ReportConfig().to_dict()}: what {@code robot_report} is when the config has none. */
    private static Map<String, Object> reportDefaults() {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("fail_on", Py.NONE);
        report.put("use_labels", Boolean.TRUE);
        report.put("use_base_iris", Boolean.TRUE);
        report.put("custom_profile", Boolean.FALSE);
        report.put("report_on", listOf("edit"));
        report.put("ensure_owl2dl_profile", Boolean.TRUE);
        report.put("release_reports", Boolean.FALSE);
        report.put("custom_sparql_checks", listOf("owldef-self-reference", "iri-range",
                "label-with-iri", "multiple-replaced_by", "dc-properties"));
        report.put("custom_sparql_exports", listOf("basic-report", "class-count-by-prefix",
                "edges", "xrefs", "obsoletes", "synonyms"));
        report.put("sparql_test_on", listOf("edit"));
        report.put("upper_ontology", Py.NONE);
        return report;
    }

    private static List<Object> listOf(String... items) {
        return new ArrayList<Object>(Arrays.asList(items));
    }

    // ===================================================================== reading

    /**
     * Reads a configuration as ODK reads it, defaults and derived fields included.
     *
     * @param configText the file's text; a leading byte-order mark is taken as PyYAML takes it
     * @throws OdkConfigException when ODK itself would refuse it, or it is not a mapping at all
     */
    public static PyObject load(String configText) {
        String text = pythonText(configText);
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor()).load(text);
        } catch (MarkedYAMLException notYaml) {
            Mark mark = notYaml.getProblemMark() != null ? notYaml.getProblemMark()
                    : notYaml.getContextMark();
            int line = mark == null ? 0 : mark.getLine() + 1;
            throw new OdkConfigException("The ODK configuration is not valid YAML"
                    + (line > 0 ? " at line " + line : "") + ": " + notYaml.getProblem()
                    + (notYaml.getContext() == null ? "" : " (" + notYaml.getContext() + ")")
                    + ".", line);
        } catch (YAMLException notYaml) {
            throw new OdkConfigException("The ODK configuration is not valid YAML: "
                    + notYaml.getMessage());
        }
        if (!(raw instanceof Map)) {
            // ODK does not check this - dacite reads a list as "no key given" and ODK seeds a
            // project with no id. Nobody means that.
            throw new OdkConfigException("The ODK configuration is not a set of 'key: value' "
                    + "lines" + (raw == null ? ": it is empty." : "."));
        }
        PyObject project = build("OntologyProject", (Map<?, ?>) raw, "");
        project.setDynamic("config_hash", configHash(configText));
        fillMissing(project);
        return project;
    }

    /**
     * The fingerprint ODK's Makefile records of its configuration ({@code CONFIG_HASH}).
     *
     * <p>SHA-256 of the text as Python's text mode hands it over, so with every line ending a
     * line feed - a config saved on Windows hashes like the same one saved on Linux, which is
     * also what {@code make config_check} computes with {@code tr -d '\r'}.
     */
    public static String configHash(String configText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(pythonText(configText).getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("No SHA-256 in this Java", impossible);
        }
    }

    /** Universal newlines: CRLF and a lone CR are a line feed, as Python reads a text file. */
    static String pythonText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static PyObject build(String type, Map<?, ?> data, String path) {
        PyObject object = new PyObject(type);
        for (Field field : CLASSES.get(type)) {
            String where = path.isEmpty() ? field.name : path + "." + field.name;
            if (data.containsKey(field.name)) {
                object.set(field.name, read(field, data.get(field.name), where));
            } else if (field.dflt == REQUIRED) {
                throw new OdkConfigException((path.isEmpty() ? "The configuration" : path)
                        + " has no '" + field.name + "', and ODK needs one there.");
            } else {
                object.set(field.name, defaultOf(field));
            }
        }
        return object;
    }

    private static Object defaultOf(Field field) {
        if (field.kind == Kind.DICT) {
            return reportDefaults();
        }
        if (field.dflt instanceof List) {
            return new ArrayList<Object>((List<?>) field.dflt);
        }
        return field.dflt;
    }

    /** One value, checked as dacite checks it against the field's type. */
    private static Object read(Field field, Object raw, String where) {
        if (raw == null) {
            if (field.optional) {
                return Py.NONE;
            }
            throw wrong(where, field, raw);
        }
        switch (field.kind) {
            case STR:
                if (raw instanceof String) {
                    return raw;
                }
                throw wrong(where, field, raw);
            case BOOL:
                if (raw instanceof Boolean) {
                    return raw;
                }
                throw wrong(where, field, raw);
            case INT:
                // A bool is an int to Python, so dacite lets one through.
                if (raw instanceof Boolean) {
                    return raw;
                }
                if (raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger) {
                    return asLong(raw, where);
                }
                throw wrong(where, field, raw);
            case LIST_STR:
                if (raw instanceof List) {
                    List<Object> items = new ArrayList<Object>();
                    for (Object item : (List<?>) raw) {
                        if (!(item instanceof String)) {
                            throw wrong(where, field, raw);
                        }
                        items.add(item);
                    }
                    return items;
                }
                throw wrong(where, field, raw);
            case DICT:
                if (raw instanceof Map) {
                    for (Object key : ((Map<?, ?>) raw).keySet()) {
                        if (!(key instanceof String)) {
                            throw wrong(where, field, raw);
                        }
                    }
                    return value(raw, where);
                }
                throw wrong(where, field, raw);
            case OBJECT:
                if (raw instanceof Map) {
                    return build(field.of, (Map<?, ?>) raw, where);
                }
                throw wrong(where, field, raw);
            case LIST_OBJECT:
                if (raw instanceof List) {
                    List<Object> items = new ArrayList<Object>();
                    int at = 0;
                    for (Object item : (List<?>) raw) {
                        String here = where + "[" + at++ + "]";
                        if (!(item instanceof Map)) {
                            throw new OdkConfigException(here + ": ODK expects a block of "
                                    + "'key: value' lines for each item, and the configuration "
                                    + "gives " + said(item) + ".");
                        }
                        items.add(build(field.of, (Map<?, ?>) item, here));
                    }
                    return items;
                }
                throw wrong(where, field, raw);
            default:
                throw new IllegalStateException(field.kind.name());
        }
    }

    /** A value ODK keeps as the YAML gave it (inside {@code robot_report}), as template values. */
    private static Object value(Object raw, String where) {
        if (raw == null) {
            return Py.NONE;
        }
        if (raw instanceof String || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger) {
            return asLong(raw, where);
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof List) {
            List<Object> items = new ArrayList<Object>();
            for (Object item : (List<?>) raw) {
                items.add(value(item, where));
            }
            return items;
        }
        if (raw instanceof Map) {
            Map<String, Object> entries = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                entries.put(String.valueOf(entry.getKey()),
                        value(entry.getValue(), where + "." + entry.getKey()));
            }
            return entries;
        }
        throw new OdkConfigException(where + ": " + said(raw) + " is not a value OntoBoard can "
                + "hand to ODK's templates as ODK would. If it is meant as text, put it in quotes.");
    }

    private static Long asLong(Object raw, String where) {
        if (raw instanceof BigInteger) {
            BigInteger big = (BigInteger) raw;
            if (big.bitLength() > 63) {
                throw new OdkConfigException(where + ": " + big + " is too large a number.");
            }
            return big.longValue();
        }
        return ((Number) raw).longValue();
    }

    private static OdkConfigException wrong(String where, Field field, Object raw) {
        String expected;
        switch (field.kind) {
            case STR:
                expected = "text";
                break;
            case BOOL:
                expected = "true or false";
                break;
            case INT:
                expected = "a whole number";
                break;
            case LIST_STR:
                expected = "a list of texts";
                break;
            case DICT:
                expected = "a block of 'key: value' lines";
                break;
            case OBJECT:
                expected = "a block of 'key: value' lines";
                break;
            default:
                expected = "a list";
                break;
        }
        return new OdkConfigException(where + ": ODK expects " + expected + " here, and the "
                + "configuration gives " + said(raw) + ". ODK itself refuses the file for this.");
    }

    /** A YAML value in words, for a message: the text "full", the number 3, nothing. */
    private static String said(Object raw) {
        if (raw == null) {
            return "nothing";
        }
        if (raw instanceof String) {
            return "the text \"" + raw + "\"";
        }
        if (raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        if (raw instanceof Number) {
            return "the number " + raw;
        }
        if (raw instanceof List) {
            return "a list";
        }
        if (raw instanceof Map) {
            return "a block";
        }
        if (raw instanceof java.util.Date) {
            return "a date";
        }
        return "a " + raw.getClass().getSimpleName();
    }

    // ===================================================================== fill_missing

    /** {@code OntologyProject.fill_missing}. */
    private static void fillMissing(PyObject project) {
        Object imports = project.get("import_group");
        if (imports instanceof PyObject) {
            fillGroup((PyObject) imports, "ImportProduct", "id", "import_group");
            deriveImports((PyObject) imports);
        }
        Object subsets = project.get("subset_group");
        if (subsets instanceof PyObject) {
            fillGroup((PyObject) subsets, "SubsetProduct", "id", "subset_group");
        }
        Object pipelines = project.get("pattern_pipelines_group");
        if (pipelines instanceof PyObject) {
            fillGroup((PyObject) pipelines, "PatternPipelineProduct", "id",
                    "pattern_pipelines_group");
        }
        Object mappings = project.get("sssom_mappingset_group");
        if (mappings instanceof PyObject) {
            fillMappings((PyObject) mappings);
        }
        Object components = project.get("components");
        if (components instanceof PyObject) {
            fillGroup((PyObject) components, "ComponentProduct", "filename", "components");
            deriveComponents((PyObject) components, project);
        }
        String options = (String) project.get("obo_format_options");
        if (!options.contains("--clean-obo")) {
            project.set("obo_format_options", (options.isEmpty() ? "" : options + " ")
                    + "--clean-obo \"strict drop-untranslatable-axioms\"");
        }
    }

    /**
     * {@code ProductGroup.fill_missing}: no products is an empty list, and every name in
     * {@code ids} that no product has yet becomes a product of defaults, at the end.
     */
    private static void fillGroup(PyObject group, String productType, String stubKey,
            String where) {
        if (!(group.get("products") instanceof List)) {
            group.set("products", new ArrayList<Object>());
        }
        @SuppressWarnings("unchecked")
        List<Object> products = (List<Object>) group.get("products");
        Object ids = group.get("ids");
        if (!(ids instanceof List)) {
            return;
        }
        for (Object id : (List<?>) ids) {
            boolean there = false;
            for (Object product : products) {
                // ODK compares with each product's `id`. A component has none, so there ODK
                // stops with an AttributeError as soon as a product is listed beside `ids`.
                if (!((PyObject) product).has("id")) {
                    throw new OdkConfigException(where + " has both 'ids' and 'products'; ODK "
                            + "cannot read that for " + where + " and stops. List them all "
                            + "under 'products'.");
                }
                if (Py.eq(((PyObject) product).get("id"), id)) {
                    there = true;
                }
            }
            if (!there) {
                Map<String, Object> stub = new LinkedHashMap<String, Object>();
                stub.put(stubKey, id);
                products.add(build(productType, stub, where + ".ids"));
            }
        }
    }

    /** {@code ImportGroup._derive_fields}. */
    private static void deriveImports(PyObject group) {
        List<Object> special = new ArrayList<Object>();
        for (Object each : (List<?>) group.get("products")) {
            PyObject product = (PyObject) each;
            if (product.get("module_type") == Py.NONE) {
                product.set("module_type", group.get("module_type"));
            } else if ("fast_slme".equals(product.get("module_type"))) {
                // A synonym ODK keeps for older configs.
                product.set("module_type", "slme");
            }
            if ("slme".equals(product.get("module_type"))) {
                if (product.get("module_type_slme") == Py.NONE) {
                    product.set("module_type_slme", group.get("module_type_slme"));
                }
                if (product.get("slme_individuals") == Py.NONE) {
                    product.set("slme_individuals", group.get("slme_individuals"));
                }
            }
            if (product.get("base_iris") == Py.NONE) {
                product.set("base_iris", listOf("http://purl.obolibrary.org/obo/"
                        + upper((String) product.get("id"))));
            }
            boolean large = Py.truthy(product.get("is_large"));
            boolean otherType = !Py.eq(product.get("module_type"), group.get("module_type"));
            boolean otherMethod = "slme".equals(product.get("module_type"))
                    && !Py.eq(product.get("module_type_slme"), group.get("module_type_slme"));
            if (large || otherType || otherMethod) {
                special.add(product);
            }
        }
        group.setDynamic("special_products", special);
    }

    /** {@code ComponentGroup._derive_fields}. */
    private static void deriveComponents(PyObject group, PyObject project) {
        int at = 0;
        for (Object each : (List<?>) group.get("products")) {
            PyObject product = (PyObject) each;
            String where = "components.products[" + at++ + "]";
            if (product.get("base_iris") == Py.NONE) {
                product.set("base_iris", listOf(project.get("uribase") + "/"
                        + upper((String) project.get("id"))));
            }
            if (Py.truthy(product.get("use_template")) && product.get("templates") == Py.NONE) {
                product.set("templates", listOf(stem(product, where) + ".tsv"));
            } else if (Py.truthy(product.get("use_mappings"))
                    && product.get("mappings") == Py.NONE) {
                product.set("mappings", listOf(stem(product, where) + ".sssom.tsv"));
            }
        }
    }

    /** {@code filename.split('.')[0]}. */
    private static String stem(PyObject component, String where) {
        Object filename = component.get("filename");
        if (!(filename instanceof String)) {
            throw new OdkConfigException(where + " is built from a template or from mappings "
                    + "and has no 'filename'; ODK stops there.");
        }
        String name = (String) filename;
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** {@code SSSOMMappingSetGroup.fill_missing}. */
    private static void fillMappings(PyObject group) {
        if (!(group.get("products") instanceof List)) {
            return;
        }
        List<?> products = (List<?>) group.get("products");
        List<Object> released = new ArrayList<Object>();
        for (Object each : products) {
            if (Py.truthy(group.get("release_mappings"))
                    || Py.truthy(((PyObject) each).get("release_mappings"))) {
                released.add(each);
            }
        }
        if (!released.isEmpty()) {
            group.setDynamic("released_products", released);
        }
        for (Object each : products) {
            PyObject product = (PyObject) each;
            if ("merged".equals(product.get("maintenance"))) {
                if (product.get("source_mappings") == Py.NONE) {
                    List<Object> others = new ArrayList<Object>();
                    for (Object other : products) {
                        if (!"merged".equals(((PyObject) other).get("maintenance"))) {
                            others.add(((PyObject) other).get("id"));
                        }
                    }
                    product.set("source_mappings", others);
                } else {
                    for (Object source : (List<?>) product.get("source_mappings")) {
                        boolean known = false;
                        for (Object other : products) {
                            known |= Py.eq(((PyObject) other).get("id"), source);
                        }
                        if (!known) {
                            throw new OdkConfigException("sssom_mappingset_group: the merged "
                                    + "mapping set '" + product.get("id") + "' names the source '"
                                    + source + "', which is not one of the group's sets. ODK "
                                    + "stops with \"Unknown source mapping set '" + source
                                    + "'\".");
                        }
                    }
                }
            } else if ("extract".equals(product.get("maintenance"))) {
                if (product.get("source_file") == Py.NONE) {
                    product.set("source_file", "$(EDIT_PREPROCESSED)");
                }
            }
        }
    }

    /** Python's {@code str.upper()}: by Unicode's rules, whatever the machine's language is. */
    static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }

    // ===================================================================== dumping

    /**
     * The project as {@code odk.py export-project} dumps it: every declared field, nested, with
     * {@code None} as {@code null} - and nothing ODK hangs on the objects afterwards.
     */
    public static Object toPlain(Object value) {
        if (value instanceof PyObject) {
            PyObject object = (PyObject) value;
            Map<String, Object> plain = new LinkedHashMap<String, Object>();
            for (String field : object.fields()) {
                plain.put(field, toPlain(object.get(field)));
            }
            return plain;
        }
        if (value instanceof List) {
            List<Object> plain = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                plain.add(toPlain(item));
            }
            return plain;
        }
        if (value instanceof Map) {
            Map<String, Object> plain = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                plain.put(String.valueOf(entry.getKey()), toPlain(entry.getValue()));
            }
            return plain;
        }
        if (value instanceof PyNone) {
            return null;
        }
        return value;
    }
}
