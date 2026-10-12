package cc.jaxy.anlobehub.feature.settings

import cc.jaxy.anlobehub.core.designsystem.R as DsR
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.anlobehub.core.common.preferences.AppLanguage
import cc.jaxy.anlobehub.core.common.preferences.AppTheme
import cc.jaxy.anlobehub.core.designsystem.component.ErrorBox
import cc.jaxy.anlobehub.core.designsystem.component.InitialAvatar
import cc.jaxy.anlobehub.core.data.user.UserProfile
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.AppearanceCard
import cc.jaxy.anlobehub.core.designsystem.component.ModelBadge
import cc.jaxy.anlobehub.core.designsystem.component.SectionTitle
import cc.jaxy.anlobehub.core.designsystem.component.SettingRow
import cc.jaxy.anlobehub.core.designsystem.component.SkeletonList
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.resolve
import cc.jaxy.anlobehub.core.designsystem.theme.AnlobehubTheme
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onKnowledgeBasesClick: () -> Unit = {},
    onProvidersClick: () -> Unit = {},
) {
    SettingsContent(
        onKnowledgeBasesClick = onKnowledgeBasesClick,
        onProvidersClick = onProvidersClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel = hiltViewModel(),
    onKnowledgeBasesClick: () -> Unit = {},
    onProvidersClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    var showSwitchDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { AnTopBar(title = stringResource(DsR.string.common_settings_title), onBack = null) },
    ) { padding ->
        when {
            uiState.loading && uiState.profile == null && uiState.error == null ->
                SkeletonList(modifier = Modifier.fillMaxSize().padding(padding))
            uiState.error != null && uiState.profile == null && uiState.baseUrl == null ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = { viewModel.load() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else ->
                SettingsBody(
                    profile = uiState.profile,
                    baseUrl = uiState.baseUrl,
                    profileError = if (uiState.profile == null) uiState.error else null,
                    onRetry = { viewModel.load() },
                    onKnowledgeBasesClick = onKnowledgeBasesClick,
                    onProvidersClick = onProvidersClick,
                    onSignOutClick = { showSignOutDialog = true },
                    onSwitchServerClick = { showSwitchDialog = true },
                    theme = theme,
                    language = language,
                    onThemeChange = { viewModel.setTheme(it) },
                    onLanguageChange = { viewModel.setLanguage(it) },
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = { viewModel.setDynamicColor(it) },
                    modifier = Modifier.padding(padding),
                )
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_signout_title)) },
            text = { Text(stringResource(R.string.settings_signout_text)) },
            confirmButton = {
                TextButton(onClick = { showSignOutDialog = false; viewModel.signOut() }) {
                    Text(stringResource(DsR.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(DsR.string.common_cancel))
                }
            },
        )
    }
    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = { Text(stringResource(R.string.switch_title)) },
            text = { Text(stringResource(R.string.switch_text)) },
            confirmButton = {
                TextButton(onClick = { showSwitchDialog = false; viewModel.switchServer() }) {
                    Text(stringResource(DsR.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchDialog = false }) {
                    Text(stringResource(DsR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsBody(
    profile: UserProfile?,
    baseUrl: String?,
    profileError: UiText?,
    onRetry: () -> Unit,
    onKnowledgeBasesClick: () -> Unit,
    onProvidersClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    theme: AppTheme,
    language: AppLanguage,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.server_copied)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = spacing.s),
    ) {
        item(key = "account-title") { SectionTitle(text = stringResource(R.string.section_account)) }
        item(key = "account-card") {
            AccountCard(
                profile = profile,
                error = profileError,
                onRetry = onRetry,
                modifier = Modifier.padding(horizontal = spacing.l),
            )
        }
        item(key = "server-title") { SectionTitle(text = stringResource(R.string.section_server)) }
        item(key = "server-card") {
            Card(modifier = Modifier.padding(horizontal = spacing.l)) {
                SettingRow(
                    icon = Icons.Filled.Dns,
                    title = stringResource(R.string.server_address),
                    subtitle = baseUrl ?: stringResource(R.string.server_unset),
                    onClick = {
                        if (!baseUrl.isNullOrBlank()) {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("baseUrl", baseUrl))) }
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    trailing = {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                    },
                )
            }
        }
        item(key = "appearance-title") { SectionTitle(text = stringResource(R.string.section_appearance)) }
        item(key = "appearance-card") {
            AppearanceCard(
                theme = theme,
                language = language,
                onThemeChange = onThemeChange,
                onLanguageChange = onLanguageChange,
                dynamicColor = dynamicColor,
                onDynamicColorChange = onDynamicColorChange,
                modifier = Modifier.padding(horizontal = spacing.l),
            )
        }
        item(key = "content-title") { SectionTitle(text = stringResource(R.string.section_content)) }
        item(key = "content-card") {
            Card(modifier = Modifier.padding(horizontal = spacing.l)) {
                Column {
                    SettingRow(
                        icon = Icons.Filled.Cloud,
                        title = stringResource(R.string.providers_title),
                        subtitle = stringResource(R.string.browse),
                        onClick = onProvidersClick,
                    )
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Filled.Folder,
                        title = stringResource(R.string.kb_title),
                        subtitle = stringResource(R.string.browse),
                        onClick = onKnowledgeBasesClick,
                    )
                }
            }
        }
        item(key = "action-title") { SectionTitle(text = stringResource(R.string.section_actions)) }
        item(key = "action-card") {
            Card(modifier = Modifier.padding(horizontal = spacing.l)) {
                Column {
                    DangerSettingRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = stringResource(R.string.settings_signout_title),
                        subtitle = stringResource(R.string.settings_signout_subtitle),
                        onClick = onSignOutClick,
                    )
                    HorizontalDivider()
                    SettingRow(
                        icon = Icons.Filled.SwapHoriz,
                        title = stringResource(R.string.switch_title),
                        subtitle = stringResource(R.string.switch_subtitle),
                        onClick = onSwitchServerClick,
                    )
                }
            }
        }
        item(key = "version") {
            val context = androidx.compose.ui.platform.LocalContext.current
            val version = remember(context) {
                runCatching {
                    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        context.packageManager.getPackageInfo(
                            context.packageName,
                            android.content.pm.PackageManager.PackageInfoFlags.of(0),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    }
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        info.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    }
                    "${info.versionName} ($code)"
                }.getOrDefault("?")
            }
            Text(
                text = stringResource(R.string.version_format, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xl),
            )
        }
    }
}


@Composable
private fun AccountCard(
    profile: UserProfile?,
    error: UiText?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val signedOutLabel = stringResource(R.string.account_signed_out)
    val displayName = profile?.fullName?.takeIf { it.isNotBlank() }
        ?: profile?.username?.takeIf { it.isNotBlank() }
        ?: signedOutLabel
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialAvatar(name = displayName, avatarUrl = profile?.avatar, size = 56.dp)
            Spacer(Modifier.width(spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile != null) {
                        Spacer(Modifier.width(spacing.s))
                        ModelBadge(text = stringResource(R.string.account_signed_in))
                    }
                }
                val email = profile?.email?.takeIf { it.isNotBlank() }
                if (email != null) {
                    Spacer(Modifier.height(spacing.xs2))
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (profile == null && error != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier.padding(
                    start = spacing.l,
                    end = spacing.s,
                    top = spacing.s,
                    bottom = spacing.s,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(DsR.string.common_retry))
                }
            }
        }
    }
}

@Composable
private fun DangerSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val danger = MaterialTheme.colorScheme.error
    ListItem(
        headlineContent = { Text(title, color = danger) },
        modifier = modifier.clickable(onClick = onClick),
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else {
            null
        },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = danger)
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
    )
}

@Preview(name = "设置-浅色", showBackground = true)
@Composable
private fun SettingsBodyLightPreview() {
    AnlobehubTheme(darkTheme = false) {
        SettingsBody(
            profile = UserProfile(
                userId = "u1",
                avatar = null,
                email = "demo@anlobehub.com",
                fullName = "安罗",
                username = "anlo",
            ),
            baseUrl = "https://demo.anlobehub.com",
            profileError = null,
            theme = AppTheme.SYSTEM,
            language = AppLanguage.SYSTEM,
            onThemeChange = {},
            onLanguageChange = {},
            onRetry = {},
            onKnowledgeBasesClick = {},
            onProvidersClick = {},
            onSignOutClick = {},
            onSwitchServerClick = {},
        )
    }
}

@Preview(name = "设置-深色", showBackground = true)
@Composable
private fun SettingsBodyDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        SettingsBody(
            profile = null,
            baseUrl = "https://demo.anlobehub.com",
            profileError = UiText.Raw("Failed to load user info. Please retry."),
            theme = AppTheme.DARK,
            language = AppLanguage.CHINESE,
            onThemeChange = {},
            onLanguageChange = {},
            onRetry = {},
            onKnowledgeBasesClick = {},
            onProvidersClick = {},
            onSignOutClick = {},
            onSwitchServerClick = {},
        )
    }
}
