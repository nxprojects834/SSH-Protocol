package com.mdevz.sp.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mdevz.sp.R
import com.mdevz.sp.databinding.FragmentSettingsBinding

class SettingsController(
    private val context: Context,
    private val binding: FragmentSettingsBinding
) {
    fun setup() {
        setupAppearance()
        setupLanguage()
        setupHttpPing()
    }

    private fun setupAppearance() {
        val currentMode =
            AppCompatDelegate.getDefaultNightMode()

        binding.radioThemeSystem.isChecked =
            currentMode ==
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
                currentMode ==
                AppCompatDelegate.MODE_NIGHT_UNSPECIFIED

        binding.radioThemeLight.isChecked =
            currentMode ==
                AppCompatDelegate.MODE_NIGHT_NO

        binding.radioThemeDark.isChecked =
            currentMode ==
                AppCompatDelegate.MODE_NIGHT_YES

        binding.groupTheme.setOnCheckedChangeListener {
            _,
            checkedId ->

            val mode =
                when (checkedId) {
                    R.id.radioThemeLight ->
                        AppCompatDelegate.MODE_NIGHT_NO

                    R.id.radioThemeDark ->
                        AppCompatDelegate.MODE_NIGHT_YES

                    else ->
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }

            if (
                AppCompatDelegate.getDefaultNightMode() !=
                mode
            ) {
                AppCompatDelegate.setDefaultNightMode(
                    mode
                )
            }
        }
    }

    private fun setupLanguage() {
        val locales =
            AppCompatDelegate.getApplicationLocales()

        val tag =
            if (locales.isEmpty) {
                ""
            } else {
                locales[0]?.language.orEmpty()
            }

        binding.radioLanguageSystem.isChecked =
            tag.isEmpty()

        binding.radioLanguageIndonesian.isChecked =
            tag == "id"

        binding.radioLanguageEnglish.isChecked =
            tag == "en"

        binding.groupLanguage.setOnCheckedChangeListener {
            _,
            checkedId ->

            val selected =
                when (checkedId) {
                    R.id.radioLanguageIndonesian ->
                        LocaleListCompat.forLanguageTags(
                            "id"
                        )

                    R.id.radioLanguageEnglish ->
                        LocaleListCompat.forLanguageTags(
                            "en"
                        )

                    else ->
                        LocaleListCompat.getEmptyLocaleList()
                }

            if (
                AppCompatDelegate.getApplicationLocales() !=
                selected
            ) {
                AppCompatDelegate.setApplicationLocales(
                    selected
                )
            }
        }
    }

    private fun setupHttpPing() {
        val preferences =
            context.getSharedPreferences(
                HTTP_PING_PREFERENCES,
                Context.MODE_PRIVATE
            )

        val enabled =
            preferences.getBoolean(
                HTTP_PING_ENABLED,
                false
            )

        val savedUrl =
            preferences.getString(
                HTTP_PING_URL,
                HTTP_PING_DEFAULT_URL
            )
                ?.trim()
                .orEmpty()

        val storedUrl =
            when {
                savedUrl.isBlank() ->
                    HTTP_PING_DEFAULT_URL

                savedUrl ==
                    HTTP_PING_LEGACY_URL ->
                    HTTP_PING_DEFAULT_URL

                else ->
                    savedUrl
            }

        val intervalSeconds =
            preferences.getInt(
                HTTP_PING_INTERVAL_SECONDS,
                HTTP_PING_DEFAULT_INTERVAL_SECONDS
            )
                .coerceAtLeast(
                    HTTP_PING_MIN_INTERVAL_SECONDS
                )

        if (
            savedUrl != storedUrl ||
            preferences.getInt(
                HTTP_PING_INTERVAL_SECONDS,
                HTTP_PING_DEFAULT_INTERVAL_SECONDS
            ) != intervalSeconds
        ) {
            preferences.edit()
                .putString(
                    HTTP_PING_URL,
                    storedUrl
                )
                .putInt(
                    HTTP_PING_INTERVAL_SECONDS,
                    intervalSeconds
                )
                .apply()
        }

        binding.switchHttpPing.isChecked =
            enabled

        binding.inputHttpPingUrl.setText(
            storedUrl
        )

        binding.inputHttpPingInterval.setText(
            intervalSeconds.toString()
        )

        binding.inputHttpPingUrl.isEnabled =
            enabled

        binding.inputHttpPingInterval.isEnabled =
            enabled

        binding.switchHttpPing
            .setOnCheckedChangeListener {
                _,
                checked ->

                binding.inputHttpPingUrl.isEnabled =
                    checked

                binding.inputHttpPingInterval.isEnabled =
                    checked

                saveHttpPing(
                    checked
                )
            }

        binding.inputHttpPingUrl.setOnFocusChangeListener {
            _,
            hasFocus ->

            if (!hasFocus) {
                saveHttpPing(
                    binding.switchHttpPing.isChecked
                )
            }
        }

        binding.inputHttpPingInterval.setOnFocusChangeListener {
            _,
            hasFocus ->

            if (!hasFocus) {
                saveHttpPing(
                    binding.switchHttpPing.isChecked
                )
            }
        }
    }

    private fun saveHttpPing(
        enabled: Boolean
    ) {
        val rawUrl =
            binding.inputHttpPingUrl.text
                ?.toString()
                ?.trim()
                .orEmpty()

        val url =
            when {
                rawUrl.isBlank() ->
                    HTTP_PING_DEFAULT_URL

                rawUrl ==
                    HTTP_PING_LEGACY_URL ->
                    HTTP_PING_DEFAULT_URL

                else ->
                    rawUrl
            }

        val intervalSeconds =
            binding.inputHttpPingInterval.text
                ?.toString()
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(
                    HTTP_PING_MIN_INTERVAL_SECONDS
                )
                ?: HTTP_PING_DEFAULT_INTERVAL_SECONDS

        if (
            binding.inputHttpPingUrl.text
                ?.toString() != url
        ) {
            binding.inputHttpPingUrl.setText(
                url
            )
        }

        if (
            binding.inputHttpPingInterval.text
                ?.toString() !=
                intervalSeconds.toString()
        ) {
            binding.inputHttpPingInterval.setText(
                intervalSeconds.toString()
            )
        }

        context.getSharedPreferences(
            HTTP_PING_PREFERENCES,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                HTTP_PING_ENABLED,
                enabled
            )
            .putString(
                HTTP_PING_URL,
                url
            )
            .putInt(
                HTTP_PING_INTERVAL_SECONDS,
                intervalSeconds
            )
            .apply()
    }

    private companion object {
        const val HTTP_PING_PREFERENCES =
            "http_ping_settings"

        const val HTTP_PING_ENABLED =
            "enabled"

        const val HTTP_PING_URL =
            "url"

        const val HTTP_PING_INTERVAL_SECONDS =
            "interval_seconds"

        const val HTTP_PING_DEFAULT_URL =
            "https://clients3.google.com/generate_204"

        const val HTTP_PING_LEGACY_URL =
            "https://www.google.com/generate_204"

        const val HTTP_PING_DEFAULT_INTERVAL_SECONDS =
            10

        const val HTTP_PING_MIN_INTERVAL_SECONDS =
            1
    }
}
