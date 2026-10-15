package com.aventure.desdice.ui

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des réglages de texte (police, couleur, taille) et du nom lisible des polices.
 * Une fausse SharedPreferences en mémoire remplace celle d'Android.
 */
class TextStyleChoiceTest {

    private val prefs = FakeSharedPreferences()

    private fun choice() = TextStyleChoice(
        prefs, "font", "color", "size",
        defaultFontFile = "Defaut.ttf",
        defaultSizeSp = 15f,
        minSizeSp = 12f,
        maxSizeSp = 20f
    )

    // ---- TextStyleChoice ----

    @Test
    fun defaults_whenNothingIsSaved() {
        val c = choice()
        assertEquals("Defaut.ttf", c.fontFile)
        assertNull(c.color)
        assertEquals(15f, c.sizeSp, 0f)
    }

    @Test
    fun savedSize_isClampedWhenLoaded() {
        prefs.edit().putFloat("size", 99f).apply()
        assertEquals(20f, choice().sizeSp, 0f)
        prefs.edit().putFloat("size", 1f).apply()
        assertEquals(12f, choice().sizeSp, 0f)
    }

    @Test
    fun setSize_isClamped_andPersisted() {
        val c = choice()
        c.setSize(18f)
        assertEquals(18f, c.sizeSp, 0f)
        assertEquals(18f, choice().sizeSp, 0f)

        c.setSize(500f)
        assertEquals(20f, c.sizeSp, 0f)
        c.setSize(-3f)
        assertEquals(12f, c.sizeSp, 0f)
        assertEquals(12f, choice().sizeSp, 0f)
    }

    @Test
    fun setFont_isPersisted() {
        val c = choice()
        c.setFont("Autre.ttf")
        assertEquals("Autre.ttf", c.fontFile)
        assertEquals("Autre.ttf", choice().fontFile)
    }

    @Test
    fun setFont_null_forgetsTheChoice_soTheDefaultComesBackOnRestart() {
        val c = choice()
        c.setFont("Autre.ttf")
        c.setFont(null)
        assertNull(c.fontFile)
        assertFalse(prefs.contains("font"))
        assertEquals("Defaut.ttf", choice().fontFile)
    }

    @Test
    fun setColor_isPersisted_withFullOpacity() {
        val c = choice()
        c.setColor(Color(0x80FF0000.toInt())) // rouge à moitié transparent
        assertEquals(0xFFFF0000.toInt(), c.color!!.toArgb())
        assertEquals(0xFFFF0000.toInt(), choice().color!!.toArgb())
    }

    @Test
    fun setColor_black_isNotConfusedWithNoColor() {
        val c = choice()
        c.setColor(Color.Black)
        assertNotNull(c.color)
        assertNotNull(choice().color)
    }

    @Test
    fun setColor_null_goesBackToAutomatic() {
        val c = choice()
        c.setColor(Color.Red)
        c.setColor(null)
        assertNull(c.color)
        assertNull(choice().color)
    }

    @Test
    fun twoCategories_areIndependent() {
        val title = TextStyleChoice(prefs, "t_font", "t_color", "t_size", "A.ttf", 22f, 16f, 34f)
        val body = TextStyleChoice(prefs, "b_font", "b_color", "b_size", "B.ttf", 15f, 12f, 20f)
        title.setColor(Color.Red)
        title.setSize(30f)
        title.setFont("Z.ttf")
        assertNull(body.color)
        assertEquals(15f, body.sizeSp, 0f)
        assertEquals("B.ttf", body.fontFile)
    }

    // ---- prettyFontName ----

    @Test
    fun prettyFontName_removesExtensionAndRegularSuffix() {
        assertEquals("Nunito", prettyFontName("Nunito-Regular.ttf"))
        assertEquals("Bangers", prettyFontName("Bangers-Regular.ttf"))
        assertEquals("Jacquard12", prettyFontName("Jacquard12-Regular.ttf"))
    }

    @Test
    fun prettyFontName_splitsCamelCase() {
        assertEquals("Fleur De Leah", prettyFontName("FleurDeLeah-Regular.ttf"))
        assertEquals("Rubik Puddles", prettyFontName("RubikPuddles-Regular.ttf"))
        assertEquals("Moo Lah Lah", prettyFontName("MooLahLah-Regular.ttf"))
    }

    @Test
    fun prettyFontName_removesVariableFontSuffix() {
        assertEquals("Kalnia Glaze", prettyFontName("KalniaGlaze-VariableFont_wdth,wght.ttf"))
        assertEquals("Honk", prettyFontName("Honk-Regular-VariableFont_MORF,SHLN.ttf"))
        assertEquals("Nabla", prettyFontName("Nabla-Regular-VariableFont_EDPT,EHLT.ttf"))
    }

    @Test
    fun prettyFontName_keepsAllCapsNamesTogether() {
        assertEquals("VT323", prettyFontName("VT323-Regular.ttf"))
    }

    @Test
    fun prettyFontName_replacesSeparatorsWithSpaces() {
        assertEquals("Kalnia Glaze Expanded Bold", prettyFontName("KalniaGlaze_Expanded-Bold.ttf"))
    }

    @Test
    fun prettyFontName_neverReturnsAnEmptyName() {
        assertEquals("-Regular.ttf", prettyFontName("-Regular.ttf"))
        assertTrue(prettyFontName("a.ttf").isNotEmpty())
    }
}

/** SharedPreferences en mémoire : suffit pour tester la persistance sans Android. */
private class FakeSharedPreferences : SharedPreferences {

    val store = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(store)

    override fun getString(key: String?, defValue: String?): String? =
        if (store.containsKey(key!!)) store[key!!] as String? else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (store.containsKey(key!!)) store[key!!] as MutableSet<String>? else defValues

    override fun getInt(key: String?, defValue: Int): Int =
        if (store.containsKey(key!!)) store[key!!] as Int else defValue

    override fun getLong(key: String?, defValue: Long): Long =
        if (store.containsKey(key!!)) store[key!!] as Long else defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        if (store.containsKey(key!!)) store[key!!] as Float else defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (store.containsKey(key!!)) store[key!!] as Boolean else defValue

    override fun contains(key: String?): Boolean = store.containsKey(key!!)

    override fun edit(): SharedPreferences.Editor = FakeEditor(store)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {

    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
        pending[key!!] = value
        return this
    }

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
        pending[key!!] = values
        return this
    }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
        pending[key!!] = value
        return this
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
        pending[key!!] = value
        return this
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
        pending[key!!] = value
        return this
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
        pending[key!!] = value
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        removals.add(key!!)
        pending.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clearAll = true
        return this
    }

    override fun commit(): Boolean {
        flush()
        return true
    }

    override fun apply() {
        flush()
    }

    private fun flush() {
        if (clearAll) target.clear()
        for (k in removals) target.remove(k)
        target.putAll(pending)
    }
}
