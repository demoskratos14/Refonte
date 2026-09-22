# -*- coding: utf-8 -*-
"""
STORIES - registre des histoires disponibles dans l'application
=================================================================
Chaque histoire partage EXACTEMENT le meme moteur de jeu (dice_engine.py)
et la meme interface -- seuls changent, par histoire :
  - l'image de fond,
  - le fichier de sauvegarde (chaque histoire garde sa propre partie),
  - le roster de totems de depart,
  - le texte de contexte specifique a l'univers, envoye a l'IA narratrice.

La cle API Mistral, elle, N'EST PAS ici : elle est partagee entre toutes
les histoires (voir app_config.json / load_app_config()), puisqu'un seul
compte gratuit suffit largement.

Il n'y a plus d'histoire codee en dur ici : STORIES demarre vide, et
TOUTES les histoires (y compris d'anciennes histoires "d'origine")
passent desormais par le meme mecanisme que les histoires personnalisees
-- creation depuis l'application (create_custom_story) ou reinjection
d'un fichier d'identite exporte (parse_identity_import / do_import_identity
dans game_api.py). Une premiere installation demarre donc sur un
carrousel vide (juste la tuile "Nouvelle histoire") tant qu'aucune
histoire n'a ete creee ou reimportee.
"""

import base64
import json
import os
import re

import image_utils


# ---------------------------------------------------------------------
# Registre des histoires codees en dur -- volontairement vide (voir
# note ci-dessus). Toute histoire passe par create_custom_story() /
# _load_custom_meta() plus bas.
# ---------------------------------------------------------------------

STORIES = {}

STORY_ORDER = []



# ---------------------------------------------------------------------
# Histoires creees a la volee depuis l'application (page "Nouvelle
# histoire" du selecteur) ou reimportees depuis un fichier d'identite
# exporte (voir parse_identity_import plus bas). STORIES etant vide (voir
# note en tete de fichier), c'est desormais l'unique mecanisme : une
# histoire est decrite par un simple fichier JSON (CUSTOM_STORIES_FILE) +
# son image de fond enregistree a part sur le disque (CUSTOM_STORY_BG_DIR)
# -- tout est
# relatif au repertoire de travail courant, comme dice_state*.json ou
# app_config.json (voir dice_web.py). L'image du premier totem, elle,
# passe par le mecanisme EXISTANT des totems ajoutes en cours de partie
# (totem_images/, voir _save_totem_image() dans dice_web.py) : seul son
# nom de fichier est garde ici, et c'est switch_story() qui l'ajoute comme
# totem de depart (avec sa propre jauge) au tout premier lancement de
# cette histoire.
# ---------------------------------------------------------------------

CUSTOM_STORIES_FILE = "custom_stories.json"
CUSTOM_STORY_BG_DIR = "custom_story_bg"
ALLOWED_BG_IMAGE_EXTS = {"jpg", "jpeg", "png", "webp", "gif"}


def _slugify_story_title(title):
    base = re.sub(r"[^a-z0-9]+", "_", (title or "").strip().lower()).strip("_")
    base = base or "histoire"
    existing = set(STORIES.keys()) | {m["slug"] for m in _load_custom_meta()}
    slug = base
    n = 2
    while slug in existing:
        slug = f"{base}_{n}"
        n += 1
    return slug


def _load_custom_meta():
    """Liste des histoires personnalisees, dans leur ordre de creation.
    Chaque entree est un petit dict de metadonnees (pas encore l'image de
    fond decodee -- voir _build_story_entry pour ca)."""
    if os.path.exists(CUSTOM_STORIES_FILE):
        try:
            with open(CUSTOM_STORIES_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                return [m for m in data if isinstance(m, dict) and m.get("slug")]
        except (OSError, ValueError):
            pass
    return []


def _save_custom_meta(meta_list):
    with open(CUSTOM_STORIES_FILE, "w", encoding="utf-8") as f:
        json.dump(meta_list, f, ensure_ascii=False, indent=2)


def create_custom_story(title, subtitle, lore_text, bg_image_bytes, bg_image_ext,
                         totem_label, totem_image_filename,
                         totem_powers="", totem_special="", protagonist_name=""):
    """Cree une nouvelle histoire : enregistre son image de fond sur le
    disque et ajoute une entree dans CUSTOM_STORIES_FILE. Renvoie le slug
    attribue (utilise ensuite par dice_web.switch_story() pour y basculer
    immediatement).

    totem_powers / totem_special sont optionnels et suivent exactement le
    meme format que pour un totem ajoute en cours de partie (voir
    /add_custom_totem dans dice_web.py) : powers_text est une chaine de
    pouvoirs separes par des virgules, special est une capacite unique en
    texte libre.

    protagonist_name (optionnel) : le prenom/nom du heros, pour que l'IA
    s'adresse a lui par son nom plutot que par la formule generique "le
    personnage principal" -- voir _build_story_entry() plus bas, qui
    construit protagonist_ref a partir de ce champ. Laisser vide
    reproduit le comportement d'avant (utile par exemple si le prenom
    doit etre demande au joueur en cours d'aventure plutot que fixe a la
    creation)."""
    title = (title or "").strip() or "Nouvelle histoire"
    slug = _slugify_story_title(title)

    os.makedirs(CUSTOM_STORY_BG_DIR, exist_ok=True)
    # Redimensionnee/recompressee ici : une photo de telephone non
    # retouchee etait la cause du "trop grosse pour etre visible" sur les
    # histoires creees depuis l'application. Toujours reencodee en JPEG
    # par ce traitement.
    bg_image_bytes = image_utils.resize_bg_bytes(bg_image_bytes)
    ext = "jpg"
    bg_filename = f"{slug}.{ext}"
    with open(os.path.join(CUSTOM_STORY_BG_DIR, bg_filename), "wb") as f:
        f.write(bg_image_bytes)

    meta = {
        "slug": slug,
        "title": title,
        "subtitle": (subtitle or "").strip(),
        "lore_text": (lore_text or "").strip(),
        "bg_image_file": bg_filename,
        "totem_label": (totem_label or "Totem de depart").strip(),
        "totem_image_filename": totem_image_filename,
        "totem_powers": (totem_powers or "").strip(),
        "totem_special": (totem_special or "").strip(),
        "protagonist_name": (protagonist_name or "").strip(),
        "save_file": f"dice_state_{slug}.json",
    }
    meta_list = _load_custom_meta()
    meta_list.append(meta)
    _save_custom_meta(meta_list)
    return slug


def delete_custom_story(slug):
    """Supprime definitivement une histoire personnalisee : son entree
    dans CUSTOM_STORIES_FILE, son image de fond, sa sauvegarde de partie
    (dice_state_<slug>.json) et l'image de son totem de depart si elle en
    avait une.

    Ne touche qu'aux histoires personnalisees (toutes les histoires,
    d'ailleurs, passent desormais par ce meme registre -- voir la note en
    tete de fichier) : si le slug fourni ne correspond a aucune entree de
    CUSTOM_STORIES_FILE (slug inconnu), ne fait rien et renvoie False.
    Renvoie True si une histoire a bien ete supprimee."""
    meta_list = _load_custom_meta()
    meta = next((m for m in meta_list if m["slug"] == slug), None)
    if meta is None:
        return False

    meta_list = [m for m in meta_list if m["slug"] != slug]
    _save_custom_meta(meta_list)

    bg_file = meta.get("bg_image_file")
    if bg_file:
        bg_path = os.path.join(CUSTOM_STORY_BG_DIR, bg_file)
        if os.path.exists(bg_path):
            os.remove(bg_path)

    save_file = meta.get("save_file")
    if save_file and os.path.exists(save_file):
        os.remove(save_file)

    # L'image du totem de depart est enregistree dans totem_images/ (voir
    # _save_totem_image() dans dice_web.py), pas dans CUSTOM_STORY_BG_DIR --
    # on la supprime aussi, puisqu'elle a ete creee specifiquement pour le
    # totem de depart de CETTE histoire (jamais partagee avec une autre).
    totem_image_filename = meta.get("totem_image_filename")
    if totem_image_filename:
        totem_image_path = os.path.join("totem_images", totem_image_filename)
        if os.path.exists(totem_image_path):
            os.remove(totem_image_path)

    return True


def is_custom_story(slug):
    """Vrai si slug correspond a une histoire existante (creee depuis
    l'application ou reimportee), donc supprimable -- faux pour un slug
    inconnu."""
    return any(m["slug"] == slug for m in _load_custom_meta())


def _build_story_entry(meta):
    """Reconstruit une entree au meme format que celles de STORIES a
    partir des metadonnees d'une histoire personnalisee -- appele a
    chaque fois qu'on a besoin de la liste complete des histoires (voir
    all_stories()), jamais mis en cache : l'image de fond est relue et
    reencodee a chaque fois, mais ca ne se produit qu'au chargement d'une
    page (selecteur, changement d'histoire), jamais a chaque requete de
    jeu."""
    bg_b64 = ""
    bg_path = os.path.join(CUSTOM_STORY_BG_DIR, meta.get("bg_image_file", ""))
    if meta.get("bg_image_file") and os.path.exists(bg_path):
        with open(bg_path, "rb") as f:
            bg_b64 = base64.b64encode(f.read()).decode("ascii")

    lore_text = meta.get("lore_text") or ""

    return {
        "slug": meta["slug"],
        "title": meta.get("title") or meta["slug"],
        "header_title": meta.get("title") or meta["slug"],
        "subtitle": meta.get("subtitle") or "",
        "save_file": meta.get("save_file") or f"dice_state_{meta['slug']}.json",
        "bg_image_b64": bg_b64,
        "thumbnail_b64": None,
        "pip_symbols": {},
        "totems": [],
        "default_pip_symbol": "",
        "protagonist_ref": (meta.get("protagonist_name") or "").strip() or "le personnage principal",
        "fixed_allies_line": "",
        "ally_help_text": {},
        "is_custom": True,
        # La description d'univers fournie a la creation constitue tout le
        # contexte narratif propre a cette histoire ; elle s'ajoute
        # simplement a la mecanique de jeu commune (des, jauges,
        # menace...) deja envoyee a l'IA.
        "lore_paragraphs": [lore_text] if lore_text else [],
        "seed_state_file": None,
        # Totem de depart (voir switch_story() dans dice_web.py) : ajoute
        # automatiquement comme totem personnalise au tout premier
        # lancement de cette histoire, avec sa propre image comme
        # "constellation" affichee sur le de de reussite.
        "default_totem": {
            "label": meta.get("totem_label") or "Totem de depart",
            "image_filename": meta.get("totem_image_filename"),
            "powers_text": meta.get("totem_powers") or "",
            "special": meta.get("totem_special") or "",
        },
    }


def all_stories():
    """Fusionne STORIES (registre code en dur, vide desormais -- voir
    note en tete de fichier) et les histoires creees/reimportees par le
    joueur depuis l'application. A utiliser partout ou stories.STORIES
    etait utilise directement pour lister/retrouver une histoire."""
    merged = dict(STORIES)
    for meta in _load_custom_meta():
        merged[meta["slug"]] = _build_story_entry(meta)
    return merged


def all_story_order():
    """Comme STORY_ORDER, mais en y ajoutant les histoires personnalisees
    a la suite, dans leur ordre de creation."""
    return STORY_ORDER + [m["slug"] for m in _load_custom_meta()]


def export_story_identity(slug, extra_totems=None, side_quests=None,
                           next_quest_id=None, story_log=None, story_summary=None):
    """Empaquete tout ce qu'il faut pour recreer une histoire personnalisee
    ailleurs (autre appareil, sauvegarde manuelle...) en un seul dict
    JSON-serialisable -- l'inverse exact de parse_identity_import()
    ci-dessous. Renvoie None si slug ne correspond a aucune histoire
    connue (slug inconnu, ou histoire supprimee entre-temps).

    Tous les parametres optionnels ci-dessous viennent de la session en
    cours (game_api.export_identity() les lit sur `session`, que ce
    module n'a pas -- stories.py ne connait que les metadonnees figees de
    l'histoire) :
    - extra_totems : les totems acquis en cours de partie, en plus du
      totem de depart deja porte par totem_label/totem_powers/... plus
      bas. Chaque element : {label, emoji, powers (liste), special,
      image_b64}. Ne change RIEN a la creation d'une histoire (un seul
      totem de depart reste possible via do_create_story) : ces totems
      supplementaires sont recrees a part, apres coup, par
      do_add_custom_totem (voir do_import_identity).
    - side_quests / next_quest_id : les quetes secondaires de la partie
      (ouvertes ou terminees).
    - story_log / story_summary : le journal (un chapitre par entree) et
      le resume long terme qui l'accompagne, tels que produits par le
      "digest" de l'IA narratrice.

    protagonist_name (lu directement sur meta, pas en parametre : c'est
    une metadonnee figee de l'histoire, comme totem_label) : le prenom du
    heros, si renseigne a la creation -- voir create_custom_story() plus
    haut. Permet a l'IA de s'adresser a lui par son nom (protagonist_ref)
    plutot que par la formule generique "le personnage principal"."""
    meta = next((m for m in _load_custom_meta() if m["slug"] == slug), None)
    if meta is None:
        return None

    bg_b64 = ""
    bg_path = os.path.join(CUSTOM_STORY_BG_DIR, meta.get("bg_image_file", ""))
    if meta.get("bg_image_file") and os.path.exists(bg_path):
        with open(bg_path, "rb") as f:
            bg_b64 = base64.b64encode(f.read()).decode("ascii")

    totem_b64 = ""
    totem_filename = meta.get("totem_image_filename")
    if totem_filename:
        totem_path = os.path.join("totem_images", totem_filename)
        if os.path.exists(totem_path):
            with open(totem_path, "rb") as f:
                totem_b64 = base64.b64encode(f.read()).decode("ascii")

    return {
        "format": "des-aventure-identite-v1",
        "title": meta.get("title") or "",
        "subtitle": meta.get("subtitle") or "",
        "lore_text": meta.get("lore_text") or "",
        "totem_label": meta.get("totem_label") or "",
        "totem_powers": meta.get("totem_powers") or "",
        "totem_special": meta.get("totem_special") or "",
        "protagonist_name": meta.get("protagonist_name") or "",
        "bg_image_b64": bg_b64,
        "totem_image_b64": totem_b64,
        "extra_totems": extra_totems or [],
        "side_quests": side_quests or [],
        "next_quest_id": next_quest_id if next_quest_id is not None else 1,
        "story_log": story_log or [],
        "story_summary": story_summary or "",
    }


def parse_identity_import(raw_text):
    """Inverse d'export_story_identity() : relit le JSON colle par le
    joueur (voir do_import_identity dans game_api.py) et renvoie un dict
    aux memes cles (title, subtitle, lore_text, totem_label, totem_powers,
    totem_special, bg_image_b64, totem_image_b64, extra_totems,
    side_quests, next_quest_id, story_log, story_summary). Tolerant : un
    JSON invalide, incomplet, ou qui n'est pas un objet renvoie un dict
    aux champs vides plutot que de lever une exception -- l'appelant n'a
    pas a se soucier du cas d'erreur, juste a verifier que les champs
    attendus sont bien remplis avant de les utiliser."""
    try:
        data = json.loads(raw_text)
    except (ValueError, TypeError):
        data = {}
    if not isinstance(data, dict):
        data = {}

    extra_totems = []
    for t in (data.get("extra_totems") or []):
        if not isinstance(t, dict):
            continue
        label = (t.get("label") or "").strip()
        if not label:
            continue
        powers = [p.strip() for p in (t.get("powers") or []) if isinstance(p, str) and p.strip()]
        extra_totems.append({
            "label": label,
            "emoji": str(t.get("emoji") or "").strip(),
            "powers": powers,
            "special": str(t.get("special") or "").strip(),
            "image_b64": t.get("image_b64") or "",
        })

    side_quests = [
        q for q in (data.get("side_quests") or [])
        if isinstance(q, dict) and "id" in q and "kind" in q and "status" in q
    ]
    try:
        next_quest_id = int(data.get("next_quest_id"))
    except (TypeError, ValueError):
        next_quest_id = (max((q["id"] for q in side_quests), default=0) + 1)

    story_log = [s for s in (data.get("story_log") or []) if isinstance(s, str) and s.strip()]
    story_summary = data.get("story_summary")
    story_summary = story_summary.strip() if isinstance(story_summary, str) else ""

    return {
        "title": (data.get("title") or "").strip(),
        "subtitle": (data.get("subtitle") or "").strip(),
        "lore_text": (data.get("lore_text") or "").strip(),
        "totem_label": (data.get("totem_label") or "").strip(),
        "totem_powers": (data.get("totem_powers") or "").strip(),
        "totem_special": (data.get("totem_special") or "").strip(),
        "protagonist_name": (data.get("protagonist_name") or "").strip(),
        "bg_image_b64": data.get("bg_image_b64") or "",
        "totem_image_b64": data.get("totem_image_b64") or "",
        "extra_totems": extra_totems,
        "side_quests": side_quests,
        "next_quest_id": next_quest_id,
        "story_log": story_log,
        "story_summary": story_summary,
    }
