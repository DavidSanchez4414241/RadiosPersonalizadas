package com.example.radioslocales

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

private const val TAG = "RadiosLocales"
private const val IMAGE_USER_AGENT = "RadiosLocales/1.0 (Android; reproductor de radio personalizado)"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme { RadiosApp() }
        }
    }
}

private enum class Screen { MAIN, ADD, GUIDE, LEGAL }

private val TIMER_OPTIONS = listOf(5, 10, 15, 25, 30, 40, 60)

private fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun statusText(isBuffering: Boolean, isPlaying: Boolean): String = when {
    isBuffering -> "Cargando…"
    isPlaying -> "▶ Sonando"
    else -> "⏸ En pausa"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadiosApp() {
    val context = LocalContext.current

    // ---- Estado ----
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var radios by remember { mutableStateOf(RadioStore.load(context)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var nowTitle by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var timerMenuOpen by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<List<Radio>?>(null) }
    var radioToDelete by remember { mutableStateOf<Radio?>(null) }

    // Vista de lista o cuadrícula (se recuerda)
    val prefs = remember { context.getSharedPreferences("ajustes", Context.MODE_PRIVATE) }
    var gridView by remember { mutableStateOf(prefs.getBoolean("vista_cuadricula", false)) }

    // Temporizador: momento (en ms) en que se apagará; 0 = desactivado
    var timerEnd by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun persist() {
        if (!RadioStore.save(context, radios)) toast("No se pudo guardar la lista")
    }

    // ---- Permiso de notificaciones (Android 13+) ----
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* si lo deniega, la radio suena igual pero sin notificación */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- Conexión con el servicio de reproducción ----
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(c: MediaController, extras: Bundle) {
                    timerEnd = extras.getLong(PlaybackService.EXTRA_TIMER_END, 0L)
                }
            })
            .buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    timerEnd = c.sessionExtras.getLong(PlaybackService.EXTRA_TIMER_END, 0L)
                    controller = c
                } catch (_: Exception) {
                    errorMsg = "No se pudo conectar con el reproductor"
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    // ---- Escuchar cambios del reproductor ----
    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            fun sync() {
                isPlaying = c.isPlaying
                isBuffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
                playingId = c.currentMediaItem?.mediaId
                nowTitle = c.currentMediaItem?.mediaMetadata?.title?.toString()
            }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Error de reproducción: ${error.errorCodeName}", error)
                    errorMsg = "No se pudo reproducir (${error.errorCodeName})"
                }
            }
            c.addListener(listener)
            sync() // por si el servicio ya estaba sonando al abrir la app
            onDispose { c.removeListener(listener) }
        }
    }

    // ---- Cuenta atrás del temporizador (solo para mostrarla) ----
    LaunchedEffect(timerEnd) {
        while (timerEnd > 0L) {
            now = System.currentTimeMillis()
            if (now >= timerEnd) break
            delay(1000)
        }
    }
    val remainingMs = if (timerEnd > 0L) (timerEnd - now).coerceAtLeast(0) else 0L

    // ---- Acciones del reproductor ----
    fun playIndex(index: Int) {
        val c = controller ?: return
        if (index !in radios.indices) return
        errorMsg = null
        c.setMediaItems(radios.map { it.toMediaItem() }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        errorMsg = null
        if (isPlaying || isBuffering) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun stopPlayback() {
        val c = controller ?: return
        errorMsg = null
        c.stop()
        c.clearMediaItems()
    }

    fun nextRadio() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) {
            errorMsg = null
            c.seekToNextMediaItem()
            c.prepare()
            c.play()
        } else {
            playIndex(0)
        }
    }

    fun previousRadio() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) {
            errorMsg = null
            c.seekToPreviousMediaItem()
            c.prepare()
            c.play()
        } else {
            playIndex(radios.lastIndex)
        }
    }

    fun playRadio(radio: Radio) {
        if (playingId == radio.streamUrl) togglePlayPause()
        else playIndex(radios.indexOf(radio))
    }

    fun setSleepTimer(minutes: Int) {
        val c = controller ?: return
        if (minutes > 0 && playingId == null) {
            toast("Pon una emisora para activar el temporizador")
            return
        }
        val args = Bundle().apply { putInt(PlaybackService.EXTRA_MINUTES, minutes) }
        c.sendCustomCommand(SessionCommand(PlaybackService.ACTION_SET_TIMER, Bundle.EMPTY), args)
        toast(
            if (minutes > 0) "La radio se apagará en $minutes minutos"
            else "Temporizador cancelado"
        )
    }

    // ---- Añadir / quitar emisoras ----
    /** Devuelve null si se añadió; si no, el mensaje de error para el formulario. */
    fun addRadio(radio: Radio): String? {
        if (radios.any { it.streamUrl == radio.streamUrl }) {
            return "Esa URL ya está en tu lista"
        }
        // Si hay lista de reproducción cargada, la mantenemos sincronizada
        controller?.let { c ->
            if (c.mediaItemCount > 0) c.addMediaItem(radio.toMediaItem())
        }
        radios = radios + radio
        persist()
        toast("Emisora añadida")
        return null
    }

    fun removeRadio(radio: Radio) {
        controller?.let { c ->
            if (playingId == radio.streamUrl) {
                // Si quitas la que suena, se detiene la reproducción
                c.stop()
                c.clearMediaItems()
            } else {
                for (i in 0 until c.mediaItemCount) {
                    if (c.getMediaItemAt(i).mediaId == radio.streamUrl) {
                        c.removeMediaItem(i)
                        break
                    }
                }
            }
        }
        radios = radios.filter { it.streamUrl != radio.streamUrl }
        persist()
    }

    // ---- Importar / exportar ----
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)!!
                    .bufferedReader().use { it.readText() }
                val list = RadioStore.parse(text)
                if (list.isEmpty()) toast("El archivo no contiene emisoras válidas")
                else pendingImport = list
            } catch (_: Exception) {
                toast("Archivo no válido: tiene que ser un JSON con una lista de emisoras")
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")!!
                    .bufferedWriter().use { it.write(RadioStore.toJson(radios)) }
                toast("Lista exportada")
            } catch (_: Exception) {
                toast("No se pudo exportar")
            }
        }
    }

    // ---- Diálogos ----
    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Importar ${imported.size} emisoras") },
            text = {
                Text("¿Quieres reemplazar tu lista actual o añadir estas emisoras a ella?\n\nToca fuera para cancelar.")
            },
            confirmButton = {
                TextButton(onClick = {
                    // La lista cambia por completo: se detiene lo que suene
                    controller?.let { c ->
                        c.stop()
                        c.clearMediaItems()
                    }
                    radios = imported
                    persist()
                    pendingImport = null
                }) { Text("Reemplazar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val merged = RadioStore.merge(radios, imported)
                    val added = merged.drop(radios.size)
                    controller?.let { c ->
                        if (c.mediaItemCount > 0 && added.isNotEmpty()) {
                            c.addMediaItems(added.map { it.toMediaItem() })
                        }
                    }
                    radios = merged
                    persist()
                    pendingImport = null
                }) { Text("Añadir") }
            }
        )
    }

    radioToDelete?.let { radio ->
        AlertDialog(
            onDismissRequest = { radioToDelete = null },
            title = { Text("Quitar emisora") },
            text = {
                Text("¿Quitar «${radio.displayName}» de tu lista? Podrás volver a añadirla cuando quieras.")
            },
            confirmButton = {
                TextButton(onClick = {
                    removeRadio(radio)
                    radioToDelete = null
                }) { Text("Quitar") }
            },
            dismissButton = {
                TextButton(onClick = { radioToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    // ---- Pantallas ----
    when (screen) {
        Screen.ADD -> AddRadioScreen(
            onBack = { screen = Screen.MAIN },
            onSave = { radio ->
                val error = addRadio(radio)
                if (error == null) screen = Screen.MAIN
                error
            }
        )

        Screen.GUIDE -> GuideScreen(onBack = { screen = Screen.MAIN })

        Screen.LEGAL -> LegalScreen(onBack = { screen = Screen.MAIN })

        Screen.MAIN -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Radios locales") },
                    actions = {
                        // Lista / cuadrícula
                        IconButton(onClick = {
                            gridView = !gridView
                            prefs.edit().putBoolean("vista_cuadricula", gridView).apply()
                        }) {
                            Icon(
                                if (gridView) Icons.Default.ViewAgenda else Icons.Default.GridView,
                                contentDescription = if (gridView) "Ver en lista" else "Ver en cuadrícula"
                            )
                        }

                        // Temporizador de apagado
                        Box {
                            TextButton(onClick = { timerMenuOpen = true }) {
                                Icon(Icons.Default.Timer, contentDescription = "Temporizador de apagado")
                                if (timerEnd > 0L) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(formatRemaining(remainingMs))
                                }
                            }
                            DropdownMenu(
                                expanded = timerMenuOpen,
                                onDismissRequest = { timerMenuOpen = false }
                            ) {
                                TIMER_OPTIONS.forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text("$minutes minutos") },
                                        onClick = {
                                            timerMenuOpen = false
                                            setSleepTimer(minutes)
                                        }
                                    )
                                }
                                if (timerEnd > 0L) {
                                    DropdownMenuItem(
                                        text = { Text("Cancelar temporizador") },
                                        onClick = {
                                            timerMenuOpen = false
                                            setSleepTimer(0)
                                        }
                                    )
                                }
                            }
                        }

                        // Menú general
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menú")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Añadir emisora") },
                                    onClick = {
                                        menuOpen = false
                                        screen = Screen.ADD
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Importar emisoras (.json)") },
                                    onClick = {
                                        menuOpen = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Exportar emisoras (.json)") },
                                    onClick = {
                                        menuOpen = false
                                        exportLauncher.launch("radios.json")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Guía de uso") },
                                    onClick = {
                                        menuOpen = false
                                        screen = Screen.GUIDE
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Aviso legal") },
                                    onClick = {
                                        menuOpen = false
                                        screen = Screen.LEGAL
                                    }
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            nowTitle ?: "Elige una emisora",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = errorMsg ?: when {
                                nowTitle == null -> "Toca una emisora para empezar"
                                isBuffering -> "Cargando…"
                                isPlaying -> "Sonando"
                                else -> "En pausa"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (errorMsg != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { previousRadio() },
                                enabled = controller != null && radios.isNotEmpty()
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Emisora anterior")
                            }
                            FilledIconButton(
                                onClick = { togglePlayPause() },
                                enabled = controller != null && nowTitle != null,
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    if (isPlaying || isBuffering) Icons.Default.Pause
                                    else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying || isBuffering) "Pausar" else "Reproducir"
                                )
                            }
                            IconButton(
                                onClick = { stopPlayback() },
                                enabled = controller != null && nowTitle != null
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Detener")
                            }
                            IconButton(
                                onClick = { nextRadio() },
                                enabled = controller != null && radios.isNotEmpty()
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Emisora siguiente")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            if (radios.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                    Text(
                        "No hay emisoras.\nAñade una desde el menú ⋮ o importa un archivo JSON.",
                        textAlign = TextAlign.Center
                    )
                }
            } else if (gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(radios, key = { it.streamUrl }) { radio ->
                        RadioGridCard(
                            radio = radio,
                            isCurrent = playingId == radio.streamUrl,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            onClick = { playRadio(radio) },
                            onDelete = { radioToDelete = radio }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(radios, key = { it.streamUrl }) { radio ->
                        RadioCard(
                            radio = radio,
                            isCurrent = playingId == radio.streamUrl,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            onClick = { playRadio(radio) },
                            onDelete = { radioToDelete = radio }
                        )
                    }
                }
            }
        }
    }
}

/** Logo de la emisora. Si no hay imagen o no carga, se muestra la inicial del nombre. */
@Composable
private fun RadioLogo(
    radio: Radio,
    modifier: Modifier = Modifier,
    imagePadding: Dp = 0.dp
) {
    val context = LocalContext.current
    var loaded by remember(radio.imageUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!loaded) {
            Text(
                radio.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (radio.imageUrl.isNotBlank()) {
            val request = remember(radio.imageUrl) {
                ImageRequest.Builder(context)
                    .data(radio.imageUrl)
                    .httpHeaders(NetworkHeaders.Builder().set("User-Agent", IMAGE_USER_AGENT).build())
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = radio.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(imagePadding),
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false }
            )
        }
    }
}

@Composable
private fun RadioCard(
    radio: Radio,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioLogo(
                radio = radio,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = radio.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (radio.city.isNotBlank()) {
                    Text(
                        text = radio.city,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isCurrent) {
                    Text(
                        text = statusText(isBuffering, isPlaying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Quitar emisora"
                )
            }
        }
    }
}

@Composable
private fun RadioGridCard(
    radio: Radio,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                RadioLogo(
                    radio = radio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Quitar emisora"
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = radio.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (radio.city.isNotBlank()) {
                Text(
                    text = radio.city,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Text(
                    text = statusText(isBuffering, isPlaying),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}