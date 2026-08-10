"""
Generates a professionally formatted PDF from Cafe_Owner_Manual.md.
Requires: pip install reportlab
Usage: python generate_manual_pdf.py
"""
import os
import re
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, KeepTogether, HRFlowable
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.cidfonts import UnicodeCIDFont

# --- FONT REGISTRATION ---
# Register system fonts that support CJK, Tamil, Thai
FONTS_DIR = "C:\\Windows\\Fonts"

# Microsoft YaHei for Chinese
try:
    pdfmetrics.registerFont(TTFont('MSYaHei', os.path.join(FONTS_DIR, 'msyh.ttc'), subfontIndex=0))
except:
    pass

# Leelawadee for Thai
try:
    pdfmetrics.registerFont(TTFont('Leelawadee', os.path.join(FONTS_DIR, 'LEELAWAD.TTF')))
except:
    pass

# Nirmala UI for Tamil
try:
    pdfmetrics.registerFont(TTFont('NirmalaUI', os.path.join(FONTS_DIR, 'Nirmala.ttc'), subfontIndex=0))
except:
    pass

# Segoe UI as good Latin base
try:
    pdfmetrics.registerFont(TTFont('SegoeUI', os.path.join(FONTS_DIR, 'segoeui.ttf')))
    pdfmetrics.registerFont(TTFont('SegoeUI-Bold', os.path.join(FONTS_DIR, 'segoeuib.ttf')))
except:
    pass

# --- COLOR PALETTE ---
PRIMARY = colors.HexColor("#1A365D")       # Deep Slate Blue
SECONDARY = colors.HexColor("#2B6CB0")     # Accent Blue
ACCENT_BG = colors.HexColor("#EDF2F7")     # Subtle Cool Grey
TEXT_DARK = colors.HexColor("#2D3748")     # Charcoal Text
LINE_COLOR = colors.HexColor("#CBD5E0")    # Light Grey Border

# Status Colors
GREEN = colors.HexColor("#2F855A")
RED = colors.HexColor("#C53030")
BLUE = colors.HexColor("#2B6CB0")
YELLOW = colors.HexColor("#D69E2E")
ORANGE = colors.HexColor("#DD6B20")


class NumberedCanvas(canvas.Canvas):
    """Two-pass canvas to dynamically compute total pages and draw
    running headers and footers on every page except the title cover."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._saved_page_states = []

    def showPage(self):
        self._saved_page_states.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        num_pages = len(self._saved_page_states)
        for state in self._saved_page_states:
            self.__dict__.update(state)
            self.draw_header_footer(num_pages)
            super().showPage()
        super().save()

    def draw_header_footer(self, page_count):
        if self._pageNumber == 1:
            return  # Skip cover page
        self.saveState()
        self.setFont("Helvetica", 8)
        self.setFillColor(TEXT_DARK)
        # Running Header
        self.drawString(54, A4[1] - 36,
                        "Warung Tom Yam POS \u2014 Panduan Pemilik Kafe / Caf\u00e9 Owner Manual")
        self.setStrokeColor(LINE_COLOR)
        self.setLineWidth(0.5)
        self.line(54, A4[1] - 42, A4[0] - 54, A4[1] - 42)
        # Running Footer
        page_str = f"Halaman / Page {self._pageNumber} daripada {page_count}"
        self.drawRightString(A4[0] - 54, 36, page_str)
        self.drawString(54, 36, "RAZStudio \u00a9 All Rights Reserved")
        self.line(54, 48, A4[0] - 54, 48)
        self.restoreState()


def sanitize_text_for_pdf(text):
    """Replace emoji with text equivalents and wrap non-Latin scripts in font tags."""
    # Replace emojis with text equivalents
    emoji_map = {
        '\U0001f310': '[Globe]',        # 🌐
        '\U0001f3a8': '[Palette]',      # 🎨
        '\u26a0\ufe0f': '[!]',          # ⚠️
        '\u26a0': '[!]',               # ⚠ (without variation selector)
        '\U0001f4a1': '[i]',            # 💡
        '\U0001f9ea': '[TEST]',         # 🧪
        '\U0001f52c': '[ALPHA]',        # 🔬
        '\u2705': '[OK]',              # ✅
        '\u270f\ufe0f': '[Edit]',       # ✏️
        '\u270f': '[Edit]',            # ✏ (without variation selector)
        '\U0001f5d1\ufe0f': '[Del]',    # 🗑️
        '\U0001f5d1': '[Del]',          # 🗑 (without variation selector)
        '\U0001f4c2': '[Folder]',       # 📁
        '\U0001f9ee': '[Calc]',         # 🧮
        '\u22ee': '(...)',              # ⋮ vertical ellipsis (three-dot menu)
        '\u2026': '...',               # … horizontal ellipsis
        '\u2191': '^',                 # ↑
        '\u2193': 'v',                 # ↓
        '\u2713': '[v]',               # ✓
        '\u2717': '[x]',               # ✗
        '\u2022': '-',                 # • (bullet, handled by style but just in case)
        '\uff0b': '+',                 # ＋ fullwidth plus
    }
    for emoji, replacement in emoji_map.items():
        text = text.replace(emoji, replacement)

    # Wrap Chinese characters in MSYaHei font
    text = re.sub(r'([\u4e00-\u9fff\u3400-\u4dbf]+)',
                  r'<font face="MSYaHei">\1</font>', text)
    # Wrap Tamil characters in NirmalaUI font
    text = re.sub(r'([\u0b80-\u0bff]+)',
                  r'<font face="NirmalaUI">\1</font>', text)
    # Wrap Thai characters in Leelawadee font
    text = re.sub(r'([\u0e00-\u0e7f]+)',
                  r'<font face="Leelawadee">\1</font>', text)

    # Strip any remaining emoji/symbols outside BMP that would show as boxes
    # (keeps all Latin, CJK already wrapped, Tamil/Thai already wrapped)
    text = re.sub(r'[\U00010000-\U0010ffff]', '', text)
    # Strip orphan variation selectors
    text = text.replace('\ufe0f', '')

    return text


def create_callout_box(text, style, bg_color=ACCENT_BG, border_color=SECONDARY):
    """Renders a decorative callout box for warnings, tips, and notes."""
    p = Paragraph(text, style)
    t = Table([[p]], colWidths=[A4[0] - 108])
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, -1), bg_color),
        ('BOX', (0, 0), (-1, -1), 0.5, border_color),
        ('LINELEFT', (0, 0), (0, -1), 3.5, border_color),
        ('TOPPADDING', (0, 0), (-1, -1), 8),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
        ('LEFTPADDING', (0, 0), (-1, -1), 12),
        ('RIGHTPADDING', (0, 0), (-1, -1), 12),
    ]))
    return t


def parse_markdown_table(table_lines, base_style):
    """Converts raw Markdown tables into formatted ReportLab Tables with text wrapping."""
    rows = []
    for line in table_lines:
        if re.match(r'^\s*\|?\s*:?---', line):
            continue  # Skip separator line
        cells = [c.strip() for c in line.strip('|').split('|')]
        rows.append(cells)

    if not rows:
        return None

    num_cols = len(rows[0])
    usable_width = A4[0] - 108  # Margins 54 left/right
    col_widths = [usable_width / num_cols] * num_cols

    formatted_data = []
    for row_idx, row in enumerate(rows):
        formatted_row = []
        for cell in row:
            cell_fmt = cell.replace('**', '<b>', 1).replace('**', '</b>', 1)
            cell_fmt = re.sub(r'`([^`]+)`',
                              r'<font face="Courier" color="#C53030">\1</font>', cell_fmt)
            # Highlight status pills
            cell_fmt = cell_fmt.replace('Hijau (Green)',
                                        '<font color="#2F855A"><b>Hijau (Green)</b></font>')
            cell_fmt = cell_fmt.replace('Merah (Red)',
                                        '<font color="#C53030"><b>Merah (Red)</b></font>')
            cell_fmt = cell_fmt.replace('Biru (Blue)',
                                        '<font color="#2B6CB0"><b>Biru (Blue)</b></font>')
            cell_fmt = cell_fmt.replace('Kuning (Yellow)',
                                        '<font color="#D69E2E"><b>Kuning (Yellow)</b></font>')
            cell_fmt = cell_fmt.replace('\U0001f9ea Testing',
                                        '<font color="#DD6B20"><b>\U0001f9ea Testing</b></font>')
            cell_fmt = cell_fmt.replace('\U0001f52c Alpha',
                                        '<font color="#C53030"><b>\U0001f52c Alpha</b></font>')
            cell_fmt = cell_fmt.replace('\u2705 Production',
                                        '<font color="#2F855A"><b>\u2705 Production</b></font>')

            p_style = base_style
            if row_idx == 0:
                p_style = ParagraphStyle('HeaderStyle', parent=base_style,
                                         fontName='Helvetica-Bold', textColor=colors.white)
            formatted_row.append(Paragraph(cell_fmt, p_style))
        formatted_data.append(formatted_row)

    t = Table(formatted_data, colWidths=col_widths)
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), PRIMARY),
        ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('GRID', (0, 0), (-1, -1), 0.5, LINE_COLOR),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, ACCENT_BG]),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
    ]))
    return t


def generate_manual_pdf(markdown_text, output_filename="Warung_Tom_Yam_POS_Manual.pdf"):
    doc = SimpleDocTemplate(
        output_filename,
        pagesize=A4,
        leftMargin=54,
        rightMargin=54,
        topMargin=54,
        bottomMargin=54
    )

    styles = getSampleStyleSheet()

    # Custom Typography Styles
    style_title = ParagraphStyle('DocTitle', parent=styles['Normal'],
                                  fontName='Helvetica-Bold', fontSize=24,
                                  leading=28, textColor=PRIMARY, alignment=1)
    style_subtitle = ParagraphStyle('DocSubtitle', parent=styles['Normal'],
                                     fontName='Helvetica', fontSize=13,
                                     leading=17, textColor=SECONDARY, alignment=1)
    style_h1 = ParagraphStyle('H1', parent=styles['Normal'],
                               fontName='Helvetica-Bold', fontSize=15,
                               leading=19, textColor=PRIMARY,
                               spaceBefore=18, spaceAfter=8, keepWithNext=True)
    style_h2 = ParagraphStyle('H2', parent=styles['Normal'],
                               fontName='Helvetica-Bold', fontSize=12,
                               leading=16, textColor=SECONDARY,
                               spaceBefore=12, spaceAfter=6, keepWithNext=True)
    style_h3 = ParagraphStyle('H3', parent=styles['Normal'],
                               fontName='Helvetica-Bold', fontSize=10,
                               leading=14, textColor=TEXT_DARK,
                               spaceBefore=10, spaceAfter=4, keepWithNext=True)
    style_body = ParagraphStyle('BodyTextCustom', parent=styles['Normal'],
                                 fontName='Helvetica', fontSize=9,
                                 leading=13, textColor=TEXT_DARK,
                                 spaceBefore=2, spaceAfter=4)
    style_bullet = ParagraphStyle('BulletCustom', parent=style_body,
                                   leftIndent=12, bulletIndent=4,
                                   spaceBefore=1, spaceAfter=2)
    style_code = ParagraphStyle('CodeBlock', parent=styles['Normal'],
                                 fontName='Courier', fontSize=8,
                                 leading=11, textColor=TEXT_DARK)
    style_callout = ParagraphStyle('CalloutText', parent=style_body,
                                    fontSize=8.5, leading=12)

    story = []
    lines = markdown_text.split('\n')
    i = 0
    in_table = False
    table_lines = []
    in_codeblock = False
    code_lines = []

    while i < len(lines):
        line = lines[i]

        # Code Block Processing
        if line.strip().startswith('```'):
            if in_codeblock:
                code_text = "<br/>".join(code_lines)
                p = Paragraph(code_text, style_code)
                t = Table([[p]], colWidths=[A4[0] - 108])
                t.setStyle(TableStyle([
                    ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor("#F7FAFC")),
                    ('BOX', (0, 0), (-1, -1), 0.5, LINE_COLOR),
                    ('PADDING', (0, 0), (-1, -1), 8),
                ]))
                story.append(Spacer(1, 4))
                story.append(t)
                story.append(Spacer(1, 6))
                code_lines = []
                in_codeblock = False
            else:
                in_codeblock = True
            i += 1
            continue

        if in_codeblock:
            safe_line = (line.replace(' ', '&nbsp;')
                            .replace('<', '&lt;')
                            .replace('>', '&gt;'))
            code_lines.append(safe_line)
            i += 1
            continue

        # Markdown Table Processing
        if '|' in line and not line.strip().startswith('>'):
            in_table = True
            table_lines.append(line)
            i += 1
            continue
        elif in_table:
            t = parse_markdown_table(table_lines, style_body)
            if t:
                story.append(Spacer(1, 4))
                story.append(t)
                story.append(Spacer(1, 6))
            table_lines = []
            in_table = False

        # Decorative markdown lines
        if line.strip() == '---':
            story.append(HRFlowable(width="100%", thickness=0.5,
                                     color=LINE_COLOR, spaceBefore=8, spaceAfter=8))
            i += 1
            continue

        # Document Header
        if line.startswith('# Warung Tom Yam POS'):
            title_text = line.replace('# ', '').replace('\u2014', '<br/>\u2014<br/>')
            story.append(Spacer(1, 15))
            story.append(Paragraph(title_text, style_title))
            story.append(Spacer(1, 8))
            story.append(Paragraph(
                "Sistem Pengurusan Kafe & Restoran / Complete Operational Guide",
                style_subtitle))
            story.append(Spacer(1, 15))
            story.append(HRFlowable(width="100%", thickness=1.5,
                                     color=PRIMARY, spaceBefore=5, spaceAfter=15))
            i += 1
            continue

        # Section Headings
        if line.startswith('## '):
            story.append(Paragraph(line.replace('## ', ''), style_h1))
            i += 1
            continue
        elif line.startswith('### '):
            story.append(Paragraph(line.replace('### ', ''), style_h2))
            i += 1
            continue
        elif line.startswith('#### '):
            story.append(Paragraph(line.replace('#### ', ''), style_h3))
            i += 1
            continue

        # Callouts / Blockquotes
        if line.strip().startswith('>'):
            callout_text = line.strip('> ').strip()
            callout_text = callout_text.replace('**', '<b>', 1).replace('**', '</b>', 1)
            callout_text = re.sub(r'`([^`]+)`',
                                  r'<font face="Courier">\1</font>', callout_text)

            bg = ACCENT_BG
            border = SECONDARY
            if '\u26a0\ufe0f' in callout_text:
                bg = colors.HexColor("#FFF5F5")
                border = RED
            elif '\U0001f4a1' in callout_text:
                bg = colors.HexColor("#FFFFF0")
                border = YELLOW

            story.append(Spacer(1, 4))
            story.append(create_callout_box(callout_text, style_callout, bg, border))
            story.append(Spacer(1, 4))
            i += 1
            continue

        # Bullet and Numbered Lists
        if re.match(r'^\s*[-*]\s+', line) or re.match(r'^\s*\d+\.\s+', line):
            fmt_line = re.sub(r'^\s*[-*]\s+', '\u2022 ', line)
            fmt_line = re.sub(r'^\s*(\d+\.)\s+', r'\1 ', fmt_line)
            fmt_line = fmt_line.replace('**', '<b>', 1).replace('**', '</b>', 1)
            fmt_line = re.sub(r'`([^`]+)`',
                              r'<font face="Courier" color="#C53030">\1</font>', fmt_line)
            story.append(Paragraph(fmt_line, style_bullet))
            i += 1
            continue

        # Standard Paragraphs
        if line.strip():
            fmt_line = line.replace('**', '<b>', 1).replace('**', '</b>', 1)
            fmt_line = re.sub(r'`([^`]+)`',
                              r'<font face="Courier" color="#C53030">\1</font>', fmt_line)
            story.append(Paragraph(fmt_line, style_body))

        i += 1

    # Catch remaining open table
    if in_table and table_lines:
        t = parse_markdown_table(table_lines, style_body)
        if t:
            story.append(t)

    doc.build(story, canvasmaker=NumberedCanvas)
    print(f"\u2705 Successfully generated PDF: {output_filename}")


if __name__ == "__main__":
    # Read from the actual markdown file
    script_dir = os.path.dirname(os.path.abspath(__file__))
    md_path = os.path.join(script_dir, "Cafe_Owner_Manual.md")

    if not os.path.exists(md_path):
        print(f"\u274c Error: Cannot find {md_path}")
        print("Make sure Cafe_Owner_Manual.md is in the same directory as this script.")
        exit(1)

    with open(md_path, "r", encoding="utf-8") as f:
        markdown_data = f.read()

    # Pre-process: sanitize emojis and wrap CJK/Tamil/Thai for font rendering
    markdown_data = sanitize_text_for_pdf(markdown_data)

    output_path = os.path.join(script_dir, "Warung_Tom_Yam_POS_Manual.pdf")
    generate_manual_pdf(markdown_data, output_path)
