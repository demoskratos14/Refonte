# -*- coding: utf-8 -*-
"""
journal_export.py - export du journal de l'histoire (session.story_log,
une entree = un chapitre) en PDF.

Ce fichier etait mentionne par le plan de migration (game_api.export_journal,
etape 8 du plan) mais n'existait pas encore : le voici. Utilise fpdf2
(pip : "fpdf2", import : "fpdf") -- c'est la dependance que l'etape 8 du
plan demande explicitement de GARDER dans app/build.gradle meme apres
avoir retire flask, précisément pour ce fichier.

Si fpdf2 n'est pas installe/embarque : fpdf_available() renvoie False et
generate_journal_pdf() renvoie None. game_api.export_journal() se rabat
alors sur un export texte brut (session.story_log_text(), dice_engine.py) --
jamais d'erreur cote joueur, juste un fichier .txt au lieu d'un .pdf.
"""

try:
    from fpdf import FPDF
    _FPDF_AVAILABLE = True
except ImportError:
    _FPDF_AVAILABLE = False


def fpdf_available():
    return _FPDF_AVAILABLE


def _safe_text(text):
    """Les polices "core" de fpdf2 (Helvetica...) n'acceptent que du
    latin-1 : on remplace les caracteres hors de cette plage (emoji,
    guillemets typographiques...) plutot que de faire planter l'export
    entier pour un seul caractere non supporte."""
    return (text or "").encode("latin-1", errors="replace").decode("latin-1")


def generate_journal_pdf(title, subtitle, story_log):
    """Genere le PDF du journal, un chapitre par entree de story_log
    (dans l'ordre). Renvoie les octets du PDF, ou None si fpdf2 n'est
    pas disponible (voir fpdf_available()) ou s'il n'y a rien a
    exporter -- dans les deux cas, l'appelant doit se rabattre sur un
    export texte."""
    if not _FPDF_AVAILABLE or not story_log:
        return None

    pdf = FPDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=18)
    pdf.add_page()

    pdf.set_font("Helvetica", "B", 18)
    pdf.multi_cell(0, 10, _safe_text(title or "Journal de l'histoire"))
    if subtitle:
        pdf.set_font("Helvetica", "I", 11)
        pdf.set_text_color(90, 90, 90)
        pdf.multi_cell(0, 7, _safe_text(subtitle))
        pdf.set_text_color(0, 0, 0)
    pdf.ln(4)

    for i, entry in enumerate(story_log, start=1):
        pdf.set_font("Helvetica", "B", 13)
        pdf.multi_cell(0, 8, _safe_text(f"Chapitre {i}"))
        pdf.set_font("Helvetica", "", 11)
        pdf.multi_cell(0, 6, _safe_text(entry))
        pdf.ln(4)

    return bytes(pdf.output())
