package com.deivela.radioslocales

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Pantalla base con barra superior y flecha de volver
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

private data class InfoSection(val title: String, val body: String)

@Composable
private fun InfoSections(sections: List<InfoSection>) {
    sections.forEach { s ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                s.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(s.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Añadir emisora
// ---------------------------------------------------------------------------

private fun isHttpUrl(text: String): Boolean {
    val t = text.trim().lowercase()
    return (t.startsWith("http://") || t.startsWith("https://")) && t.length > 8
}

/**
 * onSave devuelve null si se guardó bien (y quien llama cierra la pantalla)
 * o un mensaje de error para mostrarlo en el formulario.
 */
@Composable
fun AddRadioScreen(
    onSave: (Radio) -> String?,
    onBack: () -> Unit
) {
    var city by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val nameError = submitted && name.isBlank()
    val streamError = submitted && !isHttpUrl(streamUrl)
    val imageError = submitted && imageUrl.isNotBlank() && !isHttpUrl(imageUrl)

    SubScreen(title = "Añadir emisora", onBack = onBack) {
        Text(
            "Los campos marcados con * son obligatorios.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text("Ciudad") },
            placeholder = { Text("Ciudad, Región, País") },
            supportingText = { Text("Opcional. Ciudad, región o país de la emisora.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            )
        )

        OutlinedTextField(
            value = frequency,
            onValueChange = { frequency = it },
            label = { Text("Frecuencia") },
            placeholder = { Text("90.0 FM") },
            supportingText = {
                Text("Opcional. Se muestra delante del nombre: 90.0 FM / Nombre Emisora")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre *") },
            placeholder = { Text("Nombre Emisora") },
            isError = nameError,
            supportingText = {
                Text(
                    if (nameError) "Escribe un nombre"
                    else "Obligatorio. Es el nombre que verás en la lista."
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            )
        )

        OutlinedTextField(
            value = streamUrl,
            onValueChange = { streamUrl = it; saveError = null },
            label = { Text("URL del stream *") },
            placeholder = { Text("https://ejemplo.com/stream") },
            isError = streamError,
            supportingText = {
                Text(
                    if (streamError) "Debe empezar por http:// o https://"
                    else "Obligatorio. Dirección directa del audio (http:// o https://)."
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            )
        )

        OutlinedTextField(
            value = imageUrl,
            onValueChange = { imageUrl = it },
            label = { Text("URL de la imagen") },
            placeholder = { Text("https://ejemplo.com/logo.png") },
            isError = imageError,
            supportingText = {
                Text(
                    if (imageError) "Debe empezar por http:// o https://"
                    else "Opcional. Dirección de una imagen (logo) que se verá en la lista y en la notificación."
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            )
        )

        saveError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = {
                submitted = true
                val valid = name.isNotBlank() && isHttpUrl(streamUrl) &&
                        (imageUrl.isBlank() || isHttpUrl(imageUrl))
                if (valid) {
                    saveError = onSave(
                        Radio(
                            name = name.trim(),
                            streamUrl = streamUrl.trim(),
                            city = city.trim(),
                            frequency = frequency.trim(),
                            imageUrl = imageUrl.trim()
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Guardar") }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Cancelar")
        }
    }
}

// ---------------------------------------------------------------------------
// Guía de uso
// ---------------------------------------------------------------------------

private val GUIDE_SECTIONS = listOf(
    InfoSection(
        "Escuchar una emisora",
        "Toca cualquier emisora de la lista para escucharla. Si vuelves a tocar la que está sonando, " +
                "se pausa o se reanuda. La emisora activa aparece resaltada."
    ),
    InfoSection(
        "Vista en lista o en cuadrícula",
        "Pulsa el icono de la barra superior (junto al reloj) para alternar entre ver las emisoras en " +
                "lista o en cuadrícula, con el logo más grande. La app recuerda tu elección."
    ),
    InfoSection(
        "Controles del reproductor",
        "La barra inferior tiene: Anterior, Play/Pausa, Stop y Siguiente. Anterior y Siguiente cambian " +
                "de emisora siguiendo el orden de tu lista (al llegar a la última se vuelve a la primera). " +
                "Stop detiene la radio y cierra la notificación."
    ),
    InfoSection(
        "Segundo plano y pantalla de bloqueo",
        "La radio sigue sonando con la pantalla apagada o si sales de la app. Desde la notificación y la " +
                "pantalla de bloqueo puedes pausar y cambiar de emisora. En Android 13 o superior, acepta el " +
                "permiso de notificaciones para verlas."
    ),
    InfoSection(
        "Temporizador de apagado",
        "Con una emisora sonando, pulsa el icono del reloj de la barra superior y elige 5, 10, 15, 25, 30, " +
                "40 o 60 minutos. Verás la cuenta atrás junto al reloj. Al terminar, la radio se detiene por " +
                "completo, aunque hayas cerrado la app o bloqueado el móvil. Para cancelarlo, pulsa el reloj y " +
                "elige «Cancelar temporizador». También se cancela si pulsas Stop."
    ),
    InfoSection(
        "Añadir una emisora",
        "Menú ⋮ → «Añadir emisora». Rellena:\n" +
                "• Ciudad (opcional)\n" +
                "• Frecuencia (opcional, por ejemplo 90.0 FM). Se muestra delante del nombre.\n" +
                "• Nombre (obligatorio)\n" +
                "• URL del stream (obligatoria, debe empezar por http:// o https://)\n" +
                "• URL de la imagen (opcional, el logo de la emisora)\n" +
                "Al pulsar «Guardar» la emisora se añade a tu lista y vuelves a la pantalla principal. " +
                "Para añadir más, repite el proceso."
    ),
    InfoSection(
        "Quitar una emisora",
        "Pulsa el icono de la papelera de su tarjeta y confirma. Solo se quita de tu lista; puedes " +
                "volver a añadirla cuando quieras."
    ),
    InfoSection(
        "Exportar tu lista",
        "Menú ⋮ → «Exportar emisoras». Eliges dónde guardar el archivo radios.json. Sirve como copia de " +
                "seguridad o para pasárselo a otra persona."
    ),
    InfoSection(
        "Importar una lista",
        "Menú ⋮ → «Importar emisoras» y eliges un archivo .json. La app te preguntará si quieres " +
                "«Reemplazar» tu lista actual o «Añadir» las nuevas emisoras a la que ya tienes (las que " +
                "tengan una URL que ya existe no se duplican)."
    ),
    InfoSection(
        "Formato del archivo .json",
        "Es una lista de emisoras. Solo «name» y «streamUrl» son obligatorios; «city», «frequency» e " +
                "«imageUrl» son opcionales. Puedes editarlo con cualquier editor de texto.\n\n" +
                "[\n" +
                "  {\n" +
                "    \"name\": \"Nombre Emisora\",\n" +
                "    \"streamUrl\": \"https://ejemplo.com/stream\",\n" +
                "    \"city\": \"Ciudad, Región, País\",\n" +
                "    \"frequency\": \"90.0 FM\",\n" +
                "    \"imageUrl\": \"https://ejemplo.com/logo.png\"\n" +
                "  }\n" +
                "]"
    )
)

@Composable
fun GuideScreen(onBack: () -> Unit) {
    SubScreen(title = "Guía de uso", onBack = onBack) {
        InfoSections(GUIDE_SECTIONS)
    }
}

// ---------------------------------------------------------------------------
// Aviso legal
// ---------------------------------------------------------------------------

private val LEGAL_SECTIONS = listOf(
    InfoSection(
        "Qué es esta app",
        "Radios Locales es un reproductor de radio por Internet personalizable. No ofrece un catálogo " +
                "propio de emisoras: es el usuario quien añade sus emisoras de forma manual (o importando su " +
                "propio archivo .json) y la app se limita a reproducir los enlaces de streaming que él ha indicado."
    ),
    InfoSection(
        "Lista de ejemplo",
        "Para facilitar las primeras pruebas, la app puede incluir una lista de ejemplo con enlaces a " +
                "emisoras locales accesibles públicamente en Internet. Es solo una demostración: el usuario " +
                "puede quitarla, modificarla o sustituirla por completo en cualquier momento."
    ),
    InfoSection(
        "No alojamos ni retransmitimos contenido",
        "La app no emite, aloja, copia, graba ni retransmite contenido de audio ni de imagen. El sonido " +
                "llega directamente desde los servidores de cada emisora al dispositivo del usuario, y los " +
                "logotipos se cargan desde la dirección indicada en la lista. Nada de ello pasa por servidores " +
                "del desarrollador."
    ),
    InfoSection(
        "Responsabilidad del usuario",
        "El usuario es responsable de las direcciones (URL) que añade o importa y de que su uso sea " +
                "lícito. Se recomienda añadir únicamente emisoras cuyos enlaces su titular permita utilizar."
    ),
    InfoSection(
        "Marcas y derechos de terceros",
        "Los nombres, logotipos, marcas y contenidos de las emisoras pertenecen a sus respectivos " +
                "titulares. Esta app no tiene relación ni respaldo de ninguna emisora."
    ),
    InfoSection(
        "Sin ánimo de lucro",
        "La app es gratuita, no incluye publicidad ni compras y no obtiene beneficio económico de ningún tipo."
    ),
    InfoSection(
        "Privacidad y datos",
        "La app no tiene cuentas de usuario, no recopila datos personales y no incluye analíticas ni " +
                "publicidad. La lista de emisoras y tus preferencias de la app se guardan únicamente en el " +
                "almacenamiento interno de tu dispositivo, y la lista solo sale de él si tú decides exportarla. " +
                "Al reproducir una emisora o cargar un logotipo, tu dispositivo se conecta directamente a los " +
                "servidores de esa emisora o de esa web, que pueden registrar tu dirección IP según su propia " +
                "política."
    ),
    InfoSection(
        "Sin garantías",
        "La app se ofrece «tal cual», sin garantías de disponibilidad, continuidad o calidad. Su " +
                "funcionamiento depende de que las emisoras mantengan activos sus enlaces. El desarrollador no " +
                "se responsabiliza del contenido emitido por las emisoras, de las interrupciones del servicio ni " +
                "del uso que se haga de la app."
    )
)

@Composable
fun LegalScreen(onBack: () -> Unit) {
    SubScreen(title = "Aviso legal", onBack = onBack) {
        InfoSections(LEGAL_SECTIONS)
    }
}
