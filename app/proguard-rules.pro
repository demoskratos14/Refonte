# Regles R8 / ProGuard du build release (minifyEnabled true).
#
# Ce qui n'a PAS besoin de regle ici :
#  - org.json (JSONObject, JSONArray) : classes du systeme Android, jamais renommees.
#  - Compose, Coil, AndroidX : elles embarquent leurs propres regles.
#  - Les enums (values()) et les alias d'icones du manifeste : geres par R8 / par nom de chaine.
#  - Les polices : chargees depuis assets/fonts par nom de fichier, pas par classe.

# Traces d'erreur lisibles (numeros de ligne) une fois reconverties avec mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MusicPlayer liste les musiques "music_*" par reflexion (R.raw::class.java.fields).
# Sans cette regle, R8 supprime les champs de R.raw non references directement et la
# liste des morceaux serait vide dans le build release.
-keep class com.aventure.desdice.R$raw {
    public static <fields>;
}
