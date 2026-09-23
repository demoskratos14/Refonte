package com.aventure.desdice.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.aventure.desdice.R

/**
 * Les 9 icônes proposées : l'icône d'origine + 8 icônes livrées dans l'appli.
 *
 * Chaque icône correspond à un <activity-alias> du manifeste (voir
 * AndroidManifest_aliases.xml) : un seul est activé à la fois, c'est lui que
 * le lanceur du téléphone affiche. `previewRes` = miniature pour l'écran
 * Réglages (null = icône d'origine, dessinée depuis @mipmap/ic_launcher).
 */
enum class AppIcon(val alias: String, val previewRes: Int?) {
    ORIGINAL("IconOriginal", null),
    ICON_1("Icon1", R.drawable.icon_preview_1),
    ICON_2("Icon2", R.drawable.icon_preview_2),
    ICON_3("Icon3", R.drawable.icon_preview_3),
    ICON_4("Icon4", R.drawable.icon_preview_4),
    ICON_5("Icon5", R.drawable.icon_preview_5),
    ICON_6("Icon6", R.drawable.icon_preview_6),
    ICON_7("Icon7", R.drawable.icon_preview_7),
    ICON_8("Icon8", R.drawable.icon_preview_8)
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
