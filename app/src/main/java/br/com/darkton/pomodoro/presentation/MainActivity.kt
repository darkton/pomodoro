package br.com.darkton.pomodoro.presentation

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material3.*
import br.com.darkton.pomodoro.R
import br.com.darkton.pomodoro.data.PomodoroState
import br.com.darkton.pomodoro.presentation.theme.PomodoroTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            PomodoroTheme {
                val viewModel: PomodoroViewModel = viewModel()
                NotificationPermissionEffect()
                PomodoroApp(viewModel)
            }
        }
    }
}

@Composable
fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ -> }
        SideEffect {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun PomodoroApp(viewModel: PomodoroViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val navigationStack = remember { mutableStateListOf<AppScreen>(AppScreen.Main) }
    val currentScreen = navigationStack.last()

    AppScaffold {
        SwipeToDismissBox(
            onDismissed = {
                if (navigationStack.size > 1) {
                    navigationStack.removeLast()
                }
            },
            userSwipeEnabled = navigationStack.size > 1
        ) { isBackground ->
            val screenToShow = if (isBackground) {
                if (navigationStack.size > 1) navigationStack[navigationStack.size - 2] else null
            } else {
                currentScreen
            }

            screenToShow?.let { screen ->
                when (screen) {
                    AppScreen.Main -> MainScreen(
                        state = state,
                        onStart = { viewModel.startTimer() },
                        onPause = { viewModel.pauseTimer() },
                        onReset = { viewModel.resetTimer() },
                        onSettings = { navigationStack.add(AppScreen.Settings) }
                    )
                    AppScreen.Settings -> SettingsMenu(
                        onDismiss = { navigationStack.removeLast() },
                        onSelectFocus = { navigationStack.add(AppScreen.PickerFocus) },
                        onSelectBreak = { navigationStack.add(AppScreen.PickerBreak) },
                        onSelectRounds = { navigationStack.add(AppScreen.PickerRounds) }
                    )
                    AppScreen.PickerFocus -> FullScreenPicker(
                        label = "Focus time",
                        initialValue = state.focusMinutes,
                        range = 5..90,
                        onValueSelected = { value ->
                            viewModel.updateConfig(value, state.breakMinutes, state.totalRounds)
                            navigationStack.removeLast()
                        }
                    )
                    AppScreen.PickerBreak -> FullScreenPicker(
                        label = "Break time",
                        initialValue = state.breakMinutes,
                        range = 1..30,
                        onValueSelected = { value ->
                            viewModel.updateConfig(state.focusMinutes, value, state.totalRounds)
                            navigationStack.removeLast()
                        }
                    )
                    AppScreen.PickerRounds -> FullScreenPicker(
                        label = "Total rounds",
                        initialValue = state.totalRounds,
                        range = 1..12,
                        onValueSelected = { value ->
                            viewModel.updateConfig(state.focusMinutes, state.breakMinutes, value)
                            navigationStack.removeLast()
                        }
                    )
                }
            }
        }
    }
}

sealed class AppScreen {
    data object Main : AppScreen()
    data object Settings : AppScreen()
    data object PickerFocus : AppScreen()
    data object PickerBreak : AppScreen()
    data object PickerRounds : AppScreen()
}

@Composable
fun MainScreen(
    state: br.com.darkton.pomodoro.data.PomodoroPreferences,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onSettings: () -> Unit
) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.timerEndTimestamp, state.currentState) {
        if (state.timerEndTimestamp != null && (state.currentState == PomodoroState.FOCUS || state.currentState == PomodoroState.BREAK)) {
            while (true) {
                currentTime = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    val remainingMs = when (state.currentState) {
        PomodoroState.PAUSED -> state.remainingMillis
        else -> state.timerEndTimestamp?.let { it - currentTime } ?: 0L
    }
    
    val displayMs = if (remainingMs > 0) remainingMs else 0L
    val minutes = (displayMs / 1000) / 60
    val seconds = (displayMs / 1000) % 60

    val statusColor by animateColorAsState(
        targetValue = when (state.currentState) {
            PomodoroState.FOCUS -> MaterialTheme.colorScheme.primary
            PomodoroState.BREAK -> MaterialTheme.colorScheme.tertiary
            PomodoroState.PAUSED -> MaterialTheme.colorScheme.outline
            PomodoroState.COMPLETED -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "StatusColor"
    )

    val isActive = state.currentState != PomodoroState.IDLE && state.currentState != PomodoroState.COMPLETED
    val isRunning = state.currentState == PomodoroState.FOCUS || state.currentState == PomodoroState.BREAK

    ScreenScaffold(
        timeText = { TimeText() },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (state.currentState) {
                        PomodoroState.FOCUS -> stringResource(R.string.focus_label)
                        PomodoroState.BREAK -> stringResource(R.string.break_label)
                        PomodoroState.PAUSED -> "Paused"
                        PomodoroState.COMPLETED -> stringResource(R.string.completed_label)
                        else -> "Pomodoro"
                    },
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${state.currentRound}/${state.totalRounds}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = 64.sp,
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        ),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        )
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val mainActionInteractionSource = remember { MutableInteractionSource() }
                    val mainActionPressed by mainActionInteractionSource.collectIsPressedAsState()
                    val mainActionScale by animateFloatAsState(if (mainActionPressed) 0.85f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "MainActionScale")

                    Button(
                        onClick = if (isRunning) onPause else onStart,
                        interactionSource = mainActionInteractionSource,
                        modifier = Modifier
                            .graphicsLayer(scaleX = mainActionScale, scaleY = mainActionScale)
                            .size(54.dp),
                        shape = if (isRunning) RoundedCornerShape(16.dp) else CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isRunning) R.drawable.pause_24px else R.drawable.play_arrow_24px
                            ),
                            contentDescription = if (isRunning) "Pause" else "Start",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (!isActive) {
                        val settingsInteractionSource = remember { MutableInteractionSource() }
                        val settingsPressed by settingsInteractionSource.collectIsPressedAsState()
                        val settingsScale by animateFloatAsState(if (settingsPressed) 0.85f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "SettingsScale")

                        FilledTonalIconButton(
                            onClick = onSettings,
                            interactionSource = settingsInteractionSource,
                            modifier = Modifier
                                    .graphicsLayer(scaleX = settingsScale, scaleY = settingsScale)
                                    .size(52.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.settings_24px),
                                contentDescription = "Settings",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        val stopInteractionSource = remember { MutableInteractionSource() }
                        val stopPressed by stopInteractionSource.collectIsPressedAsState()
                        val stopScale by animateFloatAsState(if (stopPressed) 0.85f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "StopScale")

                        Button(
                            onClick = onReset,
                            interactionSource = stopInteractionSource,
                            modifier = Modifier
                                    .graphicsLayer(scaleX = stopScale, scaleY = stopScale)
                                    .size(54.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.stop_24px),
                                contentDescription = "Stop",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsMenu(
    onDismiss: () -> Unit,
    onSelectFocus: () -> Unit,
    onSelectBreak: () -> Unit,
    onSelectRounds: () -> Unit
) {
    val scrollState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = scrollState,
        timeText = { TimeText() },
        bottomButton = {
            EdgeButton(
                onClick = onDismiss
            ) {
                Text("Done")
            }
        }
    ) {
        ScalingLazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(16.dp, 32.dp, 16.dp, 48.dp)
        ) {
            item {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                TitleCard(
                    onClick = onSelectFocus,
                    title = { Text("Focus time") }
                ) {
                    Text("Set duration for focus sessions")
                }
            }
            item {
                TitleCard(
                    onClick = onSelectBreak,
                    title = { Text("Text for breaks") }
                ) {
                    Text("Set duration for breaks")
                }
            }
            item {
                TitleCard(
                    onClick = onSelectRounds,
                    title = { Text("Total rounds") }
                ) {
                    Text("Set number of sessions")
                }
            }
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun FullScreenPicker(
    label: String,
    initialValue: Int,
    range: IntRange,
    onValueSelected: (Int) -> Unit
) {
    val items = range.toList()
    val state = rememberPickerState(
        initialNumberOfOptions = items.size
    )

    LaunchedEffect(items, initialValue) {
        val index = items.indexOf(initialValue)
        if (index >= 0) {
            state.scrollToOption(index)
        }
    }

    val focusRequester = rememberActiveFocusRequester()
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    LaunchedEffect(state.selectedOptionIndex) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    val scrollInfoProvider = remember {
        object : ScrollInfoProvider {
            override val isScrollAwayValid get() = true
            override val isScrollable get() = true
            override val isScrollInProgress get() = false
            override val anchorItemOffset get() = 0f
            override val lastItemOffset get() = 1f
        }
    }

    ScreenScaffold(
        timeText = { TimeText() },
        scrollInfoProvider = scrollInfoProvider,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        coroutineScope.launch {
                            val current = state.selectedOptionIndex
                            state.scrollToOption(
                                current + if (event.verticalScrollPixels > 0) 1 else -1
                            )
                        }
                        true
                    }
                    .focusRequester(focusRequester)
                    .focusable()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Picker(
                        state = state,
                        contentDescription = label,
                        modifier = Modifier.height(110.dp)
                    ) { index ->
                        val isSelected = index == state.selectedOptionIndex
                        val fontSize by animateFloatAsState(
                            targetValue = if (isSelected) 48f else 24f,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "PickerTextScale"
                        )

                        Text(
                            text = items[index].toString(),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = fontSize.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                EdgeButton(
                    onClick = {
                        onValueSelected(items[state.selectedOptionIndex])
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Text("Done")
                }
            }
        }
    )
}
