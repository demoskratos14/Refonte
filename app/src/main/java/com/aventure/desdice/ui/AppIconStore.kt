package com.aventure.desdice.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.aventure.desdice.R

/**
 * Les icônes proposées : l'icône d'origine + 8 icônes livrées dans l'appli.
 * Parmi ces 8, 2 sont mixtes et inchangées (icônes 7 et 8) ; les 6 autres (1 à 6)
 * ont chacune été déclinées en 3 versions -- filles / garçons / duo (les 2) -- vues
 * dans AudienceChoiceScreen.kt. `audience == null` = icône commune, toujours
 * proposée quel que soit le public visé (voir iconsFor ci-dessous) ; sinon, l'icône
 * n'est proposée que si elle correspond au public actuellement choisi
 * (AudiencePrefs.audience) -- 9 icônes visibles au total, quel que soit ce choix.
 *
 * Chaque icône correspond à un <activity-alias> du manifeste (voir
 * AndroidManifest.xml) : un seul est activé à la fois, c'est lui que le lanceur du
 * téléphone affiche. `previewRes` = miniature pour l'écran Réglages (null = icône
 * d'origine, dessinée depuis @mipmap/ic_launcher).
 */
enum class AppIcon(val alias: String, val previewRes: Int?, val audience: Audience?) {
    ORIGINAL("IconOriginal", null, null),

    ICON_1_FILLES("Icon1Filles", R.drawable.icon_preview_1_filles, Audience.FILLES),
    ICON_1_GARCONS("Icon1Garcons", R.drawable.icon_preview_1_garcons, Audience.GARCONS),
    ICON_1_DUO("Icon1Duo", R.drawable.icon_preview_1_duo, Audience.DUO),

    ICON_2_FILLES("Icon2Filles", R.drawable.icon_preview_2_filles, Audience.FILLES),
    ICON_2_GARCONS("Icon2Garcons", R.drawable.icon_preview_2_garcons, Audience.GARCONS),
    ICON_2_DUO("Icon2Duo", R.drawable.icon_preview_2_duo, Audience.DUO),

    ICON_3_FILLES("Icon3Filles", R.drawable.icon_preview_3_filles, Audience.FILLES),
    ICON_3_GARCONS("Icon3Garcons", R.drawable.icon_preview_3_garcons, Audience.GARCONS),
    ICON_3_DUO("Icon3Duo", R.drawable.icon_preview_3_duo, Audience.DUO),

    ICON_4_FILLES("Icon4Filles", R.drawable.icon_preview_4_filles, Audience.FILLES),
    ICON_4_GARCONS("Icon4Garcons", R.drawable.icon_preview_4_garcons, Audience.GARCONS),
    ICON_4_DUO("Icon4Duo", R.drawable.icon_preview_4_duo, Audience.DUO),

    ICON_5_FILLES("Icon5Filles", R.drawable.icon_preview_5_filles, Audience.FILLES),
    ICON_5_GARCONS("Icon5Garcons", R.drawable.icon_preview_5_garcons, Audience.GARCONS),
    ICON_5_DUO("Icon5Duo", R.drawable.icon_preview_5_duo, Audience.DUO),

    ICON_6_FILLES("Icon6Filles", R.drawable.icon_preview_6_filles, Audience.FILLES),
    ICON_6_GARCONS("Icon6Garcons", R.drawable.icon_preview_6_garcons, Audience.GARCONS),
    ICON_6_DUO("Icon6Duo", R.drawable.icon_preview_6_duo, Audience.DUO),

    ICON_7("Icon7", R.drawable.icon_preview_7, null),
    ICON_8("Icon8", R.drawable.icon_preview_8, null)
}

/**
 * Icônes à proposer pour le public visé actuel (AudiencePrefs.audience) : toujours
 * 9 -- les 3 communes (originale + icônes 7 et 8, inchangées) + les 6 déclinées dans
 * la variante correspondante. `audience == null` (cas normalement impossible après
 * le tout premier écran) retombe sur la variante "duo".
 */
fun iconsFor(audience: Audience?): List<AppIcon> {
    val target = audience ?: Audience.DUO
    return AppIcon.values().filter { it.audience == null || it.audience == target }
}

object AppIconStore {

    // Package Kotlin des alias déclarés dans le manifeste (".Icon1" = com.aventure.desdice.Icon1).
    private const val ALIAS_PACKAGE = "com.aventure.desdice"

    private fun component(context: Context, icon: AppIcon) =
        ComponentName(context.packageName, "$ALIAS_PACKAGE.${icon.alias}")

    /** Icône actuellement active (celle dont l'alias est activé). */
    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.values().firstOrNull { icon ->
            try {
                when (pm.getComponentEnabledSetting(component(context, icon))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                    // Jamais modifié : c'est la valeur du manifeste (seul l'alias d'origine est activé).
                    else -> icon == AppIcon.ORIGINAL
                }
            } catch (e: IllegalArgumentException) {
                false // alias absent du manifeste
            }
        } ?: AppIcon.ORIGINAL
    }

    /**
     * Active l'alias de `target` puis désactive tous les autres (dans cet ordre :
     * il y a toujours au moins une icône active). DONT_KILL_APP : l'appli ne
     * redémarre pas ; le lanceur met à jour l'icône en quelques secondes.
     */
    fun apply(context: Context, target: AppIcon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        AppIcon.values().filter { it != target }.forEach { other ->
            pm.setComponentEnabledSetting(
                component(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
