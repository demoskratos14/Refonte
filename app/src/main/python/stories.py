# -*- coding: utf-8 -*-
"""
STORIES - registre des histoires disponibles dans l'application
=================================================================
Chaque histoire partage EXACTEMENT le meme moteur de jeu (dice_engine.py)
et la meme interface (dice_web.py) -- seuls changent, par histoire :
  - l'image de fond,
  - le fichier de sauvegarde (chaque histoire garde sa propre partie),
  - le roster de totems de depart (Animorph part avec les siens deja
    acquis ; Poudlard part de zero, tout se construit en jouant),
  - le texte de contexte specifique a l'univers, envoye a l'IA narratrice.

La cle API Mistral, elle, N'EST PAS ici : elle est partagee entre toutes
les histoires (voir app_config.json / load_app_config() dans dice_web.py),
puisqu'un seul compte gratuit suffit largement pour les deux.

Ajouter une troisieme histoire plus tard = ajouter une entree ici, sans
toucher au reste du code.
"""

import base64
import json
import os
import re

import image_utils
from bg_animorph_data import BG_IMAGE_B64 as _ANIMORPH_BG
from bg_poudlard_data import BG_IMAGE_B64 as _POUDLARD_BG

# Toute image de fond integree passe desormais par le meme traitement
# (mise au format portrait + redimensionnement) que celui applique aux
# histoires personnalisees (voir image_utils.py et create_custom_story()
# plus bas) -- ne change rien pour une image deja au bon format (Animorph),
# et complete automatiquement en portrait une image trop "large" (ancien
# cas de Poudlard) sans recadrer ni perdre de composition.
_ANIMORPH_BG = image_utils.resize_bg_b64(_ANIMORPH_BG)
_POUDLARD_BG = image_utils.resize_bg_b64(_POUDLARD_BG)


# ---------------------------------------------------------------------
# ANIMORPH (histoire d'origine)
# ---------------------------------------------------------------------

ANIMORPH_PIP_SYMBOLS = {
    "araignee":    {"emoji": "\U0001f577\ufe0f", "label": "Araignee (Spider-Man)"},
    "aigle":       {"emoji": "\U0001f985", "label": "Aigle"},
    "loup":        {"emoji": "\U0001f43a", "label": "Loup"},
    "renard":      {"emoji": "\U0001f98a", "label": "Renard"},
    "jaguar":      {"emoji": "\U0001f406", "label": "Jaguar"},
    "bond":        {"emoji": "\U0001f998", "label": "Grand Bond"},
    "profondeurs": {"emoji": "\U0001f42c", "label": "Profondeurs"},
    "patte":       {"emoji": "\U0001f43e", "label": "Blason d'Animorph"},
    "bouclier":    {"emoji": "\U0001f6e1\ufe0f", "label": "Bouclier (Captain America)"},
    "etoile":      {"emoji": "\U0001f31f", "label": "Etoile (Shuri / Wakanda)"},
}

ANIMORPH_TOTEMS = [
    {
        "key": "aigle", "icon": "\U0001f985", "label": "Aigle \u2014 Maitre des Courants",
        "powers": ["Vol", "Vision exceptionnelle", "Grande vitesse aerienne",
                   "Controle des courants d'air", "Creation de rafales",
                   "Vol plus precis et rapide"],
        "special": "\U0001f32c\ufe0f Ascension Absolue \u2014 Permet de monter tres haut "
                   "et d'observer toute une zone depuis le ciel.",
    },
    {
        "key": "loup", "icon": "\U0001f43a", "label": "Loup",
        "powers": ["Grande force", "Endurance", "Odorat tres developpe",
                   "Instinct de protection", "Detection et suivi de pistes"],
        "special": "",
    },
    {
        "key": "renard", "icon": "\U0001f98a", "label": "Renard",
        "powers": ["Agilite", "Discretion", "Ruse", "Precision",
                   "Intelligence tactique"],
        "special": "",
    },
    {
        "key": "jaguar", "icon": "\U0001f406", "label": "Jaguar / Jaguar Astral",
        "powers": ["Vision nocturne parfaite", "Intuition du danger",
                   "Perception spirituelle", "Communication mentale avec Gabin",
                   "Conseils et guidance"],
        "special": "\U0001f30c Fureur Astrale \u2014 Une fois par aventure, augmente "
                   "fortement les reflexes, la vitesse et la lucidite de Gabin.",
    },
    {
        "key": "bond", "icon": "\U0001f998", "label": "Totem du Grand Bond",
        "powers": ["Sauts extremement hauts", "Sauts extremement longs",
                   "Atterrissages maitrises", "Grande mobilite",
                   "Possibilite de transporter un allie pendant un bond"],
        "special": "",
    },
    {
        "key": "profondeurs", "icon": "\U0001f42c", "label": "Totem des Profondeurs",
        "powers": ["Respiration sous l'eau", "Grande vitesse aquatique",
                   "Resistance a la pression", "Perception des vibrations sous-marines",
                   "Echo-sens"],
        "special": "\U0001f30a Vague Primordiale \u2014 Creation d'une puissante onde "
                   "aquatique pouvant repousser des ennemis et modifier les courants.",
    },
]

ANIMORPH_FIXED_ALLIES_LINE = (
    "\U0001f577\ufe0fAraignee=allie Spider-Man | \U0001f6e1\ufe0fBouclier=allie "
    "Captain America | \U0001f31fEtoile=allie Shuri (Wakanda) | "
    "\U0001f43ePatte (Blason d'Animorph)=pas d'allie exterieur, declenche "
    "'Second Souffle' (relance immediatement le dernier lancer de reussite)."
)

ANIMORPH_ALLY_HELP_TEXT = {
    "araignee": "\U0001f577\ufe0f Spider-Man intervient a vos cotes !",
    "bouclier": "\U0001f6e1\ufe0f Captain America vient preter main-forte !",
    "etoile": "\U0001f31f Shuri envoie une aide high-tech depuis le Wakanda !",
}

ANIMORPH_LORE_PARAGRAPHS = [
    "UNIVERS : l'aventure se deroule dans l'univers Marvel. Tu peux y faire "
    "intervenir d'autres personnages Marvel au fil de l'histoire (nouvelles "
    "rencontres, alliances ponctuelles), en plus des trois allies deja lies "
    "a un symbole fixe (Araignee=Spider-Man, Bouclier=Captain America, "
    "Etoile=Shuri). Tous les totems listes ci-dessus sont deja acquis par "
    "Gabin des le debut de l'aventure (ce ne sont pas des decouvertes a "
    "venir).",

    "NOUVEAUX TOTEMS : quand une quete secondaire de type 'objet' est menee "
    "a terme, c'est l'occasion ideale d'inventer la rencontre d'un nouveau "
    "totem animal pour Gabin (nom, apparence, pouvoirs de ton invention) -- "
    "raconte cette decouverte dans l'histoire. Le joueur l'ajoutera ensuite "
    "lui-meme dans l'application une fois le chapitre termine (nouvelle "
    "jauge, image...) : tu n'as donc rien a gerer mecaniquement pour lui, "
    "juste a le raconter. S'il apparait plus tard dans la liste des "
    "TOTEMS/ALLIES ci-dessus (le joueur l'aura alors ajoute), integre-le "
    "naturellement a partir de ce moment-la, sans revenir sur les chapitres "
    "precedents ou il n'existait pas encore.",
]


# ---------------------------------------------------------------------
# POUDLARD (nouvelle histoire)
# ---------------------------------------------------------------------

# Un seul totem de depart : la baguette magique, recuperee des l'arrivee
# a Poudlard. Tous les autres se construisent en jouant, via le meme
# systeme de totems personnalises que sur Animorph (voir add_custom_totem
# dans dice_engine.py) -- utilise ici pour les amis, les creatures
# fantastiques ET les sorts appris en cours.
POUDLARD_PIP_SYMBOLS = {
    "baguette": {"emoji": "\U0001fa84", "label": "Baguette Magique"},
}
POUDLARD_TOTEMS = [
    {
        "key": "baguette", "icon": "\U0001fa84", "label": "Baguette Magique",
        "powers": ["premiers sorts", "concentration magique"],
        "special": "Second Sort \u2014 relance immediatement le dernier lancer de reussite.",
    },
]
POUDLARD_FIXED_ALLIES_LINE = ""

POUDLARD_LORE_PARAGRAPHS = [
    "UNIVERS : l'aventure se deroule dans l'univers de Harry Potter. Elle "
    "commence par l'arrivee du personnage a Poudlard, jusqu'a sa "
    "repartition par le Choixpeau magique dans l'une des 4 maisons "
    "(Gryffondor, Serdaigle, Poufsouffle, Serpentard). Si le prenom du "
    "personnage n'est pas encore connu au moment ou tu ecris cette scene, "
    "demande-le au joueur avant la repartition (par exemple via une "
    "question posee par un professeur ou par le Choixpeau lui-meme), "
    "plutot que d'en inventer un toi-meme.",

    "BAGUETTE MAGIQUE : des son arrivee (par exemple chez un marchand de "
    "baguettes), le personnage recoit sa baguette -- c'est son premier "
    "totem, deja acquis, ne l'invente pas comme une decouverte plus tard. "
    "Sa jauge (Second Sort) permet, une fois pleine, de relancer "
    "immediatement le dernier lancer de reussite. IMPORTANT : quand cette "
    "jauge est pleine et qu'un lancer de reussite ne satisfait pas le "
    "joueur, DEMANDE-LUI explicitement s'il souhaite utiliser sa baguette "
    "pour relancer AVANT de continuer le recit -- ne poursuis jamais "
    "l'histoire sur ce resultat sans lui avoir pose la question.",

    "TOTEMS = AMIS ET CREATURES : en dehors de la baguette, il n'y a AUCUN "
    "totem ni sort au debut de cette aventure -- tout le reste se "
    "construit en jouant. Quand le personnage se lie d'amitie avec "
    "quelqu'un ou rencontre une creature fantastique marquante, c'est "
    "l'occasion d'inventer ce nouveau compagnon (nom, apparence, ce qu'il "
    "apporte) et de raconter cette rencontre. Le joueur l'ajoutera ensuite "
    "lui-meme dans l'application (nouvelle jauge, image...) : tu n'as rien "
    "a gerer mecaniquement, juste a le raconter. S'il apparait plus tard "
    "dans la liste des TOTEMS/ALLIES ci-dessus, integre-le naturellement a "
    "partir de ce moment-la.",

    "NOUVEAUX SORTS (mecanique de cours) : regulierement, fais vivre au "
    "personnage un cours de magie qui enseigne d'abord un vrai fait "
    "scientifique ou logique, en une ou deux phrases simples et exactes "
    "(physique, biologie, nature...), puis pose une question a ce sujet. "
    "Si le joueur repond correctement, le personnage apprend un nouveau "
    "sort dont l'effet decoule logiquement de ce fait -- exemple : l'air "
    "chaud monte et l'air froid descend, donc le sort Wingardium Leviosa "
    "fait leviter les objets. Si la reponse est fausse, ne fais jamais "
    "echouer definitivement : donne un indice, un nouvel essai, ou l'aide "
    "d'un professeur. Comme pour les totems, tu n'as rien a gerer "
    "mecaniquement pour un sort appris : raconte la decouverte, le joueur "
    "l'ajoutera ensuite lui-meme dans l'application.",
]


# ---------------------------------------------------------------------
# Registre
# ---------------------------------------------------------------------

STORIES = {
    "animorph": {
        "slug": "animorph",
        "title": "Animorph",
        "header_title": "Les D\u00e9s de l'Aventure d'Animorph",
        "subtitle": "Gabin explore la jungle, guide par ses totems.",
        "save_file": "dice_state_animorph.json",
        "bg_image_b64": _ANIMORPH_BG,
        "thumbnail_b64": None,  # pas de vignette dediee : la carte utilise bg_image_b64
        "pip_symbols": ANIMORPH_PIP_SYMBOLS,
        "totems": ANIMORPH_TOTEMS,
        "default_pip_symbol": "araignee",
        "protagonist_ref": "Gabin/Animorph",
        "fixed_allies_line": ANIMORPH_FIXED_ALLIES_LINE,
        "ally_help_text": ANIMORPH_ALLY_HELP_TEXT,
        "lore_paragraphs": ANIMORPH_LORE_PARAGRAPHS,
        "seed_state_file": "dice_state_seed.json",  # partie de depart fournie (Android)
    },
    "poudlard": {
        "slug": "poudlard",
        "title": "Poudlard",
        "header_title": "Les D\u00e9s de l'Aventure \u00e0 Poudlard",
        "subtitle": "Un nouvel eleve arrive a Poudlard, sans savoir encore ce qui l'attend.",
        "save_file": "dice_state_poudlard.json",
        "bg_image_b64": _POUDLARD_BG,
        "thumbnail_b64": None,  # pas de vignette dediee : la carte utilise bg_image_b64 (comme Animorph)
        "pip_symbols": POUDLARD_PIP_SYMBOLS,
        "totems": POUDLARD_TOTEMS,
        "default_pip_symbol": "baguette",
        "protagonist_ref": "un nouvel eleve de Poudlard (prenom a demander en debut d'aventure)",
        "fixed_allies_line": POUDLARD_FIXED_ALLIES_LINE,
        "ally_help_text": {},
        "lore_paragraphs": POUDLARD_LORE_PARAGRAPHS,
        "seed_state_file": None,  # part de zero, aucune partie pre-remplie
    },
}

STORY_ORDER = ["animorph", "poudlard"]


# ---------------------------------------------------------------------
# Histoires creees a la volee depuis l'application (page "Nouvelle
# histoire" du selecteur) -- en plus des deux histoires integrees
# ci-dessus. Contrairement a celles-ci (codees en dur, avec leur image de
# fond compilee dans un module Python), une histoire personnalisee est
# decrite par un simple fichier JSON (CUSTOM_STORIES_FILE) + son image de
# fond enregistree a part sur le disque (CUSTOM_STORY_BG_DIR) -- tout est
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
                         totem_powers="", totem_special=""):
    """Cree une nouvelle histoire : enregistre son image de fond sur le
    disque et ajoute une entree dans CUSTOM_STORIES_FILE. Renvoie le slug
    attribue (utilise ensuite par dice_web.switch_story() pour y basculer
    immediatement).

    totem_powers / totem_special sont optionnels et suivent exactement le
    meme format que pour un totem ajoute en cours de partie (voir
    /add_custom_totem dans dice_web.py) : powers_text est une chaine de
    pouvoirs separes par des virgules, special est une capacite unique en
    texte libre."""
    title = (title or "").strip() or "Nouvelle histoire"
    slug = _slugify_story_title(title)

    os.makedirs(CUSTOM_STORY_BG_DIR, exist_ok=True)
    # Redimensionnee/recompressee ici (meme mecanique que pour Animorph et
    # Poudlard ci-dessus) : une photo de telephone non retouchee etait la
    # cause du "trop grosse pour etre visible" sur les histoires creees
    # depuis l'application. Toujours reencodee en JPEG par ce traitement.
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

    Ne touche JAMAIS aux histoires integrees (Animorph, Poudlard) : si le
    slug fourni ne correspond a aucune histoire personnalisee (par
    exemple parce que c'est une histoire integree, ou un slug inconnu),
    ne fait rien et renvoie False. Renvoie True si une histoire a bien
    ete supprimee."""
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
    """Vrai si slug correspond a une histoire personnalisee (creee depuis
    l'application), donc supprimable -- faux pour les histoires
    integrees (Animorph, Poudlard) ou un slug inconnu."""
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
        "protagonist_ref": "le personnage principal",
        "fixed_allies_line": "",
        "ally_help_text": {},
        "is_custom": True,
        # La description d'univers fournie a la creation remplace, pour
        # cette histoire, tout ce qui concernait l'univers des autres
        # histoires (Marvel/Animorph, Harry Potter/Poudlard...) -- elle
        # s'ajoute simplement a la mecanique de jeu commune (des, jauges,
        # menace...) deja envoyee a l'IA, comme le fait lore_paragraphs
        # pour les histoires integrees.
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
    """Fusionne les histoires integrees (STORIES, codees en dur) et
    celles creees par le joueur depuis l'application. A utiliser partout
    ou stories.STORIES etait utilise directement pour lister/retrouver
    une histoire."""
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
    personnalisee : les histoires integrees (Animorph, Poudlard) n'ont
    rien a exporter au sens de cette fonction (leurs donnees vivent dans
    ce fichier, pas dans une sauvegarde a transferer).

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
      "digest" de l'IA narratrice."""
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
        "bg_image_b64": data.get("bg_image_b64") or "",
        "totem_image_b64": data.get("totem_image_b64") or "",
        "extra_totems": extra_totems,
        "side_quests": side_quests,
        "next_quest_id": next_quest_id,
        "story_log": story_log,
        "story_summary": story_summary,
    }
