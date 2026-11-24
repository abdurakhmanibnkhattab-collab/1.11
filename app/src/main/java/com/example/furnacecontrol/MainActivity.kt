package com.example.furnacecontrol

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { FurnaceScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FurnaceScreen() {
    val context = LocalContext.current
    var timers by remember { mutableStateOf(TimerStore.load(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<FurnaceTimer?>(null) }
    var durationMinutes by remember { mutableStateOf("60") }
    var selectedSound by remember { mutableStateOf(AlarmHelper.getSelectedSound(context)) }
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (result.resultCode == android.app.Activity.RESULT_OK && picked != null) {
            selectedSound = picked.toString()
            AlarmHelper.setSelectedSound(context, selectedSound)
        }
    }
    val selectedSoundName = remember(selectedSound) {
        try {
            RingtoneManager.getRingtone(context, Uri.parse(selectedSound))?.getTitle(context)
                ?: "Сигнал будильника по умолчанию"
        } catch (_: Exception) { "Сигнал будильника по умолчанию" }
    }
    var renameTarget by remember { mutableStateOf<FurnaceTimer?>(null) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            timers = TimerStore.load(context)
        }
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Контроль термопечей") }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp)) {
                Text("8 печей · таймеры работают независимо", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(timers, key = { it.id }) { timer ->
                        val remaining = (timer.endAt - now).coerceAtLeast(0L) / 1000L
                        val time = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                            remaining / 3600, (remaining % 3600) / 60, remaining % 60)
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text(timer.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                when {
                                    timer.running -> {
                                        if (remaining == 0L) {
                                            Text("ЗАВЕРШЕНИЕ…", style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.error)
                                        } else {
                                            Text(time, style = MaterialTheme.typography.headlineSmall,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("В работе")
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = {
                                            AlarmHelper.cancel(context, timer.id)
                                            TimerStore.update(context, timer.id) { it.copy(endAt = 0L, completed = false) }
                                            AlarmHelper.refreshRinging(context)
                                            timers = TimerStore.load(context)
                                        }) { Text("Остановить") }
                                    }
                                    timer.completed -> {
                                        Text("ВРЕМЯ ВЫШЛО", color = MaterialTheme.colorScheme.error)
                                        Button(onClick = {
                                            TimerStore.update(context, timer.id) { it.copy(endAt = 0L, completed = false) }
                                            AlarmHelper.refreshRinging(context)
                                            timers = TimerStore.load(context)
                                        }) { Text("Выгружено") }
                                    }
                                    else -> {
                                        Text("Свободна")
                                        Button(onClick = { selected = timer }) { Text("Загрузить") }
                                    }
                                }
                                TextButton(onClick = { renameTarget = timer; newName = timer.name }) {
                                    Text("Изменить название")
                                }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { timer ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text("Время обработки · ${timer.name}") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = durationMinutes,
                            onValueChange = { durationMinutes = it.filter(Char::isDigit).take(7) },
                            label = { Text("Время обработки, минут") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Мелодия сигнала: $selectedSoundName")
                        OutlinedButton(onClick = {
                            val pickIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Выберите мелодию сигнала")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    selectedSound.takeIf { it.isNotBlank() }?.let(Uri::parse))
                            }
                            soundPicker.launch(pickIntent)
                        }) { Text("Выбрать мелодию") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val total = durationMinutes.toLongOrNull() ?: 0L
                        if (total > 0) {
                            val started = timer.copy(endAt = System.currentTimeMillis() + total * 60_000L,
                                completed = false)
                            TimerStore.update(context, timer.id) { started }
                            AlarmHelper.schedule(context, started)
                            timers = TimerStore.load(context)
                            selected = null
                        }
                    }) { Text("Запустить") }
                },
                dismissButton = { TextButton(onClick = { selected = null }) { Text("Отмена") } }
            )
        }

        renameTarget?.let { timer ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("Название печи") },
                text = { OutlinedTextField(newName, { newName = it }, label = { Text("Название") }) },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            TimerStore.update(context, timer.id) { it.copy(name = newName.trim()) }
                            timers = TimerStore.load(context)
                        }
                        renameTarget = null
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
            )
        }
    }
}
