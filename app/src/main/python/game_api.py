
import json
import os
import shutil
import uuid
from typing import Dict, Any, Optional

import dice_engine
from dice_engine import DiceSession, PIP_SYMBOLS, FATE_BY_KEY, SUCCESS_LABELS, FATE_FACES
import stories
import image_utils

TOTEM_IMAGES_DIR = "totem_images"
ALLOWED_TOTEM_IMAGE_EXTS = {"png", "jpg", "jpeg", "gif", "webp"}

CURRENT_STORY: Optional[str] = None
CURRENT_STORY_CONFIG: Dict[str, Any] = {}
session: Optional[DiceSession] = None
TOTEMS: list = []
TOTEMS_BY_KEY: Dict[str, Any] = {}
ALLY_HELP_TEXT: Dict[str, Any] = {}

def _rebuild_totem_derived_globals():
    global TOTEMS_BY_KEY, ALLY_HELP_TEXT
    TOTEMS_BY_KEY = {t["key"]: t for t in TOTEMS}
    ALLY_HELP_TEXT = dict(CURRENT_STORY_CONFIG.get("ally_help_text") or {})

def session_to_dict(sess: DiceSession) -> Dict[str, Any]:
    last_record = sess.history[-1] if sess.history else None
    last_result = None
    if last_record:
        last_result = dict(last_record)
        last_result["description"] = sess.describe_record(last_record)
    return {
        "history": sess.history,
        "next_id": sess.next_id,
        "pip_symbol": sess.pip_symbol,
        "pip_mode": sess.pip_mode,
        "enabled_symbols": sess.enabled_symbols,
        "custom_totems": sess.custom_totems,
        "allowed_success_values": sess.allowed_success_values,
        "allowed_fate_keys": sess.allowed_fate_keys,
        "totem_energy": sess.totem_energy,
        "threat_level": sess.threat_level,
        "side_quests": sess.side_quests,
        "next_quest_id": sess.next_quest_id,
        "story_log": sess.story_log,
        "ai_conversation": sess.ai_conversation,
        "story_summary": sess.story_summary,
        "last_summarized_index": sess.last_summarized_index,
        "last_ai_sent_id": sess.last_ai_sent_id,
        # Champs derives, ajoutes pour correspondre a ce que lit MainGameScreen.kt
        # (all_symbols/story_title/last_result n'existent pas comme attributs
        # directs de DiceSession, on les reconstruit ici a chaque appel).
        "all_symbols": [dict(v, key=k) for k, v in sess.all_symbols().items()],
        "story_title": CURRENT_STORY_CONFIG.get("title", ""),
        "last_result": last_result,
    }

def call_json(func_name: str, *args) -> str:
    """Point d'entree unique appele depuis Kotlin via Chaquopy
    (python.getModule("game_api").callAttr("call_json", nom, *args)) :
    retrouve la fonction game_api correspondante par son nom, l'appelle
    avec les arguments recus, et renvoie son resultat serialise en JSON
    (les fonctions do_* renvoient deja des dict/tuple JSON-serialisables)."""
    fn = globals().get(func_name)
    if fn is None or not callable(fn):
        raise AttributeError(f"Fonction game_api inconnue : {func_name}")
    result = fn(*args)
    return json.dumps(result, ensure_ascii=False)

def init_app_dir() -> None:
    """Appelee une seule fois depuis MainActivity.onCreate() (avant tout
    autre appel game_api), sans argument : recupere le Context Android via
    le binding Chaquopy (Python.getPlatform().getApplication()) et definit
    le repertoire de travail Python sur le stockage prive de l'app
    (getFilesDir()) -- seul repertoire durablement inscriptible sur
    Android. Necessaire car app_config.json, totem_images/ et le fichier
    de sauvegarde de session (dice_engine.SAVE_FILE) sont tous des chemins
    RELATIFS : sans ce chdir, ils pointeraient vers un repertoire courant
    indefini/non inscriptible."""
    from com.chaquo.python import Python as ChaquopyPython
    context = ChaquopyPython.getPlatform().getApplication()
    app_dir = context.getFilesDir().getAbsolutePath()
    os.makedirs(app_dir, exist_ok=True)
    os.chdir(app_dir)

def get_fate_faces() -> list:
    """Expose FATE_FACES (constante de dice_engine) a Kotlin, pour que
    MainGameScreen.kt puisse afficher emoji/label/desc de chaque face du
    de du destin sans dupliquer ces donnees cote Compose."""
    return FATE_FACES

def _save_totem_image(file_bytes: bytes, filename: str) -> Optional[str]:
    ext = ""
    if "." in filename:
        ext = filename.rsplit(".", 1)[-1].lower()
    if ext not in ALLOWED_TOTEM_IMAGE_EXTS:
        ext = "png"
    resized_bytes, resized_ext = image_utils.resize_totem_bytes(file_bytes)
    if resized_ext:
        ext = resized_ext
    os.makedirs(TOTEM_IMAGES_DIR, exist_ok=True)
    generated_filename = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(TOTEM_IMAGES_DIR, generated_filename), "wb") as f:
        f.write(resized_bytes)
    return generated_filename

def switch_story(slug: str) -> bool:
    global CURRENT_STORY, CURRENT_STORY_CONFIG, session

    story = stories.all_stories().get(slug)
    if not story:
        return False

    dice_engine.PIP_SYMBOLS.clear()
    dice_engine.PIP_SYMBOLS.update(story["pip_symbols"])
    dice_engine.DEFAULT_PIP_SYMBOL = story["default_pip_symbol"]
    dice_engine.SAVE_FILE = story["save_file"]

    CURRENT_STORY = slug
    CURRENT_STORY_CONFIG = story
    TOTEMS[:] = story["totems"]
    _rebuild_totem_derived_globals()

    seed_file = story.get("seed_state_file")
    if seed_file and not os.path.exists(dice_engine.SAVE_FILE) and os.path.exists(seed_file):
        shutil.copyfile(seed_file, dice_engine.SAVE_FILE)

    new_session = DiceSession()
    was_loaded = new_session.load()

    default_totem = story.get("default_totem")
    if not was_loaded and default_totem and default_totem.get("label"):
        key = new_session.add_custom_totem(
            default_totem["label"],
            powers_text=default_totem.get("powers_text", ""),
            special=default_totem.get("special", ""),
            image_filename=default_totem.get("image_filename"),
        )
        if key:
            new_session.pip_symbol = key
            new_session.save()

    session = new_session
    return True

def list_stories():
    """Liste toutes les histoires disponibles (integrees + personnalisees),
    dans l'ordre d'affichage, pour l'ecran de selection (StorySelectorScreen),
    accompagnee du slug de l'histoire actuellement active (CURRENT_STORY),
    au format {"stories": [...], "current_story": "..."} attendu par
    GameViewModel.loadStories(). Ne renvoie que ce qui est utile a
    l'affichage d'une liste -- pas l'etat de partie (session), qui reste
    gere separement par index()/select_story()."""
    all_stories = stories.all_stories()
    result = []
    for slug in stories.all_story_order():
        story = all_stories.get(slug)
        if not story:
            continue
        result.append({
            "slug": slug,
            "title": story.get("title", slug),
            "subtitle": story.get("subtitle", ""),
            "bg_image_b64": story.get("bg_image_b64", ""),
            "is_custom": story.get("is_custom", False),
        })
    return {"stories": result, "current_story": CURRENT_STORY or ""}

def index() -> Dict[str, Any]:
    global CURRENT_STORY, session
    if CURRENT_STORY is None:
        return session_to_dict(DiceSession())
    if session is None:
        return session_to_dict(DiceSession())
    return session_to_dict(session)

def select_story(slug: str) -> Dict[str, Any]:
    global CURRENT_STORY, session
    if slug in stories.all_stories():
        switch_story(slug)
    return session_to_dict(session if session else DiceSession())

def change_story() -> Dict[str, Any]:
    global CURRENT_STORY, session
    return session_to_dict(session if session else DiceSession())

def do_delete_story(slug: str) -> Dict[str, Any]:
    global CURRENT_STORY, CURRENT_STORY_CONFIG, session
    stories.delete_custom_story(slug)
    if CURRENT_STORY == slug:
        CURRENT_STORY = None
        CURRENT_STORY_CONFIG = {}
        session = None
    return session_to_dict(session) if session else session_to_dict(DiceSession())

def do_create_story(
    title: str,
    subtitle: str,
    lore_text: str,
    totem_label: str,
    totem_powers: str,
    totem_special: str,
    bg_image_bytes: bytes,
    bg_image_ext: str,
    totem_image_bytes: bytes,
    totem_image_filename: str,
) -> Dict[str, Any]:
    global CURRENT_STORY, CURRENT_STORY_CONFIG, session
    totem_image_filename = _save_totem_image(totem_image_bytes, totem_image_filename)
    slug = stories.create_custom_story(
        title=title,
        subtitle=subtitle,
        lore_text=lore_text,
        bg_image_bytes=bg_image_bytes,
        bg_image_ext=bg_image_ext,
        totem_label=totem_label,
        totem_image_filename=totem_image_filename,
        totem_powers=totem_powers,
        totem_special=totem_special,
    )
    switch_story(slug)
    return session_to_dict(session if session else DiceSession())

def do_import_identity(raw_text: str) -> Dict[str, Any]:
    parsed = stories.parse_identity_import(raw_text)
    return {
        "title": parsed.get("title", ""),
        "subtitle": parsed.get("subtitle", ""),
        "lore_text": parsed.get("lore_text", ""),
        "totem_label": parsed.get("totem_label", ""),
        "totem_powers": parsed.get("totem_powers", ""),
        "totem_special": parsed.get("totem_special", ""),
        # Base64, pas des bytes bruts : bytes n'est pas serialisable en
        # JSON, et call_json (voir plus bas) fait json.dumps sur ce dict --
        # c'est CreateStoryScreen (Kotlin) qui decode le base64 avant de
        # rappeler do_create_story avec les octets reconstitues.
        "bg_image_b64": parsed.get("bg_image_b64", ""),
        "totem_image_b64": parsed.get("totem_image_b64", ""),
    }

def do_roll(action: str) -> Dict[str, Any]:
    global session
    if action == "success":
        session.roll_success("")
    elif action == "fate":
        session.roll_fate("")
    elif action == "both":
        session.roll_both("")
    return session_to_dict(session)

def do_undo() -> Dict[str, Any]:
    global session
    session.undo_last()
    return session_to_dict(session)

def do_clear() -> Dict[str, Any]:
    global session
    session.clear_history()
    return session_to_dict(session)

def classic_dice_to_dict(state: Dict[str, Any]) -> Dict[str, Any]:
    """Enrichit chaque entree de l'historique avec l'emoji/libelle du
    destin (quand il y en a un) : ClassicDiceScreen (Compose) n'a pas
    acces a FATE_BY_KEY cote Kotlin, ces deux champs derives lui evitent
    d'avoir a dupliquer la table des symboles du destin."""
    history = []
    for entry in state.get("history", []):
        item = dict(entry)
        face = FATE_BY_KEY.get(item.get("fate"))
        if face:
            item["fate_emoji"] = face["emoji"]
            item["fate_label"] = face["label"]
        history.append(item)
    return {
        "history": history,
        "next_id": state.get("next_id", 1)
    }

def do_classic_dice_roll(kind: str) -> Dict[str, Any]:
    import random
    state = {"history": [], "next_id": 1}
    if os.path.exists("classic_dice_state.json"):
        try:
            with open("classic_dice_state.json", "r", encoding="utf-8") as f:
                state = json.load(f)
        except (OSError, ValueError):
            state = {"history": [], "next_id": 1}
    entry = {
        "id": state["next_id"],
        "success": random.randint(1, 6) if kind in ("success", "both") else None,
        "fate": random.choice(list(FATE_BY_KEY.keys())) if kind in ("fate", "both") else None,
    }
    state["next_id"] += 1
    state["history"].append(entry)
    state["history"] = state["history"][-30:]
    with open("classic_dice_state.json", "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    return classic_dice_to_dict(state)

def do_classic_dice_clear() -> Dict[str, Any]:
    state = {"history": [], "next_id": 1}
    with open("classic_dice_state.json", "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    return classic_dice_to_dict(state)

def classic_dice_state() -> Dict[str, Any]:
    """Etat courant de la page 'Des classiques', SANS lancer de de --
    a utiliser au chargement de ClassicDiceScreen (do_classic_dice_roll,
    lui, lance toujours un de a chaque appel, ce qui serait inattendu a
    la simple ouverture de l'ecran)."""
    state = {"history": [], "next_id": 1}
    if os.path.exists("classic_dice_state.json"):
        try:
            with open("classic_dice_state.json", "r", encoding="utf-8") as f:
                state = json.load(f)
        except (OSError, ValueError):
            state = {"history": [], "next_id": 1}
    return classic_dice_to_dict(state)

def do_set_pip_symbol(symbol: str) -> Dict[str, Any]:
    global session
    session.set_pip_symbol(symbol)
    return session_to_dict(session)

def do_set_pip_mode(mode: str) -> Dict[str, Any]:
    global session
    session.set_pip_mode(mode)
    return session_to_dict(session)

def do_toggle_enabled_symbol(symbol: str) -> Dict[str, Any]:
    global session
    session.toggle_enabled_symbol(symbol)
    return session_to_dict(session)

def do_toggle_allowed_value(value: int) -> Dict[str, Any]:
    global session
    session.toggle_allowed_value(value)
    return session_to_dict(session)

def do_reset_allowed_values() -> Dict[str, Any]:
    global session
    session.reset_allowed_values()
    return session_to_dict(session)

def do_toggle_allowed_fate(key: str) -> Dict[str, Any]:
    global session
    session.toggle_allowed_fate(key)
    return session_to_dict(session)

def do_reset_allowed_fate() -> Dict[str, Any]:
    global session
    session.reset_allowed_fate()
    return session_to_dict(session)

def _save_totem_image_bytes(raw_bytes: bytes, original_filename: str = "") -> Optional[str]:
    ext = ""
    if original_filename and "." in original_filename:
        ext = original_filename.rsplit(".", 1)[-1].lower()
    if ext not in ALLOWED_TOTEM_IMAGE_EXTS:
        ext = "png"
    resized_bytes, resized_ext = image_utils.resize_totem_bytes(raw_bytes)
    if resized_ext:
        ext = resized_ext
    os.makedirs(TOTEM_IMAGES_DIR, exist_ok=True)
    filename = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(TOTEM_IMAGES_DIR, filename), "wb") as f:
        f.write(resized_bytes)
    return filename

def do_use_totem_energy(key: str, text: str = "") -> Dict[str, Any]:
    global session
    effect_text = ""
    spent = session.spend_totem_energy(key)
    if spent:
        if key in ("patte", "baguette"):
            label = TOTEMS_BY_KEY.get(key, {}).get("label", key)
            session.second_souffle(note=f"Relance ({label})")
            effect_text = f"🔄 {label} ! Le dernier lancer de réussite est annulé et relancé à l'instant."
        elif key in ALLY_HELP_TEXT:
            effect_text = ALLY_HELP_TEXT[key]
        elif key in TOTEMS_BY_KEY and TOTEMS_BY_KEY[key]["special"]:
            effect_text = TOTEMS_BY_KEY[key]["special"]
        elif key in TOTEMS_BY_KEY:
            powers = ", ".join(TOTEMS_BY_KEY[key]["powers"])
            effect_text = f"Le pouvoir du totem {TOTEMS_BY_KEY[key]['label']} se manifeste : {powers}."
        else:
            info = session.all_symbols().get(key)
            if info:
                if info.get("special"):
                    effect_text = info["special"]
                elif info.get("powers"):
                    effect_text = f"Le pouvoir de {info['label']} se manifeste : {', '.join(info['powers'])}."
                else:
                    effect_text = f"{info['label']} intervient pour aider !"
    ai_error = None
    if spent and effect_text:
        pending = session.pending_roll()
        parts = []
        if pending:
            parts.append(build_ai_event_text(pending))
        if text:
            parts.append(text)
        parts.append(effect_text)
        _, ai_error = run_ai_narrator("\n".join(parts))
        if pending:
            session.mark_last_roll_as_sent()
    result = session_to_dict(session)
    result["spent"] = spent
    result["effect"] = effect_text
    result["ai_error"] = ai_error
    return result
def do_add_custom_totem(
    label: str,
    powers: str = "",
    special: str = "",
    emoji: str = "",
    image_bytes: Optional[bytes] = None,
    image_filename: str = ""
) -> Dict[str, Any]:
    global session
    saved_filename = None
    if image_bytes:
        saved_filename = _save_totem_image_bytes(image_bytes, image_filename)
    session.add_custom_totem(
        label,
        powers_text=powers,
        special=special,
        emoji=emoji,
        image_filename=saved_filename
    )
    return session_to_dict(session)

def do_remove_custom_totem(key: str) -> Dict[str, Any]:
    global session
    entry = next((t for t in session.custom_totems if t["key"] == key), None)
    session.remove_custom_totem(key)
    if entry and entry.get("image"):
        try:
            os.remove(os.path.join(TOTEM_IMAGES_DIR, entry["image"]))
        except OSError:
            pass
    return session_to_dict(session)

def do_complete_side_quest(quest_id: int) -> Dict[str, Any]:
    global session
    session.complete_side_quest(quest_id)
    return session_to_dict(session)

def do_add_story_entry(text: str) -> Dict[str, Any]:
    global session
    session.add_story_entry(text)
    return session_to_dict(session)

def build_mechanics_context(auto_mode=False):
    success_compact = {
        1: "Echec critique, rattrapable (jamais la fin de l'histoire)",
        2: "Echec ou reussite tres dure",
        3: "Reussite partielle",
        4: "Bonne reussite",
        5: "Tres bonne reussite",
        6: "Reussite heroique",
    }
    fate_compact = {
        "coeur": "allie/protection : un ami ou heros intervient, guerison, lien "
                 "renforce, ou miracle",
        "question": "quete secondaire debloquee (nouvel ami ou nouvel objet/totem), "
                    "a faire quand on veut",
        "soleil": "benediction : energie positive, pouvoir renforce/stabilise, "
                  "protection, amelioration durable",
        "etoile": "chance exceptionnelle : opportunite rare, decouverte precieuse, "
                  "ou recompense speciale",
        "exclamation": "aide arrive : une connaissance, ou un animal lie a un "
                       "totem, intervient",
        "spirale": "chaos/transformation : effet imprevisible, mutation, ou "
                   "consequence inattendue",
    }
    totems_compact = {
        "aigle": (["vol", "vision exceptionnelle", "vitesse aerienne",
                   "controle du vent", "rafales", "vol precis"],
                  "Ascension Absolue : monte tres haut, vue globale depuis le ciel"),
        "loup": (["force", "endurance", "odorat", "protection", "pistage"], ""),
        "renard": (["agilite", "discretion", "ruse", "precision", "tactique"], ""),
        "jaguar": (["vision nocturne", "intuition du danger", "perception spirituelle",
                    "lien mental avec Gabin", "conseils"],
                   "Fureur Astrale : 1x/aventure, boost reflexes/vitesse/lucidite"),
        "bond": (["sauts tres hauts", "sauts tres longs", "atterrissage maitrise",
                  "mobilite", "peut porter un allie"], ""),
        "profondeurs": (["respiration aquatique", "vitesse aquatique",
                         "resistance a la pression", "perception des vibrations",
                         "echo-sens"],
                        "Vague Primordiale : onde qui repousse et change les courants"),
    }

    protagonist_ref = CURRENT_STORY_CONFIG.get("protagonist_ref", "le joueur")
    lines = []
    lines.append("=== CONTEXTE IA NARRATRICE ===")
    if auto_mode:
        lines.append(f"Narrateur d'une histoire heroique interactive, 2e personne ('tu'), "
                      f"adressee a {protagonist_ref}. Mecaniques ci-dessous ; l'histoire se "
                      "poursuit directement dans cette conversation, evenement par evenement.")
    else:
        lines.append(f"Narrateur d'une histoire heroique interactive, 2e personne ('tu'), "
                      f"adressee a {protagonist_ref}. Mecaniques ci-dessous, puis l'histoire deja vecue.")
    lines.append("")
    lines.append("DE DE REUSSITE (1-6, jamais de fin d'histoire meme sur un 1, "
                  "toujours moyen de se rattraper) :")
    lines.append(" | ".join(f"{v}={success_compact[v]}" for v in SUCCESS_LABELS))
    lines.append("")
    lines.append("DE DU DESTIN (6 symboles ; le ? ne retombe pas tant qu'une quete "
                  "secondaire ouverte n'est pas terminee, une seule active a la fois) :")
    lines.append(" | ".join(f"{f['emoji']}{f['label']}={fate_compact[f['key']]}"
                             for f in FATE_FACES))
    lines.append("")
    lines.append("TOTEMS/ALLIES (symbole choisi sur le de de reussite) :")
    for t in stories.all_stories().get(CURRENT_STORY, {}).get("totems", []):
        compact = totems_compact.get(t["key"])
        powers, special = compact if compact else (t["powers"], t["special"])
        suffix = f" — spe: {special}" if special else ""
        lines.append(f"{t['icon']}{t['label']}: {', '.join(powers)}{suffix}")
    for t in session.custom_totems:
        icon = t.get("emoji") or "\U0001F43E"
        powers_txt = ", ".join(t["powers"]) if t["powers"] else "(pouvoirs non precises)"
        special_txt = f" — spe: {t['special']}" if t.get("special") else ""
        lines.append(f"{icon}{t['label']} (ajoute par le joueur): {powers_txt}{special_txt}")
    fixed_allies_line = CURRENT_STORY_CONFIG.get("fixed_allies_line") or ""
    if fixed_allies_line:
        lines.append(fixed_allies_line)
    if not stories.all_stories().get(CURRENT_STORY, {}).get("totems", []) and not session.custom_totems:
        lines.append("(aucun pour l'instant -- tout reste a decouvrir en jouant)")
    lines.append(
        f"Jauge par totem/allie : +score obtenu (symbole unique) ou +1/symbole "
        f"(melange) a chaque lancer concerne -> pleine a {dice_engine.TOTEM_ENERGY_THRESHOLD} "
        "points, alors utilisable (capacite ou aide correspondante)."
    )
    lines.append("")
    lines.append(
        f"JAUGE DE MENACE : +3 sur un 1, -1 sur un 5 ou 6. A {dice_engine.THREAT_THRESHOLD} "
        "points, complication secondaire inattendue puis retombe a 0."
    )
    for paragraph in CURRENT_STORY_CONFIG.get("lore_paragraphs") or []:
        lines.append("")
        lines.append(paragraph)
    lines.append("")
    lines.append(
        "TON DU RECIT : l'histoire s'adresse a un enfant -- ecris de maniere "
        "vivante et chaleureuse, et parseme regulierement tes paragraphes de "
        "quelques emojis/petites icones pertinentes (\u2728\U0001F31F\U0001F43E "
        "etc.) pour illustrer les evenements et egayer le texte. Reste sobre : "
        "quelques emojis bien places par paragraphe suffisent, jamais un "
        "amoncellement d'icones qui alourdirait la reponse pour rien."
    )
    lines.append("")
    if auto_mode:
        lines.append(
            "ATTENDU DE TOI : histoire collaborative et immersive integrant directement "
            "les evenements de jeu que je te transmets (lancers de des, pouvoirs "
            "utilises, quetes...) ; a chaque action/incertitude tu me demandes "
            "explicitement de lancer le de de reussite, le de du destin, ou les deux, "
            f"et tu attends le resultat suivant avant de continuer ; tu t'adresses "
            f"toujours a {protagonist_ref} en 'tu'."
        )
    else:
        lines.append(
            "ATTENDU DE TOI : histoire collaborative et immersive integrant mes "
            "lancers ; a chaque action/incertitude tu me demandes de lancer "
            "reussite/destin/les deux et attends mon resultat avant de continuer ; "
            "si j'utilise une jauge pleine ou qu'un ?/! survient je te colle un "
            "petit bloc genere par l'appli pour te le signaler precisement ; a la "
            "fin de chaque chapitre tu me donnes un resume a coller dans l'appli "
            f"pour garder une trace permanente ; tu t'adresses toujours a "
            f"{protagonist_ref} en 'tu'."
        )
    return "\n".join(lines)

def build_full_prompt():
    lines = [build_mechanics_context(auto_mode=False), "", "--- HISTOIRE DEJA VECUE ---"]
    story = session.story_log_text()
    lines.append(story if story else "(aucun chapitre enregistre pour l'instant, on commence "
                                       "une aventure toute neuve)")
    return "\n".join(lines)

def build_ai_kickoff_message():
    lines = [build_mechanics_context(auto_mode=True)]
    story = session.story_log_text()
    if story:
        lines.append("")
        lines.append("--- HISTOIRE DEJA VECUE ---")
        lines.append(story)
        lines.append("")
        lines.append("Commence maintenant le prochain chapitre de l'histoire, "
                      "dans la continuite directe de ce qui precede.")
    else:
        lines.append("")
        lines.append("Aucun chapitre n'a encore ete joue. Commence maintenant le tout "
                      "premier chapitre de cette aventure : plante le decor et presente "
                      "la situation de depart de Gabin/Animorph, puis demande-moi le "
                      "premier lancer des que la situation l'exige.")
    return "\n".join(lines)

def maybe_update_story_summary():
    STORY_SUMMARY_TRIGGER = 6
    STORY_SUMMARY_MODEL = "ministral-8b-2512"
    STORY_SUMMARY_MAX_TOKENS = 400
    pending = session.pending_summary_messages()
    if len(pending) < STORY_SUMMARY_TRIGGER:
        return
    if not has_mistral_key():
        return

    existing = session.story_summary
    block = _format_messages_for_summary(pending)
    summarizer_system = (
        "Tu condenses une histoire de jeu de role pour enfant (en francais) "
        "en une liste tres compacte des faits a retenir : personnages "
        "rencontres, objets/totems obtenus, lieux visites, quetes en cours "
        "ou terminees, evenements marquants. Style neutre et factuel, pas "
        "de tournures narratives ni de fioritures. 150 mots maximum."
    )
    user_prompt = (
        (f"Resume existant :\n{existing}\n\n" if existing else "")
        + f"Nouveaux evenements a integrer :\n{block}\n\n"
        + "Donne le resume complet mis a jour (fusion de l'ancien resume et "
          "des nouveaux evenements), 150 mots maximum."
    )
    import mistral_client
    def get_mistral_key():
        APP_CONFIG_FILE = "app_config.json"
        if os.path.exists(APP_CONFIG_FILE):
            try:
                with open(APP_CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, dict):
                    return str(data.get("mistral_api_key") or "")
            except (OSError, ValueError):
                pass
        return ""

    def has_mistral_key():
        return bool(get_mistral_key())

    text, error = mistral_client.chat(
        get_mistral_key(),
        [
            {"role": "system", "content": summarizer_system},
            {"role": "user", "content": user_prompt},
        ],
        model=STORY_SUMMARY_MODEL,
        max_tokens=STORY_SUMMARY_MAX_TOKENS,
        prompt_cache_key=(f"{CURRENT_STORY}-summary" if CURRENT_STORY else None),
    )
    if error or not text:
        return
    session.apply_story_summary(text)

def _format_messages_for_summary(messages):
    lines = []
    for m in messages:
        role = "Joueur" if m["role"] == "user" else "Narrateur"
        lines.append(f"{role} : {m['content']}")
    return "\n".join(lines)

def load_app_config():
    APP_CONFIG_FILE = "app_config.json"
    if os.path.exists(APP_CONFIG_FILE):
        try:
            with open(APP_CONFIG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return {
                    "mistral_api_key": str(data.get("mistral_api_key") or ""),
                    "mistral_model": str(data.get("mistral_model") or "mistral-small-latest"),
                }
        except (OSError, ValueError):
            pass
    return {"mistral_api_key": "", "mistral_model": "mistral-small-latest"}

def save_app_config(config):
    APP_CONFIG_FILE = "app_config.json"
    with open(APP_CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)

def get_mistral_key():
    return load_app_config()["mistral_api_key"]

def set_mistral_key(key):
    config = load_app_config()
    config["mistral_api_key"] = (key or "").strip()
    save_app_config(config)

def clear_mistral_key():
    config = load_app_config()
    config["mistral_api_key"] = ""
    save_app_config(config)

def has_mistral_key():
    return bool(get_mistral_key())

def get_mistral_model():
    return load_app_config()["mistral_model"]

def set_mistral_model(model):
    config = load_app_config()
    config["mistral_model"] = (model or "").strip() or "mistral-small-latest"
    save_app_config(config)

def get_config_screen_state() -> Dict[str, Any]:
    """Etat initial pour ConfigureKeyScreen (6a) : la cle elle-meme n'est
    jamais renvoyee en clair -- seulement has_key (pour savoir si une cle
    est deja enregistree) et masked_key (ses 4 derniers caracteres, le
    reste remplace par des etoiles, comme l'affichait l'ancienne page
    HTML) -- le modele actuellement choisi, et la liste des modeles
    proposes (mistral_client.MODEL_CHOICES, normalisee en listes
    [valeur, libelle] pour la serialisation JSON -- peu importe que
    MODEL_CHOICES soit fait de tuples ou de listes cote Python)."""
    import mistral_client
    key = get_mistral_key()
    masked_key = ("*" * max(0, len(key) - 4)) + key[-4:] if key else ""
    return {
        "has_key": has_mistral_key(),
        "masked_key": masked_key,
        "model": get_mistral_model(),
        "model_choices": [list(choice) for choice in mistral_client.MODEL_CHOICES],
    }

def run_ai_narrator(event_text):
    if not has_mistral_key() or not event_text:
        return None, None
    if not session.ai_conversation:
        session.add_ai_message("system", build_mechanics_context(auto_mode=True))
    session.add_ai_message("user", event_text)
    import mistral_client
    text, error = mistral_client.chat(
        get_mistral_key(), session.ai_messages_to_send(),
        model=get_mistral_model(),
        prompt_cache_key=CURRENT_STORY or None,
    )
    if error:
        return None, error
    session.add_ai_message("assistant", text)
    maybe_update_story_summary()
    return text, None

def reset_story_to_origin():
    global session
    story = CURRENT_STORY_CONFIG or {}
    if os.path.exists(dice_engine.SAVE_FILE):
        os.remove(dice_engine.SAVE_FILE)
    seed_file = story.get("seed_state_file")
    if seed_file and os.path.exists(seed_file):
        shutil.copyfile(seed_file, dice_engine.SAVE_FILE)
    new_session = DiceSession()
    was_loaded = new_session.load()
    default_totem = story.get("default_totem")
    if not was_loaded and default_totem and default_totem.get("label"):
        key = new_session.add_custom_totem(
            default_totem["label"],
            powers_text=default_totem.get("powers_text", ""),
            special=default_totem.get("special", ""),
            image_filename=default_totem.get("image_filename"),
        )
        if key:
            new_session.pip_symbol = key
    new_session.save()
    session = new_session

def do_reset_ai_conversation() -> Dict[str, Any]:
    reset_story_to_origin()
    return session_to_dict(session)

def do_send_full_prompt() -> Dict[str, Any]:
    global session
    _, ai_error = run_ai_narrator(build_ai_kickoff_message())
    result = session_to_dict(session)
    result["ai_error"] = ai_error
    return result

def do_send_ai_message(text: str = "") -> Dict[str, Any]:
    global session
    pending = session.pending_roll()
    parts = []
    if pending:
        parts.append(build_ai_event_text(pending))
    if text:
        parts.append(text)
    combined = "\n".join(parts)
    ai_error = None
    if combined:
        _, ai_error = run_ai_narrator(combined)
        if pending:
            session.mark_last_roll_as_sent()
    result = session_to_dict(session)
    result["ai_error"] = ai_error
    return result

def build_ai_event_text(record):
    if not record:
        return ""
    parts = [session.describe_record(record)]
    note = narrator_note_for_record(record)
    if note:
        parts.append(note)
    return "\n".join(parts)

def narrator_note_for_record(record):
    if not record or record.get("fate") not in ("question", "exclamation"):
        return ""
    face = FATE_BY_KEY[record["fate"]]
    text = f"{face['emoji']} {face['label']} : {face['desc']}"
    side_quest = record.get("side_quest")
    if side_quest:
        kind_txt = "se faire un nouvel ami" if side_quest["kind"] == "ami" else "trouver un nouvel objet (ou un nouveau totem)"
        text += f"\n(Quete secondaire debloquee : {kind_txt}.)"
    return text

def export_journal() -> tuple:
    title = CURRENT_STORY_CONFIG.get("title") or "Journal de l'histoire"
    subtitle = CURRENT_STORY_CONFIG.get("subtitle") or ""
    pdf_bytes = None
    try:
        import journal_export
        pdf_bytes = journal_export.generate_journal_pdf(title, subtitle, session.story_log)
    except ImportError:
        pdf_bytes = None
    if pdf_bytes is None:
        text = session.story_log_text() or "(aucun chapitre enregistré pour l'instant)"
        return text.encode("utf-8"), f"journal_{CURRENT_STORY}.txt", "text/plain"
    return pdf_bytes, f"journal_{CURRENT_STORY}.pdf", "application/pdf"

def export_identity() -> tuple:
    if not CURRENT_STORY_CONFIG.get("is_custom"):
        return b"", "", ""  # a toi de decider comment Compose affiche ce cas
    data = stories.export_story_identity(CURRENT_STORY)
    if data is None:
        return b"", "", ""
    payload = json.dumps(data, ensure_ascii=False, indent=2)
    return payload.encode("utf-8"), f"histoire_{CURRENT_STORY}.json", "application/json"