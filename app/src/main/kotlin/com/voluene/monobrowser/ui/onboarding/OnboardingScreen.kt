package com.voluene.monobrowser.ui.onboarding

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.settings.AppLanguage
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.settings.SearchEngine
import com.voluene.monobrowser.settings.ToolbarPosition
import com.voluene.monobrowser.ui.common.MonoButton

/** Pure step arithmetic (desktop `onboarding.ts`: clampStep / nextStep / prevStep / isLastStep). */
object OnboardingSteps {
    const val COUNT = 4 // welcome, search engine, toolbar position, default browser
    fun clamp(index: Int) = index.coerceIn(0, COUNT - 1)
    fun next(index: Int) = minOf(COUNT - 1, index + 1)
    fun prev(index: Int) = maxOf(0, index - 1)
    fun isLast(index: Int) = index >= COUNT - 1
}

/**
 * Returns an action that asks Android to make this app the default browser: the ROLE_BROWSER request dialog on
 * API 29+ (needs `startActivityForResult`), otherwise (or when unavailable / already held) the default-apps settings.
 */
@Composable
fun rememberDefaultBrowserRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return remember(context) {
        {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) && !roles.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                } else {
                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                }
            } else {
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            }
            runCatching { launcher.launch(intent) }.onFailure { runCatching { launcher.launch(Intent(Settings.ACTION_SETTINGS)) } }
        }
    }
}

/**
 * First-launch welcome flow (shown once, skippable): welcome + language, search engine, toolbar position,
 * default browser. Every choice is applied immediately; [onFinish] marks the flow as done.
 */
@Composable
fun OnboardingScreen(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, onFinish: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val requestDefault = rememberDefaultBrowserRequest()
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step = OnboardingSteps.prev(step) }
    BackHandler(enabled = step == 0, onBack = onFinish)

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.onboarding_step, step + 1, OnboardingSteps.COUNT),
                color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            MonoButton(stringResource(R.string.onboarding_skip), onFinish)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp).background(scheme.outlineVariant)) {
            Box(Modifier.fillMaxWidth((step + 1) / OnboardingSteps.COUNT.toFloat()).height(2.dp).background(scheme.onBackground))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                0 -> {
                    Text(stringResource(R.string.app_name), color = scheme.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text(stringResource(R.string.onboarding_welcome_pitch), color = scheme.onBackground, fontSize = 17.sp)
                    Heading(R.string.onboarding_language)
                    Text(stringResource(R.string.onboarding_language_hint), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
                    Choice(stringResource(R.string.settings_language_system), null, settings.language == AppLanguage.SYSTEM) { onChange { it.copy(language = AppLanguage.SYSTEM) } }
                    Choice("Polski", null, settings.language == AppLanguage.PL) { onChange { it.copy(language = AppLanguage.PL) } }
                    Choice("English", null, settings.language == AppLanguage.EN) { onChange { it.copy(language = AppLanguage.EN) } }
                }
                1 -> {
                    Heading(R.string.onboarding_search_heading)
                    Text(stringResource(R.string.onboarding_search_text), color = scheme.onBackground, fontSize = 15.sp)
                    Choice("Google", stringResource(R.string.onboarding_google_hint), settings.searchEngine == SearchEngine.GOOGLE) { onChange { it.copy(searchEngine = SearchEngine.GOOGLE) } }
                    Choice("DuckDuckGo", stringResource(R.string.onboarding_ddg_hint), settings.searchEngine == SearchEngine.DUCKDUCKGO) { onChange { it.copy(searchEngine = SearchEngine.DUCKDUCKGO) } }
                    Text(stringResource(R.string.onboarding_search_more), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                2 -> {
                    Heading(R.string.onboarding_toolbar_heading)
                    Text(stringResource(R.string.onboarding_toolbar_text), color = scheme.onBackground, fontSize = 15.sp)
                    Choice(stringResource(R.string.settings_toolbar_bottom), stringResource(R.string.onboarding_toolbar_bottom_hint), settings.toolbarPosition == ToolbarPosition.BOTTOM) { onChange { it.copy(toolbarPosition = ToolbarPosition.BOTTOM) } }
                    Choice(stringResource(R.string.settings_toolbar_top), stringResource(R.string.onboarding_toolbar_top_hint), settings.toolbarPosition == ToolbarPosition.TOP) { onChange { it.copy(toolbarPosition = ToolbarPosition.TOP) } }
                }
                else -> {
                    Heading(R.string.onboarding_default_heading)
                    Text(stringResource(R.string.onboarding_default_text), color = scheme.onBackground, fontSize = 15.sp)
                    MonoButton(stringResource(R.string.onboarding_default_button), requestDefault, Modifier.fillMaxWidth(), inverted = true)
                    Text(stringResource(R.string.onboarding_privacy_note), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.onboarding_back), { step = OnboardingSteps.prev(step) }, Modifier.weight(1f), enabled = step > 0)
            MonoButton(
                stringResource(if (OnboardingSteps.isLast(step)) R.string.onboarding_start else R.string.onboarding_next),
                { if (OnboardingSteps.isLast(step)) onFinish() else step = OnboardingSteps.next(step) },
                Modifier.weight(1f), inverted = true,
            )
        }
    }
}

@Composable
private fun Heading(res: Int) {
    Text(stringResource(res), color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Choice(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.onBackground else scheme.background
    val fg = if (selected) scheme.background else scheme.onBackground
    Column(Modifier.fillMaxWidth().background(bg).border(2.dp, scheme.outline, RectangleShape).clickable(onClick = onClick).padding(14.dp)) {
        Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, color = fg.copy(alpha = 0.75f), fontSize = 13.sp)
    }
}
