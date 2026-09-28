package com.antivocale.app.ui.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import com.antivocale.app.ui.viewmodel.LiveTranscriptionViewModel.ErrorKind
import com.antivocale.app.ui.viewmodel.LiveTranscriptionViewModel.Status
import com.antivocale.app.util.ClipboardWriter
import com.antivocale.app.util.ToastCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Live mode screen: dictate into the microphone, text appears phrase by phrase
 * (5..10 s behind speech). See [LiveTranscriptionViewModel] for the pipeline.
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
                        onKeepScreenOn = { on ->
                            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        },
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
    onKeepScreenOn: (Boolean) -> Unit,
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

    LaunchedEffect(state.isRunning) { onKeepScreenOn(state.isRunning) }
    LaunchedEffect(state.phrases.size) {
        if (state.phrases.isNotEmpty()) listState.animateScrollToItem(state.phrases.lastIndex)
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
                    val hasText = state.fullText.isNotEmpty()
                    IconButton(
                        enabled = hasText,
                        onClick = {
                            ClipboardWriter.copy(context, context.getString(R.string.live_title), state.fullText)
                            ToastCompat.show(context, R.string.live_copied)
                        },
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.live_copy)) }
                    IconButton(
                        enabled = hasText,
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, state.fullText)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        },
                    ) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.live_share)) }
                    IconButton(
                        enabled = hasText && !state.isRunning,
                        onClick = { viewModel.clear() },
                    ) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.live_clear)) }
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
            StatusHeader(state)
            Spacer(Modifier.height(8.dp))
            if (state.phrases.isEmpty()) {
                Text(
                    text = stringResource(R.string.live_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(state.phrases) { _, phrase ->
                        Row(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = formatClock(phrase.startSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(48.dp).padding(top = 3.dp),
                            )
                            // The shared render cap (CLAUDE.md): a looping decode
                            // must not blow Compose's constraint limit here either.
                            CappedTranscriptText(
                                text = phrase.text,
                                searchQuery = "",
                                container = MaterialTheme.colorScheme.surface,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            // Room for the FAB over the last phrase.
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun StatusHeader(state: LiveTranscriptionViewModel.LiveUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.backendName?.let {
            Text(
                text = stringResource(R.string.live_model, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val statusText = when (state.status) {
            Status.IDLE -> stringResource(R.string.live_status_idle)
            Status.LOADING -> stringResource(R.string.live_status_loading)
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
        state.error?.let { kind ->
            val message = when (kind) {
                ErrorKind.NO_MODEL -> stringResource(R.string.live_error_no_model)
                ErrorKind.MICROPHONE -> stringResource(R.string.live_error_microphone)
                ErrorKind.BUSY -> stringResource(R.string.live_error_busy)
                ErrorKind.DECODE -> stringResource(R.string.live_error_decode)
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatClock(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
