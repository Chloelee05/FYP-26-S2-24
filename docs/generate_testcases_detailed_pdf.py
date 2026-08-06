"""Render the detailed report to docs/AuctionHub_TestCases_Detailed.pdf.

This is the long form of the test documentation: coverage matrix, curated
cases in tabular form, the confidentiality defect writeups and the appendices.
It is the reference version, kept for the supervisor and for the presentation.

The cases that go into the Final Technical Document are formatted separately,
one bordered block per case, by generate_testcases_ftd_pdf.py.

The tables are built as real ReportLab tables with selectable text rather than
as preformatted blocks or images.

Two things are checked before anything is rendered, because a document that
cites a test method that no longer exists is worse than no document:

  1. Every test class and method named in the case tables is resolved against
     the actual sources under FYP/src/test/java and FYP/Frontend/src.
  2. The text is scanned for em dashes and en dashes, which are not wanted
     anywhere in this deliverable.

Either check failing aborts the build with a non-zero exit code.

Usage:
    python3 -m venv .venv && .venv/bin/pip install reportlab
    .venv/bin/python docs/generate_testcases_pdf.py
"""

import html
import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    KeepTogether,
    NextPageTemplate,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)
from reportlab.platypus.tableofcontents import TableOfContents

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "docs" / "AuctionHub_TestCases_Report.md"
OUTPUT = REPO / "docs" / "AuctionHub_TestCases_Detailed.pdf"
JAVA_TESTS = REPO / "FYP" / "src" / "test" / "java"
FRONTEND_SRC = REPO / "FYP" / "Frontend" / "src"

TITLE = "AuctionHub: Software Test Documentation"
TEAM = "FYP-26-S2-24"
DATE = "6 August 2026"

EM_DASH = "\u2014"
EN_DASH = "\u2013"

# Landscape A4 minus margins, for the seven column case tables.
LANDSCAPE_W = landscape(A4)[0] - 24 * mm
PORTRAIT_W = A4[0] - 40 * mm

BODY_FONT = "Helvetica"
BOLD_FONT = "Helvetica-Bold"
MONO_FONT = "Courier"

INK = colors.HexColor("#111111")
RULE = colors.HexColor("#9aa0a6")
HEADER_BG = colors.HexColor("#e8eaed")
ROW_BG = colors.HexColor("#f7f8f9")


# ---------------------------------------------------------------------------
# Markdown parsing
# ---------------------------------------------------------------------------

def split_row(line):
    """Split a markdown table row into cells, ignoring the outer pipes."""
    return [c.strip() for c in line.strip().strip("|").split("|")]


def is_divider(line):
    return bool(re.fullmatch(r"\|[\s:\-|]+\|", line.strip()))


def parse(md):
    """Turn the markdown into a flat list of (kind, payload) blocks."""
    blocks = []
    lines = md.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped or stripped == "---":
            i += 1
            continue

        if stripped.startswith("```"):
            i += 1
            code = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i])
                i += 1
            i += 1
            blocks.append(("code", "\n".join(code)))
            continue

        if stripped.startswith("#"):
            level = len(stripped) - len(stripped.lstrip("#"))
            blocks.append(("h%d" % level, stripped.lstrip("#").strip()))
            i += 1
            continue

        if stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                if not is_divider(lines[i]):
                    rows.append(split_row(lines[i]))
                i += 1
            if rows:
                blocks.append(("table", rows))
            continue

        if stripped.startswith(">"):
            quote = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                quote.append(lines[i].strip().lstrip(">").strip())
                i += 1
            blocks.append(("quote", " ".join(q for q in quote if q)))
            continue

        if re.match(r"^[-*]\s+", stripped):
            items = []
            while i < len(lines) and re.match(r"^[-*]\s+", lines[i].strip()):
                items.append(re.sub(r"^[-*]\s+", "", lines[i].strip()))
                i += 1
            blocks.append(("bullets", items))
            continue

        if re.match(r"^\d+\.\s+", stripped):
            items = []
            while i < len(lines) and re.match(r"^\d+\.\s+", lines[i].strip()):
                items.append(re.sub(r"^\d+\.\s+", "", lines[i].strip()))
                i += 1
            blocks.append(("numbers", items))
            continue

        para = []
        while i < len(lines) and lines[i].strip() and not re.match(
            r"^(\||#|```|>|[-*]\s|\d+\.\s|---$)", lines[i].strip()
        ):
            para.append(lines[i].strip())
            i += 1
        if para:
            blocks.append(("para", " ".join(para)))

    return blocks


def inline(text):
    """Convert markdown inline spans to ReportLab markup, escaping XML first."""
    placeholders = []

    def stash(fragment):
        placeholders.append(fragment)
        return "\x00%d\x00" % (len(placeholders) - 1)

    # Code spans are escaped and stashed before anything else can touch them,
    # so that content like <script>alert(1)</script> survives intact.
    def code_span(m):
        return stash('<font face="%s" size="7.6">%s</font>'
                     % (MONO_FONT, html.escape(m.group(1))))

    text = re.sub(r"`([^`]+)`", code_span, text)
    text = html.escape(text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<i>\1</i>", text)

    for idx, fragment in enumerate(placeholders):
        text = text.replace("\x00%d\x00" % idx, fragment)
    return text


# ---------------------------------------------------------------------------
# Reference verification
# ---------------------------------------------------------------------------

CASE_TABLE_HEADERS = ("Test class and method", "Test file and test name")


def collect_references(blocks):
    """Pull (class, method) pairs out of the second column of the case tables."""
    refs = []
    for kind, payload in blocks:
        if kind != "table" or not payload:
            continue
        header = payload[0]
        if len(header) < 2 or header[1] not in CASE_TABLE_HEADERS:
            continue
        for row in payload[1:]:
            if len(row) < 2:
                continue
            current_class = None
            for token in re.findall(r"`([^`]+)`", row[1]):
                token = token.strip()
                if re.fullmatch(r"[A-Za-z][\w]*\.[A-Za-z_]\w*", token):
                    current_class, method = token.split(".", 1)
                    refs.append((row[0], current_class, method))
                elif re.fullmatch(r"\.[A-Za-z_]\w*", token) and current_class:
                    refs.append((row[0], current_class, token[1:]))
                elif token.endswith((".test.jsx", ".test.js")):
                    refs.append((row[0], token, None))
    return refs


def verify_references(refs):
    """Confirm every cited class exists and declares the cited method."""
    java_files = {p.stem: p for p in JAVA_TESTS.rglob("*.java")}
    frontend_files = {p.name: p for p in FRONTEND_SRC.rglob("*")
                      if p.is_file()}
    cache = {}
    problems = []

    for case_id, owner, method in refs:
        if method is None:
            if owner not in frontend_files:
                problems.append("%s: frontend test file %s not found"
                                % (case_id, owner))
            continue

        path = java_files.get(owner)
        if path is None:
            problems.append("%s: test class %s not found under %s"
                            % (case_id, owner, JAVA_TESTS.relative_to(REPO)))
            continue

        if path not in cache:
            cache[path] = path.read_text(encoding="utf-8")
        if not re.search(r"\b%s\s*\(" % re.escape(method), cache[path]):
            problems.append("%s: %s.%s not declared in %s"
                            % (case_id, owner, method, path.name))

    return problems


# ---------------------------------------------------------------------------
# Styles
# ---------------------------------------------------------------------------

def build_styles():
    ss = getSampleStyleSheet()
    s = {}
    s["title"] = ParagraphStyle("t", parent=ss["Normal"], fontName=BOLD_FONT,
                                fontSize=23, leading=29, alignment=TA_CENTER,
                                textColor=INK)
    s["subtitle"] = ParagraphStyle("st", parent=ss["Normal"], fontName=BODY_FONT,
                                   fontSize=12, leading=19, alignment=TA_CENTER,
                                   textColor=INK)
    s["h1"] = ParagraphStyle("h1", parent=ss["Normal"], fontName=BOLD_FONT,
                             fontSize=15, leading=19, spaceBefore=16,
                             spaceAfter=8, textColor=INK)
    s["h2"] = ParagraphStyle("h2", parent=ss["Normal"], fontName=BOLD_FONT,
                             fontSize=11.5, leading=15, spaceBefore=13,
                             spaceAfter=6, textColor=INK)
    s["h3"] = ParagraphStyle("h3", parent=ss["Normal"], fontName=BOLD_FONT,
                             fontSize=10, leading=13, spaceBefore=10,
                             spaceAfter=5, textColor=INK)
    s["body"] = ParagraphStyle("b", parent=ss["Normal"], fontName=BODY_FONT,
                               fontSize=9.2, leading=13.4, spaceAfter=6,
                               textColor=INK)
    s["quote"] = ParagraphStyle("q", parent=s["body"], leftIndent=10,
                                rightIndent=10, spaceBefore=4, spaceAfter=8,
                                borderPadding=6, backColor=ROW_BG)
    s["bullet"] = ParagraphStyle("bu", parent=s["body"], leftIndent=13,
                                 bulletIndent=3, spaceAfter=3)
    s["code"] = ParagraphStyle("c", parent=ss["Normal"], fontName=MONO_FONT,
                               fontSize=8, leading=11.2, leftIndent=8,
                               textColor=INK)
    s["cell"] = ParagraphStyle("ce", parent=ss["Normal"], fontName=BODY_FONT,
                               fontSize=7.4, leading=9.4, textColor=INK)
    s["cellhead"] = ParagraphStyle("ch", parent=s["cell"], fontName=BOLD_FONT)
    s["toc1"] = ParagraphStyle("toc1", parent=ss["Normal"], fontName=BOLD_FONT,
                               fontSize=10, leading=17, textColor=INK)
    s["toc2"] = ParagraphStyle("toc2", parent=ss["Normal"], fontName=BODY_FONT,
                               fontSize=9.2, leading=14.5, leftIndent=14,
                               textColor=INK)
    return s


# ---------------------------------------------------------------------------
# Table layout
# ---------------------------------------------------------------------------

# Hand-tuned proportional widths for the recurring tables, keyed on the first
# two header cells. Anything not listed here is measured from its content.
TUNED_WIDTHS = {
    ("ID", "Test class and method"): [0.075, 0.196, 0.170, 0.144, 0.144,
                                      0.196, 0.075],
    ("ID", "Test file and test name"): [0.075, 0.196, 0.170, 0.144, 0.144,
                                        0.196, 0.075],
    ("ID", "Defect"): [0.055, 0.325, 0.185, 0.315, 0.120],
    ("ID", "Gap"): [0.055, 0.455, 0.075, 0.415],
    ("#", "Website section / module"): [0.035, 0.245, 0.075, 0.080, 0.075,
                                        0.490],
    ("Read path", "Verdict"): [0.30, 0.70],
    # The file column holds monospaced paths, which are wider per character
    # than the measurement assumes.
    ("File", "Tests"): [0.42, 0.09, 0.49],
}

MIN_COLUMN_FRACTION = 0.06


def measured_ratios(rows):
    """Size columns from their content, so no column collapses to one letter.

    Cell length is capped before averaging: past a point a longer cell just
    wraps to more lines, and letting it grow without limit starves the short
    columns next to it.
    """
    n = len(rows[0])
    weights = []
    for col in range(n):
        lengths = []
        for row in rows[1:]:
            if col < len(row):
                lengths.append(min(len(re.sub(r"[`*]", "", row[col])), 90))
        body = sum(lengths) / len(lengths) if lengths else 1.0
        weights.append(max(body, len(rows[0][col]), 1.0))

    total = sum(weights)
    ratios = [w / total for w in weights]

    # Lift anything below the floor, then take the difference back off the
    # columns that are above it, in proportion to their size.
    deficit = sum(MIN_COLUMN_FRACTION - r for r in ratios
                  if r < MIN_COLUMN_FRACTION)
    if deficit > 0:
        spare = sum(r for r in ratios if r >= MIN_COLUMN_FRACTION)
        ratios = [MIN_COLUMN_FRACTION if r < MIN_COLUMN_FRACTION
                  else r - deficit * (r / spare) for r in ratios]
    return ratios


def column_widths(rows, total):
    header = rows[0]
    key = tuple(header[:2])
    ratios = TUNED_WIDTHS.get(key)
    if ratios is None or len(ratios) != len(header):
        ratios = measured_ratios(rows)
    return [total * r for r in ratios]


def make_table(rows, styles, total_width):
    header, body = rows[0], rows[1:]
    data = [[Paragraph(inline(c), styles["cellhead"]) for c in header]]
    for row in body:
        row = row + [""] * (len(header) - len(row))
        data.append([Paragraph(inline(c), styles["cell"]) for c in row[:len(header)]])

    table = Table(data, colWidths=column_widths(rows, total_width),
                  repeatRows=1, hAlign="LEFT")
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), HEADER_BG),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3.5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3.5),
    ]
    for r in range(2, len(data), 2):
        style.append(("BACKGROUND", (0, r), (-1, r), ROW_BG))
    table.setStyle(TableStyle(style))
    return table


# ---------------------------------------------------------------------------
# Document assembly
# ---------------------------------------------------------------------------

class Doc(BaseDocTemplate):
    """Adds outline entries and TOC notifications for numbered headings."""

    def afterFlowable(self, flowable):
        if not isinstance(flowable, Paragraph):
            return
        style = flowable.style.name
        if style not in ("h1", "h2"):
            return
        text = re.sub(r"<[^>]+>", "", flowable.getPlainText()).strip()
        # The contents page heading is not an entry in its own contents.
        if text == "Contents":
            return
        level = 0 if style == "h1" else 1
        key = "sec-%d" % id(flowable)
        self.canv.bookmarkPage(key)
        self.canv.addOutlineEntry(text, key, level=level, closed=(level == 0))
        # getPlainText has already resolved entities, and the contents page
        # renders each entry as a Paragraph, so a bare "&" has to be re-escaped
        # before it reaches the parser a second time.
        self.notify("TOCEntry",
                    (level, html.escape(text, quote=False), self.page, key))


def page_furniture(canvas, doc):
    canvas.saveState()
    width, height = canvas._pagesize
    canvas.setFont(BODY_FONT, 7.5)
    canvas.setFillColor(colors.HexColor("#5f6368"))
    canvas.drawString(12 * mm, 8 * mm, "%s  |  %s" % (TEAM, TITLE))
    canvas.drawRightString(width - 12 * mm, 8 * mm, "Page %d" % canvas.getPageNumber())
    canvas.setStrokeColor(RULE)
    canvas.setLineWidth(0.4)
    canvas.line(12 * mm, 12 * mm, width - 12 * mm, 12 * mm)
    canvas.restoreState()


def title_page(styles):
    story = [Spacer(1, 52 * mm),
             Paragraph(TITLE, styles["title"]),
             Spacer(1, 7 * mm),
             Paragraph("Test strategy, coverage matrix and curated test case "
                       "catalogue", styles["subtitle"]),
             Spacer(1, 22 * mm)]

    meta = [["Project", "AuctionHub Online Auction Platform"],
            ["Team code", TEAM],
            ["Document", "Software Test Documentation for the Final Technical "
                         "Document (FTD)"],
            ["Date", DATE],
            ["Automated tests", "1,738 total: 1,548 backend and 190 frontend, "
                                "0 failing"],
            ["Cases documented", "124, spanning all 16 functional areas"]]

    label = ParagraphStyle("lbl", parent=styles["body"], fontName=BOLD_FONT,
                           fontSize=9.2, spaceAfter=0)
    value = ParagraphStyle("val", parent=styles["body"], spaceAfter=0)
    data = [[Paragraph(k, label), Paragraph(v, value)] for k, v in meta]
    table = Table(data, colWidths=[38 * mm, 92 * mm], hAlign="CENTER")
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -2), 0.3, RULE),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 0),
    ]))
    story.append(table)
    story.append(PageBreak())
    return story


def build_story(blocks, styles):
    story = title_page(styles)

    toc = TableOfContents()
    toc.levelStyles = [styles["toc1"], styles["toc2"]]
    story.append(Paragraph("Contents", styles["h1"]))
    story.append(Spacer(1, 3 * mm))
    story.append(toc)
    story.append(PageBreak())

    # Every seven column table is a case table, and those go landscape. The
    # narrative around them stays portrait, so the two templates alternate.
    mode = "portrait"
    pending = []

    def flush_to(target):
        nonlocal mode
        if target == mode:
            return
        story.append(NextPageTemplate(target))
        story.append(PageBreak())
        mode = target

    def orientation_for(block):
        """Tables of five or more columns need the landscape template."""
        kind, payload = block
        if kind != "table":
            return None
        return "landscape" if len(payload[0]) >= 5 else "portrait"

    skip_title = True
    for index, (kind, payload) in enumerate(blocks):
        if kind == "h1" and skip_title:
            skip_title = False
            continue

        if kind == "table":
            wide = len(payload[0]) >= 5
            flush_to("landscape" if wide else "portrait")
            width = LANDSCAPE_W if wide else PORTRAIT_W
            story.append(Spacer(1, 2 * mm))
            story.append(make_table(payload, styles, width))
            story.append(Spacer(1, 4 * mm))
            continue

        if kind in ("h1", "h2", "h3", "h4"):
            # Markdown "##" is a numbered top-level section, "###" a numbered
            # subsection, "####" an unnumbered heading inside one.
            level = {"h1": "h1", "h2": "h1", "h3": "h2", "h4": "h3"}[kind]
            # If the heading introduces a table that needs the other
            # orientation, switch before the heading so the two stay together
            # instead of leaving the heading alone at the foot of a page.
            following = blocks[index + 1] if index + 1 < len(blocks) else None
            target = orientation_for(following) if following else None
            if target is not None:
                flush_to(target)
            elif level == "h1":
                flush_to("portrait")
            story.append(Paragraph(inline(payload), styles[level]))
            continue

        if kind == "para":
            story.append(Paragraph(inline(payload), styles["body"]))
            continue

        if kind == "quote":
            story.append(Paragraph(inline(payload), styles["quote"]))
            continue

        if kind == "code":
            flush_to("portrait")
            lines = [Paragraph(html.escape(l) or "&nbsp;", styles["code"])
                     for l in payload.split("\n")]
            box = Table([[lines]], colWidths=[PORTRAIT_W], hAlign="LEFT")
            box.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (-1, -1), ROW_BG),
                ("BOX", (0, 0), (-1, -1), 0.4, RULE),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ]))
            story.append(KeepTogether(box))
            story.append(Spacer(1, 4 * mm))
            continue

        if kind in ("bullets", "numbers"):
            for n, item in enumerate(payload, 1):
                bullet = "%d." % n if kind == "numbers" else "\u2022"
                story.append(Paragraph(inline(item), styles["bullet"],
                                       bulletText=bullet))
            story.append(Spacer(1, 3 * mm))
            continue

    story.extend(pending)
    return story


def main():
    md = SOURCE.read_text(encoding="utf-8")

    bad = []
    for name, ch in (("em dash", EM_DASH), ("en dash", EN_DASH)):
        if ch in md:
            bad.append("%s appears %d time(s) in %s"
                       % (name, md.count(ch), SOURCE.name))
    if bad:
        print("Dash check failed:")
        for b in bad:
            print("  " + b)
        return 1

    blocks = parse(md)

    refs = collect_references(blocks)
    problems = verify_references(refs)
    if problems:
        print("Reference check failed:")
        for p in problems:
            print("  " + p)
        return 1
    print("Reference check passed: %d cited test methods all resolve." % len(refs))

    styles = build_styles()
    doc = Doc(str(OUTPUT), pagesize=A4, title=TITLE, author="Chloe / %s" % TEAM,
              subject="Software Test Documentation")

    # Frames default to 6pt of padding on every side. Zeroing it makes the
    # declared frame width the real usable width, so a table sized to
    # PORTRAIT_W or LANDSCAPE_W lands exactly on the margin instead of 6pt
    # past it.
    padding = dict(leftPadding=0, rightPadding=0, topPadding=0,
                   bottomPadding=0)
    portrait_frame = Frame(20 * mm, 16 * mm, PORTRAIT_W, A4[1] - 34 * mm,
                           id="portrait", **padding)
    landscape_frame = Frame(12 * mm, 16 * mm, LANDSCAPE_W,
                            landscape(A4)[1] - 30 * mm, id="landscape",
                            **padding)
    doc.addPageTemplates([
        PageTemplate(id="portrait", frames=[portrait_frame], pagesize=A4,
                     onPage=page_furniture),
        PageTemplate(id="landscape", frames=[landscape_frame],
                     pagesize=landscape(A4), onPage=page_furniture),
    ])

    doc.multiBuild(build_story(blocks, styles))
    print("Wrote %s" % OUTPUT.relative_to(REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
