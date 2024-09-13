package my.noveldokusha.settings.views

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsEngineDialog(
    onEngineChange: (packageName: String) -> Unit,
    visible: Boolean,
    setVisible: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var engines by remember { mutableStateOf<List<TtsEngine>?>(null) }
    val state = rememberLazyListState()
    if (visible) BasicAlertDialog(onDismissRequest = { setVisible(false) }) {
        if (engines == null) {
            engines = listOf(TtsEngine (
                name = stringResource(R.string.follow_system),
                packageName = ""
            ))  + getEngines(context)
        }
        Card {
            LazyColumn(
                state = state,
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(engines!!) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { onEngineChange(it.packageName) }) {
                             Text(
                                 text = it.name,
                                 modifier = Modifier.weight(1f)
                             )
                        }
                    }
                }
            }
        }
    }
}

data class TtsEngine(
    val name: String,
    val packageName: String,
)

fun getEngines(context: Context): List<TtsEngine> {
    val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
    return context
        .packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map {
            TtsEngine(
                name = getPackageName(context, it.activityInfo.packageName),
                packageName = it.activityInfo.packageName
            )
        }
}

fun getPackageName(context: Context, packageName: String): String {
    //TODO Handle not found cases
    val info = context.packageManager.getApplicationInfo(packageName, 0)
    return context.packageManager.getApplicationLabel(info).toString()
}