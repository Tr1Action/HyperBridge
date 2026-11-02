package com.d4viddf.hyperbridge.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.service.MusicIslandGestureService
import com.d4viddf.hyperbridge.service.MusicIslandPrefs
import com.d4viddf.hyperbridge.ui.components.ListOptionCard

/** [Tr1Action] Settings for swipe-to-skip on the music island. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicIslandSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { MusicIslandPrefs.get(context) }

    var serviceRunning by remember { mutableStateOf(MusicIslandGestureService.isRunning()) }
    var swipe by remember { mutableStateOf(MusicIslandPrefs.swipeEnabled(prefs)) }
    var invert by remember { mutableStateOf(MusicIslandPrefs.invertDirection(prefs)) }
    var showArea by remember { mutableStateOf(MusicIslandPrefs.showArea(prefs)) }
    var width by remember { mutableIntStateOf(MusicIslandPrefs.widthPercent(prefs)) }
    var extraH by remember { mutableIntStateOf(MusicIslandPrefs.extraHeightDp(prefs)) }
    var offsetX by remember { mutableIntStateOf(MusicIslandPrefs.offsetXDp(prefs)) }

    // Re-check the accessibility service when returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceRunning = MusicIslandGestureService.isRunning()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Don't leave the pink debug strip on screen after leaving this page
            prefs.edit { putBoolean(MusicIslandPrefs.KEY_SHOW_AREA, false) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_island_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.music_island_screen_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
            )

            ListOptionCard(
                title = stringResource(R.string.music_island_service_status),
                subtitle = stringResource(
                    if (serviceRunning) R.string.music_island_service_on else R.string.music_island_service_off
                ),
                icon = Icons.Outlined.Accessibility,
                shape = RoundedCornerShape(24.dp),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            if (!serviceRunning) {
                Text(
                    text = stringResource(R.string.music_island_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            ListOptionCard(
                title = stringResource(R.string.music_island_swipe),
                subtitle = stringResource(R.string.music_island_swipe_desc),
                icon = Icons.Outlined.SwipeLeft,
                shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 4.dp),
                onClick = {
                    swipe = !swipe
                    prefs.edit { putBoolean(MusicIslandPrefs.KEY_SWIPE, swipe) }
                },
                trailingContent = {
                    Switch(checked = swipe, onCheckedChange = {
                        swipe = it
                        prefs.edit { putBoolean(MusicIslandPrefs.KEY_SWIPE, it) }
                    })
                }
            )
            Spacer(Modifier.height(2.dp))
            ListOptionCard(
                title = stringResource(R.string.music_island_invert),
                subtitle = stringResource(R.string.music_island_invert_desc),
                icon = Icons.Outlined.SwapHoriz,
                shape = RoundedCornerShape(4.dp),
                onClick = {
                    invert = !invert
                    prefs.edit { putBoolean(MusicIslandPrefs.KEY_INVERT, invert) }
                },
                trailingContent = {
                    Switch(checked = invert, onCheckedChange = {
                        invert = it
                        prefs.edit { putBoolean(MusicIslandPrefs.KEY_INVERT, it) }
                    })
                }
            )
            Spacer(Modifier.height(2.dp))
            ListOptionCard(
                title = stringResource(R.string.music_island_show_area),
                subtitle = stringResource(R.string.music_island_show_area_desc),
                icon = Icons.Outlined.CropFree,
                shape = RoundedCornerShape(4.dp, 4.dp, 24.dp, 24.dp),
                onClick = {
                    showArea = !showArea
                    prefs.edit { putBoolean(MusicIslandPrefs.KEY_SHOW_AREA, showArea) }
                },
                trailingContent = {
                    Switch(checked = showArea, onCheckedChange = {
                        showArea = it
                        prefs.edit { putBoolean(MusicIslandPrefs.KEY_SHOW_AREA, it) }
                    })
                }
            )

            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(stringResource(R.string.music_island_width, width), style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = width.toFloat(),
                        onValueChange = { width = it.toInt() },
                        onValueChangeFinished = { prefs.edit { putInt(MusicIslandPrefs.KEY_WIDTH, width) } },
                        valueRange = 15f..90f
                    )
                    Text(stringResource(R.string.music_island_height, extraH), style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = extraH.toFloat(),
                        onValueChange = { extraH = it.toInt() },
                        onValueChangeFinished = { prefs.edit { putInt(MusicIslandPrefs.KEY_EXTRA_H, extraH) } },
                        valueRange = 0f..40f
                    )
                    Text(stringResource(R.string.music_island_offset, offsetX), style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = offsetX.toFloat(),
                        onValueChange = { offsetX = it.toInt() },
                        onValueChangeFinished = { prefs.edit { putInt(MusicIslandPrefs.KEY_OFFSET_X, offsetX) } },
                        valueRange = -100f..100f
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
