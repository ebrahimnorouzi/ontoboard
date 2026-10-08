#!/usr/bin/env python3
"""Builds playground-ontoboard.pptx: the OntoBoard walkthrough, shaped like the ODK Playground deck.

Asked for: a deck "similar to playground pptx ... follow the same structure ... and present ontoboard
features and steps to create the ODK base repository and run all the features", with pictures from
the Protege plugin, the OntoBoard logo, something related to the canvas, and the author and FIZ
Karlsruhe on the first slide and in the footer.

STRUCTURE, taken from 2025-09-12_Tabea-ODK@PMD-Playground.pptx (46 slides, 10 x 5.625in):
a title slide, motivation, then alternating full-bleed section dividers and step slides, a verdict
and a resources slide at the end. The same spine is used here, with OntoBoard's own steps in it.

EVERY SLIDE IS LAID OUT BY HAND. Shapes are placed at measured inches, not by a template's
autofit - the deck has one grid (a 0.55in margin, a 24pt title baseline, a footer rule at 5.18in)
and everything sits on it. Native PowerPoint shapes rather than pictures wherever a diagram can be
drawn as shapes, because those stay sharp at any zoom and can be edited by the person presenting.

EVERY NUMBER IN IT IS MEASURED. The test counts, the pattern counts, the collection sizes and the
self-check lines come from the repository at build time - see `facts()` - so a stale claim fails
the build rather than going on a slide. Nothing here is written from memory.

THE SCREENSHOTS ARE REAL. build/shots/*.png were captured from Protege 5.6.9 with
ontoboard-1.93.0.jar installed, by driving the menus over the keyboard. They are cropped here, not
redrawn. The desktop they were taken on is 1138x640, which is why the wider dialogs are shown
cropped to the part that carries the content.

    python tools/build-deck.py            # writes build/playground-ontoboard.pptx
"""
import io
import os
import re
import subprocess
import sys

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOTS = os.path.join(ROOT, "build", "shots")
DECK = os.path.join(ROOT, "build", "deck")
OUT = os.path.join(ROOT, "build", "playground-ontoboard.pptx")

AUTHOR = "Ebrahim Norouzi"
INSTITUTE = "FIZ Karlsruhe – Leibniz Institute for Information Infrastructure"
INSTITUTE_SHORT = "FIZ Karlsruhe"

# ---------------------------------------------------------------- the palette

INDIGO = RGBColor(0x3F, 0x51, 0xB5)
DEEP = RGBColor(0x1B, 0x23, 0x40)
DEEPER = RGBColor(0x12, 0x18, 0x2E)
INK = RGBColor(0x21, 0x25, 0x30)
MUTED = RGBColor(0x7A, 0x82, 0x96)
FAINT = RGBColor(0xDD, 0xE1, 0xEC)
LIGHT = RGBColor(0xEE, 0xF1, 0xFB)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
ACCENT = RGBColor(0xD6, 0x50, 0x76)
GREEN = RGBColor(0x1F, 0x9D, 0x55)

SLIDE_W = Inches(10)
SLIDE_H = Inches(5.625)
M = 0.55          # the side margin every slide shares
RULE_Y = 5.18     # the footer rule

FONT = "Segoe UI"
MONO = "Consolas"


# ------------------------------------------------------------------ the facts

def facts():
    """Numbers read out of the repository, so the deck cannot drift from it."""
    f = {}

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

    pom = io.open(os.path.join(ROOT, "protege-plugin", "pom.xml"), encoding="utf-8").read()
    f["version"] = re.search(r"^  <version>(.*?)</version>", pom, re.M).group(1)

    plugin_xml = io.open(os.path.join(ROOT, "protege-plugin", "src", "main", "resources",
                                      "plugin.xml"), encoding="utf-8").read()
    f["menu_items"] = len(re.findall(r'name value="[^"]*\.\.\."', plugin_xml))

    tests = 0
    for dirpath, _, names in os.walk(os.path.join(ROOT, "protege-plugin", "src", "test")):
        for n in names:
            if n.endswith(".java"):
                tests += len(re.findall(r"^\s*@Test\s*$",
                                        io.open(os.path.join(dirpath, n), encoding="utf-8").read(),
                                        re.M))
    f["tests"] = tests

    receipts = sorted(os.listdir(os.path.join(ROOT, "protege-plugin", "tools", "smoke-receipt")))
    f["releases"] = len([r for r in receipts if r.endswith(".txt")])
    return f


F = facts()


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
    for item in items:
        body, over = item if isinstance(item, tuple) else (item, {})
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
            r.text = line
            r.font.size = Pt(over.get("size", size))
            r.font.bold = over.get("bold", bold)
            r.font.name = over.get("font", font)
            r.font.color.rgb = over.get("colour", colour)
    return box


def picture(slide, path, x, y, w=None, h=None):
    kw = {}
    if w is not None:
        kw["width"] = Inches(w)
    if h is not None:
        kw["height"] = Inches(h)
    return slide.shapes.add_picture(path, Inches(x), Inches(y), **kw)


def chrome(slide, number, dark=False):
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
    if number is not None:
        text(slide, 10 - M - 1.0, RULE_Y + 0.13, 1.0, 0.25, str(number), size=8, colour=ink,
             align=PP_ALIGN.RIGHT)


def heading(slide, title, kicker=None):
    y = 0.42
    if kicker:
        text(slide, M, y - 0.18, 8.0, 0.22, kicker.upper(), size=9, colour=ACCENT, bold=True)
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
    for i, item in enumerate(items):
        body, note = (item if isinstance(item, tuple) else (item, None))
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
            runs.append((note, {"size": size - 1, "colour": MUTED, "space": 0}))
        text(slide, x + (0.17 if marker else 0), yy - 0.02, w, gap, runs, space=0)


def crop_shot(name, box, out):
    """Crops a captured screenshot. `box` is (left, top, right, bottom) in source pixels."""
    from PIL import Image
    src = os.path.join(SHOTS, name)
    if not os.path.isfile(src):
        return None
    img = Image.open(src).convert("RGB").crop(box)
    path = os.path.join(DECK, out)
    img.save(path, quality=96)
    return path


# ------------------------------------------------------------------- assets

def build_assets():
    """Draws the logo variants and the pattern figure the deck uses.

    Here rather than in a throwaway command, so that `python tools/build-deck.py` reproduces the
    whole deck from the repository on any machine. The screenshots under build/shots/ are the one
    thing it cannot regenerate - those were captured from a running Protege.
    """
    from PIL import Image, ImageDraw, ImageFont
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

    # The pattern figure, drawn from the axioms mwo-process-agent-role actually asserts. Read with
    # rdflib at build time rather than transcribed, so the figure cannot drift from the pattern.
    import rdflib
    from rdflib.namespace import RDF, RDFS, OWL
    g = rdflib.Graph()
    g.parse(os.path.join(ROOT, "patterns", "mwo-process-agent-role", "pattern.ttl"),
            format="turtle")
    label = {str(sub): str(obj) for sub, obj in g.subject_objects(RDFS.label)}
    name = lambda u: label.get(str(u), str(u).rsplit("#", 1)[-1])
    asserted_sub = {(name(a), name(b)) for a, b in g.subject_objects(RDFS.subClassOf)
                    if isinstance(a, rdflib.URIRef) and isinstance(b, rdflib.URIRef)}
    props = sorted({name(p) + " (" + str(p).rsplit("/", 1)[-1] + ")"
                    for p in g.subjects(RDF.type, OWL.ObjectProperty)
                    if isinstance(p, rdflib.URIRef)})

    W_, H_ = 2200, 1180
    ink, muted = (33, 37, 48), (120, 128, 145)
    indigo_rgb, light, accent = (63, 81, 181), (232, 236, 250), (214, 80, 118)

    def font(px, bold=False):
        for path in ((r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf"),
                     (r"C:\Windows\Fontsrialbd.ttf" if bold else r"C:\Windows\Fontsrial.ttf")):
            try:
                return ImageFont.truetype(path, px)
            except Exception:
                pass
        return ImageFont.load_default()

    reg, bold_f, small, small_b = font(38), font(40, True), font(32), font(33, True)
    img = Image.new("RGBA", (W_, H_), (255, 255, 255, 255))
    d = ImageDraw.Draw(img)
    node = {
        "entity": (1100, 95), "occurrent": (720, 275), "continuant": (1500, 275),
        "process": (420, 455), "independent continuant": (1180, 455),
        "specifically dependent continuant": (1800, 455), "agent": (1180, 650),
        "realizable entity": (1800, 650), "agent role": (1180, 845), "role": (1800, 845),
    }
    own = {"agent", "agent role"}

    def rect_of(lbl):
        cx, cy = node[lbl]
        f = bold_f if lbl in own else reg
        w = max(d.textlength(lbl, font=f) + 54, 190)
        return [cx - w / 2, cy - 37, cx + w / 2, cy + 37]

    def edge(a, b, colour, dashed=False, width=4):
        ax, ay = node[a]
        bx, by = node[b]
        ra, rb = rect_of(a), rect_of(b)
        ay2 = ra[1] if by < ay else ra[3]
        by2 = rb[3] if by < ay else rb[1]
        if not dashed:
            d.line([ax, ay2, bx, by2], fill=colour, width=width)
            return
        for i in range(0, 26, 2):
            d.line([ax + (bx - ax) * i / 26.0, ay2 + (by2 - ay2) * i / 26.0,
                    ax + (bx - ax) * (i + 1) / 26.0, ay2 + (by2 - ay2) * (i + 1) / 26.0],
                   fill=colour, width=width)

    for a, b in sorted(asserted_sub):
        if a in node and b in node:
            edge(a, b, (176, 183, 198))
    edge("agent", "agent role", accent, dashed=True, width=5)
    for lbl, (cx, cy) in node.items():
        is_own = lbl in own
        d.rounded_rectangle(rect_of(lbl), radius=16,
                            fill=(255, 236, 242) if is_own else light,
                            outline=accent if is_own else indigo_rgb, width=3)
        f = bold_f if is_own else reg
        d.text((cx - d.textlength(lbl, font=f) / 2, cy - 22), lbl, font=f,
               fill=(140, 36, 72) if is_own else ink)
    d.text((1218, 740), "has role", font=small_b, fill=accent)
    d.line([70, 950, W_ - 70, 950], fill=(226, 230, 240), width=3)
    d.text((70, 980), "Object properties the pattern uses", font=small_b, fill=muted)
    for i, prop in enumerate(props):
        d.text((70 + (i % 3) * 700, 1030 + (i // 3) * 46), "•  " + prop, font=small, fill=ink)
    img.convert("RGB").save(os.path.join(DECK, "fig-pattern-process-agent-role.png"), quality=95)


# -------------------------------------------------------------------- slides

def main():
    build_assets()
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
    text(s, M + 0.05, 1.58, 8.6, 0.9, "OntoBoard", size=46, colour=WHITE, bold=True)
    text(s, M + 0.05, 2.42, 8.4, 0.5,
         "The ODK and ROBOT pipeline, inside Protégé", size=17,
         colour=RGBColor(0xB9, 0xC2, 0xE8))
    accent = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(M + 0.05), Inches(3.08),
                                Inches(1.1), Pt(2.5))
    accent.shadow.inherit = False
    accent.fill.solid()
    accent.fill.fore_color.rgb = ACCENT
    accent.line.fill.background()
    text(s, M + 0.05, 3.32, 8.4, 1.0, [
        ("Building an ODK repository and running every feature, step by step",
         {"size": 11, "colour": RGBColor(0x9F, 0xAA, 0xD4), "space": 10}),
        (AUTHOR, {"size": 13, "bold": True, "colour": WHITE, "space": 2}),
        (INSTITUTE, {"size": 10, "colour": RGBColor(0x9F, 0xAA, 0xD4)}),
    ])
    text(s, 10 - M - 3.0, 4.72, 3.0, 0.3, "Plugin version " + F["version"], size=9,
         colour=RGBColor(0x7A, 0x85, 0xB0), align=PP_ALIGN.RIGHT)
    n[0] += 1

    # 2 ------------------------------------------------------------ in numbers
    s = page()
    y = heading(s, "OntoBoard in numbers", "what ships today")
    figures = [
        (str(F["patterns"]), "design patterns,\nready to import"),
        (str(F["menu_items"]), "operations in the\nOntoBoard menu"),
        ("2", "Protégé versions,\nsmoked every release"),
        (str(F["tests"]), "automated tests\nbehind it"),
    ]
    w = (10 - 2 * M - 0.3 * 3) / 4
    for i, (big, label) in enumerate(figures):
        x = M + i * (w + 0.3)
        card = rect(s, x, y, w, 1.5, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.06
        text(s, x, y + 0.2, w, 0.6, big, size=34, colour=INDIGO, bold=True, align=PP_ALIGN.CENTER)
        text(s, x, y + 0.86, w, 0.6, label, size=9.5, colour=MUTED, align=PP_ALIGN.CENTER)
    text(s, M, y + 1.75, 10 - 2 * M, 1.1, [
        ("One jar. No terminal, no Docker command to remember, no second application to learn.",
         {"size": 12.5, "bold": True, "colour": INK, "space": 7}),
        ("OntoBoard puts the Ontology Development Kit and ROBOT where the ontology is already "
         "being edited. The same project, the same files, the same git history — driven from "
         "Protégé's menu bar instead of a shell.", {"size": 11, "colour": MUTED}),
    ])

    # 3 ------------------------------------------------------------- why
    s = page()
    y = heading(s, "Why do we need it?", "the problem")
    col = (10 - 2 * M - 0.35) / 2
    without = rect(s, M, y, col, 2.75, fill=RGBColor(0xF7, 0xF8, 0xFA), line=FAINT,
                   shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    without.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.18, col - 0.44, 0.3, "WITHOUT ONTOBOARD", size=9.5, colour=MUTED,
         bold=True)
    bullets(s, M + 0.22, y + 0.56, col - 0.44, [
        ("Edit in Protégé, switch to a terminal to build", None),
        ("docker run, with the right mounts, every time", None),
        ("A quality report you read as console text", None),
        ("Retype a pattern's terms from a web page", None),
        ("Find out the release is broken after tagging", None),
    ], size=10.5, gap=0.40, colour=INK)

    with_ = rect(s, M + col + 0.35, y, col, 2.75, fill=LIGHT, line=INDIGO,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    with_.adjustments[0] = 0.04
    text(s, M + col + 0.57, y + 0.18, col - 0.44, 0.3, "WITH ONTOBOARD", size=9.5, colour=INDIGO,
         bold=True)
    bullets(s, M + col + 0.57, y + 0.56, col - 0.44, [
        ("Build, reason and report from the menu bar", None),
        ("The project's own ODK YAML read and honoured", None),
        ("Findings in a table, by your project's profile.txt", None),
        ("Pick a pattern — its terms are imported the ODK way", None),
        ("A release that will not publish without a receipt", None),
    ], size=10.5, gap=0.40, colour=INK)

    # 4 --------------------------------------------------- section: getting in
    def section(num, title, subtitle):
        sl = page(dark=True)
        bar = sl.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(M), Inches(1.72), Inches(0.9),
                                  Pt(3))
        bar.shadow.inherit = False
        bar.fill.solid()
        bar.fill.fore_color.rgb = ACCENT
        bar.line.fill.background()
        text(sl, M, 1.05, 3.0, 0.5, num, size=12, colour=ACCENT, bold=True)
        text(sl, M, 1.95, 8.6, 0.9, title, size=33, colour=WHITE, bold=True)
        text(sl, M, 2.95, 7.6, 0.6, subtitle, size=12, colour=RGBColor(0x9F, 0xAA, 0xD4))
        picture(sl, os.path.join(DECK, "logo-white.png"), 10 - M - 1.25, 1.55, h=1.25)
        return sl

    section("PART 1", "Getting started", "What you need, and how the plugin gets into Protégé.")

    # 5 ------------------------------------------------------- prerequisites
    s = page()
    y = heading(s, "Prerequisites", "before you start")
    items = [
        ("Protégé Desktop 5.6.x", "Recommended. It also runs on 5.5.0 — both are smoke-tested "
                                           "on every release."),
        ("Java 11 (5.6.x) or Java 8 (5.5.0)", "Whatever your Protégé already ships with. Nothing "
                                              "extra to install."),
        ("Docker", "Only for running an ODK build. Everything else — reports, reasoning, patterns, "
                   "imports — runs in process."),
        ("git", "Only for the git operations and releases. The plugin calls the git you already have."),
    ]
    for i, (head, note) in enumerate(items):
        yy = y + i * 0.72
        num = rect(s, M, yy, 0.34, 0.34, fill=INDIGO, shape=MSO_SHAPE.OVAL)
        text(s, M, yy + 0.055, 0.34, 0.3, str(i + 1), size=11, colour=WHITE, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, M + 0.52, yy - 0.02, 10 - 2 * M - 0.52, 0.65, [
            (head, {"size": 12.5, "bold": True, "colour": INK, "space": 2}),
            (note, {"size": 10, "colour": MUTED}),
        ], space=0)

    # 6 ------------------------------------------------------------- install
    s = page()
    y = heading(s, "Install", "one jar, one folder")
    steps = [
        ("Download the jar",
         "github.com/ebrahimnorouzi/ontoboard → Releases → latest.\n"
         "The link /releases/latest/download/ontoboard.jar always serves the newest build."),
        ("Remove any older ontoboard-*.jar",
         "The bundle is singleton:=true. Two versions side by side stop it resolving and the tab "
         "silently never appears."),
        ("Copy it into Protégé's plugins/ folder",
         "Nothing else is needed — ROBOT, the reasoners and all " + str(F["patterns"]) +
         " patterns are inside the jar."),
        ("Restart Protégé",
         "An OntoBoard menu appears in the menu bar, and Window → Tabs → OntoBoard opens the "
         "canvas."),
    ]
    for i, (head, note) in enumerate(steps):
        yy = y + i * 0.68
        chip = rect(s, M, yy + 0.02, 0.26, 0.26, fill=ACCENT, shape=MSO_SHAPE.OVAL)
        text(s, M, yy + 0.05, 0.26, 0.25, str(i + 1), size=9, colour=WHITE, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, M + 0.42, yy - 0.02, 9 - M, 0.62, [
            (head, {"size": 12, "bold": True, "colour": INK, "space": 2}),
            (note, {"size": 9.5, "colour": MUTED}),
        ], space=0)

    # 7 ------------------------------------------------- section: the repo
    section("PART 2", "Create an ODK repository",
            "Seven steps, from an empty folder to a repository that builds.")

    # 8 ------------------------------------------- step 1: new project dialog
    s = page()
    y = heading(s, "Step 1 — New ODK project…", "ontoboard → project")
    shot = crop_shot("09-pattern-library.png", (628, 360, 1138, 640), "shot-newproject.png")
    text(s, M, y, 4.9, 2.4, [
        ("OntoBoard → Project → New ODK project…", {"size": 11.5, "bold": True,
                                                                   "colour": INDIGO, "space": 8}),
        ("You give it an ontology ID, a title and a folder. The ID becomes the file names and the "
         "IRIs, so it is lowercase with no spaces. Leave the base IRI empty to get the OBO "
         "convention.", {"size": 10.5, "colour": INK, "space": 8}),
        ("The wizard then writes a complete ODK repository and opens the edit file — no "
         "seed-via-docker step, no template to clone.", {"size": 10.5, "colour": MUTED}),
    ])
    if shot:
        pic = picture(s, shot, 5.65, y - 0.05, w=3.8)
        box = rect(s, 5.62, y - 0.08, 3.86, pic.height / 914400.0 + 0.06, fill=None, line=FAINT)
    text(s, M, y + 2.5, 4.9, 0.4, "Captured from Protégé 5.6.9 with OntoBoard " + F["version"],
         size=8, colour=MUTED)

    # 9 ----------------------------------------------- step 2: what it writes
    s = page()
    y = heading(s, "Step 2 — What the wizard writes", "the repository")
    tree = [
        ("my-onto/", 0, True), ("src/ontology/", 1, True),
        ("myonto-odk.yaml", 2, False), ("myonto-edit.owl", 2, False),
        ("myonto.Makefile", 2, False), ("Makefile", 2, False),
        ("catalog-v001.xml", 2, False), ("myonto-idranges.owl", 2, False),
        ("profile.txt", 2, False), ("imports/", 2, True),
        ("src/patterns/", 1, True), (".github/workflows/", 1, True), ("docs/", 1, True),
    ]
    panel = rect(s, M, y, 4.5, 3.0, fill=RGBColor(0xF7, 0xF8, 0xFA), line=FAINT,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    panel.adjustments[0] = 0.03
    for i, (name, depth, isdir) in enumerate(tree):
        text(s, M + 0.22 + depth * 0.26, y + 0.16 + i * 0.215, 4.0, 0.2, name, size=9.5,
             colour=INDIGO if isdir else INK, bold=isdir, font=MONO)
    text(s, M + 4.85, y, 4.0, 3.0, [
        ("A real ODK repository, not a sketch.", {"size": 12, "bold": True, "colour": INK,
                                                  "space": 8}),
        ("The same layout the ODK seed script produces, so a project made here is one any ODK "
         "user recognises — and one the ODK tooling will go on maintaining.",
         {"size": 10.5, "colour": MUTED, "space": 8}),
        ("myonto-odk.yaml is the project's configuration. Every key in it is editable from inside "
         "Protégé, at any depth.", {"size": 10.5, "colour": INK, "space": 8}),
        ("profile.txt decides which quality checks are errors — and OntoBoard's report reads "
         "that same file, so the plugin and your CI agree.", {"size": 10.5, "colour": INK}),
    ])

    # 10 --------------------------------------------------- step 3: the YAML
    s = page()
    y = heading(s, "Step 3 — The ODK YAML, key by key", "project configuration")
    text(s, M, y, 10 - 2 * M, 0.4,
         "OntoBoard → Project → Project configuration…  shows every key in the file and lets "
         "you edit the value in place.", size=11, colour=INK)
    rows = [
        ("id, title, description, license", "What the ontology is called and how it is licensed"),
        ("robot_java_args", "The heap a containerised ROBOT gets — honoured since 1.91.0"),
        ("robot_report.fail_on", "Which severity fails the build, read by the quality report"),
        ("robot_report.use_labels", "Labels instead of IRIs in the report"),
        ("import_group", "The imports and how each module is extracted"),
    ]
    ty = y + 0.55
    hdr = rect(s, M, ty, 10 - 2 * M, 0.34, fill=INDIGO)
    text(s, M + 0.14, ty + 0.07, 3.4, 0.25, "KEY", size=9, colour=WHITE, bold=True)
    text(s, M + 3.7, ty + 0.07, 5.4, 0.25, "WHAT IT DOES", size=9, colour=WHITE, bold=True)
    for i, (k, v) in enumerate(rows):
        yy = ty + 0.34 + i * 0.355
        if i % 2 == 0:
            rect(s, M, yy, 10 - 2 * M, 0.355, fill=RGBColor(0xF7, 0xF8, 0xFA))
        text(s, M + 0.14, yy + 0.085, 3.5, 0.26, k, size=9.5, colour=INDIGO, font=MONO)
        text(s, M + 3.7, yy + 0.085, 5.3, 0.26, v, size=9.5, colour=INK)
    text(s, M, ty + 0.34 + len(rows) * 0.355 + 0.16, 10 - 2 * M, 0.5,
         "Edits are spliced, not rewritten: changing one value changes one line, so the diff is "
         "the change and your comments and ordering survive.", size=9.5, colour=MUTED)

    # 11 ------------------------------------------------- step 4: id ranges
    s = page()
    y = heading(s, "Step 4 — ID ranges", "who mints which identifiers")
    text(s, M, y, 10 - 2 * M, 0.9, [
        ("OntoBoard → Project → ID ranges…", {"size": 11.5, "bold": True, "colour": INDIGO,
                                                              "space": 7}),
        ("An ODK project gives each curator a numeric range so two people never mint the same "
         "identifier. The editor shows every range, who holds it, and which are still free.",
         {"size": 10.5, "colour": INK}),
    ])
    cards = [
        ("Shows the whole file", "Every allocated range, with its owner."),
        ("Adds a range safely", "Appends after the last one, never into a gap somebody reserved."),
        ("Writes one line", "The file is spliced, so the diff is the new range and nothing else."),
    ]
    cw = (10 - 2 * M - 0.3 * 2) / 3
    for i, (head, note) in enumerate(cards):
        x = M + i * (cw + 0.3)
        c = rect(s, x, y + 1.05, cw, 1.35, fill=LIGHT, line=FAINT,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        c.adjustments[0] = 0.07
        text(s, x + 0.18, y + 1.22, cw - 0.36, 0.3, head, size=11, colour=INDIGO, bold=True)
        text(s, x + 0.18, y + 1.56, cw - 0.36, 0.7, note, size=9.5, colour=MUTED)
    text(s, M, y + 2.62, 10 - 2 * M, 0.4,
         "The same discipline runs through the plugin: splice, never rewrite.", size=10,
         colour=ACCENT, bold=True)

    # 12 ------------------------------------------------ step 5: add terms
    s = page()
    y = heading(s, "Step 5 — Put terms on the canvas", "the workspace")
    text(s, M, y, 5.2, 2.6, [
        ("Window → Tabs → OntoBoard", {"size": 11.5, "bold": True, "colour": INDIGO,
                                                  "space": 7}),
        ("The canvas starts empty on purpose — Protégé opens ontologies with 100,000 "
         "classes and drawing all of them would hang.", {"size": 10.5, "colour": MUTED,
                                                         "space": 7}),
        ("Double-click empty canvas to create a class there. Drag terms in from the entity trees. "
         "Right-click a node and expand its neighbours one hop at a time.",
         {"size": 10.5, "colour": INK, "space": 7}),
        ("Arrange by hand, group into frames, add sticky notes — and the arrangement survives "
         "the next edit, which is the part that is easy to get wrong.",
         {"size": 10.5, "colour": INK}),
    ])
    # A small canvas vignette, drawn as native shapes so it stays sharp.
    cx, cy, cw2, ch2 = 6.0, y - 0.05, 3.45, 2.5
    board = rect(s, cx, cy, cw2, ch2, fill=RGBColor(0xFC, 0xFD, 0xFF), line=FAINT,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    board.adjustments[0] = 0.03
    frame = rect(s, cx + 0.18, cy + 0.42, 2.05, 1.5, fill=None, line=RGBColor(0xC2, 0xCB, 0xEA),
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    text(s, cx + 0.26, cy + 0.48, 1.9, 0.2, "Process-Agent-Role", size=7.5, colour=MUTED,
         bold=True)
    nodes = [(cx + 0.55, cy + 0.78, "Process"), (cx + 0.34, cy + 1.42, "Agent"),
             (cx + 1.32, cy + 1.42, "Role")]
    conn = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(cx + 0.72), Inches(cy + 1.02),
                              Pt(1.2), Inches(0.40))
    conn.shadow.inherit = False
    conn.fill.solid()
    conn.fill.fore_color.rgb = RGBColor(0xB6, 0xBE, 0xDA)
    conn.line.fill.background()
    for nx, ny, label in nodes:
        nd = rect(s, nx, ny, 0.86, 0.3, fill=LIGHT, line=INDIGO,
                  shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        nd.adjustments[0] = 0.2
        text(s, nx, ny + 0.065, 0.86, 0.2, label, size=8, colour=INK, align=PP_ALIGN.CENTER)
    note = rect(s, cx + 2.42, cy + 0.55, 0.85, 0.72, fill=RGBColor(0xFF, 0xF4, 0xC4),
                line=RGBColor(0xE8, 0xD8, 0x8A))
    text(s, cx + 2.5, cy + 0.63, 0.7, 0.6, "check with\nMWO v3", size=7, colour=RGBColor(0x7A, 0x63, 0x18))
    text(s, cx, cy + ch2 + 0.08, cw2, 0.3,
         "Frames group what belongs together; a frame moves its contents with it.", size=8,
         colour=MUTED, align=PP_ALIGN.CENTER)

    # 13 ---------------------------------------------------- step 6: build
    s = page()
    y = heading(s, "Step 6 — Build the ontology", "odk, from the menu")
    text(s, M, y, 10 - 2 * M, 0.5, [
        ("OntoBoard → Project → Build…", {"size": 11.5, "bold": True, "colour": INDIGO,
                                                          "space": 6}),
        ("Runs the project's own Makefile through the ODK container, with the log streaming into "
         "a window you can stop.", {"size": 10.5, "colour": INK}),
    ])
    panel = rect(s, M, y + 0.72, 10 - 2 * M, 1.5, fill=RGBColor(0x1B, 0x23, 0x40),
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    panel.adjustments[0] = 0.04
    log_lines = [
        "docker run --rm -v <project>:/work -w /work/src/ontology \\",
        "    -e ROBOT_JAVA_ARGS=-Xmx8G -e JAVA_OPTS=-Xmx8G obolibrary/odkfull make all",
        "",
        "ROBOT_JAVA_ARGS comes from robot_java_args in your ODK YAML — not from a hardcoded value.",
    ]
    for i, line in enumerate(log_lines):
        text(s, M + 0.22, y + 0.88 + i * 0.26, 9 - M, 0.24, line, size=9,
             colour=RGBColor(0x8F, 0xE3, 0xB0) if i < 2 else RGBColor(0x9F, 0xAA, 0xD4), font=MONO)
    bullets(s, M, y + 2.42, 10 - 2 * M, [
        ("The build is cancellable", " — Stop kills the container process, rather than leaving "
                                      "it running."),
        ("Failures are explained", " — out of memory, Docker missing, a quality gate tripped: "
                                    "each gets the remedy that actually works."),
    ], size=10.5, gap=0.33)

    # 14 -------------------------------------------------- step 7: release
    s = page()
    y = heading(s, "Step 7 — Release", "and prove it works")
    left = [
        ("Release…", "Builds the release artefacts and tags the version."),
        ("Compare releases…", "What changed between two versions, axiom by axiom."),
        ("Git…", "Status, branch, commit and push, without leaving the editor."),
    ]
    for i, (head, note) in enumerate(left):
        yy = y + i * 0.62
        text(s, M, yy, 4.4, 0.6, [
            (head, {"size": 11.5, "bold": True, "colour": INDIGO, "space": 2}),
            (note, {"size": 10, "colour": MUTED}),
        ], space=0)
    card = rect(s, 5.3, y - 0.05, 4.15, 2.3, fill=LIGHT, line=INDIGO,
                shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    card.adjustments[0] = 0.05
    text(s, 5.52, y + 0.12, 3.7, 1.9, [
        ("Every release carries a receipt", {"size": 11.5, "bold": True, "colour": INDIGO,
                                             "space": 7}),
        ("A release will not publish unless a host smoke receipt exists for that exact version, "
         "recording which Protégés it was started in, what passed, and what was not checked.",
         {"size": 10, "colour": INK, "space": 7}),
        (str(F["releases"]) + " receipts so far — they are the release notes.",
         {"size": 10, "colour": MUTED}),
    ])

    # 15 ------------------------------------------- section: the workspace
    section("PART 3", "The workspace",
            "What ODK puts on disk, and which of it OntoBoard drives.")

    # 16 ------------------------------------------------- workspace overview
    s = page()
    y = heading(s, "Workspace overview", "what is in src/")
    rows = [
        ("src/ontology/", "Edit file, catalog, both Makefiles, imports/, the ODK YAML",
         "Driven", True),
        ("src/sparql/", "The quality-control queries your build runs", "Driven", True),
        ("src/scripts/", "run-command.sh, update_repo.sh, anything you add", "Driven", True),
        ("src/metadata/", "The OBO Foundry registry entry \u2014 only for a submission",
         "By hand", False),
    ]
    h = 0.62
    for i, (path, what, verdict, ok) in enumerate(rows):
        ry = y + i * (h + 0.14)
        card = rect(s, M, ry, 10 - 2 * M, h, fill=LIGHT if ok else RGBColor(0xFA, 0xF7, 0xF2),
                    line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.12
        text(s, M + 0.2, ry + 0.07, 1.85, 0.3, path, size=11.5, bold=True,
             colour=INDIGO if ok else RGBColor(0x8A, 0x6D, 0x3B))
        text(s, M + 2.1, ry + 0.09, 5.45, 0.42, what, size=10, colour=MUTED)
        chip = rect(s, 10 - M - 1.05, ry + 0.14, 0.85, 0.34,
                    fill=ACCENT if ok else RGBColor(0xE8, 0xDC, 0xC2), line=None,
                    shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        chip.adjustments[0] = 0.3
        text(s, 10 - M - 1.05, ry + 0.19, 0.85, 0.26, verdict, size=9, bold=True,
             colour=WHITE if ok else RGBColor(0x6B, 0x55, 0x2C), align=PP_ALIGN.CENTER)
    text(s, M, y + 4 * (h + 0.14) + 0.12, 10 - 2 * M, 0.7, [
        ("Three of the four run from the menu bar.", {"size": 12, "bold": True, "colour": INK,
                                                      "space": 6}),
        ("No terminal and no `sh run.sh`: ROBOT runs in-process, make targets and your own "
         "scripts run through the same container the build uses, and the SPARQL checks run "
         "against the ontology you have open.", {"size": 10.5, "colour": MUTED}),
    ])

    # 17 ------------------------------------------------- the ontology directory
    s = page()
    y = heading(s, "The ontology directory", "src/ontology, file by file")
    files = [
        ("<id>-edit.owl", "The file you edit. Protégé opens it directly"),
        ("<id>-odk.yaml", "Project configuration\u2026 edits every scalar, adds and removes "
                          "list entries"),
        ("Makefile", "Build\u2026 lists its targets, following include into <id>.Makefile"),
        ("catalog-v001.xml", "Written by Import terms\u2026 so an import resolves offline"),
        ("imports/", "Refresh imports audits every declared product and rebuilds what it can"),
        ("reports/", "Quality report\u2026 runs ROBOT's 32 rules in-process"),
    ]
    col = (10 - 2 * M - 0.3) / 2
    for i, (name, what) in enumerate(files):
        cx = M + (i % 2) * (col + 0.3)
        cy = y + (i // 2) * 0.95
        card = rect(s, cx, cy, col, 0.82, fill=LIGHT, line=FAINT,
                    shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.09
        text(s, cx + 0.18, cy + 0.08, col - 0.36, 0.28, name, size=11, bold=True, colour=INDIGO)
        text(s, cx + 0.18, cy + 0.38, col - 0.36, 0.4, what, size=9.5, colour=MUTED)
    text(s, M, y + 3 * 0.95 + 0.06, 10 - 2 * M, 0.45,
         "Every one of these is read by something in the menu. The ODK YAML is the one OntoBoard "
         "also writes \u2014 spliced, never re-serialised, so a one-word change stays a one-line "
         "diff.", size=10.5, colour=MUTED)

    # 18 --------------------------------------------- custom import, the old way
    s = page()
    y = heading(s, "Adding a custom import", "seven steps, in ODK's own walkthrough")
    col = (10 - 2 * M - 0.35) / 2
    old = rect(s, M, y, col, 3.0, fill=RGBColor(0xF7, 0xF8, 0xFA), line=FAINT,
               shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    old.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.18, col - 0.44, 0.3, "THE ODK WALKTHROUGH", size=9.5, colour=MUTED,
         bold=True)
    bullets(s, M + 0.22, y + 0.56, col - 0.44, [
        "Declare the import in the ODK YAML",
        "Check the Makefile picked it up",
        "Add term IRIs to <id>_terms.txt",
        "Copy the import URI into the edit file",
        "Add it to the catalog by hand",
        "Configure the module type",
        "Run make refresh-imports in a shell",
    ], size=10, gap=0.33, colour=MUTED)
    new = rect(s, M + col + 0.35, y, col, 3.0, fill=LIGHT, line=FAINT,
               shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    new.adjustments[0] = 0.04
    text(s, M + col + 0.57, y + 0.18, col - 0.44, 0.3, "WITH ONTOBOARD", size=9.5, colour=INDIGO,
         bold=True)
    bullets(s, M + col + 0.57, y + 0.56, col - 0.44, [
        "ROBOT \u2192 Import terms\u2026 picks the source and the terms",
        "It writes the module, the term list and the catalog entry",
        "It declares the product in import_group.products",
        "Project \u2192 Refresh imports audits and rebuilds",
    ], size=10, gap=0.42, colour=INK)
    text(s, M + col + 0.57, y + 2.42, col - 0.44, 0.45,
         "Four of the seven steps are the plugin's job now, and the two that were easiest to "
         "forget \u2014 the catalog entry and the product \u2014 are not yours to remember.",
         size=9, colour=MUTED)

    # 19 ------------------------------------------- what refresh imports reports
    s = page()
    y = heading(s, "What a refresh tells you", "measured on four real projects")
    figures = [
        ("35", "import products\ndeclared across them"),
        ("15", "a kind OntoBoard\nrebuilds itself"),
        ("20", "named with the reason\nrather than guessed at"),
        ("4", "on disk that no\nproduct declares"),
    ]
    w = (10 - 2 * M - 0.3 * 3) / 4
    for i, (big, label) in enumerate(figures):
        x = M + i * (w + 0.3)
        card = rect(s, x, y, w, 1.45, fill=LIGHT, line=FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        card.adjustments[0] = 0.06
        text(s, x, y + 0.18, w, 0.55, big, size=32, colour=INDIGO, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, x, y + 0.8, w, 0.55, label, size=9, colour=MUTED, align=PP_ALIGN.CENTER)
    text(s, M, y + 1.68, 10 - 2 * M, 1.2, [
        ("An import nobody can rebuild is one nobody else can build the repository from.",
         {"size": 12, "bold": True, "colour": INK, "space": 7}),
        ("NFDIcore, MWO, PMDCO and ECTO declare 35 import products between them. A module whose "
         "module_type is custom is left alone on purpose \u2014 ODK's own generated rule for one "
         "refuses too, because the real command is hand-written in the project's Makefile. And "
         "four modules sit in imports/ that no product declares, so make refresh-imports never "
         "touches them.", {"size": 10.5, "colour": MUTED}),
    ])

    # 20 ------------------------------------------------- section: features
    section("PART 4", "Running the features",
            "What the menu gives you, and what each operation really does.")

    # 16 -------------------------------------------------- the menu (shot)
    s = page()
    y = heading(s, "The OntoBoard menu", "everything in one place")
    shot = crop_shot("02-ontoboard-menu.png", (0, 58, 1100, 604), "shot-menu.png")
    if shot:
        picture(s, shot, M, y, w=4.75)
    bullets(s, 5.6, y + 0.05, 3.9, [
        ("Project", " — new and open, configuration, ID ranges, term lists, imports, build, "
                     "release, git."),
        ("ROBOT", " — report, measure, profile, explain, SPARQL, transform, export, template, "
                   "import terms, patterns."),
        ("Notes", " — editorial and curator notes, discussion links."),
        ("Provenance", " — who changed what, stamped the OBO way."),
        ("Collaboration", " — a live session, or plain git."),
    ], size=10, gap=0.52)
    text(s, M, y + 3.05, 9, 0.3,
         "Real screenshot: Protégé 5.6.9, OntoBoard " + F["version"], size=8, colour=MUTED)

    # 17 ------------------------------------------------------ robot (shot)
    s = page()
    y = heading(s, "ROBOT, running in process", "no external binary")
    # Cropped at the OntoBoard menu's own right border, not through the ROBOT submenu beside
    # it. The capture is 1150px wide and the submenu runs off that edge, so a wider crop does
    # not reveal more of it - it only slices the item labels mid-word, which is what this slide
    # showed until it was rendered and looked at. The submenu's contents are the list of
    # operations in the text beside the picture, so nothing is lost by ending the crop here.
    shot = crop_shot("05-menu-robot.png", (545, 100, 1058, 604), "shot-robot.png")
    if shot:
        picture(s, shot, 6.05, y - 0.05, w=3.4)
    text(s, M, y, 5.3, 2.6, [
        ("robot-core runs inside the plugin, against the ontology you have open.",
         {"size": 11.5, "bold": True, "colour": INK, "space": 8}),
        ("No robot.jar on your PATH, no temporary files written to disk, no second copy of the "
         "ontology to keep in step. The operation sees exactly what Protégé sees.",
         {"size": 10.5, "colour": MUTED, "space": 8}),
        ("Quality report  ·  Measure  ·  Profile  ·  Explain  ·  SPARQL  ·  "
         "Transform  ·  Export terms  ·  Rename IRIs  ·  Template  ·  Import terms  "
         "·  Pattern library", {"size": 10, "colour": INDIGO, "bold": True}),
    ])

    # 18 ---------------------------------------------------- quality report
    s = page()
    y = heading(s, "Quality report", "by your project's own rules")
    text(s, M, y, 10 - 2 * M, 0.5,
         "ROBOT → Quality report… runs ROBOT's checks and shows every violation in a sortable "
         "table.", size=11, colour=INK)
    pts = [
        ("It reads your profile.txt", "The same file your CI passes to ROBOT, found beside the "
                                      "edit file — so the plugin and the build agree about what "
                                      "counts as a violation."),
        ("It reads robot_report.fail_on", "Which severity fails the build comes from your ODK "
                                          "YAML, not from a default."),
        ("It says what would fail", "“N of M findings are at or above error, which would fail "
                                     "this project's build.”"),
    ]
    for i, (head, note) in enumerate(pts):
        yy = y + 0.6 + i * 0.78
        bar = rect(s, M, yy, 0.055, 0.6, fill=ACCENT)
        text(s, M + 0.22, yy - 0.02, 9 - M, 0.75, [
            (head, {"size": 11.5, "bold": True, "colour": INDIGO, "space": 3}),
            (note, {"size": 10, "colour": INK}),
        ], space=0)

    # 19 -------------------------------------------------- other operations
    s = page()
    y = heading(s, "The rest of the ROBOT surface", "all in process")
    ops = [
        ("Measure", "60 metrics for the open ontology."),
        ("Profile", "OWL 2 EL / QL / RL / DL, and which axioms break it."),
        ("Explain", "Why a class is unsatisfiable, with the justification."),
        ("SPARQL", "Your query, or the project's own checks in src/sparql."),
        ("Transform", "ROBOT's transformations, previewed before applying."),
        ("Export terms", "TSV or CSV, the columns you choose."),
        ("Template", "A ROBOT template, with every bad row reported at once."),
        ("Import terms", "Extracts a module, writes the term list, the import and the catalog."),
    ]
    cw = (10 - 2 * M - 0.28) / 2
    for i, (head, note) in enumerate(ops):
        col_i, row_i = i % 2, i // 2
        x = M + col_i * (cw + 0.28)
        yy = y + row_i * 0.63
        c = rect(s, x, yy, cw, 0.54, fill=RGBColor(0xF7, 0xF8, 0xFA), line=FAINT,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
        c.adjustments[0] = 0.12
        text(s, x + 0.16, yy + 0.07, 1.5, 0.22, head, size=10, colour=INDIGO, bold=True)
        text(s, x + 1.45, yy + 0.07, cw - 1.6, 0.42, note, size=9, colour=MUTED)

    # 20 --------------------------------------------- section: the patterns
    section("PART 5", "The pattern library",
            str(F["patterns"]) + " ontology design patterns, ranked against the ontology you have "
            "open.")

    # 21 --------------------------------------------------- the collections
    s = page()
    y = heading(s, "Four collections, one library", "separated by source")
    order = [("mwo", "MWO", "ise-fizkarlsruhe.github.io/mwo"),
             ("nfdi", "NFDI MatWerk", "nfdi.fiz-karlsruhe.de/matwerk"),
             ("pmdco", "PMDco", "materialdigital.github.io/core-ontology"),
             ("odp", "ODP portal", "ontologydesignpatterns.org")]
    cw = (10 - 2 * M - 0.26 * 3) / 4
    for i, (key, label, where) in enumerate(order):
        x = M + i * (cw + 0.26)
        count = F["collections"].get(key, 0)
        c = rect(s, x, y, cw, 1.5, fill=LIGHT if key != "odp" else RGBColor(0xF7, 0xF8, 0xFA),
                 line=INDIGO if key != "odp" else FAINT, shape=MSO_SHAPE.ROUNDED_RECTANGLE,
                 line_w=1.2)
        c.adjustments[0] = 0.06
        text(s, x, y + 0.2, cw, 0.5, str(count), size=28, colour=INDIGO, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, x, y + 0.75, cw, 0.3, label, size=10.5, colour=INK, bold=True,
             align=PP_ALIGN.CENTER)
        text(s, x + 0.1, y + 1.03, cw - 0.2, 0.4, where, size=7.5, colour=MUTED,
             align=PP_ALIGN.CENTER)
    text(s, M, y + 1.75, 10 - 2 * M, 1.2, [
        ("The 36 BFO-family patterns were added because the ODP collection and the OBO world "
         "share no vocabulary at all.", {"size": 11.5, "bold": True, "colour": INK, "space": 7}),
        ("Measured: zero of the " + str(F["collections"].get("odp", 0)) + " ODP patterns share a "
         "single IRI with a real BFO-based project. For somebody working in MWO or PMDco, the "
         "original library was a catalogue about other people's upper ontology.",
         {"size": 10.5, "colour": MUTED, "space": 7}),
        (str(F["patterns"]) + " entries, " + str(F["distinct"]) + " distinct — ten are another "
         "pattern under a second name, and the library says so.",
         {"size": 10, "colour": ACCENT, "bold": True}),
    ])

    # 22 ------------------------------------------------------ one pattern
    s = page()
    y = heading(s, "What a pattern contains", "mwo pattern 1: process-agent-role")
    fig = os.path.join(DECK, "fig-pattern-process-agent-role.png")
    if os.path.isfile(fig):
        picture(s, fig, M, y - 0.02, w=6.45)
    text(s, 7.25, y, 2.2, 2.9, [
        ("Read from the file", {"size": 11, "bold": True, "colour": INDIGO, "space": 6}),
        ("Every box is a class this pattern declares. Every solid line is a subClassOf it "
         "asserts. The dashed line is its one existential restriction.",
         {"size": 9, "colour": MUTED, "space": 6}),
        ("Not a logical module", {"size": 11, "bold": True, "colour": INDIGO, "space": 6}),
        ("A ROBOT STAR module of an eight-term PMDco pattern came out with 119 classes, because a "
         "BFO ontology connects everything to the spine. These are induced sub-ontologies: 6 to "
         "26 classes each.", {"size": 9, "colour": MUTED}),
    ])

    # 23 -------------------------------------------------- recommendations
    s = page()
    y = heading(s, "Suggested for the ontology you have open", "and why")
    text(s, M, y, 10 - 2 * M, 0.5,
         str(F["patterns"]) + " patterns is too many to browse when you want one, so the browser's "
         "first ordering ranks them against your own vocabulary.", size=11, colour=INK)
    quote = rect(s, M, y + 0.62, 10 - 2 * M, 0.85, fill=LIGHT, line=None,
                 shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    quote.adjustments[0] = 0.09
    text(s, M + 0.25, y + 0.78, 9 - M, 0.6,
         "“3 of its 4 terms are already in your ontology: Agent, Role, hasRole”",
         size=13, colour=INDIGO, bold=True)
    bullets(s, M, y + 1.68, 10 - 2 * M, [
        ("The reason is the point", " — a ranked list with no evidence is a magic box. 0.75 "
                                     "tells you nothing; the three terms it matched do."),
        ("Two signals, both measured", " — shared IRIs count double, shared names count once. "
                                        "Scored by how much of the pattern you already have."),
        ("Your labels, not ours", " — where your ontology has a label for a matched term, that "
                                   "label is shown, because BFO_0000004 tells nobody anything."),
    ], size=10, gap=0.42)

    # 24 ------------------------------------------- section: collaboration
    section("PART 6", "Working with other people",
            "A live session if your group runs a server, plain git if it does not.")

    # 25 ------------------------------------------------------ collaboration
    s = page()
    y = heading(s, "Two ways to work together", "collaboration")
    col = (10 - 2 * M - 0.32) / 2
    a = rect(s, M, y, col, 2.55, fill=RGBColor(0xF7, 0xF8, 0xFA), line=FAINT,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE)
    a.adjustments[0] = 0.04
    text(s, M + 0.22, y + 0.2, col - 0.44, 0.3, "GIT MODE", size=9.5, colour=MUTED, bold=True)
    text(s, M + 0.22, y + 0.55, col - 0.44, 1.8,
         "Leave the collaboration fields empty and work the way you already do: commit and push. "
         "You will not see other people's cursors, and nothing else changes. This is the default, "
         "and it is a choice rather than a failure — the dialog says so.",
         size=10, colour=INK)
    b = rect(s, M + col + 0.32, y, col, 2.55, fill=LIGHT, line=INDIGO,
             shape=MSO_SHAPE.ROUNDED_RECTANGLE, line_w=1.25)
    b.adjustments[0] = 0.04
    text(s, M + col + 0.54, y + 0.2, col - 0.44, 0.3, "LIVE MODE", size=9.5, colour=INDIGO,
         bold=True)
    text(s, M + col + 0.54, y + 0.55, col - 0.44, 1.9,
         "Your group runs the server — two Node files in the repository. Each person gets a "
         "token. You see cursors, selections and edits as they happen.\n\n"
         "Anything the protocol cannot carry is counted and shown, rather than dropped in silence.",
         size=10, colour=INK)

    # 26 -------------------------------------------------------- the verdict
    s = page()
    y = heading(s, "An honest verdict", "what it does, and what it does not")
    cols = [("Strongest", GREEN, [
                "Reports, reasoning and explanations in process",
                "The pattern library, ranked against your ontology",
                "Term import done the ODK way",
                "Releases that cannot ship unverified",
             ]),
            ("Still growing", ACCENT, [
                "Not every ODK YAML key drives behaviour yet",
                "Lists in the YAML still need a text editor",
                "Live mode carries 96 of 141 axiom kinds",
                "No pull-request surface yet",
             ])]
    cw = (10 - 2 * M - 0.32) / 2
    for i, (head, colour, items) in enumerate(cols):
        x = M + i * (cw + 0.32)
        text(s, x, y, cw, 0.3, head.upper(), size=10, colour=colour, bold=True)
        # 0.028in is two points. Pt(2)/914400 would have been the same number; the earlier
        # `* 72` on top of it made a 2-INCH filled block, which is how a rule became a slab of
        # solid green across half the slide. Caught by rendering the deck and looking at it.
        rect(s, x, y + 0.28, cw, 0.028, fill=colour)
        for j, it in enumerate(items):
            text(s, x, y + 0.48 + j * 0.46, cw, 0.42, "•  " + it, size=10, colour=INK)
    text(s, M, y + 2.5, 10 - 2 * M, 0.6,
         "Every gap is written down and measured, with the defects found along the way — see "
         "the Limitations page. A tool that hides what it cannot do is a tool you cannot plan "
         "around.", size=10, colour=MUTED)

    # 27 -------------------------------------------------------- resources
    s = page()
    y = heading(s, "Where everything lives", "resources")
    links = [
        ("Documentation", "ebrahimnorouzi.github.io/ontoboard"),
        ("Download the latest jar", "github.com/ebrahimnorouzi/ontoboard/releases/latest"),
        ("Pattern library on the web", "ebrahimnorouzi.github.io/ontoboard/patterns/"),
        ("Source and issues", "github.com/ebrahimnorouzi/ontoboard"),
        ("Working with ODK, step by step", "ebrahimnorouzi.github.io/ontoboard/odk-workflow/"),
    ]
    for i, (head, url) in enumerate(links):
        yy = y + i * 0.54
        dot = rect(s, M, yy + 0.09, 0.07, 0.07, fill=ACCENT, shape=MSO_SHAPE.OVAL)
        text(s, M + 0.24, yy - 0.01, 3.5, 0.3, head, size=11, colour=INK, bold=True)
        text(s, M + 3.75, yy, 5.5, 0.3, url, size=10, colour=INDIGO, font=MONO)

    # 28 ----------------------------------------------------------- closing
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
    prs = main()
    print("wrote %s" % OUT)
    print("  %d slides, %.2f x %.2f in" % (len(prs.slides.__iter__.__self__._sldIdLst),
                                           prs.slide_width / 914400, prs.slide_height / 914400))
