package cc.jaxy.anlobehub.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.designsystem.component.AnTextField
import cc.jaxy.anlobehub.core.designsystem.component.AvatarImage
import cc.jaxy.anlobehub.core.designsystem.component.AnTopBar
import cc.jaxy.anlobehub.core.designsystem.component.ErrorBox
import cc.jaxy.anlobehub.core.designsystem.component.InitialAvatar
import cc.jaxy.anlobehub.core.designsystem.component.SectionTitle
import cc.jaxy.anlobehub.core.designsystem.component.SkeletonList
import cc.jaxy.anlobehub.core.designsystem.text.resolve
import cc.jaxy.anlobehub.core.designsystem.theme.spacing
import cc.jaxy.anlobehub.core.designsystem.R as DsR

/**
 * Standalone create/edit page. `agentId == null` creates a new agent.
 * Field set mirrors web `CreateAgentSchema` core: avatar/title/description/
 * model/systemRole/openingMessage/openingQuestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    agentId: String?,
    onBack: () -> Unit,
    onOpenModelPicker: (current: AIModel?) -> Unit,
    onCreated: (String) -> Unit = {},
    viewModel: AgentEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val errorText = uiState.error?.resolve()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(agentId) { viewModel.bind(agentId) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(uiState.createdId) {
        uiState.createdId?.let {
            viewModel.consumeCreated()
            onCreated(it)
        }
    }
    val savedText = stringResource(R.string.agents_saved)
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) snackbar.showSnackbar(savedText)
    }
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }
    Scaffold(
        topBar = {
            AnTopBar(
                title = if (agentId == null) {
                    stringResource(R.string.agents_create_title)
                } else {
                    stringResource(R.string.agents_edit_title)
                },
                onBack = onBack,
                actions = {
                    if (agentId != null) {
                        IconButton(onClick = { viewModel.duplicate { } }) {
                            Icon(
                                imageVector = Icons.Filled.FileCopy,
                                contentDescription = stringResource(R.string.agents_duplicate),
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.agents_delete),
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !uiState.saving && !uiState.loading,
                    ) {
                        if (uiState.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.agents_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.loading -> SkeletonList(
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            uiState.error != null && agentId != null && uiState.title.isBlank() ->
                ErrorBox(
                    text = uiState.error!!.resolve(),
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            else -> AgentEditBody(
                state = uiState,
                onAvatarChange = viewModel::onAvatarChange,
                onTitleChange = viewModel::onTitleChange,
                onDescriptionChange = viewModel::onDescriptionChange,
                onSystemRoleChange = viewModel::onSystemRoleChange,
                onOpeningMessageChange = viewModel::onOpeningMessageChange,
                onQuestionChange = viewModel::onQuestionChange,
                onAddQuestion = viewModel::addQuestion,
                onRemoveQuestion = viewModel::removeQuestion,
                onOpenModelPicker = {
                    val current = uiState.model.takeIf { it.isNotBlank() }?.let {
                        AIModel(
                            id = it,
                            displayName = uiState.modelDisplayName.takeIf { n -> n.isNotBlank() },
                            providerId = uiState.provider.takeIf { p -> p.isNotBlank() },
                        )
                    }
                    onOpenModelPicker(current)
                },
                onModelClear = viewModel::clearModel,
                onUploadAvatar = { uri ->
                    val prepared = AvatarImage.prepare(context, uri)
                    android.util.Log.d(
                        "AgentEdit",
                        "prepared=${prepared?.bytes?.size}",
                    )
                    if (prepared != null) {
                        viewModel.uploadAvatar(prepared.bytes, prepared.mime)
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agents_delete_title)) },
            text = { Text(stringResource(R.string.agents_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    enabled = !uiState.deleting,
                ) {
                    Text(stringResource(R.string.agents_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(DsR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun AgentEditBody(
    state: AgentEditViewModel.UiState,
    onAvatarChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSystemRoleChange: (String) -> Unit,
    onOpeningMessageChange: (String) -> Unit,
    onQuestionChange: (Int, String) -> Unit,
    onAddQuestion: () -> Unit,
    onRemoveQuestion: (Int) -> Unit,
    onOpenModelPicker: () -> Unit,
    onModelClear: () -> Unit,
    onUploadAvatar: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) onUploadAvatar(uri) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        SectionTitle(text = stringResource(R.string.agents_edit_section_identity))
        var showAvatarPicker by remember { mutableStateOf(false) }
        LaunchedEffect(state.avatar, state.uploading) {
            if (!state.uploading && state.avatar.startsWith("http")) {
                showAvatarPicker = false
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAvatarPicker = true }
                .padding(horizontal = spacing.l, vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            InitialAvatar(
                name = state.title.takeIf { it.isNotBlank() } ?: "?",
                avatarUrl = state.avatar.takeIf { it.isNotBlank() },
                size = 56.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agents_edit_avatar_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.agents_avatar_tap_to_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showAvatarPicker) {
            AvatarPickerSheet(
                current = state.avatar,
                uploading = state.uploading,
                onPick = {
                    onAvatarChange(it)
                    showAvatarPicker = false
                },
                onUploadClick = { imagePicker.launch("image/*") },
                onDismiss = { if (!state.uploading) showAvatarPicker = false },
            )
        }
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            AnTextField(
                value = state.title,
                onValueChange = onTitleChange,
                label = stringResource(R.string.agents_create_title_label),
                modifier = Modifier.fillMaxWidth(),
                isError = state.titleError,
                supportingText = if (state.titleError) {
                    { Text(stringResource(R.string.agents_create_title_required)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            AnTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = stringResource(R.string.agents_edit_description_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
        }
        SectionTitle(text = stringResource(R.string.agents_edit_section_model))
        ListItem(
            headlineContent = { Text(stringResource(R.string.agents_create_model_label)) },
            supportingContent = {
                Text(
                    state.modelDisplayName.takeIf { it.isNotBlank() }
                        ?: state.model.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.agents_create_model_auto),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.model.isNotBlank()) {
                        IconButton(onClick = onModelClear) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(DsR.string.common_clear),
                            )
                        }
                    }
                    Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenModelPicker),
        )
        SectionTitle(text = stringResource(R.string.agents_edit_section_prompt))
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            AnTextField(
                value = state.systemRole,
                onValueChange = onSystemRoleChange,
                label = stringResource(R.string.agents_create_system_role_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
        }
        SectionTitle(text = stringResource(R.string.agents_edit_section_opening))
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            AnTextField(
                value = state.openingMessage,
                onValueChange = onOpeningMessageChange,
                label = stringResource(R.string.agents_edit_opening_message_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            state.openingQuestions.forEachIndexed { index, question ->
                AnTextField(
                    value = question,
                    onValueChange = { onQuestionChange(index, it) },
                    label = stringResource(R.string.agents_edit_opening_question_label, index + 1),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { onRemoveQuestion(index) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(DsR.string.common_delete),
                            )
                        }
                    },
                )
            }
            OutlinedButton(onClick = onAddQuestion) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.agents_edit_add_question))
            }
        }
    }
}
