package my.noveldokusha.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import my.noveldoksuha.coreui.theme.ColorAccent
import my.noveldoksuha.coreui.theme.textPadding
import my.noveldokusha.settings.R
import my.noveldokusha.settings.views.TtsEngineDialog
import my.noveldokusha.settings.views.getPackageName

@Composable
internal fun SettingsTtsEngine(
    state: MutableState<String>,
) {
    val currentEngine = state.value
    val currentName = when(currentEngine) {
        "" -> stringResource(R.string.follow_system)
        else -> getPackageName(LocalContext.current, currentEngine)
    }
    var isDialogVisible by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(
            text = stringResource(id = R.string.tts_engine),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = ColorAccent
        )
        ListItem(
            headlineContent = {
                Text(text = stringResource(R.string.change_default_tts_engine))
            },
            supportingContent = {
                Column {
                    Text(text = currentName)
                }
            },
            leadingContent = {
                Icon(Icons.Outlined.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.onPrimary)
            },
            modifier = Modifier.clickable {
                isDialogVisible = true
            }
        )
    }
    TtsEngineDialog (
        onEngineChange = {
            state.value = it
            isDialogVisible = false
        },
        visible = isDialogVisible,
        setVisible = { isDialogVisible = it }
    )
}

