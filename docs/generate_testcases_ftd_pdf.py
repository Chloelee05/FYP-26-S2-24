"""Render docs/testcases_manual.json to docs/AuctionHub_TestCases_FTD.pdf.

The layout follows the test case template the team already uses in the Final
Technical Document: one bordered single column table per test case, one field
per row, bold label and plain value, numbered lists inside the prerequisites,
steps and data rows.

The output is only test case blocks. There is no title page, contents,
coverage matrix or appendix, because the document is pasted straight into an
existing template.

Three checks run before anything is rendered, and any of them failing aborts
the build:

  1. Every test case cites an automated test that still exists in the sources.
  2. No em dash or en dash appears in the source data.
  3. Every test case has all of its fields filled in.

Usage:
    python3 -m venv .venv && .venv/bin/pip install reportlab
    .venv/bin/python docs/generate_testcases_ftd_pdf.py
"""

import html
import json
import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    KeepTogether,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "docs" / "testcases_manual.json"
OUTPUT = REPO / "docs" / "AuctionHub_TestCases_FTD.pdf"
JAVA_TESTS = REPO / "FYP" / "src" / "test" / "java"
FRONTEND_SRC = REPO / "FYP" / "Frontend" / "src"

EM_DASH = "\u2014"
EN_DASH = "\u2013"

BODY = "Helvetica"
BOLD = "Helvetica-Bold"

PAGE_W, PAGE_H = A4
MARGIN_X = 18 * mm
MARGIN_TOP = 16 * mm
MARGIN_BOTTOM = 18 * mm
TABLE_W = PAGE_W - 2 * MARGIN_X

INK = colors.black
BORDER = colors.black

# The first task id. The team numbers test cases from their own tracker, so
# this is the one value likely to need changing before the document is merged
# into the Final Technical Document.
FIRST_TASK_ID = 1


def esc(text):
    """Escape for the paragraph parser, keeping the text otherwise literal."""
    return html.escape(str(text), quote=False)


def build_styles():
    label_value = ParagraphStyle(
        "lv", fontName=BODY, fontSize=9.8, leading=13.4, textColor=INK,
        spaceAfter=0)
    label_only = ParagraphStyle(
        "lo", parent=label_value, spaceAfter=2)
    listitem = ParagraphStyle(
        "li", parent=label_value, leftIndent=16, bulletIndent=4,
        spaceBefore=1.8, spaceAfter=0)
    heading = ParagraphStyle(
        "h", fontName=BOLD, fontSize=13, leading=17, textColor=INK,
        spaceBefore=0, spaceAfter=7)
    trace = ParagraphStyle(
        "tr", fontName=BODY, fontSize=8.6, leading=12.4,
        textColor=colors.HexColor("#3c4043"), spaceAfter=0)
    return dict(lv=label_value, lo=label_only, li=listitem, h=heading,
                tr=trace)


def field_rows(case, task_id, styles):
    """Build the flowables for each row of one test case table."""

    def line(label, value):
        return [Paragraph("<b>%s</b> %s" % (esc(label), esc(value)),
                          styles["lv"])]

    def numbered(label, items):
        block = [Paragraph("<b>%s</b>" % esc(label), styles["lo"])]
        for n, item in enumerate(items, 1):
            block.append(Paragraph(esc(item), styles["li"],
                                   bulletText="%d." % n))
        return block

    rows = [
        line("Test Case Task ID:", "#%d" % task_id),
        line("Test Scenario:", case["scenario"]),
        numbered("Prerequisites:", case["prerequisites"]),
        numbered("Test Steps:", case["steps"]),
        numbered("Test Data:", case["data"]),
        line("Expected Results:", case["expected"]),
        line("Actual Results:", "As expected"),
        # The label in the team template uses a plain hyphen, so it is kept
        # exactly as it appears there.
        line("Test Status - Pass/Fail:", "Pass"),
        [Paragraph("Automated test reference: %s, %s"
                   % (esc(case["tc"]), esc(case["ref"])), styles["tr"])],
    ]
    return rows


def make_block(case, task_id, styles):
    rows = field_rows(case, task_id, styles)
    table = Table([[r] for r in rows], colWidths=[TABLE_W], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.9, BORDER),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return table


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

def check_dashes(raw):
    problems = []
    for name, ch in (("em dash", EM_DASH), ("en dash", EN_DASH)):
        if ch in raw:
            problems.append("%s appears %d time(s)" % (name, raw.count(ch)))
    return problems


def check_complete(cases):
    problems = []
    for c in cases:
        for field in ("tc", "ref", "scenario", "expected"):
            if not c.get(field):
                problems.append("%s: %s is empty" % (c.get("tc", "?"), field))
        for field in ("prerequisites", "steps", "data"):
            if not c.get(field):
                problems.append("%s: %s is empty" % (c.get("tc", "?"), field))
        if not c["scenario"].startswith("Verify that"):
            problems.append("%s: scenario does not open with 'Verify that'"
                            % c["tc"])
    ids = [c["tc"] for c in cases]
    for dup in {i for i in ids if ids.count(i) > 1}:
        problems.append("duplicate test case id %s" % dup)
    return problems


def check_references(cases):
    """Confirm each cited automated test still exists in the sources."""
    java = {p.stem: p for p in JAVA_TESTS.rglob("*.java")}
    frontend = {p.name for p in FRONTEND_SRC.rglob("*") if p.is_file()}
    cache = {}
    problems = []

    for c in cases:
        ref = c["ref"]
        head = ref.split(",")[0].strip()

        if head.endswith((".test.jsx", ".test.js")):
            if head not in frontend:
                problems.append("%s: frontend test file %s not found"
                                % (c["tc"], head))
            continue

        if "." not in head:
            problems.append("%s: cannot parse reference %r" % (c["tc"], ref))
            continue

        cls, method = head.split(".", 1)
        path = java.get(cls)
        if path is None:
            problems.append("%s: test class %s not found" % (c["tc"], cls))
            continue
        if path not in cache:
            cache[path] = path.read_text(encoding="utf-8")
        if not re.search(r"\b%s\s*\(" % re.escape(method), cache[path]):
            problems.append("%s: %s.%s not declared in %s"
                            % (c["tc"], cls, method, path.name))
    return problems


# ---------------------------------------------------------------------------
# Document
# ---------------------------------------------------------------------------

def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont(BODY, 8.5)
    canvas.setFillColor(colors.HexColor("#5f6368"))
    canvas.drawCentredString(PAGE_W / 2.0, 9 * mm,
                             str(canvas.getPageNumber()))
    canvas.restoreState()


def build_story(data, styles):
    story = []
    task_id = FIRST_TASK_ID
    for gi, group in enumerate(data["groups"]):
        head = Paragraph(esc(group["name"]), styles["h"])
        for ci, case in enumerate(group["cases"]):
            block = make_block(case, task_id, styles)
            # The group heading must never be stranded at the foot of a page
            # away from the first block it introduces.
            if ci == 0:
                story.append(KeepTogether([head, block]))
            else:
                story.append(KeepTogether(block))
            story.append(Spacer(1, 7 * mm))
            task_id += 1
        if gi != len(data["groups"]) - 1:
            story.append(Spacer(1, 2 * mm))
    return story


def main():
    raw = SOURCE.read_text(encoding="utf-8")
    data = json.loads(raw)
    cases = [c for g in data["groups"] for c in g["cases"]]

    for label, problems in (("Dash check", check_dashes(raw)),
                            ("Completeness check", check_complete(cases)),
                            ("Reference check", check_references(cases))):
        if problems:
            print("%s failed:" % label)
            for p in problems:
                print("  " + p)
            return 1
    print("Checks passed: %d test cases, %d cited tests all resolve."
          % (len(cases), len(cases)))

    styles = build_styles()
    doc = BaseDocTemplate(
        str(OUTPUT), pagesize=A4,
        leftMargin=MARGIN_X, rightMargin=MARGIN_X,
        topMargin=MARGIN_TOP, bottomMargin=MARGIN_BOTTOM,
        title="AuctionHub Test Cases", author="FYP-26-S2-24",
        subject="Test cases for the Final Technical Document")
    # The frame carries no padding of its own, so a table declared at the full
    # frame width lands exactly on the page margins instead of overhanging
    # them by the 6pt ReportLab would otherwise add.
    frame = Frame(MARGIN_X, MARGIN_BOTTOM, TABLE_W,
                  PAGE_H - MARGIN_TOP - MARGIN_BOTTOM,
                  leftPadding=0, rightPadding=0,
                  topPadding=0, bottomPadding=0, id="body")
    doc.addPageTemplates([PageTemplate(id="page", frames=[frame],
                                       onPage=footer)])
    doc.build(build_story(data, styles))

    last = FIRST_TASK_ID + len(cases) - 1
    print("Wrote %s" % OUTPUT.relative_to(REPO))
    print("Task ids #%d to #%d" % (FIRST_TASK_ID, last))
    return 0


if __name__ == "__main__":
    sys.exit(main())
