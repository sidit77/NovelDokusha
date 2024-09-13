package my.noveldokusha.text_to_speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import me.nanihadesuka.algorithms.delimiterAwareTextSplitter

interface Utterance<T : Utterance<T>> {
    enum class PlayState { PLAYING, FINISHED, LOADING }

    val utteranceId: String
    val playState: PlayState
    fun copyWithState(playState: PlayState): T
}

data class VoiceData(
    val id: String,
    val language: String,
    val needsInternet: Boolean,
    val quality: Int,
)

class TextToSpeechManager<T : Utterance<T>>(
    context: Context,
    initialItemState: T,
    engine: String
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _queueList = mutableMapOf<String, T>()
    private val _queueListItemSize = mutableMapOf<String, Int>()
    private val _currentTextSpeakFlow = MutableSharedFlow<T>()
    val availableVoices = mutableStateListOf<VoiceData>()
    val voiceSpeed = mutableFloatStateOf(1f)
    val voicePitch = mutableFloatStateOf(1f)
    val activeVoice = mutableStateOf<VoiceData?>(null)
    val serviceLoadedFlow = MutableSharedFlow<Unit>(replay = 1)

    val queueList = _queueList as Map<String, T>
    val currentTextSpeakFlow = _currentTextSpeakFlow.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed()
    )

    val service = TextToSpeech(context, {
        when (it) {
            TextToSpeech.SUCCESS -> {
                listenToUtterances()
                availableVoices.addAll(getAvailableVoices())
                updateActiveVoice()
                scope.launch { serviceLoadedFlow.emit(Unit) }
            }
            TextToSpeech.ERROR -> Unit
            else -> Unit
        }
    }, engine)

    val currentActiveItemState = mutableStateOf(initialItemState)

    fun stop() {
        service.stop()
        _queueList.clear()
        _queueListItemSize.clear()
    }

    fun speak(text: String, textSynthesis: T) {
        val subItems = delimiterAwareTextSplitter(
            fullText = text,
            maxSliceLength = maxStringLengthPerTextUnit(),
            charDelimiter = '.'
        )
        _queueList[textSynthesis.utteranceId] = textSynthesis
        _queueListItemSize[textSynthesis.utteranceId] = subItems.size

        subItems.forEachIndexed { index, textSlice ->
            val uniqueID = "$index|${textSynthesis.utteranceId}"
            val bundle = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uniqueID)
            }
            service.speak(textSlice, TextToSpeech.QUEUE_ADD, bundle, uniqueID)
        }
    }

    fun setCurrentSpeakState(textSynthesis: T) {
        currentActiveItemState.value = textSynthesis
        scope.launch { _currentTextSpeakFlow.emit(textSynthesis) }
    }

    fun trySetVoiceById(id: String): Boolean {
        val voice = service.voices.find { it.name == id } ?: return false
        service.voice = voice
        updateActiveVoice()
        return true
    }

    fun trySetVoicePitch(value: Float): Boolean {
        if (value < 0.1 || value > 5) return false
        val result = service.setPitch(value)
        if (result == TextToSpeech.SUCCESS) {
            voicePitch.floatValue = value
            return true
        }
        return false
    }

    fun trySetVoiceSpeed(value: Float): Boolean {
        if (value < 0.1 || value > 5) return false
        val result = service.setSpeechRate(value)
        if (result == TextToSpeech.SUCCESS) {
            voiceSpeed.floatValue = value
            return true
        }
        return false
    }

    private fun maxStringLengthPerTextUnit(): Int {
        return TextToSpeech.getMaxSpeechInputLength()
    }

    private fun updateActiveVoice() {
        activeVoice.value = service.voice?.toVoiceData()
    }

    private fun Voice.toVoiceData() = VoiceData(
        id = name,
        language = locale.displayLanguage,
        needsInternet = isNetworkConnectionRequired,
        quality = quality
    )

    private fun getAvailableVoices(): List<VoiceData> {
        return service.voices.map { it.toVoiceData() }
    }

    private fun listenToUtterances() {
        service.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == null) return

                val itemUtteranceIndex = utteranceId
                    .substringBefore('|', "")
                    .toIntOrNull() ?: return
                val isFirstSubItem = itemUtteranceIndex == 0
                if (!isFirstSubItem) {
                    return
                }

                val itemUtteranceId = utteranceId.substringAfter('|')
                val res: T = _queueList[itemUtteranceId]
                    ?.copyWithState(playState = Utterance.PlayState.PLAYING)
                    ?: return

                currentActiveItemState.value = res
                scope.launch { _currentTextSpeakFlow.emit(res) }
            }

            override fun onDone(utteranceId: String?) {
                onErrorCall(utteranceId = utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onErrorCall(utteranceId = utteranceId)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
            }

            private fun onErrorCall(utteranceId: String?) {
                if (utteranceId == null) return
                val subItemUtteranceIndex = utteranceId
                    .substringBefore('|', "")
                    .toIntOrNull() ?: return
                val itemUtteranceId = utteranceId.substringAfter('|')

                val itemSize = _queueListItemSize[itemUtteranceId]?.minus(1) ?: return
                val isSubItemLastIndex = itemSize == subItemUtteranceIndex
                if (!isSubItemLastIndex) {
                    return
                }

                val res: T = _queueList[itemUtteranceId]
                    ?.copyWithState(playState = Utterance.PlayState.FINISHED)
                    ?: return

                _queueList.remove(itemUtteranceId)
                _queueListItemSize.remove(itemUtteranceId)

                currentActiveItemState.value = res
                scope.launch { _currentTextSpeakFlow.emit(res) }
            }
        })
    }
}