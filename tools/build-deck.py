#!/usr/bin/env python3
"""Builds playground-ontoboard.pptx: the OntoBoard walkthrough, shaped like the ODK Playground deck.

Asked for: a deck "similar to playground pptx ... follow the same structure ... and present ontoboard
features and steps to create the ODK base repository and run all the features", with pictures from
the Protege plugin, the OntoBoard logo, something related to the canvas, and the author and FIZ
Karlsruhe on the first slide and in the footer.

STRUCTURE, taken from 2025-09-12_Tabea-ODK@PMD-Playground.pptx (46 slides, 10 x 5.625in):
a title slide, motivation, then alternating full-bleed section dividers and step slides, a verdict
and a resources slide at the end. The same spine is used here, with OntoBoard's own steps in it.

EVERY SLIDE IS LAID OUT BY HAND, AND CHECKED. Shapes are placed at measured inches, not by a
template's autofit - the deck has one grid (a 0.55in margin, a 24pt title baseline, a footer rule at
5.18in) and everything sits on it. Native PowerPoint shapes rather than pictures wherever a diagram
can be drawn as shapes, because those stay sharp at any zoom and can be edited by the person
presenting. check_layout() then reads the finished deck back: every text box is measured against
its own text in the deck's own fonts, and a shape off the slide, two shapes that partly overlap, or
body text under 10pt (7pt in the footer) fails the build. Every layout defect this deck shipped
before - a caption split to opposite edges, a two-inch slab where a two-point rule was meant, a
submenu sliced mid-word - was found by rendering it and looking. A build that refuses is cheaper.

EVERY NUMBER IN IT IS MEASURED, AND EVERY MENU PATH IS READ. The test counts, the pattern counts,
the collection sizes, the Protege hosts and the operations in the menu come from the repository at
build time - see `facts()` - so a stale claim fails the build rather than going on a slide. A number
the documentation measured outside this repository (four real ODK projects, the MatWerk sheets) is
read out of the page that states it, with enough of its sentence around it that a corrected page
takes the slide with it. And every "OntoBoard -> ..." on a slide is built by `path()` from
plugin.xml: 1.133.0 regrouped the menu by job, and this deck went on sending people to a ROBOT
submenu that no longer existed. Now a renamed or moved item stops the build. Nothing here is
written from memory.

NO OLD SCREENSHOT IS SHOWN. build/shots/*.png are real - captured from Protege 5.6.9 with
ontoboard-1.93.0.jar installed, by driving the menus over the keyboard - but the menu they show was
regrouped in 1.133.0, and the last one kept, the New ODK project dialog, was a crop cut off at its
second label that showed none of the fields: it read as an empty two-row dialog. Where a picture of
the current plugin belongs, the slide carries a dashed frame saying exactly what to capture, and
that slide's speaker notes list it: a drawing made to look like a screenshot would be the one kind
of picture worse than none.

    python tools/build-deck.py            # writes playground-ontoboard.pptx
"""
import io
import itertools
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.dml import MSO_LINE_DASH_STYLE
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, MSO_AUTO_SIZE, PP_ALIGN
from pptx.util import Inches, Pt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOTS = os.path.join(ROOT, "build", "shots")
DECK = os.path.join(ROOT, "build", "deck")
# Beside playground.pptx, the deck this one is shaped after, and ignored by git for the same
# reason that one is: a 200 KB binary regenerated from tools/build-deck.py on demand does not
# belong in the history, and the builder is what is worth keeping.
OUT = os.path.join(ROOT, "playground-ontoboard.pptx")

AUTHOR = "Ebrahim Norouzi"
INSTITUTE = "FIZ Karlsruhe – Leibniz Institute for Information Infrastructure"
INSTITUTE_SHORT = "FIZ Karlsruhe"

# The plugin version the one surviving screenshot was captured from. Printed on the slide that
# shows it, because a picture of an older plugin presented as the current one is a false claim.
SHOT_VERSION = "1.93.0"

# ---------------------------------------------------------------- the palette

INDIGO = RGBColor(0x3F, 0x51, 0xB5)
DEEP = RGBColor(0x1B, 0x23, 0x40)
DEEPER = RGBColor(0x12, 0x18, 0x2E)
INK = RGBColor(0x21, 0x25, 0x30)
MUTED = RGBColor(0x7A, 0x82, 0x96)
FAINT = RGBColor(0xDD, 0xE1, 0xEC)
LIGHT = RGBColor(0xEE, 0xF1, 0xFB)
PAPER = RGBColor(0xF7, 0xF8, 0xFA)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
ACCENT = RGBColor(0xD6, 0x50, 0x76)
GREEN = RGBColor(0x1F, 0x9D, 0x55)

SLIDE_W = Inches(10)
SLIDE_H = Inches(5.625)
M = 0.55          # the side margin every slide shares
RULE_Y = 5.18     # the footer rule
BOTTOM = 5.05     # nothing above the footer may come lower than this

FONT = "Segoe UI"
MONO = "Consolas"
ARROW = " \u2192 "

# The figure of one pattern, and the width it is shown at - which decides how large its lettering
# has to be drawn for 10pt to survive the scaling.
FIG_PATTERN = "mwo-process-agent-role"
FIG_W = 6.45
BFO = "http://purl.obolibrary.org/obo/BFO_"


# ------------------------------------------------------------------ the facts

class StaleClaim(Exception):
    """A slide quotes something the repository no longer says."""


def _read(*parts):
    return io.open(os.path.join(ROOT, *parts), encoding="utf-8").read()


def said(parts, pattern, flags=0):
    """What `pattern` captures in a repository file - or a build failure naming both.

    For the numbers somebody measured outside this repository and wrote down on a page in it. The
    pattern carries some of the sentence around the number on purpose: if the page is corrected or
    rewritten, the match fails and the slide has to be looked at again, instead of quietly keeping
    a figure its own source no longer gives.
    """
    parts = (parts,) if isinstance(parts, str) else parts
    found = re.search(pattern, _read(*parts), flags)
    if found is None:
        raise StaleClaim("%s no longer says what a slide quotes from it:\n    %s\nRead the page "
                         "again and rewrite the slide that uses it." % ("/".join(parts), pattern))
    groups = found.groups()
    return groups[0] if len(groups) == 1 else groups


WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
         "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
         "eighteen", "nineteen", "twenty"]


def words(n):
    """A count as the prose around it writes one: words up to twenty, digits after that."""
    return WORDS[n] if 0 <= n < len(WORDS) else format(n, ",")


def number(word):
    """The other way: "Three" or "1,227" as an int."""
    lower = word.strip().lower()
    return WORDS.index(lower) if lower in WORDS else int(lower.replace(",", ""))


def shown(name):
    """A menu label as a slide shows it: plugin.xml writes "...", typesetting wants one glyph."""
    return name.replace("...", "\u2026")


def facts():
    """Numbers, names and menu paths read out of the repository, so the deck cannot drift from it."""
    f = {}
    java = ("protege-plugin", "src", "main", "java", "de", "fizkarlsruhe", "ise", "ontoboard")

    # --- the pattern library: patterns/index.tsv is generated from the pattern directories and
    # checked against them by PatternIndexTest, so it is the authority on what ships.
    index = os.path.join(ROOT, "patterns", "index.tsv")
    rows = []
    for line in io.open(index, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        cells = line.rstrip("\n").split("\t")
        if len(cells) >= 10:
            rows.append(cells)
    f["patterns"] = len(rows)
    f["distinct"] = len([r for r in rows if not r[6]])
    by = {}
    for r in rows:
        by[r[2]] = by.get(r[2], 0) + 1
    f["collections"] = by
    f["publishers"] = len({r[3] for r in rows})
    f["with_cq"] = len([r for r in rows if r[8].strip()])
    f["pattern_names"] = {r[0]: r[1] for r in rows}

    # The size of the BFO-family patterns, counted in the files rather than taken from the page
    # that states it: that page says 6 to 26, and the files have since grown past it.
    import rdflib
    from rdflib.namespace import OWL, RDF
    sizes = []
    for r in rows:
        if r[2] in ("mwo", "nfdi", "pmdco"):
            g = rdflib.Graph()
            g.parse(os.path.join(ROOT, "patterns", r[0], "pattern.ttl"), format="turtle")
            sizes.append(len({c for c in g.subjects(RDF.type, OWL.Class)
                              if isinstance(c, rdflib.URIRef)}))
    f["bfo_family"] = len(sizes)
    f["bfo_family_classes"] = (min(sizes), max(sizes))

    pom = _read("protege-plugin", "pom.xml")
    f["version"] = re.search(r"^  <version>(.*?)</version>", pom, re.M).group(1)

    # --- the menu, as plugin.xml declares it. Protege builds the OntoBoard menu from these
    # extensions alone, so this is what anybody sees; a slide that names a path takes it from here.
    plugin = ET.parse(os.path.join(ROOT, "protege-plugin", "src", "main", "resources",
                                   "plugin.xml")).getroot()
    entries, tabs = {}, []
    for ext in plugin.findall("extension"):
        point = ext.get("point", "")
        if point.endswith("WorkspaceTab"):
            tabs.append(ext.find("label").get("value"))
        if not point.endswith("EditorKitMenuAction"):
            continue
        value = lambda tag: ext.find(tag).get("value") if ext.find(tag) is not None else None
        entries[ext.get("id")] = {"id": ext.get("id"), "name": value("name"),
                                  "path": value("path"), "tooltip": value("toolTip"),
                                  "action": ext.find("class") is not None}
    f["tabs"] = tabs
    root = [e for e in entries.values() if e["name"] == "OntoBoard" and e["path"].startswith("/")]
    if len(root) != 1:
        raise StaleClaim("plugin.xml no longer declares one top-level OntoBoard menu")
    root = root[0]

    def parent(entry):
        # A child names its parent as <bundleSymbolicName>.<extensionId>, before the slot.
        where = entry["path"].rsplit("/", 1)[0]
        return entries.get(where[len("ontoboard."):]) if where.startswith("ontoboard.") else None

    def chain(entry):
        names = []
        while entry is not None and entry is not root:
            names.insert(0, entry["name"])
            entry = parent(entry)
        return ["OntoBoard"] + names if entry is root else None

    def slot(entry):
        return entry["path"].rsplit("/", 1)[1]

    f["menu_index"] = []
    for entry in entries.values():
        names = chain(entry)
        if names is not None and entry is not root:
            f["menu_index"].append(dict(entry, chain=names))
    f["menu_items"] = len([e for e in f["menu_index"] if e["action"]])
    children = lambda of: sorted([e for e in entries.values() if parent(e) is of], key=slot)
    f["menu"] = []
    for entry in children(root):
        kids = children(entry)
        f["menu"].append({"name": entry["name"], "group": slot(entry).split("-")[0],
                          "items": [k["name"] for k in kids] if not entry["action"] else None})
    f["tools"] = [e["name"] for e in entries.values()
                  if e["path"].startswith("org.protege.editor.owl.menu.tools/")]
    report = [e for e in f["menu_index"] if e["name"] == "Quality report..."][0]
    f["report_rules"] = int(re.search(r"ROBOT's (\d+) rules", report["tooltip"]).group(1))

    tests = 0
    for dirpath, _, names in os.walk(os.path.join(ROOT, "protege-plugin", "src", "test")):
        for n in names:
            if n.endswith(".java"):
                tests += len(re.findall(r"^\s*@Test\s*$",
                                        io.open(os.path.join(dirpath, n), encoding="utf-8").read(),
                                        re.M))
    f["tests"] = tests

    # --- the receipts. Every release commits one, and build.yml refuses a version without it.
    # The newest says which Protege hosts the release was started in: those are the supported
    # versions, measured, rather than whichever ones this deck last remembered.
    folder = os.path.join(ROOT, "protege-plugin", "tools", "smoke-receipt")
    receipts = [r for r in os.listdir(folder) if r.endswith(".txt")]
    f["releases"] = len(receipts)
    newest = max(receipts, key=lambda r: tuple(int(p) for p in r[:-4].split(".")))
    hosts = re.findall(r"^\s+\d{4}-\d{2}-\d{2}\s+Protege (\S+)\s+OWL API \S+\s+JRE (.+?)\s+-Xmx",
                       _read("protege-plugin", "tools", "smoke-receipt", newest), re.M)
    if not hosts:
        raise StaleClaim("the newest smoke receipt, %s, names no Protege host" % newest)
    f["hosts"] = sorted(hosts, key=lambda h: tuple(int(p) for p in h[0].split(".")), reverse=True)
    f["recommended"] = said(("docs", "getting-started.md"),
                            r"Protégé \*\*(\d+\.\d+)\.x\*\* is recommended")
    if not f["hosts"][0][0].startswith(f["recommended"] + "."):
        raise StaleClaim("getting-started.md recommends %s.x, and the newest receipt's newest "
                         "host is %s" % (f["recommended"], f["hosts"][0][0]))

    # --- the board
    f["board_limit"] = int(said(java + ("views", "SchemaCanvasView.java"),
                                r"EVERYTHING_LIMIT = (\d+);"))
    f["board_since"] = said(("docs", "canvas.md"),
                            r"A board saved by OntoBoard before (\d+\.\d+\.\d+) keeps the terms")
    f["drawn"] = said(("docs", "limitations.md"),
                      r"every logical axiom - ([\d,]+) of\s+\1 in one, ([\d,]+) of\s+\2 in the "
                      r"other")

    # --- projects, the build, imports
    f["scaffold_files"] = int(said(("docs", "odk-workflow.md"),
                                   r"then (\d+) files written with no\s+Docker and no network"))
    f["wizard_fields"] = [label.rstrip(":") for label in re.findall(
        r'fields\.add\(new JLabel\("([^"]+)"\)\);', _read(*java + ("odk", "ProjectWizard.java")))]
    targets = _read(*java + ("odk", "InProcessTargets.java"))
    f["targets"] = re.findall(r'"([^"]+)"', re.search(
        r"TARGETS = Collections\.unmodifiableList\(Arrays\.asList\(\s*(.*?)\)\);", targets,
        re.S).group(1))
    f["test_runs"] = re.findall(r'"([^"]+)"', re.search(
        r'if \("test"\.equals\(target\)\) \{\s*return Arrays\.asList\((.*?)\);', targets,
        re.S).group(1))
    f["image"] = said(java + ("odk", "MakeRun.java"), r'DEFAULT_IMAGE = "([^"]+)"')
    f["heap"] = said(java + ("odk", "OdkBuildSettings.java"),
                     r'DEFAULT_ROBOT_JAVA_ARGS = "([^"]+)"')
    f["import_steps"] = [number(w) for w in said(
        ("docs", "odk-workflow.md"),
        r"ODK's own walkthrough is (\w+) steps\. (\w+) of them OntoBoard does for you")]
    f["projects"] = said(("docs", "odk-workflow.md"),
                         r"Measured across (\w+), (\w+), (\w+) and (\w+):\s+\d+ term lists")
    products, rebuilt = said(("docs", "odk-workflow.md"),
                             r"All (\d+) products those four projects declare now have a "
                             r"source\. (\d+) are a kind OntoBoard rebuilds")
    f["refresh"] = {
        "products": int(products), "rebuilt": int(rebuilt),
        "named": int(said(("docs", "odk-workflow.md"),
                          r"The other (\d+) are \*\*named with the reason\*\*")),
        "custom": int(said(("docs", "odk-workflow.md"), r"^\| `custom` \|.*\| (\d+) \|$", re.M)),
        "subset": int(said(("docs", "odk-workflow.md"),
                           r"^\| `slme` with `SUBSET` \|.*\| (\d+) \|$", re.M)),
        "mirror": int(said(("docs", "odk-workflow.md"), r"^\| `mirror` \|.*\| (\d+) \|$", re.M)),
        "filter": int(said(("docs", "odk-workflow.md"), r"^\| `filter` \|.*\| (\d+) \|$", re.M)),
        "undeclared": number(said(("docs", "odk-workflow.md"),
                                  r"(\w+) of the four projects had one")),
    }
    if f["refresh"]["custom"] + f["refresh"]["subset"] + f["refresh"]["mirror"] \
            + f["refresh"]["filter"] != f["refresh"]["named"]:
        raise StaleClaim("odk-workflow.md's table of unrebuilt imports no longer adds up to the "
                         "number it names")
    f["transform_kinds"] = [k.split()[0].lower() for k in re.findall(
        r'^\s+[A-Z_]+\("([^"]+)",', _read(*java + ("robot", "RobotTransform.java")), re.M)]
    f["menu_moved"] = said(("docs", "getting-started.md"),
                           r"\*\*Moved in (\d+\.\d+\.\d+)\*\*, when the menu stopped being "
                           r"grouped by tool")
    guide = _read("docs", "guide.md").split("## The path", 1)[1].split("Steps 5 to 8", 1)[0]
    f["guide_steps"] = len(re.findall(r"^\d+\. ", guide, re.M))

    # --- the sheets and the knowledge graph. The sheet editor's buttons and the board's controls
    # are named on slides too, so their labels are read from the views that create them.
    view = _read(*java + ("views", "SheetEditorView.java"))
    f["sheet_labels"] = set(re.findall(r'(?:button|item)\("([^"]+)"', view))
    if '" Folder: "' not in view:
        raise StaleClaim("SheetEditorView no longer labels the open folder 'Folder: ...'")
    f["board_source"] = _read(*java + ("views", "SchemaCanvasView.java"))
    f["sheet_places"] = number(said(("docs", "limitations.md"),
                                    r"The sheet editor looks in (\w+) places"))
    f["levels_up"] = int(said(java + ("sheet", "TemplateDiscovery.java"),
                              r"MOST_LEVELS_UP = (\d+);"))
    f["folder_example"] = said(("docs", "odk-workflow.md"),
                               r"\(\*Folder: (kg/templates \(\d+ sheets\))\*\)")
    f["marks"] = said(("docs", "odk-workflow.md"),
                      r"On the MatWerk\s+sheets that is ([\d,]+) marked cells on a graph that "
                      r"builds with zero problems")
    f["corrected"] = said(("docs", "odk-workflow.md"),
                          r"\*\*The ones with an obvious correction can be fixed in one undoable "
                          r"step\.\*\* (\d+) were applied to\s+the MatWerk sheets at once, and the "
                          r"built graph was byte-identical")
    kinds = said(("docs", "odk-workflow.md"),
                 r"\*\*The mistakes are marked where the typing happens\.\*\* (\w+) kinds — "
                 r"(.+?)\. On the", re.S)
    f["kinds"] = [k.strip() for k in re.sub(r"\s+", " ", kinds[1]).split(",")]
    if len(f["kinds"]) != number(kinds[0]):
        raise StaleClaim("odk-workflow.md names %s kinds of marked mistake and lists %d"
                         % (kinds[0], len(f["kinds"])))
    f["matwerk"] = said(("docs", "requirements-status.md"),
                        r"Measured on the whole (\d+)-sheet MatWerk graph: ([\d,]+) rows, "
                        r"([\d,]+) axioms, ([\d,]+) individuals, (\d+) problems")
    f["elk"] = said(("docs", "requirements-status.md"),
                    r"ELK over the built graph was measured at ([\d.]+)s for ([\d,]+) axioms")
    f["excel"] = said(("docs", "requirements-status.md"),
                      r"Verified cell-by-cell against openpyxl on (\w+) workbooks \(([\d,]+) "
                      r"cells")
    f["label_cells"] = said(("docs", "fixed.md"),
                            r"is written this way in two columns across \*\*(\d+) filled "
                            r"cells\*\*")
    iri = _read(*java + ("sheet", "IriGenerator.java")).split("enum Source", 1)[1]
    f["iri_sources"] = re.findall(r'^\s+[A-Z_]+\("([^"]+)", (?:true|false)\)',
                                  iri.split(";", 1)[0], re.M)
    f["row_since"] = said(java + ("robot", "TemplateSpec.java"),
                          r"The template row became editable in (\d+\.\d+\.\d+)")
    spec = _read("protege-plugin", "src", "test", "java", "de", "fizkarlsruhe", "ise", "ontoboard",
                 "robot", "TemplateSpecTest.java")
    corpus = spec.split("CORPUS = Arrays.asList(", 1)[1].split(");", 1)[0]
    corpus = "\n".join(l for l in corpus.splitlines() if not l.strip().startswith("//"))
    f["spec_corpus"] = len(re.findall(r'"(?:[^"\\]|\\.)*"', corpus))

    # --- patterns, collaboration, limits
    f["star"] = said(("docs", "odk-workflow.md"),
                     r"a PMDco pattern documented with (\w+) terms came out with\s+(\d+) classes")
    said(("docs", "odk-workflow.md"), r"\*\*zero\*\* of the \d+ ODP patterns share a single IRI")
    f["quote"] = re.sub(r"\s+", " ", said(("docs", "odk-workflow.md"),
                                          r"\*\"(\d+ of its \d+ terms are already\s+in your "
                                          r"ontology: [^\"]+)\"\*"))
    f["collab_kinds"] = number(said(("docs", "limitations.md"),
                                    r"Live collaboration carries (\w+) kinds of axiom"))
    return f


F = facts()


def _entry(label):
    """The one plugin.xml entry under the OntoBoard menu with this label, or a build failure."""
    hits = [e for e in F["menu_index"] if e["name"] == label.replace("\u2026", "...")]
    if len(hits) != 1:
        raise StaleClaim("plugin.xml has %d entries called %r under the OntoBoard menu; a slide "
                         "sends people there" % (len(hits), label))
    return hits[0]


def path(label, full=True):
    """The way to a menu item, read from plugin.xml: "OntoBoard -> Spreadsheets -> Template..."."""
    names = _entry(label)["chain"]
    return ARROW.join(shown(n) for n in (names if full else names[1:]))


def where(label):
    """The submenu an item is in, as a path: "OntoBoard -> Build and release"."""
    return ARROW.join(shown(n) for n in _entry(label)["chain"][:-1])


def item(label):
    """An item's own label, after checking that the menu still has it."""
    return shown(_entry(label)["name"])


def tooltip(label):
    """What the plugin itself says an item does - its tooltip, typeset."""
    return _entry(label)["tooltip"].replace(" - ", " \u2014 ").replace("Protege", "Protégé")


def tab(label):
    """The way to a workspace tab, after checking plugin.xml still declares one by that name."""
    if label not in F["tabs"]:
        raise StaleClaim("plugin.xml declares no workspace tab called %r" % label)
    return "Window" + ARROW + "Tabs" + ARROW + label


def control(label):
    """A button or popup entry of the sheet editor, after checking SheetEditorView still has it."""
    if label not in F["sheet_labels"]:
        raise StaleClaim("SheetEditorView has no button or menu entry called %r" % label)
    return shown(label)


def on_board(label):
    """A control of the board, after checking SchemaCanvasView still creates one by that name."""
    if '"%s"' % label not in F["board_source"]:
        raise StaleClaim("SchemaCanvasView has no control called %r" % label)
    return shown(label)


# ------------------------------------------------------------- measuring text

# The deck's two typefaces, located the way the person presenting it will have them. Measured
# with Pillow at twenty pixels to the point, which keeps rounding below a hundredth of a point.
_FONT_FILES = {(FONT, False): "segoeui.ttf", (FONT, True): "segoeuib.ttf",
               (MONO, False): "consola.ttf", (MONO, True): "consolab.ttf"}
_FONT_DIRS = [os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts"), "/Library/Fonts",
              os.path.expanduser("~/Library/Fonts"), "/usr/share/fonts/truetype/msttcorefonts"]
_SCALE = 20
_faces = {}


def _face(font, bold, size):
    key = (font, bool(bold), size)
    if key not in _faces:
        from PIL import ImageFont
        _faces[key] = None
        name = _FONT_FILES.get((font, bool(bold)))
        for folder in _FONT_DIRS:
            if name and os.path.isfile(os.path.join(folder, name)):
                _faces[key] = ImageFont.truetype(os.path.join(folder, name),
                                                 int(round(size * _SCALE)))
                break
    return _faces[key]


def can_measure():
    return _face(FONT, False, 10) is not None and _face(MONO, False, 10) is not None


def wrap(line, size, bold=False, font=FONT, width=1.0):
    """The lines one paragraph takes in `width` inches, broken where PowerPoint breaks them: at a
    space, and inside a word only when the word alone is wider than the line. Each line comes back
    with its width in inches, so a word too wide for its box can be told apart from a long line."""
    face = _face(font, bold, size)
    room = width * 72 * _SCALE
    lines, current = [], ""
    for word in line.split(" "):
        trial = word if not current else current + " " + word
        if not current or face.getlength(trial) <= room:
            current = trial
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return [(ln, face.getlength(ln) / _SCALE / 72.0) for ln in lines]


def line_height(size, font=FONT, bold=False):
    """One line in inches: the face's ascent and descent, which is what single spacing means."""
    ascent, descent = _face(font, bold, size).getmetrics()
    return (ascent + descent) / float(_SCALE) / 72.0


def height_of(paragraphs, width):
    """Inches a list of (text, size, bold, font, space_after_pt) needs in a box `width` wide."""
    total = 0.0
    for at, (body, size, bold, font, after) in enumerate(paragraphs):
        total += len(wrap(body, size, bold, font, width)) * line_height(size, font, bold)
        if at < len(paragraphs) - 1:
            total += after / 72.0
    return total


# ------------------------------------------------------------------- plumbing

def blank(prs):
    return prs.slides.add_slide(prs.slide_layouts[6])


def rect(slide, x, y, w, h, fill=None, line=None, shape=MSO_SHAPE.RECTANGLE, line_w=1.0):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    s.shadow.inherit = False
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = fill
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = line
        s.line.width = Pt(line_w)
    s.text_frame.word_wrap = True
    s.text_frame.margin_left = Inches(0.1)
    s.text_frame.margin_right = Inches(0.1)
    s.text_frame.margin_top = Inches(0.05)
    s.text_frame.margin_bottom = Inches(0.05)
    return s


def keep_together(line):
    """What a line must not be broken between: a hyphenated word, a number and its unit, a dash.

    A hyphen inside a word becomes U+2011, which Segoe UI draws exactly as it draws "-": the
    renders showed refresh-imports, robot-core and sub-ontologies each broken across two lines,
    which reads as a word split in half. Not inside anything with a dot or a slash, because an
    address or a file name has to survive being copied off a slide, and not in Consolas, which has
    no such glyph. A number keeps the word after it ("9 sheets" had become "(9" and "sheets)" on
    two lines), and an em dash stays at the end of its line rather than opening the next one.
    """
    line = " ".join(tok if "/" in tok or "." in tok.rstrip(".,;:)")
                    else re.sub(r"(?<=\w)-(?=\w)", "\u2011", tok) for tok in line.split(" "))
    line = re.sub(r"(?<=\d) (?=[^\W\d])", "\u00a0", line)
    line = re.sub(r"(?<=\d\u00a0of) (?=\d)", "\u00a0", line)   # "921 of 921" is one figure
    line = re.sub(r"(?<=\d\u00a0to) (?=\d)", "\u00a0", line)   # and "6 to 33" one range
    return line.replace(" — ", "\u00a0— ")


def text(slide, x, y, w, h, runs, size=11, colour=INK, bold=False, align=PP_ALIGN.LEFT,
         space=4, anchor=MSO_ANCHOR.TOP, font=FONT, line_spacing=None):
    """`runs` is a string, or a list of (string, {overrides}) for mixed formatting per paragraph."""
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    items = runs if isinstance(runs, list) else [runs]
    first = True
    for item_ in items:
        body, over = item_ if isinstance(item_, tuple) else (item_, {})
        # One paragraph per line, rather than one run carrying "\n".
        #
        # Setting run.text to a string with a newline in it puts a line break inside the
        # paragraph, and the alignment then applied to the first line only: every two-line
        # centred caption in this deck came out with its first line hard left and its second
        # hard right. It is visible on the "in numbers" slide, where "design patterns," and
        # "ready to import" sat at opposite edges of their card. Found by rendering the deck
        # and looking at it, which is the only way this kind of defect gets found.
        lines = body.split("\n")
        for at, line in enumerate(lines):
            p = tf.paragraphs[0] if first else tf.add_paragraph()
            first = False
            p.alignment = over.get("align", align)
            # Space only after the last line of a label, so the lines of one caption stay
            # together and the gap to the next item is unchanged.
            p.space_after = Pt(over.get("space", space) if at == len(lines) - 1 else 0)
            if line_spacing:
                p.line_spacing = line_spacing
            r = p.add_run()
            face = over.get("font", font)
            r.text = keep_together(line) if face == FONT else line
            r.font.size = Pt(over.get("size", size))
            r.font.bold = over.get("bold", bold)
            r.font.name = face
            r.font.color.rgb = over.get("colour", colour)
    return box


def picture(slide, path_, x, y, w=None, h=None):
    kw = {}
    if w is not None:
        kw["width"] = Inches(w)
    if h is not None:
        kw["height"] = Inches(h)
    return slide.shapes.add_picture(path_, Inches(x), Inches(y), **kw)


def chrome(slide, number_, dark=False):
    """The footer every slide carries: the author, the institute, and the slide number.

    Asked for by name - "mention my name and mention FIZKarlsruhe in the page bottom as layout".
    Drawn on each slide rather than put in a master, because this deck has two backgrounds and a
    master footer would need the wrong colour on one of them.
    """
    rule = RGBColor(0x33, 0x3C, 0x5C) if dark else FAINT
    ink = RGBColor(0x8A, 0x93, 0xB5) if dark else MUTED
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(M), Inches(RULE_Y),
                                  Inches(10 - 2 * M), Pt(0.75))
    line.shadow.inherit = False
    line.fill.solid()
    line.fill.fore_color.rgb = rule
    line.line.fill.background()

    logo = os.path.join(DECK, "logo-white.png" if dark else "logo-indigo.png")
    if os.path.isfile(logo):
        picture(slide, logo, M, RULE_Y + 0.12, h=0.19)
    text(slide, M + 0.3, RULE_Y + 0.13, 6.0, 0.25,
         "OntoBoard  ·  " + AUTHOR + "  ·  " + INSTITUTE_SHORT, size=8, colour=ink)
    if number_ is not None:
        text(slide, 10 - M - 1.0, RULE_Y + 0.13, 1.0, 0.25, str(number_), size=8, colour=ink,
             align=PP_ALIGN.RIGHT)


def heading(slide, title, kicker=None):
    y = 0.42
    if kicker:
        text(slide, M, y - 0.18, 8.0, 0.22, kicker.upper(), size=10, colour=ACCENT, bold=True)
        y += 0.12
    text(slide, M, y, 10 - 2 * M, 0.5, title, size=23, colour=INDIGO, bold=True)
    return y + 0.62


def dark_background(slide, colour=DEEP):
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, SLIDE_W, SLIDE_H)
    bg.shadow.inherit = False
    bg.fill.solid()
    bg.fill.fore_color.rgb = colour
    bg.line.fill.background()
    return bg


def bullets(slide, x, y, w, items, size=11, gap=0.30, colour=INK, marker=True):
    for i, entry in enumerate(items):
        body, note = (entry if isinstance(entry, tuple) else (entry, None))
        yy = y + i * gap
        if marker:
            dot = slide.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x), Inches(yy + 0.055),
                                         Inches(0.065), Inches(0.065))
            dot.shadow.inherit = False
            dot.fill.solid()
            dot.fill.fore_color.rgb = ACCENT
            dot.line.fill.background()
        runs = [(body, {"bold": True, "size": size, "colour": colour})]
        if note:
            runs.append((note, {"size": max(10, size - 1), "colour": MUTED, "space": 0}))
        # Each box ends where the next begins: they used to overlap by the 0.02in the text is
        # nudged up by, which nobody could see and check_layout() rightly refuses.
        text(slide, x + (0.17 if marker else 0), yy - 0.02, w, gap, runs, space=0)


def points(slide, x, y, w, items, step, bar=0.6, head_size=11.5):
    """The deck's recurring list: an accent bar, a bold line, and the sentence that explains it."""
    for i, (head, note) in enumerate(items):
        yy = y + i * step
        rect(slide, x, yy, 0.055, bar, fill=ACCENT)
        text(slide, x + 0.22, yy - 0.02, w - 0.22, step - 0.04, [
            (head, {"size": head_size, "bold": True, "colour": INDIGO, "space": 3}),
            (note, {"size": 10, "colour": INK}),
        ], space=0)


def crop_shot(name, box, out):
    """Crops a captured screenshot. `box` is (left, top, right, bottom) in source pixels."""
    from PIL import Image
    src = os.path.join(SHOTS, name)
    if not os.path.isfile(src):
        return None
    img = Image.open(src).convert("RGB").crop(box)
    out_path = os.path.join(DECK, out)
    img.save(out_path, quality=96)
    return out_path


PLACEHOLDERS = []


def placeholder(slide, number_, x, y, w, h, what):
    """Where a picture of the current plugin belongs and none has been taken yet.

    A dashed frame in muted type, saying exactly what to capture - never a drawing of the window,
    because one that looked like a screenshot would show a plugin that does not exist. The caller
    puts the same words in the slide's speaker notes, and the build prints every one at the end,
    so the list of pictures still owed is never more than a run away.
    """
    frame = rect(slide, x, y, w, h, fill=PAPER, line=MUTED, line_w=1.25)
    frame.line.dash_style = MSO_LINE_DASH_STYLE.DASH
    tf = frame.text_frame
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    tf.margin_left = tf.margin_right = Inches(0.25)
    tf.margin_top = tf.margin_bottom = Inches(0.15)
    for at, (body, bold) in enumerate((("SCREENSHOT TO CAPTURE:", True), (what, False))):
        p = tf.paragraphs[0] if at == 0 else tf.add_paragraph()
        p.alignment = PP_ALIGN.CENTER
        p.space_after = Pt(6 if at == 0 else 0)
        r = p.add_run()
        r.text = keep_together(body)
        r.font.size = Pt(10.5)
        r.font.bold = bold
        r.font.name = FONT
        r.font.color.rgb = MUTED
    said_ = "SCREENSHOT TO CAPTURE: " + what
    PLACEHOLDERS.append((number_, said_))
    return said_


def notes(slide, *paragraphs):
    slide.notes_slide.notes_text_frame.text = "\n\n".join(paragraphs)


# ------------------------------------------------------------------- assets

def build_assets():
    """Draws the logo variants and the pattern figure the deck uses.

    Here rather than in a throwaway command, so that `python tools/build-deck.py` reproduces the
    whole deck from the repository on any machine. The screenshots under build/shots/ are the one
    thing it cannot regenerate - those were captured from a running Protege.
    """
    from PIL import Image, ImageDraw
    os.makedirs(DECK, exist_ok=True)

    def mark(size, colour, board=None):
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        if board:
            d.rounded_rectangle([size * 0.03, size * 0.03, size * 0.97, size * 0.97],
                                radius=int(size * 0.19), fill=board)
        top, left, right = (0.50, 0.345), (0.308, 0.650), (0.692, 0.650)
        radius = 0.093 * size
        width = max(2, int(0.030 * size))
        at = lambda pt: (pt[0] * size, pt[1] * size)
        for a, b in ((top, left), (top, right), (left, right)):
            d.line([at(a), at(b)], fill=colour, width=width)
        for centre in (top, left, right):
            x, y = at(centre)
            d.ellipse([x - radius, y - radius, x + radius, y + radius], fill=colour)
        return img

    white, indigo = (255, 255, 255, 255), (63, 81, 181, 255)
    mark(512, white).save(os.path.join(DECK, "logo-white.png"))
    mark(512, indigo).save(os.path.join(DECK, "logo-indigo.png"))
    return draw_pattern_figure()


def draw_pattern_figure():
    """The pattern figure, drawn from the axioms FIG_PATTERN actually asserts.

    Read with rdflib at build time rather than transcribed - and laid out from what it reads, too.
    Until 1.134.0 the boxes sat at hand-placed coordinates for ten labels, so when the pattern
    grew two classes (immaterial entity, spatial region) the figure silently left them out while
    the slide beside it said "every box is a class this pattern declares". Now every declared class
    gets a box, every asserted subClassOf a line, and every existential restriction a dashed one.

    The layout is the plain one for a tree: each class is given the width of its subtree, parents
    are centred over their children, and children are ordered largest first, which is what puts
    "agent" beside the chain that ends in "agent role", the class its restriction points at.

    The lettering is sized for the slide, not for the file. The image is shown FIG_W inches wide,
    so a label drawn at `px` pixels on a canvas `W` wide reads at px * FIG_W * 72 / W points; the
    figure is refused rather than drawn if that falls under 10pt. It used to be 8pt.
    """
    from PIL import Image, ImageDraw, ImageFont
    import rdflib
    from rdflib.namespace import OWL, RDF, RDFS

    g = rdflib.Graph()
    g.parse(os.path.join(ROOT, "patterns", FIG_PATTERN, "pattern.ttl"), format="turtle")
    label = {str(sub): str(obj) for sub, obj in g.subject_objects(RDFS.label)}
    name = lambda u: label.get(str(u), re.split(r"[#/]", str(u))[-1])
    declared = {c for c in g.subjects(RDF.type, OWL.Class) if isinstance(c, rdflib.URIRef)}
    names = {c: name(c) for c in declared}
    parent = {}
    for c in declared:
        supers = sorted(names[b] for b in g.objects(c, RDFS.subClassOf) if b in declared)
        if supers:
            parent[names[c]] = supers[0]
    restrictions = []
    for c in sorted(declared, key=lambda u: names[u]):
        for b in g.objects(c, RDFS.subClassOf):
            filler, prop = g.value(b, OWL.someValuesFrom), g.value(b, OWL.onProperty)
            if isinstance(b, rdflib.BNode) and prop is not None and filler in declared:
                restrictions.append((names[c], name(prop), names[filler]))
    own = {names[c] for c in declared if not str(c).startswith(BFO)}
    props = sorted({name(p) + " (" + str(p).rsplit("/", 1)[-1] + ")"
                    for p in g.subjects(RDF.type, OWL.ObjectProperty)
                    if isinstance(p, rdflib.URIRef)})

    def font(px, bold=False):
        for file_ in ((r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf"),
                      (r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf")):
            try:
                return ImageFont.truetype(file_, px)
            except Exception:
                pass
        return ImageFont.load_default()

    PX = 48
    reg, bold_f = font(PX), font(PX + 2, True)
    probe = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    ink, muted = (33, 37, 48), (120, 128, 145)
    indigo_rgb, light, accent = (63, 81, 181), (232, 236, 250), (214, 80, 118)
    PAD, GAP, ROW, HALF, MARGIN, TOP = 30, 44, 128, 42, 70, 40

    box_w = {n: max(probe.textlength(n, font=bold_f if n in own else reg) + 2 * PAD, 200)
             for n in names.values()}
    kids = {n: [] for n in names.values()}
    for child, sup in parent.items():
        kids[sup].append(child)
    size = {}

    def count(n):
        size[n] = 1 + sum(count(k) for k in kids[n])
        return size[n]

    roots = sorted([n for n in names.values() if n not in parent], key=lambda n: n)
    for r in roots:
        count(r)
    for n in kids:
        kids[n].sort(key=lambda k: (-size[k], k))
    span = {}

    def spread(n):
        inner = sum(spread(k) for k in kids[n]) + GAP * max(0, len(kids[n]) - 1)
        span[n] = max(box_w[n], inner)
        return span[n]

    pos, depth = {}, {}

    def place(n, left, level):
        inner = sum(span[k] for k in kids[n]) + GAP * max(0, len(kids[n]) - 1)
        at, xs = left + (span[n] - inner) / 2.0, []
        for k in kids[n]:
            place(k, at, level + 1)
            xs.append(pos[k][0])
            at += span[k] + GAP
        cx = (xs[0] + xs[-1]) / 2.0 if xs else left + span[n] / 2.0
        pos[n], depth[n] = (cx, TOP + HALF + level * ROW), level

    left = MARGIN
    for r in roots:
        spread(r)
        place(r, left, 0)
        left += span[r] + GAP
    W_ = int(left - GAP + MARGIN)
    smallest = PX * FIG_W * 72.0 / W_
    if smallest < 10:
        raise StaleClaim("the %s figure would print its labels at %.1fpt; it has outgrown one "
                         "slide" % (FIG_PATTERN, smallest))

    def box(n):
        cx, cy = pos[n]
        return [cx - box_w[n] / 2.0, cy - HALF, cx + box_w[n] / 2.0, cy + HALF]

    def crosses(segment, skip):
        (x0, y0), (x1, y1) = segment
        for n in names.values():
            if n in skip:
                continue
            b = box(n)
            if min(x0, x1) <= b[2] and max(x0, x1) >= b[0] and min(y0, y1) <= b[3] \
                    and max(y0, y1) >= b[1]:
                return True
        return False

    bottom = max(box(n)[3] for n in names.values())
    rule_y = bottom + 46
    col_w = (W_ - 2 * MARGIN) / 2.0
    for p in props:
        if probe.textlength("•  " + p, font=reg) > col_w - 20:
            raise StaleClaim("the property %r is too long for the figure's two columns" % p)
    H_ = int(rule_y + 40 + PX + 18 + math.ceil(len(props) / 2.0) * (PX + 12) + 30)

    img = Image.new("RGBA", (W_, H_), (255, 255, 255, 255))
    d = ImageDraw.Draw(img)
    for child, sup in sorted(parent.items()):
        cb, sb = box(child), box(sup)
        d.line([pos[child][0], cb[1], pos[sup][0], sb[3]], fill=(176, 183, 198), width=4)

    def dashed(points_, width=5):
        for (x0, y0), (x1, y1) in zip(points_, points_[1:]):
            length = math.hypot(x1 - x0, y1 - y0)
            steps = max(2, int(length / 18))
            for i in range(0, steps, 2):
                d.line([x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps,
                        x0 + (x1 - x0) * (i + 1) / steps, y0 + (y1 - y0) * (i + 1) / steps],
                       fill=accent, width=width)

    for a, prop, b in restrictions:
        ab, bb = box(a), box(b)
        ax, by = pos[a][0], pos[b][1]
        edge_x = bb[0] if pos[b][0] > ax else bb[2]
        down = ((ax, ab[3]), (ax, by))
        across = ((ax, by), (edge_x, by))
        # An elbow - down from the restricted class, across to the filler - when that crosses
        # no other box; a straight line when it would.
        if depth[b] > depth[a] and not crosses(down, {a, b}) and not crosses(across, {a, b}):
            dashed([down[0], down[1], across[1]])
            tw = d.textlength(prop, font=bold_f)
            lx, ly = (ax + edge_x) / 2.0 - tw / 2.0, by - HALF - PX - 6
            if crosses(((lx, ly), (lx + tw, ly + PX + 8)), set()):
                raise StaleClaim("the %s figure has no room for the label %r" % (FIG_PATTERN, prop))
            d.text((lx, ly), prop, font=bold_f, fill=accent)
        else:
            dashed([(ax, ab[3]), (pos[b][0], bb[1])])
    for n in names.values():
        is_own = n in own
        d.rounded_rectangle(box(n), radius=16, fill=(255, 236, 242) if is_own else light,
                            outline=accent if is_own else indigo_rgb, width=3)
        d.text(pos[n], n, font=bold_f if is_own else reg, anchor="mm",
               fill=(140, 36, 72) if is_own else ink)
    d.line([MARGIN, rule_y, W_ - MARGIN, rule_y], fill=(226, 230, 240), width=3)
    d.text((MARGIN, rule_y + 26), "Object properties the pattern uses", font=bold_f, fill=muted)
    for i, prop in enumerate(props):
        d.text((MARGIN + (i % 2) * col_w, rule_y + 26 + PX + 18 + (i // 2) * (PX + 12)),
               "•  " + prop, font=reg, fill=ink)
    img.convert("RGB").save(os.path.join(DECK, "fig-pattern-process-agent-role.png"))
    return {"size": (W_, H_), "classes": len(declared), "restrictions": len(restrictions),
            "own": sorted(own)}


# -------------------------------------------------------------- the checks

EMU_IN = 914400.0


def check_layout(prs):
    """Reads the finished deck back and lists what a person would see wrong with it.

    Four checks, each one a defect this deck has shipped or nearly shipped:

    - a shape off the slide - python-pptx writes coordinates past the edge without complaint;
    - two shapes that partly overlap. A text box on a card is fine and so is a chip on a row:
      one holds the other. Two text boxes holding each other, or any two shapes crossing an edge,
      are not;
    - body text under 10pt, or footer text under 7pt;
    - text that needs more room than its box, measured with the deck's own fonts and wrapped the
      way PowerPoint wraps, and a word too wide to fit its box at all.

    The measuring needs Segoe UI and Consolas, which every Windows and Office install has. Where
    they are missing the fit is not checked, and the build says so rather than passing silently.
    """
    problems = []
    measurable = can_measure()
    eps = 0.01
    for number_, slide in enumerate(prs.slides, 1):
        boxes = []
        for shape in slide.shapes:
            x0, y0 = shape.left / EMU_IN, shape.top / EMU_IN
            x1, y1 = x0 + shape.width / EMU_IN, y0 + shape.height / EMU_IN
            words_ = shape.has_text_frame and shape.text_frame.text.strip()
            label = (shape.text_frame.text.strip().split("\n")[0][:40] if words_
                     else shape.shape_type and str(shape.shape_type).split(" ")[0] or "shape")
            if x0 < -eps or y0 < -eps or x1 > 10 + eps or y1 > 5.625 + eps:
                problems.append("slide %d: %r is outside the slide (%.2f,%.2f)-(%.2f,%.2f)"
                                % (number_, label, x0, y0, x1, y1))
            boxes.append((x0, y0, x1, y1, bool(words_), label))
            if not words_:
                continue
            least = 7 if y0 >= RULE_Y - eps else 10
            tf = shape.text_frame
            paragraphs = []
            for p in tf.paragraphs:
                runs = [r for r in p.runs if r.text]
                if not runs:
                    paragraphs.append(("", 10, False, FONT, 0))
                    continue
                font = runs[0].font
                size = font.size.pt if font.size is not None else 18
                if size < least - 1e-6:
                    problems.append("slide %d: %r is %.1fpt, under %dpt" % (number_, label,
                                                                           size, least))
                after = p.space_after.pt if p.space_after is not None else 0
                paragraphs.append(("".join(r.text for r in runs), size, bool(font.bold),
                                   font.name or FONT, after))
            if not measurable:
                continue
            inner_w = (shape.width - tf.margin_left - tf.margin_right) / EMU_IN
            inner_h = (shape.height - tf.margin_top - tf.margin_bottom) / EMU_IN
            for body, size, bold, font, _ in paragraphs:
                for line, width in wrap(body, size, bold, font, inner_w):
                    if width > inner_w + 0.005:
                        problems.append("slide %d: %r does not fit across its %.2fin box"
                                        % (number_, line[:40], inner_w))
            need = height_of(paragraphs, inner_w)
            if need > inner_h + 0.02:
                problems.append("slide %d: %r needs %.2fin and has %.2fin"
                                % (number_, label, need, inner_h))
        for (a, b) in itertools.combinations(boxes, 2):
            ix = min(a[2], b[2]) - max(a[0], b[0])
            iy = min(a[3], b[3]) - max(a[1], b[1])
            if ix <= eps or iy <= eps:
                continue
            a_holds_b = a[0] <= b[0] + eps and a[1] <= b[1] + eps and a[2] >= b[2] - eps \
                and a[3] >= b[3] - eps
            b_holds_a = b[0] <= a[0] + eps and b[1] <= a[1] + eps and b[2] >= a[2] - eps \
                and b[3] >= a[3] - eps
            if (a_holds_b or b_holds_a) and not (a[4] and b[4]):
                continue
            problems.append("slide %d: %r and %r overlap" % (number_, a[5], b[5]))
    if not measurable:
        print("note: Segoe UI or Consolas is not installed here, so text fit was not checked")
    return problems


# -------------------------------------------------------------------- slides

def main():
    figure = build_assets()
    prs = Presentation()
    prs.slide_width = SLIDE_W
    prs.slide_height = SLIDE_H
    n = [0]

    def page(dark=False, numbered=True):
        s = blank(prs)
        if dark:
            dark_background(s)
        n[0] += 1
        chrome(s, n[0] if numbered else None, dark=dark)
        return s

    # 1 ---------------------------------------------------------------- title
    s = blank(prs)
    dark_background(s, DEEPER)
    band = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.11), SLIDE_H)
    band.shadow.inherit = False
    band.fill.solid()
    band.fill.fore_color.rgb = ACCENT
    band.line.fill.background()
    picture(s, os.path.join(DECK, "logo-white.png"), M + 0.05, 0.62, h=0.78)
    # 46pt Segoe UI is 0.85in from ascent to descent; the box used to be cut at 0.84 and the
    # subtitle's box started inside it.
    text(s, M + 0.05, 1.52, 8.6, 0.88, "OntoBoard", size=46, colour=WHITE, bold=True)
    text(s, M + 0.05, 2.44, 8.4, 0.4,
         "The ODK and ROBOT pipeline, inside Protégé", size=17,
         colour=RGBColor(0xB9, 0xC2, 0xE8))
    accent = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(M + 0.05), Inches(3.08),
                                Inches(1.1), Pt(2.5))
    accent.shadow.inherit = False
    accent.fill.solid()
    accent.fill.fore_color.rgb = ACCENT
    accent.line.fill.background()
    text(s, M + 0.05, 3.32, 8.4, 0.9, [
        ("Building an ODK repository and running every feature, step by step",
         {"size": 11, "colour": RGBColor(0x9F, 0xAA, 0xD4), "space": 10}),
        (AUTHOR, {"size": 13, "bold": True, "colour": WHITE, "space": 2}),
        (INSTITUTE, {"size": 10, "colour": RGBColor(0x9F, 0xAA, 0xD4)}),
    ])
    text(s, 10 - M - 3.0, 4.72, 3.0, 0.3, "Plugin version " + F["version"], size=10,
         colour=RGBColor(0x7A, 0x85, 0xB0), align=PP_ALIGN.RIGHT)
    n[0] += 1

    # 2 ---------------------------------------------------- what it is, in one sentence
    # Asked for: somebody who has never heard of OntoBoard should know what it is from one
    # sentence. The sentence is the documentation's own opening, docs/index.md, so the deck and
    # the site introduce it the same way.
    s = page()
    y = heading(s, "What OntoBoard is", "in one sentence")
    text(s, M, y, 10 - 2 * M, 0.95,
         "OntoBoard is a plugin for Protégé Desktop: it runs the Ontology Development Kit (ODK) "
         "and ROBOT pipeline — building, reasoning, reporting, releasing — from Protégé's "
         "menus, and draws your whole ontology on a board you can work on like a whiteboard.",
         size=16, colour=INK)
    cards = [
        ("ODK, from the menu",
         "Start a project, or turn an ontology you already have into one; build, test and "
         "release it with nothing else installed."),
        ("The ontology, on a board",
         "Every class, property and individual as soon as the tab opens: patterns framed, "
         "classes as trees, a term's relations brought forward."),
        ("Spreadsheets, into a graph",
         "One sheet per kind of thing: identifiers minted, names checked across sheets, rows "
         "turned into individuals that use your ontology."),
    ]
    cw = (10 - 2 * M - 0.3 * 2) / 3
    cy = y + 1.12
    for i, (head, body) in enumerate(cards):
        x = M + i * (cw + 0.3)
        c = rect(s, x, cy, cw, 1.62, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        c.adjustments[0] = 0.06
        text(s, x + 0.2, cy + 0.16, cw - 0.4, 0.3, head, size=11.5, colour=INDIGO, bold=True)
        text(s, x + 0.2, cy + 0.5, cw - 0.4, 1.02, body, size=10, colour=MUTED)
    text(s, M, cy + 1.84, 10 - 2 * M, 0.7, [
        ("New to ODK, ROBOT or eXtreme Design?",
         {"size": 11.5, "bold": True, "colour": INK, "space": 3}),
        (path("Start here: the guide...") + " shows the " + words(F["guide_steps"])
         + " steps from an idea to a released ontology, ticks the ones your project has done, "
         "and has a button for each.", {"size": 10.5, "colour": MUTED}),
    ])

    # 3 ------------------------------------------------------------ in numbers
    s = page()
    y = heading(s, "OntoBoard in numbers", "what ships today")
    figures = [
        (str(F["patterns"]), "design patterns,\nready to import"),
        (str(F["menu_items"]), "operations in the\nOntoBoard menu"),
        (str(len(F["hosts"])), "Protégé versions,\nsmoke-tested every release"),
        (format(F["tests"], ","), "automated tests\nbehind it"),
    ]
    w = (10 - 2 * M - 0.3 * 3) / 4
    for i, (big, label) in enumerate(figures):
        x = M + i * (w + 0.3)
        card = rect(s, x, y, w, 1.5, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.06
        text(s, x, y + 0.2, w, 0.62, big, size=34, colour=INDIGO, bold=True, align=PP_ALIGN.CENTER)
        text(s, x, y + 0.86, w, 0.58, label, size=10, colour=MUTED, align=PP_ALIGN.CENTER)
    # The receipts moved here from the release step, where they read as something an ontology's
    # release produces. They are OntoBoard's own: build.yml refuses to build a version that has
    # none, and each is that release's notes.
    text(s, M, y + 1.75, 10 - 2 * M, 1.2, [
        ("One jar. No terminal, no Docker command to remember, no second application to learn.",
         {"size": 12.5, "bold": True, "colour": INK, "space": 7}),
        ("Every OntoBoard release carries a host smoke receipt as its release notes: which "
         "Protégés it was started in, what passed, and what was not checked. The build refuses "
         "a version without one — " + format(F["releases"], ",") + " so far.",
         {"size": 11, "colour": MUTED}),
    ])

    # 4 ------------------------------------------------------------- why
    s = page()
    y = heading(s, "Why do we need it?", "the problem")
    col = (10 - 2 * M - 0.35) / 2
    without = rect(s, M, y, col, 2.75, fill=PAPER, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    without.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.18, col - 0.44, 0.3, "WITHOUT ONTOBOARD", size=10, colour=MUTED,
         bold=True)
    bullets(s, M + 0.22, y + 0.56, col - 0.44, [
        ("Edit in Protégé, switch to a terminal to build", None),
        ("docker run, with the right mounts, every time", None),
        ("A quality report you read as console text", None),
        ("Retype a pattern's terms from a web page", None),
        ("A tree of names; relations and rules out of sight", None),
    ], size=10.5, gap=0.40, colour=INK)

    with_ = rect(s, M + col + 0.35, y, col, 2.75, fill=LIGHT, line=INDIGO,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    with_.adjustments[0] = 0.04
    text(s, M + col + 0.57, y + 0.18, col - 0.44, 0.3, "WITH ONTOBOARD", size=10, colour=INDIGO,
         bold=True)
    bullets(s, M + col + 0.57, y + 0.56, col - 0.44, [
        ("Build, reason and report from the menu bar", None),
        ("The project's own ODK YAML read and honoured", None),
        ("Findings in a table, by your project's profile.txt", None),
        ("Pick a pattern — its terms are imported the ODK way", None),
        ("Every class, property and rule on one board", None),
    ], size=10.5, gap=0.40, colour=INK)

    # 5 --------------------------------------------------- section: getting in
    def section(num, title, subtitle):
        sl = page(dark=True)
        bar = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(M), Inches(1.72), Inches(0.9),
                                  Pt(3))
        bar.shadow.inherit = False
        bar.fill.solid()
        bar.fill.fore_color.rgb = ACCENT
        bar.line.fill.background()
        text(sl, M, 1.05, 3.0, 0.5, num, size=12, colour=ACCENT, bold=True)
        # Ends short of the logo's left edge (8.2in): an 8.6in box reached under it.
        text(sl, M, 1.95, 7.5, 0.9, title, size=33, colour=WHITE, bold=True)
        text(sl, M, 2.95, 7.6, 0.6, subtitle, size=12, colour=RGBColor(0x9F, 0xAA, 0xD4))
        picture(sl, os.path.join(DECK, "logo-white.png"), 10 - M - 1.25, 1.55, h=1.25)
        return sl

    section("PART 1", "Getting started", "What you need, and how the plugin gets into Protégé.")

    # 6 ------------------------------------------------------- prerequisites
    # From the newest smoke receipt and docs/getting-started.md ("What works with nothing
    # installed"). Docker used to be listed as needed for any build; since the in-process build a
    # project OntoBoard made needs nothing, and Docker is for a repository ODK's seed made.
    s = page()
    y = heading(s, "Prerequisites", "before you start")
    hosts = [version for version, _ in F["hosts"]]
    items = [
        ("Protégé Desktop " + " or ".join(hosts),
         "The newer is recommended, and every release is smoke-tested in "
         + ("both" if len(hosts) == 2 else "all " + words(len(hosts))) + ". Protégé 6 is not "
         "supported yet: it needs OntoBoard ported to OWL API 5."),
        ("Nothing else, for a project OntoBoard makes",
         "Protégé brings its own Java, and build, test and release run on the ROBOT inside the "
         "plugin — no ROBOT, ODK, Docker or make to install."),
        ("Docker, Podman or a native ODK — for ODK's own repositories",
         "Only to build a repository made by ODK's seed, with run.sh beside its Makefile: its "
         "recipes call tools that exist only in the ODK image."),
        ("git, for the history",
         "For " + path("Git...", full=False) + ", " + path("Open from GitHub...", full=False)
         + ", and the commits and tags " + item("Turn an ontology into an ODK repository...")
         + " records; " + item("Pull request...") + " also needs the GitHub CLI. Widoco and "
         "Konclude you install when you want them."),
    ]
    for i, (head, note) in enumerate(items):
        yy = y + i * 0.86
        rect(s, M, yy, 0.34, 0.34, fill=INDIGO, shape=MSO_SHAPE.OVAL)
        text(s, M, yy + 0.06, 0.34, 0.24, str(i + 1), size=11, colour=WHITE, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, M + 0.52, yy - 0.02, 10 - 2 * M - 0.52, 0.82, [
            (head, {"size": 12.5, "bold": True, "colour": INK, "space": 2}),
            (note, {"size": 10, "colour": MUTED}),
        ], space=0)

    # 7 ------------------------------------------------------------- install
    s = page()
    y = heading(s, "Install", "one jar, one folder")
    steps = [
        ("Download the jar",
         "github.com/ebrahimnorouzi/ontoboard → Releases. The link "
         "/releases/latest/download/ontoboard.jar always serves the newest build."),
        ("Remove any older ontoboard-*.jar",
         "The bundle is singleton:=true: two versions side by side stop it resolving, and the "
         "tab silently never appears."),
        ("Copy it into Protégé's plugins/ folder",
         "ROBOT and all " + str(F["patterns"]) + " patterns are inside the jar; nothing else is "
         "needed."),
        ("Restart Protégé",
         "An OntoBoard menu appears beside Tools, " + tab("OntoBoard") + " opens the board, and "
         + item("Start here: the guide...") + " walks you through the rest."),
    ]
    for i, (head, note) in enumerate(steps):
        yy = y + i * 0.88
        rect(s, M, yy + 0.02, 0.26, 0.26, fill=ACCENT, shape=MSO_SHAPE.OVAL)
        text(s, M, yy + 0.05, 0.26, 0.2, str(i + 1), size=10, colour=WHITE, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, M + 0.42, yy - 0.02, 4.65, 0.84, [
            (head, {"size": 12, "bold": True, "colour": INK, "space": 2}),
            (note, {"size": 10, "colour": MUTED}),
        ], space=0)
    groups = [e["group"] for e in F["menu"]]
    below = [e for e in F["menu"] if e["group"] == groups[-1] and e["items"] is None]
    shot = placeholder(
        s, n[0], 5.85, y, 3.6, 2.95,
        "the OntoBoard menu open in Protégé " + hosts[0] + " with OntoBoard " + F["version"]
        + ": " + item("Start here: the guide...") + " at the top, the "
        + words(len([e for e in F["menu"] if e["items"] is not None])) + " submenus, and the "
        + words(len(below)) + " items below them")
    notes(s, "Placeholder on this slide — " + shot,
          "The menu was regrouped by job in " + F["menu_moved"] + "; the screenshots in "
          "build/shots/ show the older one, so none of them is used here.")

    # 8 ------------------------------------------------- section: the repo
    section("PART 2", "Create an ODK repository",
            "From an empty folder to a repository that builds, step by step.")

    # 9 ------------------------------------------- step 1: new project dialog
    s = page()
    y = heading(s, "Step 1 — " + item("New ODK project..."), where("New ODK project..."))
    if not [t for t in F["tools"] if t.lower() == "new odk project..."]:
        raise StaleClaim("the Tools menu no longer has New ODK project...; slide 'Step 1' says so")
    text(s, M, y, 4.85, 3.75, [
        (path("New ODK project...") + "  (also under Tools)",
         {"size": 11.5, "bold": True, "colour": INDIGO, "space": 8}),
        ("You give it an ontology ID, a title and a folder. The ID names the files and the "
         "terms, so it is lowercase: a letter first, then letters, digits or underscores.",
         {"size": 10.5, "colour": INK, "space": 7}),
        ("The base IRI arrives filled in with OBO's address, which is right only for an ontology "
         "registered with the OBO Foundry; otherwise use a domain you control, such as "
         "w3id.org/<id>/.", {"size": 10.5, "colour": MUTED, "space": 7}),
        ("It writes the project and opens the edit file — no seed-via-docker step, nothing to "
         "download.", {"size": 10.5, "colour": INK, "space": 7}),
        ("Already have an ontology? " + path("Turn an ontology into an ODK repository...",
                                             full=False)
         + " makes the same project from it, its earlier versions becoming releases, commits "
         "and tags.", {"size": 10.5, "colour": MUTED}),
    ])
    # The 1.93.0 crop that stood here was cut off at the dialog's second label - no field at all -
    # and read as an empty two-row dialog. The current one asks for every field below.
    shot = placeholder(
        s, n[0], 5.65, y, 3.8, 2.7,
        "the " + item("New ODK project...") + " dialog in OntoBoard " + F["version"] + " (Protégé "
        + F["hosts"][0][0] + "), with all " + words(len(F["wizard_fields"])) + " fields - "
        + ", ".join(F["wizard_fields"]) + " - and the hint below them")
    notes(s, "Placeholder on this slide — " + shot)

    # 10 ----------------------------------------------- step 2: what it writes
    # The tree is OntoBoard's scaffold, checked against OdkScaffold.java below: imports/ and
    # src/patterns/ used to be drawn in it, and the scaffold stopped creating empty directories
    # long ago - imports/ appears with the first import.
    s = page()
    y = heading(s, "Step 2 — What the wizard writes", "the repository")
    tree = [
        ("myonto/", 0, True), ("src/ontology/", 1, True),
        ("myonto-edit.owl", 2, False), ("myonto-odk.yaml", 2, False),
        ("Makefile   myonto.Makefile", 2, False), ("myonto-idranges.owl", 2, False),
        ("catalog-v001.xml   profile.txt", 2, False),
        ("src/sparql/", 1, True), ("check_labels.rq", 2, False),
        (".github/workflows/", 1, True), ("qc.yml   docs.yml", 2, False),
        ("docs/", 1, True), ("index.md   editing.md   release.md", 2, False),
        ("mkdocs.yaml   README.md", 1, False), (".gitignore   .gitattributes", 1, False),
    ]
    scaffold = _read("protege-plugin", "src", "main", "java", "de", "fizkarlsruhe", "ise",
                     "ontoboard", "odk", "OdkScaffold.java")
    files = [part for entry, _, isdir in tree if not isdir for part in entry.split()]
    for part in files:
        token = part.replace("myonto", "")
        if token not in scaffold:
            raise StaleClaim("OdkScaffold.java no longer writes %s; slide 'Step 2' draws it" % part)
    if len(files) != F["scaffold_files"]:
        raise StaleClaim("slide 'Step 2' draws %d files, odk-workflow.md says the wizard writes %d"
                         % (len(files), F["scaffold_files"]))
    panel = rect(s, M, y, 4.5, 3.3, fill=PAPER, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    panel.adjustments[0] = 0.03
    for i, (name, depth, isdir) in enumerate(tree):
        text(s, M + 0.22 + depth * 0.26, y + 0.14 + i * 0.2, 3.9 - depth * 0.26, 0.2, name,
             size=10, colour=INDIGO if isdir else INK, bold=isdir, font=MONO)
    text(s, M + 4.85, y, 4.05, 3.3, [
        ("An ODK layout, in " + words(F["scaffold_files"]) + " files.",
         {"size": 12, "bold": True, "colour": INK, "space": 8}),
        ("Written with no Docker and no network. ODK users find their way round it — but it is "
         "OntoBoard's kind of project: its Makefile builds inside Protégé, and ODK's own "
         "update_repo does not run on it.", {"size": 10.5, "colour": MUTED, "space": 8}),
        ("myonto-odk.yaml is the project's configuration: every key is shown, every scalar is "
         "editable at any depth, and list entries can be added and removed.",
         {"size": 10.5, "colour": INK, "space": 8}),
        ("profile.txt decides which quality checks are errors — and the quality report reads "
         "that same file, so the plugin and your CI agree.", {"size": 10.5, "colour": INK}),
    ])

    # 11 --------------------------------------------------- step 3: the YAML
    # odk-workflow.md, "Reading and writing are not the same as honouring": these are the keys
    # something in the plugin acts on. The rest are kept exactly as written and obeyed by nothing.
    s = page()
    y = heading(s, "Step 3 — The ODK YAML, key by key", "project configuration")
    text(s, M, y, 10 - 2 * M, 0.45,
         path("Project configuration...") + " shows every key in the file, changes any scalar in "
         "place, and adds or removes list entries.", size=11, colour=INK)
    rows = [
        ("id, title, description, license", "Read by " + item("Update project files...")
         + " to regenerate the project's files"),
        ("robot_java_args", "The heap a containerised build gives ROBOT"),
        ("robot_report.fail_on", "Which severity the quality report counts as failing the build"),
        ("robot_report.use_labels", "Labels instead of IRIs in the report"),
        ("export_formats", "The formats " + item("Release...") + " writes"),
        ("import_group.products", "The imports " + item("Refresh imports...")
         + " audits and rebuilds"),
    ]
    ty = y + 0.55
    value_w = 10 - M - (M + 3.7) - 0.1   # to the table's right edge, not past it
    rect(s, M, ty, 10 - 2 * M, 0.34, fill=INDIGO)
    text(s, M + 0.14, ty + 0.07, 3.4, 0.22, "KEY", size=10, colour=WHITE, bold=True)
    text(s, M + 3.7, ty + 0.07, value_w, 0.22, "WHAT IT DRIVES", size=10, colour=WHITE,
         bold=True)
    for i, (k, v) in enumerate(rows):
        yy = ty + 0.34 + i * 0.34
        if i % 2 == 0:
            rect(s, M, yy, 10 - 2 * M, 0.34, fill=PAPER)
        text(s, M + 0.14, yy + 0.08, 3.5, 0.2, k, size=10, colour=INDIGO, font=MONO)
        text(s, M + 3.7, yy + 0.07, value_w, 0.22, v, size=10, colour=INK)
    text(s, M, ty + 0.34 + len(rows) * 0.34 + 0.14, 10 - 2 * M, 0.6,
         "Edits are spliced, not rewritten: changing one value changes one line, so the diff is "
         "the change and your comments survive. Keys nothing here acts on — github_org, repo, "
         "release_artefacts, primary_release — are kept exactly as written.",
         size=10, colour=MUTED)

    # 12 ------------------------------------------------- step 4: id ranges
    # The third card used to say "writes one line ... splice, never rewrite". It does not:
    # IdRanges re-renders the file from a template. What it guarantees instead (odk-workflow.md,
    # since 1.77.0) is that nothing is lost - and the name is the one the plugin mints under,
    # since 1.134.0 (fixed.md).
    s = page()
    y = heading(s, "Step 4 — ID ranges", "who mints which identifiers")
    text(s, M, y, 10 - 2 * M, 0.95, [
        (path("ID ranges..."), {"size": 11.5, "bold": True, "colour": INDIGO, "space": 7}),
        ("An ODK project gives each curator a block of numbers, so two people never mint the same "
         "identifier. This shows every block and who holds it, and allocates the next one.",
         {"size": 10.5, "colour": INK}),
    ])
    cards = [
        ("Shows the whole file", "Every allocated block, with its owner."),
        ("Allocates the next block", "After the highest one and sized like the others; a block "
         "that would overlap is refused."),
        ("Under the name you mint with", "Your collaboration display name, else your login — the "
         "name the board and the sheet editor use."),
    ]
    cw = (10 - 2 * M - 0.3 * 2) / 3
    for i, (head, note) in enumerate(cards):
        x = M + i * (cw + 0.3)
        c = rect(s, x, y + 1.1, cw, 1.4, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        c.adjustments[0] = 0.07
        text(s, x + 0.18, y + 1.26, cw - 0.36, 0.26, head, size=11, colour=INDIGO, bold=True)
        text(s, x + 0.18, y + 1.58, cw - 0.36, 0.85, note, size=10, colour=MUTED)
    text(s, M, y + 2.7, 10 - 2 * M, 0.6,
         "The file is re-rendered rather than spliced, so its formatting is normalised — but "
         "nothing in it is dropped: a line that would not survive stops the write.", size=10.5,
         colour=ACCENT, bold=True)

    # 13 ---------------------------------------------------- step 5: the board
    # Rewritten for 1.131.0, when the board stopped starting empty: every term is on it as soon
    # as the tab opens (docs/canvas.md, getting-started.md). The old slide's reason for an empty
    # canvas - drawing 100,000 classes would hang - is answered by the limit read from the view.
    s = page()
    y = heading(s, "Step 5 — Your whole ontology on the board", "the board")
    text(s, M, y, 4.95, 3.85, [
        (tab("OntoBoard"), {"size": 11.5, "bold": True, "colour": INDIGO, "space": 7}),
        ("Every class, property and individual of the open ontology is on the board as soon as "
         "the tab opens — imports included, up to " + format(F["board_limit"], ",")
         + " terms, the ontology's own first.", {"size": 10.5, "colour": INK, "space": 7}),
        ("It is laid out as an outline: library patterns framed along the top, classes as trees, "
         "individuals under their classes, each property hierarchy in a frame of its own.",
         {"size": 10.5, "colour": MUTED, "space": 7}),
        ("Select a term and it comes forward with everything it is connected to. Tab adds a "
         "subclass, Enter a sibling, and a double-click on empty board a new class.",
         {"size": 10.5, "colour": INK, "space": 7}),
        ("The arrangement is saved beside the ontology, in <file>.ontoboard.json; the ontology "
         "itself is never touched by the diagram. A board saved before " + F["board_since"]
         + " keeps the terms chosen then — " + on_board("More") + ARROW
         + on_board("Show every term") + " switches it over.",
         {"size": 10, "colour": MUTED}),
    ])
    shot = placeholder(
        s, n[0], 5.75, y, 3.7, 3.0,
        "the OntoBoard tab in OntoBoard " + F["version"] + " just after opening an ontology "
        "(pizza is enough): the whole ontology as an outline — pattern frames along the top, "
        "class trees, property frames at the right")
    notes(s, "Placeholder on this slide — " + shot)

    # 14 ---------------------------------------------------- step 6: build
    # MakeRun.Route, in its own order: a project OntoBoard scaffolded builds in process; ODK's own
    # repositories go through a native ODK or a container. The command is MakeRun's, with the
    # image and the heap it falls back to read from the code.
    s = page()
    y = heading(s, "Step 6 — Build the ontology", where("Build..."))
    text(s, M, y, 10 - 2 * M, 0.5, [
        (path("Build..."), {"size": 11.5, "bold": True, "colour": INDIGO, "space": 4}),
        ("Pick one of the project's make targets. The whole transcript is kept, and Stop ends the "
         "build rather than leaving it running.", {"size": 10.5, "colour": INK}),
    ])
    col = (10 - 2 * M - 0.3) / 2
    a = rect(s, M, y + 0.66, col, 1.32, fill=LIGHT, line=INDIGO, shape=MSO_SHAPE.ROUNDED_RECTANGLE,
             line_w=1.25)
    a.adjustments[0] = 0.06
    text(s, M + 0.18, y + 0.76, col - 0.36, 1.16, [
        ("A PROJECT ONTOBOARD MADE", {"size": 10, "bold": True, "colour": INDIGO, "space": 3}),
        ("Every target runs inside Protégé, on the ROBOT in the plugin — nothing to install. "
         "The test target runs " + ", ".join(F["test_runs"][:-1]) + " and " + F["test_runs"][-1]
         + ".",
         {"size": 10, "colour": INK, "space": 3}),
        (", ".join(F["targets"]), {"size": 10, "colour": INDIGO, "font": MONO}),
    ], space=0)
    b = rect(s, M + col + 0.3, y + 0.66, col, 1.32, fill=PAPER, line=FAINT,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    b.adjustments[0] = 0.06
    text(s, M + col + 0.48, y + 0.76, col - 0.36, 1.16, [
        ("A REPOSITORY ODK'S SEED MADE", {"size": 10, "bold": True, "colour": MUTED, "space": 3}),
        ("run.sh beside its Makefile: the build goes through a native ODK install, or through "
         "Docker or Podman in the project's own ODK image — below.",
         {"size": 10, "colour": INK}),
    ], space=0)
    panel = rect(s, M, y + 2.12, 10 - 2 * M, 0.98, fill=DEEP, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    panel.adjustments[0] = 0.06
    log_lines = [
        "docker run --rm -v <project>:/work -w /work/src/ontology \\",
        "    -e ROBOT_JAVA_ARGS=" + F["heap"] + " -e JAVA_OPTS=" + F["heap"] + " " + F["image"]
        + " make <target>",
        "ROBOT_JAVA_ARGS comes from robot_java_args in your ODK YAML; " + F["heap"]
        + " only when it says nothing.",
    ]
    for i, line in enumerate(log_lines):
        text(s, M + 0.22, y + 2.22 + i * 0.25, 10 - 2 * M - 0.44, 0.2, line, size=10,
             colour=RGBColor(0x8F, 0xE3, 0xB0) if i < 2 else RGBColor(0x9F, 0xAA, 0xD4),
             font=MONO)
    bullets(s, M, y + 3.24, col - 0.2, [
        ("A failure says why", "The rule and the terms that break it, not only an exit code."),
    ], size=10.5, gap=0.44)
    bullets(s, M + col + 0.3, y + 3.24, col - 0.2, [
        ("Unsaved edits? It asks first", "A build checks the file, not the window."),
    ], size=10.5, gap=0.44)

    # 15 -------------------------------------------------- step 7: release
    s = page()
    y = heading(s, "Step 7 — Release", where("Release..."))
    left = [
        (item("Release..."), "Reasons, writes the inferences down, stamps a version and keeps a "
         "copy that never changes — and refuses if the project's quality profile reports an "
         "error, or a term the last release published has vanished."),
        ("Dated or semantic — you choose", "OBO's .../releases/2026-10-12/mwo.owl, or MatWerk's "
         ".../mwo/3.0.2. They publish different IRIs, so it is not a preference."),
        (item("History...") + " and " + item("Compare releases..."),
         "Every version the project kept, and what changed between two of them, term by term: "
         "added, obsoleted, redefined, moved."),
    ]
    for i, (head, note) in enumerate(left):
        yy = y + i * 1.05
        text(s, M, yy, 4.55, 1.0, [
            (head, {"size": 11.5, "bold": True, "colour": INDIGO, "space": 2}),
            (note, {"size": 10, "colour": MUTED}),
        ], space=0)
    # ReleaseAction and Release.stamp: both conventions stamp owl:versionIRI and owl:versionInfo,
    # CHANGES.md is written from the diff when there is a release before it, and a format the
    # project asks for that is not written here is named, not skipped in silence.
    card = rect(s, 5.3, y - 0.05, 4.15, 3.02, fill=LIGHT, line=INDIGO,
                shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    card.adjustments[0] = 0.05
    text(s, 5.52, y + 0.12, 3.7, 2.72, [
        ("What a release writes", {"size": 11.5, "bold": True, "colour": INDIGO, "space": 7}),
        ("releases/<version>/<id>.owl, kept for good, stamped with owl:versionIRI and "
         "owl:versionInfo; a semantic release links owl:priorVersion to the one before it.",
         {"size": 10, "colour": INK, "space": 7}),
        ("CHANGES.md beside it, generated from what changed since the release before.",
         {"size": 10, "colour": INK, "space": 7}),
        ("Each format export_formats asks for — and it says which it does not write.",
         {"size": 10, "colour": INK, "space": 7}),
        ("One file per release: ODK's base, full and simple variants are not written yet.",
         {"size": 10, "colour": MUTED}),
    ])

    # 16 ------------------------------------------- section: the workspace
    section("PART 3", "The workspace",
            "What ODK puts on disk, and which of it OntoBoard drives.")

    # 17 ------------------------------------------------- workspace overview
    # odk-workflow.md, "The workspace": src/templates joined in 1.117.0, and src/metadata stopped
    # being "by hand" when Check project metadata started reading it (it still writes nothing).
    s = page()
    y = heading(s, "Workspace overview", "what is in src/")
    rows = [
        ("src/ontology/", "Edit file, catalog, both Makefiles, imports/ and the ODK YAML — read "
         "and acted on", "Driven", True),
        ("src/sparql/", "Your own checks: " + path("SPARQL...", full=False)
         + " runs them as make sparql_test does", "Driven", True),
        ("src/scripts/", path("Run a project script...", full=False)
         + " lists them and runs one, with arguments", "Driven", True),
        ("src/templates/", "ROBOT templates: opened in the OntoBoard Sheets tab, built by "
         + path("Template...", full=False), "Driven", True),
        ("src/metadata/", "The OBO Foundry entry: " + path("Check project metadata", full=False)
         + " reads it, nothing writes it", "Checked", False),
    ]
    h = 0.5
    for i, (folder, what, verdict, ok) in enumerate(rows):
        ry = y + i * (h + 0.1)
        card = rect(s, M, ry, 10 - 2 * M, h, fill=LIGHT if ok else RGBColor(0xFA, 0xF7, 0xF2),
                    line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.14
        text(s, M + 0.2, ry + 0.12, 1.85, 0.26, folder, size=11.5, bold=True,
             colour=INDIGO if ok else RGBColor(0x8A, 0x6D, 0x3B))
        text(s, M + 2.1, ry + 0.06, 5.45, 0.4, what, size=10, colour=MUTED)
        chip = rect(s, 10 - M - 1.05, ry + 0.08, 0.85, 0.34,
                    fill=ACCENT if ok else RGBColor(0xE8, 0xDC, 0xC2), line=None,
                    shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        chip.adjustments[0] = 0.3
        text(s, 10 - M - 1.05, ry + 0.135, 0.85, 0.22, verdict, size=10, bold=True,
             colour=WHITE if ok else RGBColor(0x6B, 0x55, 0x2C), align=PP_ALIGN.CENTER)
    driven = len([r for r in rows if r[3]])
    text(s, M, y + len(rows) * (h + 0.1) + 0.06, 10 - 2 * M, 0.82, [
        (words(driven).capitalize() + " of the " + words(len(rows)) + " are driven from the "
         "menu; the last is read and checked, never written.",
         {"size": 12, "bold": True, "colour": INK, "space": 5}),
        ("No terminal and no sh run.sh: ROBOT runs in process, your scripts run in the "
         "project's ODK container or a native ODK, and the SPARQL checks run against the "
         "ontology you have open.", {"size": 10.5, "colour": MUTED}),
    ])

    # 18 ------------------------------------------------- the ontology directory
    s = page()
    y = heading(s, "The ontology directory", "src/ontology, file by file")
    files = [
        ("<id>-edit.owl", "The file you edit. Protégé opens and saves it; OntoBoard never "
         "writes it"),
        ("<id>-odk.yaml", item("Project configuration...") + " edits every scalar, adds and "
         "removes list entries"),
        ("Makefile, <id>.Makefile", item("Build...") + " lists the targets, following include "
         "into your own Makefile"),
        ("catalog-v001.xml", "Written by " + item("Import terms from a list...")
         + ", so an import resolves offline"),
        ("imports/", item("Refresh imports...") + " audits every declared product and "
         "rebuilds what it can"),
        ("profile.txt", item("Quality report...") + " reads it beside the edit file — ROBOT's "
         + str(F["report_rules"]) + " rules otherwise"),
    ]
    col = (10 - 2 * M - 0.3) / 2
    for i, (name, what) in enumerate(files):
        cx = M + (i % 2) * (col + 0.3)
        cy = y + (i // 2) * 0.95
        card = rect(s, cx, cy, col, 0.84, fill=LIGHT, line=FAINT,
                    shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.09
        text(s, cx + 0.18, cy + 0.08, col - 0.36, 0.26, name, size=11, bold=True, colour=INDIGO)
        text(s, cx + 0.18, cy + 0.38, col - 0.36, 0.4, what, size=10, colour=MUTED)
    text(s, M, y + 3 * 0.95 + 0.04, 10 - 2 * M, 0.62,
         "Every one of these is read by something in the menu. The ODK YAML and the term lists "
         "are spliced, never re-serialised, so a one-word change stays a one-line diff; the edit "
         "file is only ever saved by Protégé.", size=10.5, colour=MUTED)

    # 19 --------------------------------------------- custom import, the old way
    # odk-workflow.md, "Adding a custom import": ODK's walkthrough is six steps there, and the
    # count and how many of them OntoBoard does are read from that sentence.
    s = page()
    total, done = F["import_steps"]
    y = heading(s, "Adding a custom import", words(total) + " steps, in ODK's own walkthrough")
    col = (10 - 2 * M - 0.35) / 2
    old = rect(s, M, y, col, 3.62, fill=PAPER, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    old.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.18, col - 0.44, 0.24, "THE ODK WALKTHROUGH", size=10, colour=MUTED,
         bold=True)
    odk_steps = [
        "Declare it under import_group in the YAML",
        "Check the Makefile picked it up",
        "Add term IRIs to <import>_terms.txt, then make refresh-imports",
        "Copy the import into the edit file and the catalog",
        "Copy the import schema into <id>.Makefile — custom modules only",
        "Set module_type, then update_repo, clean, make",
    ]
    if len(odk_steps) != total:
        raise StaleClaim("odk-workflow.md counts %d ODK steps; the slide lists %d"
                         % (total, len(odk_steps)))
    bullets(s, M + 0.22, y + 0.54, col - 0.44, odk_steps, size=10, gap=0.48, colour=MUTED)
    new = rect(s, M + col + 0.35, y, col, 3.62, fill=LIGHT, line=FAINT,
               shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    new.adjustments[0] = 0.04
    text(s, M + col + 0.57, y + 0.18, col - 0.44, 0.24, "WITH ONTOBOARD", size=10, colour=INDIGO,
         bold=True)
    bullets(s, M + col + 0.57, y + 0.54, col - 0.44, [
        path("Import terms from a list...", full=False) + ", or " + item(
            "Find terms in another ontology...") + ", picks the source and the terms",
        "It writes the module, the term list, the import and the catalog entry",
        "It declares the product in import_group.products",
        item("Refresh imports...") + " audits and rebuilds; " + item("Imported terms...")
        + " adds and takes out terms by label",
    ], size=10, gap=0.56, colour=INK)
    text(s, M + col + 0.57, y + 2.84, col - 0.44, 0.7,
         words(done).capitalize() + " of the " + words(total) + " steps are one operation now. "
         "Copying the import schema is only for a custom module — which ODK's own generated "
         "rule refuses too.", size=10, colour=MUTED)

    # 20 ------------------------------------------- what refresh imports reports
    s = page()
    r = F["refresh"]
    projects = F["projects"]
    y = heading(s, "What a refresh tells you",
                "measured on " + words(len(projects)) + " real projects")
    figures = [
        (str(r["products"]), "import products\ndeclared across them"),
        (str(r["rebuilt"]), "of a kind OntoBoard\nrebuilds itself"),
        (str(r["named"]), "named with the reason\nrather than guessed at"),
        ("%d of %d" % (r["undeclared"], len(projects)), "projects with a module\nno product "
                                                        "declares"),
    ]
    w = (10 - 2 * M - 0.3 * 3) / 4
    for i, (big, label) in enumerate(figures):
        x = M + i * (w + 0.3)
        card = rect(s, x, y, w, 1.45, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.06
        text(s, x, y + 0.18, w, 0.58, big, size=32, colour=INDIGO, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, x, y + 0.82, w, 0.56, label, size=10, colour=MUTED, align=PP_ALIGN.CENTER)
    text(s, M, y + 1.68, 10 - 2 * M, 1.95, [
        ("An import nobody can rebuild is one nobody else can build the repository from.",
         {"size": 12, "bold": True, "colour": INK, "space": 7}),
        (path("Refresh imports...") + " reads the products the project declares instead of "
         "only listing imports/. " + ", ".join(projects[:-1]) + " and " + projects[-1] + " declare "
         + str(r["products"]) + " between them. Of the " + str(r["named"]) + " it does not "
         "rebuild, " + str(r["custom"]) + " are custom — ODK's own generated rule refuses those "
         "too, because the real command is hand-written in the project's Makefile — "
         + str(r["subset"]) + " use SUBSET, " + str(r["mirror"]) + " are mirrors and "
         + str(r["filter"]) + " a filter. A module in imports/ that no product declares is "
         "one make refresh-imports never touches.", {"size": 10.5, "colour": MUTED}),
    ])

    # 21 ------------------------------------------------- section: features
    section("PART 4", "Running the features",
            "What the menu gives you, and what each operation really does.")

    # 22 -------------------------------------------------------------- the menu
    # Drawn from plugin.xml, not photographed: 02-ontoboard-menu.png shows the 1.93.0 menu - a
    # ROBOT submenu, Notes, Provenance... - which 1.133.0 regrouped by job. A table generated from
    # the manifest cannot fall behind the menu, and the rows are measured so a menu that has grown
    # past one slide fails the build instead of running into the footer.
    s = page()
    y = heading(s, "The OntoBoard menu", "grouped by the job you came to do, since "
                + F["menu_moved"])
    table, seen_submenu = [], False
    for group, entries in itertools.groupby(F["menu"], key=lambda e: e["group"]):
        entries, gap = list(entries), bool(table)
        loose = [e["name"] for e in entries if e["items"] is None]
        if loose:
            table.append(("Below them" if seen_submenu else "Top of the menu", loose, gap))
            gap = False
        for e in entries:
            if e["items"] is not None:
                table.append((e["name"], e["items"], gap))
                gap, seen_submenu = False, True
    name_w, items_w = 1.75, 10 - 2 * M - 1.75
    heights = []
    for name, items_, _ in table:
        lines = max(len(wrap(", ".join(shown(i) for i in items_), 10, False, FONT,
                             items_w - 0.16)) if can_measure() else 2, 1)
        heights.append(lines * 0.185 + 0.1)
    if y + sum(heights) + 0.08 * len([t for t in table if t[2]]) > BOTTOM:
        raise StaleClaim("the OntoBoard menu no longer fits one slide; split the table")
    yy = y
    for i, ((name, items_, gap), rh) in enumerate(zip(table, heights)):
        yy += 0.08 if gap else 0
        rect(s, M, yy, 10 - 2 * M, rh, fill=PAPER if i % 2 == 0 else LIGHT)
        text(s, M + 0.14, yy + 0.045, name_w - 0.2, rh - 0.07, shown(name), size=10.5,
             colour=INDIGO, bold=True)
        text(s, M + name_w, yy + 0.05, items_w - 0.16, rh - 0.07,
             ", ".join(shown(i) for i in items_), size=10, colour=INK)
        yy += rh
    # What moved where, for anybody who learnt the old menu - said, not shown, because the table
    # needs the slide.
    notes(s, "Read from plugin.xml when the deck was built. Since " + F["menu_moved"] + " the "
          "menu is grouped by job rather than by tool: ROBOT's items are split between Reuse, "
          "Terms and Check and test; Notes are part of Terms; Knowledge graph is Spreadsheets; "
          "Provenance... is " + item("Record who edits terms...") + "; Build..., Release..., "
          "History..., Compare releases... and Documentation... moved from Project to Build and "
          "release; Imports is " + path("Check imports", full=False) + ".")

    # 23 ------------------------------------------------------ robot, in process
    # Where 05-menu-robot.png was: a picture of a submenu that no longer exists. What survives is
    # the point it made - robot-core runs in process - and where each ROBOT command went, which is
    # requirements-status.md's table of ROBOT operations, with every path read from plugin.xml.
    s = page()
    y = heading(s, "ROBOT, running in process", "no external binary")
    text(s, M, y, 10 - 2 * M, 0.72, [
        ("robot-core runs inside the plugin, against the ontology you have open.",
         {"size": 11.5, "bold": True, "colour": INK, "space": 4}),
        ("No robot.jar on your PATH, no temporary files, no second copy of the ontology to keep in "
         "step: each operation sees exactly what Protégé sees. Where each ROBOT command is now:",
         {"size": 10.5, "colour": MUTED}),
    ])
    robot = [("report", "Quality report..."), ("measure", "Measure..."),
             ("validate-profile", "Profile..."), ("explain", "Explain..."),
             ("query", "SPARQL..."), ("extract", "Import terms from a list..."),
             ("rename", "Rename IRIs..."), (", ".join(F["transform_kinds"]), "Transform..."),
             ("export", "Export terms..."), ("template", "Template..."),
             ("diff", "Compare releases..."), ("reason", "Release...")]
    cols, gap_x = 3, 0.2
    cw = (10 - 2 * M - gap_x * (cols - 1)) / cols
    row_h = []
    for at in range(0, len(robot), cols):
        need = 0
        for command, label in robot[at:at + cols]:
            need = max(need, height_of([(path(label, full=False), 10, True, FONT, 2),
                                        ("robot " + command, 10, False, MONO, 0)], cw - 0.28)
                       if can_measure() else 0.55)
        row_h.append(need + 0.14)
    yy = y + 0.86
    if yy + sum(row_h) + 0.1 * (len(row_h) - 1) > BOTTOM:
        raise StaleClaim("the ROBOT commands no longer fit one slide")
    for r_i, at in enumerate(range(0, len(robot), cols)):
        for c_i, (command, label) in enumerate(robot[at:at + cols]):
            x = M + c_i * (cw + gap_x)
            c = rect(s, x, yy, cw, row_h[r_i], fill=PAPER, line=FAINT,
                     shape=MSO_SHAPE.ROUNDED_RECTANGLE)
            c.adjustments[0] = 0.1
            text(s, x + 0.14, yy + 0.06, cw - 0.28, row_h[r_i] - 0.1, [
                (path(label, full=False), {"size": 10, "bold": True, "colour": INDIGO,
                                           "space": 2}),
                ("robot " + command, {"size": 10, "colour": ACCENT, "font": MONO}),
            ], space=0)
        yy += row_h[r_i] + 0.1

    # 24 ---------------------------------------------------- quality report
    s = page()
    y = heading(s, "Quality report", "by your project's own rules")
    text(s, M, y, 5.0, 0.5,
         path("Quality report...") + " runs ROBOT's " + str(F["report_rules"])
         + " rules in process and shows every violation in a sortable table.", size=11, colour=INK)
    pts = [
        ("It reads your profile.txt", "The same file your CI passes to ROBOT, found beside the "
                                      "edit file — so the plugin and the build agree about what "
                                      "counts as a violation."),
        ("It reads robot_report.fail_on", "Which severity fails the build comes from your ODK "
                                          "YAML, not from a default."),
        ("It says what would fail", "“N of M findings are at or above 'ERROR', which would fail "
                                    "this project's build.”"),
    ]
    points(s, M, y + 0.72, 5.0, pts, step=0.92, bar=0.7)
    shot = placeholder(
        s, n[0], 5.85, y, 3.6, 3.1,
        path("Quality report...", full=False) + " in OntoBoard " + F["version"] + " on an ODK "
        "project with a profile.txt: the findings table, and the line saying how many would fail "
        "the project's build")
    notes(s, "Placeholder on this slide — " + shot)

    # 25 -------------------------------------------------- the rest of the toolbox
    # Every description on this slide is the item's own tooltip in plugin.xml - what the plugin
    # says about itself, so the slide cannot promise more than the menu does. (It used to say
    # Konclude was "fetched and run for you"; it is found if you have installed it.)
    s = page()
    y = heading(s, "The rest of the toolbox", "every description is the menu's own")
    columns = [
        ["Write a test from a question...", "Explain...", "Profile...", "Check project metadata"],
        ["Obsolete the selected term...", "Rename IRIs...", "Transform...", "Export terms..."],
        ["Find terms in another ontology...", "Imported terms...", "Documentation..."],
    ]
    cw = (10 - 2 * M - 0.25 * 2) / 3
    for c_i, labels in enumerate(columns):
        x, yy, current = M + c_i * (cw + 0.25), y, None
        for label in labels:
            submenu = _entry(label)["chain"][1]
            if submenu != current:
                current = submenu
                text(s, x, yy, cw, 0.22, shown(submenu).upper(), size=10, colour=ACCENT,
                     bold=True)
                yy += 0.3
            body = [(item(label), 10.5, True, FONT, 2), (tooltip(label), 10, False, FONT, 0)]
            need = height_of(body, cw) if can_measure() else 0.75
            text(s, x, yy, cw, need + 0.04, [
                (item(label), {"size": 10.5, "bold": True, "colour": INDIGO, "space": 2}),
                (tooltip(label), {"size": 10, "colour": MUTED}),
            ], space=0)
            yy += need + 0.14
        if yy > BOTTOM:
            raise StaleClaim("a column of the toolbox slide runs into the footer")

    # 26 ------------------------------------ section: knowledge graphs
    section("PART 5", "Building a knowledge graph",
            "Spreadsheets of data, turned into individuals, without leaving Protégé.")

    # 27 ------------------------------------------------ the sheet editor
    s = page()
    y = heading(s, "A spreadsheet inside Protégé",
                tab("OntoBoard Sheets") + ", or " + path("Sheet editor", full=False))
    text(s, M, y, 10 - 2 * M, 0.62,
         "Each sheet is a ROBOT template: row 1 is your headings, row 2 says what each column is, "
         "and every row after that is one thing. The editor opens the whole folder at once, "
         "because a sheet can name the rows of every other sheet in it, in any order.",
         size=11, colour=INK)
    pts = [
        ("It finds your templates itself",
         "The first of " + words(F["sheet_places"]) + " places that holds a sheet opens without a "
         "file chooser, and the toolbar says which — the next two slides."),
        ("Names come from the sheets and the ontology",
         "A column that names another term completes from every open sheet and from the "
         "ontology, so a reference to nothing shows up as you type."),
        ("Nothing is written until you press Save",
         "These files are in git, and somebody else is editing them."),
    ]
    points(s, M, y + 0.82, 10 - 2 * M, pts, step=0.8)

    # 28 ------------------------------------------- where the sheet editor looks
    # TemplateDiscovery, 1.134.0. The order, the reasons and the four-levels rule are the class's
    # own; until then the editor knew two places and said "No templates yet" over nine templates
    # one folder away (fixed.md).
    s = page()
    y = heading(s, "Where the sheet editor looks", words(F["sheet_places"]) + " places, in this "
                                                                           "order")
    places = [
        ("templates/", "beside the open file — or, for an ODK edit file in src/ontology/, the "
                       "project's src/templates/ instead"),
        ("src/templates/", "the ODK project's own templates"),
        ("kg/templates/", "a knowledge graph kept beside the ontology"),
        ("templates/", "at the project root"),
    ]
    if len(places) != F["sheet_places"]:
        raise StaleClaim("limitations.md counts %d places the sheet editor looks; the slide "
                         "draws %d" % (F["sheet_places"], len(places)))
    for i, (folder, why) in enumerate(places):
        yy = y + i * 0.74
        rect(s, M, yy + 0.02, 0.3, 0.3, fill=INDIGO, shape=MSO_SHAPE.OVAL)
        text(s, M, yy + 0.07, 0.3, 0.2, str(i + 1), size=10, colour=WHITE, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, M + 0.45, yy - 0.02, 4.3, 0.68, [
            (folder, {"size": 11, "bold": True, "colour": INDIGO, "font": MONO, "space": 2}),
            (why, {"size": 10, "colour": MUTED}),
        ], space=0)
    card = rect(s, 5.45, y - 0.04, 10 - M - 5.45, 2.86, fill=LIGHT, line=FAINT,
                shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    card.adjustments[0] = 0.05
    bullets(s, 5.65, y + 0.16, 10 - M - 5.65 - 0.37, [
        ("The first of them that holds a .tsv or .csv opens; the others are a click away.", None),
        ("The project is the nearest folder holding .git or src/ontology/, at most "
         + words(F["levels_up"]) + " levels up.", None),
        ("So a project nested in another repository never picks up its host's templates.", None),
        ("Nothing searches subfolders, and opening the view creates nothing.", None),
    ], size=10.5, gap=0.62, colour=INK)

    # 29 ------------------------------------------- it says where, and follows
    s = page()
    y = heading(s, "It names the folder, and follows you", "the toolbar and the active ontology")
    text(s, M, y, 4.95, 3.85, [
        ("Folder: " + F["folder_example"], {"size": 11, "bold": True, "colour": INDIGO,
                                             "font": MONO, "space": 2}),
        ("The toolbar always names the open folder and how many sheets it holds; hover it to "
         "see where it looked first.", {"size": 10.5, "colour": INK, "space": 9}),
        (control("Project templates ▾"), {"size": 11, "bold": True, "colour": INDIGO,
                                          "space": 2}),
        ("Lists every one of those places with what it holds — even one not there yet, which "
         "becomes where " + control("New sheet...") + " writes — then "
         + control("Reload this folder from disk") + ", and " + control("Another folder...")
         + " for anywhere else.", {"size": 10.5, "colour": INK, "space": 9}),
        ("When the active ontology changes", {"size": 11, "bold": True, "colour": INDIGO,
                                              "space": 2}),
        ("Another file of the same project changes nothing. Another project opens its own "
         "templates — unless that would replace unsaved edits or a folder you chose; then the "
         "status line says where its templates are, and waits.",
         {"size": 10.5, "colour": INK}),
    ])
    shot = placeholder(
        s, n[0], 5.75, y, 3.7, 3.0,
        "the OntoBoard Sheets tab in OntoBoard " + F["version"] + " on a project whose sheets "
        "are in kg/templates: the toolbar reading Folder: " + F["folder_example"]
        + ", with " + control("Project templates ▾") + " open")
    notes(s, "Placeholder on this slide — " + shot)

    # 30 ------------------------------------------- what it finds in real data
    s = page()
    y = heading(s, "It builds, and it is still wrong", "checking data, not syntax")
    sheets, _, _, _, problems = F["matwerk"]
    text(s, M, y, 10 - 2 * M, 0.5,
         path("Check sheets...") + " reads the whole folder against the open ontology and lists, "
         "cell by cell, what is wrong — and changes nothing. The sheet editor marks the same "
         "problems where you type.", size=11, colour=INK)
    figures = [
        (F["marks"], "cells marked on the\n" + sheets + "-sheet MatWerk graph"),
        (problems, "problems when ROBOT\nbuilds that same graph"),
        (F["corrected"], "corrected in one undoable\nstep, the build byte-identical"),
    ]
    w = (10 - 2 * M - 0.3 * 2) / 3
    for i, (big, label) in enumerate(figures):
        x = M + i * (w + 0.3)
        card = rect(s, x, y + 0.68, w, 1.32, fill=LIGHT, line=FAINT,
                    shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.06
        text(s, x, y + 0.8, w, 0.56, big, size=28, colour=INDIGO, bold=True, align=PP_ALIGN.CENTER)
        text(s, x, y + 1.4, w, 0.54, label, size=10, colour=MUTED, align=PP_ALIGN.CENTER)
    text(s, M, y + 2.18, 10 - 2 * M, 0.24, "THE " + words(len(F["kinds"])).upper() + " KINDS IT MARKS",
         size=10, colour=ACCENT, bold=True)
    x, yy = M, y + 2.5
    for kind in F["kinds"]:
        width = (wrap(kind, 10, False, FONT, 9)[0][1] if can_measure() else 0.07 * len(kind)) + 0.3
        if x + width > 10 - M:
            x, yy = M, yy + 0.4
        chip = rect(s, x, yy, width, 0.3, fill=PAPER, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        chip.adjustments[0] = 0.4
        text(s, x, yy + 0.05, width, 0.2, kind, size=10, colour=INK, align=PP_ALIGN.CENTER)
        x += width + 0.12
    if yy + 0.3 > BOTTOM:
        raise StaleClaim("the kinds of marked mistake no longer fit their slide")

    # 31 --------------------------------------------- a row becomes an individual
    # The kicker was "and back again", which is the one thing that does not happen - see the next
    # slide. The HermiT claim that sat here ("does not finish at all") had no source left in the
    # repository, so it went; ELK's time over the graph is requirements-status.md's.
    s = page()
    y = heading(s, "From a row to an individual", "what the editor adds to a sheet")
    seconds, axioms = F["elk"]
    pts = [
        ("Create the thing you just referred to",
         "Right-click a name that points at nothing: the sheet, the type and the identifier are "
         "worked out from the sheets' own evidence — and it refuses rather than guessing when they "
         "do not say."),
        ("Record who added it, and when",
         control("Record who") + " adds dcterms:contributor, created and date columns and "
         "stamps the rows — in "
         "the sheet, which is the source the graph is built from."),
        ("Reason over what comes out",
         "Protégé's reasoners run over the built graph: whether it is consistent with the "
         "ontology, and what each thing turns out to be. ELK was measured at " + seconds
         + " s over a built graph of " + axioms + " axioms."),
    ]
    points(s, M, y + 0.04, 10 - 2 * M, pts, step=1.08, bar=0.88)

    # 32 ----------------------------------- what Template does, and what it does not
    # docs/knowledge-graph.md, "What Template... does, and what it does not", and the known
    # constraints in limitations.md. The deck said "and back again" for a release; it is not.
    s = page()
    y = heading(s, "What " + item("Template...") + " does, and does not",
                "no round trip, no live sync")
    pts = [
        ("It only adds",
         path("Template...") + " adds the axioms a sheet produces to the ontology open in "
         "Protégé, in memory until File → Save. It never removes or updates any: change a "
         "label, run it again, and the graph has both labels."),
        ("Nothing keeps the sheets and the graph in step",
         "No individual is linked back to its row. To rebuild, undo the earlier run "
         "(Edit → Undo, same session) or build into a fresh copy of the graph file."),
        ("Declare the identifiers' prefix in the graph file",
         "IDs such as kg:PB_0000001 build nothing until the graph declares kg: (Active ontology → "
         "Ontology Prefixes). " + item("Template...") + " names the missing prefix, and so does "
         + item("Check sheets...") + " once the graph holds the rows."),
        (item("SPARQL...") + " answers from what is asserted",
         "It queries the ontology and its imports, not the reasoner's conclusions — ask Protégé's "
         "DL Query, with a reasoner running."),
        (item("Build...") + " does not build the graph",
         "It builds the ontology, from the ontology's own window, and the Makefile OntoBoard "
         "writes has no target for the graph: " + item("Template...") + " is what builds it."),
    ]
    points(s, M, y, 10 - 2 * M, pts, step=0.76, bar=0.6, head_size=11)

    # 33 ------------------------------------- identifiers, and the file people have
    s = page()
    y = heading(s, "The two things that sent people back out", "identifiers, and Excel")
    text(s, M, y, 10 - 2 * M, 0.3,
         "Both were reasons to leave the editor, and both were where the data went wrong.",
         size=11, colour=INK)
    sources = F["iri_sources"]
    workbooks, cells = F["excel"]
    pts = [
        ("An identifier is not a thing to type",
         control("Generate IRIs...") + " fills every row that has none, " + words(len(sources))
         + " ways: "
         + "; ".join(src[0].lower() + src[1:] for src in sources) + "."),
        ("At the width its neighbours use",
         "A sheet of seven-digit identifiers gets MWO_0001226, not MWO_1226. The old rule "
         "dropped the padding, and the result sorted wrongly, matched no project's iddigits — "
         "and built perfectly happily, so nothing ever said so."),
        ("The workbook people actually have",
         control("Import Excel...") + " reads a real .xlsx — shared strings, styles, dates, "
         "formulas, rich text — and writes each tab into the folder as a .tsv; it does not "
         "write .xlsx back. Checked "
         "cell by cell against openpyxl on " + workbooks + " workbooks: " + cells + " cells."),
    ]
    points(s, M, y + 0.5, 10 - 2 * M, pts, step=1.12, bar=0.9)

    # 34 ------------------------------------------ the row that decides everything
    # The third point used to cite a template "committed in this repo" with thirteen bare A
    # columns. No such template is in the repository any more, so the claim could not be checked
    # and went; the A rdfs:label@en defect is in fixed.md with its measurement.
    s = page()
    y = heading(s, "The row that decides what gets built", "and why it is checked as you type")
    text(s, M, y, 10 - 2 * M, 0.5,
         "Row 2 of a ROBOT template says what each column does. Until " + F["row_since"]
         + " it was painted into the column header and could not be edited here — so the one row "
         "that controls the build was the one row you had to leave Protégé to change.",
         size=11, colour=INK)
    pts = [
        ("A spec ROBOT does not know costs the whole sheet",
         "Not the column — the sheet: Template.addTable throws and nothing at all is built. So "
         "every edit is checked against a transcription of robot-core's own "
         "validateTemplateString, and a test asks robot-core about the same "
         + str(F["spec_corpus"]) + " strings and requires the same answer."),
        ("Red breaks it, amber builds and does nothing",
         "\u201cII\u201d produces no axiom and no error. A \u201c>\u201d in front of a class column is "
         "accepted and discarded. An axiom annotation that follows nothing annotates nothing. All "
         "legal, all silent, all marked."),
        ("\u201cA rdfs:label@en\u201d gives no name at all",
         "It looks like an English label and makes a property called rdfs:label@en; "
         "\u201cAL rdfs:label@en\u201d is the spelling that works, and " + item("Template...")
         + " says so. In the MatWerk graph it had hidden the names in " + F["label_cells"]
         + " filled cells."),
    ]
    points(s, M, y + 0.74, 10 - 2 * M, pts, step=1.08, bar=0.88)

    # 35 --------------------------------------------- section: the patterns
    section("PART 6", "The pattern library",
            str(F["patterns"]) + " ontology design patterns, ranked against the ontology you have "
            "open.")

    # 36 --------------------------------------------------- the collections
    # Five collections since the BFO four arrived in 1.96.0; the slide had four cards. Drawn as
    # bars now, because the point it makes - one collection outnumbers the rest - is a length.
    s = page()
    order = [("mwo", "MWO", "ise-fizkarlsruhe.github.io/mwo"),
             ("nfdi", "NFDI MatWerk", "nfdi.fiz-karlsruhe.de/matwerk"),
             ("pmdco", "PMDco", "materialdigital.github.io/core-ontology"),
             ("bfo", "BFO 2020", "purl.obolibrary.org/obo/bfo"),
             ("odp", "ODP portal", "ontologydesignpatterns.org")]
    unknown = set(F["collections"]) - {key for key, _, _ in order}
    if unknown:
        raise StaleClaim("the pattern index has collections the slide does not draw: %s"
                         % ", ".join(sorted(unknown)))
    y = heading(s, words(len(order)).capitalize() + " collections, one library",
                "separated by source")
    biggest = max(F["collections"].values())
    for i, (key, label, source) in enumerate(order):
        yy = y + i * 0.38
        count = F["collections"].get(key, 0)
        text(s, M, yy + 0.04, 1.45, 0.22, label, size=10.5, colour=INK, bold=True)
        length = max(0.05, 3.6 * count / float(biggest))
        rect(s, M + 1.55, yy + 0.03, length, 0.24, fill=INDIGO if key != "odp" else MUTED)
        text(s, M + 1.55 + length + 0.08, yy + 0.04, 0.5, 0.22, str(count), size=10.5,
             colour=INDIGO, bold=True)
        text(s, 6.35, yy + 0.05, 10 - M - 6.35, 0.22, source, size=10, colour=MUTED)
    family = F["bfo_family"]
    text(s, M, y + len(order) * 0.38 + 0.18, 10 - 2 * M, 1.75, [
        ("The " + str(family) + " patterns from MWO, NFDI MatWerk and PMDco were added because "
         "the ODP collection and the OBO world share no vocabulary at all.",
         {"size": 11.5, "bold": True, "colour": INK, "space": 7}),
        ("Measured: zero of the " + str(F["collections"].get("odp", 0)) + " ODP patterns share "
         "a single IRI with a real BFO-based project. The BFO patterns come from the BFO 2020 "
         "that NFDIcore pins and MWO agrees with.", {"size": 10.5, "colour": MUTED, "space": 7}),
        (str(F["patterns"]) + " entries, " + str(F["distinct"]) + " distinct — "
         + words(F["patterns"] - F["distinct"]) + " are another pattern under a second name, and "
         "the library says so.", {"size": 10.5, "colour": ACCENT, "bold": True}),
    ])

    # 37 ------------------------------------------------------ one pattern
    s = page()
    y = heading(s, "What a pattern contains",
                "mwo " + F["pattern_names"].get(FIG_PATTERN, FIG_PATTERN))
    fig = os.path.join(DECK, "fig-pattern-process-agent-role.png")
    fig_h = FIG_W * figure["size"][1] / float(figure["size"][0])
    if y - 0.02 + fig_h > BOTTOM:
        raise StaleClaim("the pattern figure is %.2fin tall and runs into the footer" % fig_h)
    picture(s, fig, M, y - 0.02, w=FIG_W)
    low, high = F["bfo_family_classes"]
    terms, star = F["star"]
    text(s, 7.25, y, 10 - M - 7.25, 3.85, [
        ("Read from the file", {"size": 11, "bold": True, "colour": INDIGO, "space": 5}),
        ("All " + words(figure["classes"]) + " classes it declares are boxes, every subClassOf it "
         "asserts is a solid line, and the dashed line is its "
         + ("one existential restriction." if figure["restrictions"] == 1 else
            words(figure["restrictions"]) + " existential restrictions.") + " Pink: the terms it "
         "adds to BFO.", {"size": 10, "colour": MUTED, "space": 7}),
        ("Not a logical module", {"size": 11, "bold": True, "colour": INDIGO, "space": 5}),
        ("A ROBOT STAR module of a PMDco pattern with " + terms + " terms came out with " + star
         + " classes, because a BFO ontology connects everything to its spine. These are induced "
         "sub-ontologies instead: " + str(low) + " to " + str(high) + " classes each.",
         {"size": 10, "colour": MUTED}),
    ])

    # 38 -------------------------------------------------- recommendations
    s = page()
    y = heading(s, "Suggested for the ontology you have open", "and why")
    text(s, M, y, 10 - 2 * M, 0.5,
         str(F["patterns"]) + " patterns is too many to browse when you want one, so "
         + path("Pattern library...") + " opens on Suggested for this ontology: ranked against "
         "your own vocabulary.", size=11, colour=INK)
    quote = rect(s, M, y + 0.66, 10 - 2 * M, 0.8, fill=LIGHT, line=None,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    quote.adjustments[0] = 0.09
    # As tall as the card and centred in it: a top-anchored box with no padding sat the one line
    # high, with twice the space below it as above.
    said = text(s, M + 0.25, y + 0.66, 10 - 2 * M - 0.5, 0.8, "“" + F["quote"] + "”",
                size=13, colour=INDIGO, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    said.text_frame.auto_size = MSO_AUTO_SIZE.NONE
    bullets(s, M, y + 1.7, 10 - 2 * M, [
        ("The reason is the point", "A ranked list with no evidence is a magic box: 0.75 tells "
                                    "you nothing, and the three terms it matched do."),
        ("Two signals, both measured", "Shared IRIs count double, shared names count once — "
                                       "scored by how much of the pattern you already have."),
        ("Your labels, not ours", "Where your ontology has a label for a matched term, that label "
                                  "is shown, because BFO_0000004 tells nobody anything."),
    ], size=10.5, gap=0.66)

    # 39 ------------------------------------------- section: collaboration
    section("PART 7", "Working with other people",
            "A live session if your group runs a server, plain git if it does not.")

    # 40 ------------------------------------------------------ collaboration
    # "two Node files in the repository" was three (collab/: server, bridge, token minting);
    # what it carries is limitations.md's, not a count of axiom types.
    s = page()
    y = heading(s, "Two ways to work together", "collaboration")
    col = (10 - 2 * M - 0.32) / 2
    a = rect(s, M, y, col, 2.45, fill=PAPER, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    a.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.2, col - 0.44, 0.24, "GIT MODE", size=10, colour=MUTED, bold=True)
    text(s, M + 0.22, y + 0.55, col - 0.44, 1.75, [
        ("Leave the collaboration fields empty and work as you already do: "
         + path("Git...", full=False) + " commits, pulls and pushes, and "
         + path("Pull request...", full=False) + " proposes your branch.",
         {"size": 10, "colour": INK, "space": 8}),
        ("You will not see other people's cursors, and nothing else changes. It is the default, "
         "and a choice rather than a failure — the dialog says so.",
         {"size": 10, "colour": INK}),
    ])
    b = rect(s, M + col + 0.32, y, col, 2.45, fill=LIGHT, line=INDIGO,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    b.adjustments[0] = 0.04
    text(s, M + col + 0.54, y + 0.2, col - 0.44, 0.24, "LIVE MODE", size=10, colour=INDIGO,
         bold=True)
    text(s, M + col + 0.54, y + 0.55, col - 0.44, 1.75, [
        ("Your group runs the server — a small Node service in the repository's collab/ folder — "
         "and each person gets a token. " + path("Collaboration...", full=False)
         + " connects: cursors, selections and edits as they happen.",
         {"size": 10, "colour": INK, "space": 8}),
        ("It carries " + words(F["collab_kinds"]) + " kinds of axiom — declarations, SubClassOf, "
         "class assertions and annotation assertions. Anything else is counted and shown in the "
         "status bar, never dropped in silence.", {"size": 10, "colour": INK}),
    ])

    # 41 -------------------------------------------------------- the verdict
    # From limitations.md as it stands: the old right-hand column said lists in the YAML needed a
    # text editor (Add to a list... since 1.97.0), that live mode carried 96 of 141 axiom kinds
    # (it carries four) and that there was no pull-request surface (Pull request... since 1.101.0).
    s = page()
    y = heading(s, "An honest verdict", "what it does, and what it does not")
    first, second = F["drawn"]
    cols = [("Strongest", GREEN, [
                "ROBOT's report, reasoning and explanations, in process",
                "A project that builds and releases with nothing installed",
                "Every logical axiom drawn — " + first + " of " + first + " and " + second + " of "
                + second + " in two published ontologies",
                "Patterns and other ontologies' terms, reused the ODK way",
             ]),
            ("Known constraints", ACCENT, [
                "Sheets and graph do not round-trip: " + item("Template...") + " only adds",
                item("SPARQL...") + " answers from asserted axioms, not inferences",
                "Live editing carries " + words(F["collab_kinds"]) + " kinds of axiom",
                "Protégé 6 is not supported",
             ]),
            ("Not built yet", INDIGO, [
                "One check for a loose .owl file — today Build needs a project",
                "ODK's base, full and simple release files",
                "Reviewing a pull request — still the browser's job",
                "Acting on most ODK YAML keys — they are kept, not obeyed",
             ])]
    cw = (10 - 2 * M - 0.3 * 2) / 3
    for i, (head, colour, items_) in enumerate(cols):
        x = M + i * (cw + 0.3)
        text(s, x, y, cw, 0.22, head.upper(), size=10, colour=colour, bold=True)
        # 0.028in is two points. Pt(2)/914400 would have been the same number; the earlier
        # `* 72` on top of it made a 2-INCH filled block, which is how a rule became a slab of
        # solid green across half the slide. Caught by rendering the deck and looking at it.
        rect(s, x, y + 0.28, cw, 0.028, fill=colour)
        for j, it in enumerate(items_):
            text(s, x, y + 0.42 + j * 0.62, cw, 0.56, "•  " + it, size=10, colour=INK)
    text(s, M, y + 3.0, 10 - 2 * M, 0.6,
         "Every gap is written down and measured, with the defects found along the way — see "
         "the Limitations page. A tool that hides what it cannot do is a tool you cannot plan "
         "around.", size=10, colour=MUTED)

    # 42 -------------------------------------------------------- resources
    s = page()
    y = heading(s, "Where everything lives", "resources")
    links = [
        ("Documentation", "ebrahimnorouzi.github.io/ontoboard"),
        ("Start here: the guide", "ebrahimnorouzi.github.io/ontoboard/guide/"),
        ("Working with ODK, step by step", "ebrahimnorouzi.github.io/ontoboard/odk-workflow/"),
        ("From spreadsheets to a knowledge graph",
         "ebrahimnorouzi.github.io/ontoboard/knowledge-graph/"),
        ("Limitations, measured", "ebrahimnorouzi.github.io/ontoboard/limitations/"),
        ("Pattern library on the web", "ebrahimnorouzi.github.io/ontoboard/patterns/"),
        ("Download the latest jar", "github.com/ebrahimnorouzi/ontoboard/releases/latest"),
        ("Source and issues", "github.com/ebrahimnorouzi/ontoboard"),
    ]
    for page_ in ("guide.md", "odk-workflow.md", "knowledge-graph.md", "limitations.md"):
        if not os.path.isfile(os.path.join(ROOT, "docs", page_)):
            raise StaleClaim("docs/%s is gone; the resources slide links it" % page_)
    for i, (head, url) in enumerate(links):
        yy = y + i * 0.46
        rect(s, M, yy + 0.09, 0.07, 0.07, fill=ACCENT, shape=MSO_SHAPE.OVAL)
        text(s, M + 0.24, yy, 3.5, 0.24, head, size=11, colour=INK, bold=True)
        text(s, M + 3.75, yy + 0.02, 10 - 2 * M - 3.75, 0.22, url, size=10, colour=INDIGO,
             font=MONO)

    # 43 ----------------------------------------------------------- closing
    s = blank(prs)
    dark_background(s, DEEPER)
    band = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.11), SLIDE_H)
    band.shadow.inherit = False
    band.fill.solid()
    band.fill.fore_color.rgb = ACCENT
    band.line.fill.background()
    picture(s, os.path.join(DECK, "logo-white.png"), M + 0.05, 1.15, h=0.72)
    text(s, M + 0.05, 2.05, 8.6, 0.8, "Thank you", size=38, colour=WHITE, bold=True)
    text(s, M + 0.05, 2.95, 8.0, 0.4,
         "ebrahimnorouzi.github.io/ontoboard", size=13, colour=RGBColor(0xB9, 0xC2, 0xE8),
         font=MONO)
    text(s, M + 0.05, 3.75, 8.0, 0.7, [
        (AUTHOR, {"size": 13, "bold": True, "colour": WHITE, "space": 2}),
        (INSTITUTE, {"size": 10, "colour": RGBColor(0x9F, 0xAA, 0xD4)}),
    ])
    n[0] += 1

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    prs.save(OUT)
    return prs


if __name__ == "__main__":
    # The placeholders and problems carry arrows and accents, and a Windows console's cp1252 has no
    # arrow: printing the list of pictures still owed must not be what fails the build.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    prs = main()
    print("wrote %s" % OUT)
    print("  %d slides, %.2f x %.2f in, plugin %s" % (len(prs.slides), prs.slide_width / EMU_IN,
                                                      prs.slide_height / EMU_IN, F["version"]))
    for at, what in PLACEHOLDERS:
        print("  slide %d: %s" % (at, what))
    found = check_layout(prs)
    if found:
        print("\nThe deck was written, and it is not right:")
        for problem in found:
            print("  " + problem)
        sys.exit(1)
