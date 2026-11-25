package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.R
import com.example.data.model.WallpaperAppState
import java.util.Locale

enum class AppLanguage(
    val tag: String,
    @StringRes val labelRes: Int
) {
    SYSTEM(WallpaperAppState.DEFAULT_APP_LANGUAGE, R.string.language_system),
    ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional_chinese);

    companion object {
        fun fromTag(tag: String): AppLanguage =
            values().firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: SYSTEM
    }
}

object AppLocalePrefs {
    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LANGUAGE_TAG = "app_language"

    fun getLanguageTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE_TAG, WallpaperAppState.DEFAULT_APP_LANGUAGE)
            ?.ifBlank { WallpaperAppState.DEFAULT_APP_LANGUAGE }
            ?: WallpaperAppState.DEFAULT_APP_LANGUAGE
    }

    fun saveLanguageTag(context: Context, languageTag: String) {
        val normalized = languageTag.ifBlank { WallpaperAppState.DEFAULT_APP_LANGUAGE }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, normalized)
            .apply()
    }
}

object AppLocaleHelper {

    fun localeForLanguage(language: AppLanguage): Locale? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.SIMPLIFIED_CHINESE -> Locale.Builder()
            .setLanguage("zh")
            .setScript("Hans")
            .setRegion("CN")
            .build()
        AppLanguage.TRADITIONAL_CHINESE -> Locale.Builder()
            .setLanguage("zh")
            .setScript("Hant")
            .setRegion("TW")
            .build()
    }

    fun systemDefaultLocale(): Locale =
        Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)
            ?: Locale.getDefault()

    fun buildLocalizedConfiguration(baseConfig: Configuration, languageTag: String): Configuration {
        val language = AppLanguage.fromTag(languageTag)
        val targetLocale = localeForLanguage(language) ?: systemDefaultLocale()
        return Configuration(baseConfig).apply {
            setLocale(targetLocale)
            setLocales(LocaleList(targetLocale))
            setLayoutDirection(targetLocale)
        }
    }

    fun wrapContext(base: Context): Context {
        val savedTag = AppLocalePrefs.getLanguageTag(base)
        if (AppLanguage.fromTag(savedTag) == AppLanguage.SYSTEM) {
            return base
        }
        val config = buildLocalizedConfiguration(base.resources.configuration, savedTag)
        return base.createConfigurationContext(config)
    }
}

@Composable
fun ProvideAppLocale(
    languageTag: String,
    content: @Composable () -> Unit
) {
    val currentContext = LocalContext.current
    val currentConfig = LocalConfiguration.current
    val localizedConfig = remember(languageTag, currentConfig) {
        AppLocaleHelper.buildLocalizedConfiguration(currentConfig, languageTag)
    }
    val localizedContext = remember(currentContext, localizedConfig) {
        val resourceContext = currentContext.createConfigurationContext(localizedConfig)
        object : ContextWrapper(currentContext) {
            override fun getResources(): Resources = resourceContext.resources
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        content = content
    )
}
