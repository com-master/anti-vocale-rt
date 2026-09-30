package com.antivocale.app.ui.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.antivocale.app.transcription.TimedSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.antivocale.app.audio.live.AudioLevel
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.ui.components.CappedTranscriptText
import com.antivocale.app.ui.theme.AntiVocaleTheme
import com.antivocale.app.ui.theme.TextScale
import com.antivocale.app.ui.theme.fromName
import com.antivocale.app.ui.theme.ThemeMode
import com.antivocale.app.ui.theme.ThemeType
import com.antivocale.app.ui.viewmodel.LiveTranscriptionViewModel
import com.antivocale.app.service.live.LiveSessionController
import com.antivocale.app.service.live.LiveSessionController.ErrorKind
import com.antivocale.app.service.live.LiveSessionController.Status
import com.antivocale.app.util.ClipboardWriter
import com.antivocale.app.util.ToastCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Live mode screen: dictate into the microphone, text appears phrase by phrase
 * (5..10 s behind speech). See [com.antivocale.app.service.live.LiveSessionController] for the pipeline.
 */
@AndroidEntryPoint
class LiveTranscriptionActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeName by preferencesManager.themePreference.collectAsState(initial = PreferencesManager.DEFAULT_THEME)
            val themeModeName by preferencesManager.themeMode.collectAsState(initial = PreferencesManager.DEFAULT_THEME_MODE)
            val textScaleName by preferencesManager.textScalePreference.collectAsState(initial = PreferencesManager.DEFAULT_TEXT_SCALE)
            val theme = runCatching { ThemeType.valueOf(themeName) }.getOrDefault(ThemeType.DEFAULT)
            val mode = runCatching { ThemeMode.valueOf(themeModeName) }.getOrDefault(ThemeMode.SYSTEM)
            AntiVocaleTheme(brand = theme, mode = mode, textScale = TextScale.fromName(textScaleName)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LiveTranscriptionScreen(
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LiveTranscriptionActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranscriptionScreen(
    onBack: () -> Unit,
    viewModel: LiveTranscriptionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
        else ToastCompat.show(context, R.string.live_mic_permission_denied)
    }

    LaunchedEffect(state.segments.size) {
        if (state.segments.isNotEmpty()) listState.animateScrollToItem(state.segments.lastIndex)
    }

    fun copy(text: String) {
        ClipboardWriter.copy(context, context.getString(R.string.live_title), text)
        ToastCompat.show(context, R.string.live_copied)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.live_back))
                    }
                },
                actions = {
                    IconButton(enabled = state.hasText, onClick = { copy(state.plainText) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.live_copy))
                    }
                    IconButton(enabled = state.hasText, onClick = { copy(state.timedText) }) {
                        Icon(Icons.Default.Schedule, contentDescription = stringResource(R.string.live_copy_timed))
                    }
                    IconButton(
                        enabled = state.hasText,
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, state.timedText)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                    ) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.live_share)) }
                },
            )
        },
        floatingActionButton = {
            val running = state.isRunning
            ExtendedFloatingActionButton(
                onClick = {
                    when {
                        running -> viewModel.stop()
                        viewModel.hasMicPermission() -> viewModel.start()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                icon = { Icon(if (running) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null) },
                text = { Text(stringResource(if (running) R.string.live_stop else R.string.live_start)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            StatusHeader(state, onRolesChange = viewModel::setRolesEnabled)
            MicSettings(
                state = state,
                onGain = viewModel::setMicGain,
                onSensitivity = viewModel::setSensitivity,
                onAutoLevel = viewModel::setAutoLevel,
            )
            Spacer(Modifier.height(8.dp))
            if (state.segments.isEmpty()) {
                Text(
                    text = stringResource(R.string.live_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(state.segments) { index, segment ->
                        val newTurn = segment.speaker != null &&
                            segment.speaker != state.segments.getOrNull(index - 1)?.speaker
                        PhraseRow(
                            segment = segment,
                            time = clock.format(Date(state.sessionStartWallMs + segment.startMs)),
                            showSpeaker = newTurn,
                        )
                    }
                }
            }
            // Room for the FAB over the last phrase.
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun PhraseRow(segment: TimedSegment, time: String, showSpeaker: Boolean) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (showSpeaker) {
            LiveTranscriptFormat.speakerLabel(segment)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = speakerColor(segment.speaker ?: 0),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Row {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp).padding(top = 3.dp),
            )
            // The shared render cap (CLAUDE.md): a looping decode
            // must not blow Compose's constraint limit here either.
            CappedTranscriptText(
                text = segment.text,
                searchQuery = "",
                container = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun speakerColor(speaker: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    return palette[speaker % palette.size]
}

/**
 * Quiet-speaker controls: gain (live, before the speech detector), detector
 * sensitivity (next session) and automatic leveling. Collapsed by default;
 * the level meter above shows the effect of the gain while listening.
 */
@Composable
private fun MicSettings(
    state: LiveSessionController.LiveUiState,
    onGain: (Float) -> Unit,
    onSensitivity: (Float) -> Unit,
    onAutoLevel: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.live_mic_settings))
            Spacer(Modifier.width(4.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (!expanded) return@Column
        Text(
            text = stringResource(R.string.live_gain, state.micGain),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = state.micGain,
            onValueChange = { onGain((it * 2).roundToInt() / 2f) },
            valueRange = AudioLevel.MIN_GAIN..AudioLevel.MAX_GAIN,
        )
        Text(
            text = stringResource(R.string.live_sensitivity, (state.sensitivity * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = state.sensitivity,
            onValueChange = { onSensitivity((it * 20).roundToInt() / 20f) },
            valueRange = 0f..1f,
        )
        if (state.isRunning) {
            Text(
                text = stringResource(R.string.live_sensitivity_next),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.live_auto_level),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.autoLevel, onCheckedChange = onAutoLevel)
        }
    }
}

@Composable
private fun StatusHeader(
    state: LiveSessionController.LiveUiState,
    onRolesChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.rolesEnabled,
                enabled = !state.isRunning,
                onClick = { onRolesChange(!state.rolesEnabled) },
                label = { Text(stringResource(R.string.live_roles)) },
            )
            Spacer(Modifier.width(12.dp))
            state.backendName?.let {
                Text(
                    text = stringResource(R.string.live_model, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val statusText = when (state.status) {
            Status.IDLE -> stringResource(R.string.live_status_idle)
            Status.LOADING ->
                if (state.preparingRoles) stringResource(R.string.live_status_loading_roles)
                else stringResource(R.string.live_status_loading)
            Status.LISTENING ->
                if (state.speaking) stringResource(R.string.live_status_speech, state.bufferedSeconds)
                else stringResource(R.string.live_status_listening)
            Status.FINISHING -> stringResource(R.string.live_status_finishing)
            Status.ERROR -> stringResource(R.string.live_status_error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = statusText, style = MaterialTheme.typography.titleSmall)
            if (state.pendingPhrases > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.live_pending, state.pendingPhrases, state.pendingPhrases),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.status == Status.LOADING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (state.status == Status.LISTENING) {
            LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
        }
        if (state.savedToHistory) {
            val saved = state.savedFileName?.let { stringResource(R.string.live_saved_file, it) }
                ?: stringResource(R.string.live_saved_history)
            Text(
                text = saved,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { kind ->
            val message = when (kind) {
                ErrorKind.NO_MODEL -> stringResource(R.string.live_error_no_model)
                ErrorKind.MICROPHONE -> stringResource(R.string.live_error_microphone)
                ErrorKind.BUSY -> stringResource(R.string.live_error_busy)
                ErrorKind.DECODE -> stringResource(R.string.live_error_decode)
                ErrorKind.ROLES_UNAVAILABLE -> stringResource(R.string.live_error_roles)
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
