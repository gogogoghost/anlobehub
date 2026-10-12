package cc.jaxy.anlobehub.feature.auth

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.anlobehub.core.designsystem.component.AnTextField
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.PasswordField
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.resolve
import cc.jaxy.anlobehub.core.designsystem.theme.AnlobehubTheme
import cc.jaxy.anlobehub.core.designsystem.theme.hapticClick
import cc.jaxy.anlobehub.core.designsystem.theme.spacing

@Composable
fun LoginScreen(
    baseUrl: String,
    onLoggedIn: () -> Unit,
    onChangeServer: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(baseUrl) {
        viewModel.attachBaseUrl(baseUrl)
    }
    // 输入框状态放 Screen 层（旋转/返回不丢）；ViewModel 已存（如上次邮箱）则沿用。
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(uiState.email) {
        if (email.isBlank() && uiState.email.isNotBlank()) {
            email = uiState.email
        }
    }
    LoginContent(
        baseUrl = baseUrl,
        email = email,
        password = password,
        loading = uiState.loading,
        error = uiState.error,
        onEmailChange = {
            email = it
            viewModel.onEmailChange(it)
        },
        onPasswordChange = {
            password = it
            viewModel.onPasswordChange(it)
        },
        onLogin = { viewModel.login(onLoggedIn) },
        onChangeServer = onChangeServer,
    )
}

@Composable
private fun LoginContent(
    baseUrl: String,
    email: String,
    password: String,
    loading: Boolean,
    error: UiText?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onChangeServer: () -> Unit = {},
) {
    val view = LocalView.current
    val spacing = MaterialTheme.spacing
    val passwordFocus = remember { FocusRequester() }
    val canLogin = email.isNotBlank() && password.isNotBlank() && !loading
    LaunchedEffect(error) {
        if (error != null) hapticClick(view)
    }
    Scaffold(
        topBar = { AnTopBar(title = stringResource(R.string.auth_login_title), onBack = null) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(spacing.xxl),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                onClick = onChangeServer,
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = spacing.m,
                        vertical = spacing.s,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.auth_change_server),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(spacing.l),
                    )
                    Text(
                        text = baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = spacing.xs),
                    )
                }
            }
            Spacer(Modifier.height(spacing.l))
            Text(
                text = stringResource(R.string.auth_login_welcome),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.auth_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xl))
            AnTextField(
                value = email,
                onValueChange = onEmailChange,
                label = stringResource(R.string.auth_login_email_label),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { passwordFocus.requestFocus() },
                ),
                isError = error != null,
            )
            Spacer(Modifier.height(spacing.m))
            PasswordField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                keyboardActions = KeyboardActions(
                    onDone = { if (canLogin) onLogin() },
                ),
                isError = error != null,
                supportingText = {
                    if (error != null) Text(error.resolve())
                },
            )
            Spacer(Modifier.height(spacing.m))
            Button(
                onClick = {
                    hapticClick(view)
                    onLogin()
                },
                enabled = canLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = spacing.s).size(spacing.l),
                        strokeWidth = spacing.xs2,
                    )
                }
                Text(if (loading) stringResource(R.string.auth_login_submitting) else stringResource(R.string.auth_login_title))
            }
        }
    }
}

@Preview(name = "登录-浅色", showBackground = true)
@Composable
private fun LoginContentLightPreview() {
    AnlobehubTheme(darkTheme = false) {
        LoginContent(
            baseUrl = "https://your-lobehub.example.com",
            email = "user@example.com",
            password = "secret123",
            loading = false,
            error = null,
            onEmailChange = {},
            onPasswordChange = {},
            onLogin = {},
        )
    }
}

@Preview(
    name = "登录-深色",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LoginContentDarkPreview() {
    AnlobehubTheme(darkTheme = true) {
        LoginContent(
            baseUrl = "https://your-lobehub.example.com",
            email = "user@example.com",
            password = "wrong",
            loading = false,
            error = UiText.Raw("邮箱或密码错误"),
            onEmailChange = {},
            onPasswordChange = {},
            onLogin = {},
        )
    }
}
