package io.pelle.sak.notes.ui

import io.pelle.sak.api.model.NoteResponse
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import io.pelle.sak.core.ui.SakTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.AccountMenu
import io.pelle.sak.core.ui.Busy
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.Error
import io.pelle.sak.core.ui.Page
import io.pelle.sak.core.ui.resolve
import io.pelle.sak.core.ui.UpdateBanner
import io.pelle.sak.core.ui.UpdateNotice
import io.pelle.sak.core.ui.UndoBar
import io.pelle.sak.core.ui.UndoTime
import io.pelle.sak.core.Undo
import io.pelle.sak.core.uiText
import io.pelle.sak.notes.UNDO_REMOVE_MS
import io.pelle.sak.notes.MAX_LABELS
import io.pelle.sak.notes.MAX_LABEL_LENGTH
import io.pelle.sak.notes.MAX_PHOTOS
import io.pelle.sak.notes.MAX_RECORDINGS
import io.pelle.sak.notes.MAX_TEXT_LENGTH
import io.pelle.sak.notes.NOTE_COLORS
import io.pelle.sak.notes.NotesModel
import io.pelle.sak.notes.scaledPhoto
import io.pelle.sak.notes.NotesScreen
import io.pelle.sak.notes.R
import io.pelle.sak.notes.RecordingFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// the colors of the notes, the same in the light and the dark mode, with the ink on them (the
// --note-* of sak-frontend/src/styles.scss)
private val NOTE_BACKGROUNDS =
    mapOf(
        NoteResponse.Color.YELLOW to Color(0xFFFFF59D),
        NoteResponse.Color.PINK to Color(0xFFF8BBD0),
        NoteResponse.Color.BLUE to Color(0xFFB3E5FC),
        NoteResponse.Color.GREEN to Color(0xFFC5E1A5),
        NoteResponse.Color.ORANGE to Color(0xFFFFCC80),
    )
private val NOTE_INK = Color(0xFF2B2A27)

// a field on a note: its text, label and outline in the ink of the note, black enough to read on
// every color of a note
@Composable
private fun noteFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = NOTE_INK,
        unfocusedTextColor = NOTE_INK,
        focusedLabelColor = NOTE_INK,
        unfocusedLabelColor = NOTE_INK.copy(alpha = 0.75f),
        focusedPlaceholderColor = NOTE_INK.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = NOTE_INK.copy(alpha = 0.6f),
        focusedBorderColor = NOTE_INK,
        unfocusedBorderColor = NOTE_INK.copy(alpha = 0.6f),
        cursorColor = NOTE_INK,
        focusedSupportingTextColor = NOTE_INK.copy(alpha = 0.75f),
        unfocusedSupportingTextColor = NOTE_INK.copy(alpha = 0.75f),
    )

private fun noteColor(color: NoteResponse.Color) = NOTE_BACKGROUNDS.getValue(color)

private val COLOR_NAMES =
    mapOf(
        NoteResponse.Color.YELLOW to R.string.notes_colors_yellow,
        NoteResponse.Color.PINK to R.string.notes_colors_pink,
        NoteResponse.Color.BLUE to R.string.notes_colors_blue,
        NoteResponse.Color.GREEN to R.string.notes_colors_green,
        NoteResponse.Color.ORANGE to R.string.notes_colors_orange,
    )

// a photo is taken or chosen for a note, or for a new note (NEW)
private const val NEW = -1

@Composable
fun NotesApp(model: NotesModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    val player = rememberPlayer(model)
    // a removal can be undone longer when the phone asks for more time for a control (AN-34)
    UndoTime(UNDO_REMOVE_MS) { model.undoMs = it }
    // a recording plays only while its note is open, the app in front and the user signed in (AN-28)
    val open = (screen as? NotesScreen)?.open
    LaunchedEffect(open) { player.stop() }
    CompositionLocalProvider(
        LocalPhotoPicker provides rememberPhotoPicker(model),
        LocalMicrophone provides rememberMicrophone(model),
        LocalPlayer provides player,
    ) {
        when (val current = screen) {
            is NotesScreen -> {
                val open = current.note(current.open)
                if (open != null) NoteScreen(current, open, model) else WallScreen(current, model)
                current.deleting?.let { id ->
                    ConfirmDelete(
                        stringResource(R.string.notes_this_note),
                        onDelete = { model.delete(id) },
                        onDismiss = model::dismissDelete,
                    )
                }
            }
            else -> Busy()
        }
    }
}

// takes a photo with the camera or chooses one of the phone, for a note or a new note
private class PhotoPicker(
    val hasCamera: Boolean,
    val take: (noteId: Int) -> Unit,
    val choose: (noteId: Int) -> Unit,
)

private val LocalPhotoPicker = staticCompositionLocalOf<PhotoPicker> { error("no photo picker") }

// starts a recording once the app may use the microphone, asks for it first
private val LocalMicrophone = staticCompositionLocalOf<(RecordingFor) -> Unit> { error("no microphone") }

// the camera and the photo picker of the app, at its root: the note they are for is kept with the
// saved state; the photo is read and scaled by scaledPhoto and sent by the model
@Composable
private fun rememberPhotoPicker(model: NotesModel): PhotoPicker {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<Int?>(null) }
    // the camera writes into a file of the cache, shared with it by the file provider only
    val cameraFile = remember { File(context.cacheDir, "photos/camera.jpg") }
    val cameraUri =
        remember {
            cameraFile.parentFile?.mkdirs()
            FileProvider.getUriForFile(context, "${context.packageName}.photos", cameraFile)
        }

    fun send(
        uri: Uri,
        done: () -> Unit = {},
    ) {
        val noteId = pending ?: return
        pending = null
        val read: suspend () -> ByteArray? = {
            try {
                scaledPhoto(context, uri)
            } finally {
                // also when the read is cancelled: the file of the camera goes
                withContext(NonCancellable + Dispatchers.IO) { done() }
            }
        }
        if (noteId == NEW) model.createWithPhoto(read) else model.addPhoto(noteId, read)
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            // the file of the camera (with its EXIF) goes also when no photo was taken (AN-27)
            if (taken) {
                send(cameraUri) { cameraFile.delete() }
            } else {
                pending = null
                cameraFile.delete()
            }
        }
    // the app declares the camera for the scan of a QR code (core), so android lets the camera app take
    // a photo for it only once the user allowed the camera; asked for when the user takes a photo
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (allowed) camera.launch(cameraUri) else pending = null
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(uri) else pending = null
        }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return remember(camera, permission, picker) {
        PhotoPicker(
            hasCamera,
            take = { noteId ->
                pending = noteId
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    camera.launch(cameraUri)
                } else {
                    permission.launch(Manifest.permission.CAMERA)
                }
            },
            choose = { noteId ->
                pending = noteId
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

// a recording starts once the permission to record is given, asked for the first time
@Composable
private fun rememberMicrophone(model: NotesModel): (RecordingFor) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<RecordingFor?>(null) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pending ?: return@rememberLauncherForActivityResult
            pending = null
            if (granted) model.record(target) else model.microphoneDenied()
        }
    return remember(ask) {
        { target ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                model.record(target)
            } else {
                pending = target
                ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

// the wall of the notes, newest first, of the label filtered by or all; the main action creates a
// note (the plus, UX-2), a note with a photo or a recording is created by the icon of what it starts
// with (UX-2.1)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallScreen(
    screen: NotesScreen,
    model: NotesModel,
) {
    val picker = LocalPhotoPicker.current
    val microphone = LocalMicrophone.current
    var photoMenu by remember { mutableStateOf(false) }
    val busy = screen.creating || screen.recording != null
    Page(
        stringResource(R.string.notes_app_name),
        bottomBar = { BottomBars(screen, model) },
        actions = {
            Box {
                IconButton(onClick = { photoMenu = true }, enabled = !busy, modifier = Modifier.testTag("create-with-photo")) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = stringResource(R.string.notes_page_create_with_photo))
                }
                PhotoMenu(photoMenu, picker, NEW) { photoMenu = false }
            }
            IconButton(onClick = { microphone(RecordingFor.New) }, enabled = !busy, modifier = Modifier.testTag("create-with-recording")) {
                Icon(painterResource(R.drawable.ic_microphone), contentDescription = stringResource(R.string.notes_page_create_with_recording))
            }
            IconButton(onClick = model::create, enabled = !busy, modifier = Modifier.testTag("create-note")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.notes_page_create_note))
            }
            AccountMenu(screen, model, home = true)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = screen.loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize().testTag("notes"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
            ) {
                val full = StaggeredGridItemSpan.FullLine
                screen.update?.let { item(span = full) { UpdateBanner(it, model::update) } }
                screen.updateNotice?.let { item(span = full) { UpdateNotice(it, model::dismissUpdateNotice) } }
                if (screen.creating) item(span = full) { Text(stringResource(R.string.notes_uploading)) }
                screen.error?.let { item(span = full) { Message(it, model::dismissError) } }
                screen.notice?.let { item(span = full) { Message(it, model::dismissNotice, error = false) } }
                if (screen.chips.isNotEmpty()) item(span = full) { LabelFilter(screen, model) }
                if (!screen.loading && screen.error == null && screen.notes.isEmpty()) {
                    item(span = full) {
                        Text(
                            screen.filter?.let { stringResource(R.string.notes_page_none_labelled, it) } ?: stringResource(R.string.notes_empty),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(screen.notes, key = { it.id }) { note -> NoteCard(screen, note, model) }
            }
        }
    }
}

// the photo of the camera or of the phone, for a note or a new note
@Composable
private fun PhotoMenu(
    expanded: Boolean,
    picker: PhotoPicker,
    noteId: Int,
    dismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.notes_photo_take)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
            enabled = picker.hasCamera,
            onClick = {
                dismiss()
                picker.take(noteId)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.notes_photo_choose)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_image), contentDescription = null) },
            onClick = {
                dismiss()
                picker.choose(noteId)
            },
        )
    }
}

// one label at a time, or all
@Composable
private fun LabelFilter(
    screen: NotesScreen,
    model: NotesModel,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("filter"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // how many notes there are: the notes shown, or while a label is filtered the last count of all
        val count = if (screen.filter == null) screen.notes.size.takeUnless { screen.loading } else screen.allCount
        FilterChip(
            selected = screen.filter == null,
            onClick = { model.filter(null) },
            label = { Text(count?.let { stringResource(R.string.notes_all_count, it) } ?: stringResource(R.string.notes_page_all)) },
        )
        screen.chips.forEach { label ->
            FilterChip(selected = screen.filter == label, onClick = { model.filter(label) }, label = { Text("#$label") })
        }
    }
}

// a note on the wall in its color: its text, its labels and how many photos and recordings it has; a
// tap opens it, the menu of the card deletes it after a confirmation (UX-12, UX-4.1)
@Composable
private fun NoteCard(
    screen: NotesScreen,
    note: NoteResponse,
    model: NotesModel,
) {
    var menu by remember { mutableStateOf(false) }
    val deleting = note.id in screen.deletingNow
    Card(
        colors = CardDefaults.cardColors(containerColor = noteColor(note.color), contentColor = NOTE_INK),
        modifier = Modifier.fillMaxWidth().clickable { model.open(note.id) }.testTag("note-${note.id}"),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // the menu at the top right of the card, also next to a long text
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    note.text.ifBlank { stringResource(R.string.notes_card_write) },
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    color = if (note.text.isBlank()) NOTE_INK.copy(alpha = 0.6f) else NOTE_INK,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                Box {
                    if (deleting) {
                        val deletingText = stringResource(R.string.notes_deleting)
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = deletingText }, strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(CoreR.drawable.ic_menu), contentDescription = stringResource(R.string.notes_note_menu), tint = NOTE_INK)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(CoreR.string.shared_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                model.askDelete(note.id)
                            },
                            modifier = Modifier.testTag("delete-${note.id}"),
                        )
                    }
                }
            }
            if (note.photos.isNotEmpty()) Thumbnail(note.photos.first().id, model, Modifier.padding(end = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)))
            val counts =
                listOfNotNull(
                    note.photos.size.takeIf { it > 1 }?.let { "📷 $it" },
                    note.recordings.size.takeIf { it > 0 }?.let { "🎙 $it" },
                ).joinToString("  ")
            if (counts.isNotEmpty()) Text(counts, style = MaterialTheme.typography.bodySmall)
            // a label filters the wall by it, as on the web
            if (note.labels.isNotEmpty()) {
                Row(Modifier.padding(end = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    note.labels.forEach { label ->
                        Text(
                            "#${label.name}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(role = Role.Button) { model.filter(label.name) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// the thumbnail of a stored photo or the waveform of a recording, fetched once by the model
@Composable
private fun Thumbnail(
    fileId: Int,
    model: NotesModel,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    recording: Boolean = false,
) {
    val bitmap by produceState<ImageBitmap?>(null, fileId, full) {
        value =
            model.file(fileId, thumbnail = !full, recording = recording)?.let { bytes ->
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
    }
    Box(modifier.background(NOTE_INK.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = if (full) ContentScale.Fit else ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
    }
}

// a note in its own screen: its text, its color, its labels, its photos and recordings and what the
// AI discovers in them; back leads to the wall, the text typed is saved first
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteScreen(
    screen: NotesScreen,
    note: NoteResponse,
    model: NotesModel,
) {
    BackHandler { model.open(null) }
    val picker = LocalPhotoPicker.current
    val microphone = LocalMicrophone.current
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val deleting = note.id in screen.deletingNow
    Page(
        stringResource(R.string.notes_note),
        bottomBar = { BottomBars(screen, model) },
        navigationIcon = {
            IconButton(onClick = { model.open(null) }, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.notes_back_to_notes))
            }
        },
        actions = {
            Box {
                // while the note is deleted its menu waits, a second delete does nothing (AN-51)
                IconButton(onClick = { menu = true }, enabled = !deleting, modifier = Modifier.testTag("note-menu")) {
                    if (deleting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(CoreR.drawable.ic_menu), contentDescription = stringResource(R.string.notes_note_menu))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_delete_note), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            model.askDelete(note.id)
                        },
                    )
                }
            }
        },
    ) { padding ->
        // pulled down, the notes load again (ANDROID-3)
        PullToRefreshBox(
            isRefreshing = screen.loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // a note is light in every theme: its fields, labels and buttons take the light colors and
            // the dark ink of the note, so they read on it also while the phone is dark
            SakTheme(light = true) {
                CompositionLocalProvider(LocalContentColor provides NOTE_INK) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(noteColor(note.color))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        screen.error?.let { Message(it, model::dismissError) }
                        screen.notice?.let { Message(it, model::dismissNotice, error = false) }
                        NoteText(note, model)
                        ColorChoice(note, model)
                        Labels(screen, note, model)
                        Media(screen, note, model, picker, microphone) { viewing = it }
                    }
                }
            }
        }
    }
    viewing?.let { fileId -> PhotoDialog(fileId, model) { viewing = null } }
}

// the text, saved once typing pauses; kept here while typing so the answers of the saves do not move
// the cursor
@Composable
private fun NoteText(
    note: NoteResponse,
    model: NotesModel,
) {
    var text by remember(note.id) { mutableStateOf(note.text) }
    // a text the backend or the AI changed takes the place of the one here
    LaunchedEffect(note.text) { if (note.text != text) text = note.text }
    OutlinedTextField(
        value = text,
        // at most MAX_TEXT_LENGTH characters, the field says so once it is full (AN-24)
        onValueChange = {
            text = it.take(MAX_TEXT_LENGTH)
            model.setText(note.id, text)
        },
        label = { Text(stringResource(R.string.notes_text)) },
        placeholder = { Text(stringResource(R.string.notes_card_write)) },
        supportingText = if (text.length >= MAX_TEXT_LENGTH) ({ Text(stringResource(R.string.notes_text_full, MAX_TEXT_LENGTH)) }) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = noteFieldColors(),
        minLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("text"),
    )
}

// the color of the note, one of NOTE_COLORS
@Composable
private fun ColorChoice(
    note: NoteResponse,
    model: NotesModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.notes_card_color), style = MaterialTheme.typography.titleSmall, color = NOTE_INK)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NOTE_COLORS.forEach { color ->
                val name = stringResource(R.string.notes_color_of, stringResource(COLOR_NAMES.getValue(color)))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(noteColor(color))
                        .border(BorderStroke(if (note.color == color) 3.dp else 1.dp, NOTE_INK), CircleShape)
                        .clickable(role = Role.RadioButton) { model.setColor(note.id, color) }
                        .semantics {
                            contentDescription = name
                            selected = note.color == color
                        }.testTag("color-$color"),
                )
            }
        }
    }
}

// the labels of the note, each removed by its x; a new one or one of the known ones added, at most
// MAX_LABELS (the field says why it takes no more, UX-8)
@Composable
private fun Labels(
    screen: NotesScreen,
    note: NoteResponse,
    model: NotesModel,
) {
    var label by rememberSaveable(note.id) { mutableStateOf("") }
    val full = note.labels.size >= MAX_LABELS
    fun add() {
        model.addLabel(note.id, label)
        label = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.notes_card_labels), style = MaterialTheme.typography.titleSmall, color = NOTE_INK)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            note.labels.forEach { each ->
                val remove = stringResource(R.string.notes_label_remove, each.name)
                InputChip(
                    selected = false,
                    onClick = { model.removeLabel(note.id, each.name) },
                    label = { Text("#${each.name}") },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_xmark), contentDescription = remove, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        // the known labels the note does not have yet, one tap adds one
        // disabled, not hidden, once the note has MAX_LABELS (UX-8)
        val known = screen.labels.filter { name -> note.labels.none { it.name == name } && (label.isBlank() || name.contains(label.trim().lowercase())) }
        if (known.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                known.take(12).forEach { name ->
                    FilterChip(selected = false, enabled = !full, onClick = { model.addLabel(note.id, name) }, label = { Text("#$name") })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
                label = { Text(stringResource(R.string.notes_label_new)) },
                singleLine = true,
                enabled = !full,
                supportingText = if (full) ({ Text(stringResource(R.string.notes_card_labels_full, MAX_LABELS)) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (label.isNotBlank()) add() }),
                colors = noteFieldColors(),
                modifier = Modifier.weight(1f).testTag("label"),
            )
            OutlinedButton(onClick = ::add, enabled = !full && label.isNotBlank(), modifier = Modifier.testTag("add-label")) {
                Text(stringResource(R.string.notes_label_add))
            }
        }
    }
}

// the photos and recordings of the note: a photo opens in full, a recording plays; each can go (undo
// brings it back), and the AI discovers the text in it when it is turned on (otherwise its button stays,
// disabled, and the screen says why, UX-8). A note takes MAX_PHOTOS and MAX_RECORDINGS, the add buttons
// say why they are disabled (AN-24)
@Composable
private fun Media(
    screen: NotesScreen,
    note: NoteResponse,
    model: NotesModel,
    picker: PhotoPicker,
    microphone: (RecordingFor) -> Unit,
    view: (Int) -> Unit,
) {
    var photoMenu by remember { mutableStateOf(false) }
    val uploading = note.id in screen.uploading
    val photosFull = note.photos.size >= MAX_PHOTOS
    val recordingsFull = note.recordings.size >= MAX_RECORDINGS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notes_photos), style = MaterialTheme.typography.titleSmall, color = NOTE_INK)
        note.photos.forEachIndexed { index, photo ->
            val number = index + 1
            val name = stringResource(R.string.notes_photo_of, number)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Thumbnail(
                    photo.id,
                    model,
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { view(photo.id) }
                        .semantics { contentDescription = name }
                        .testTag("photo-${photo.id}"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = NOTE_INK, modifier = Modifier.weight(1f))
                    Discover(screen, note.id, photo.id, stringResource(R.string.notes_discover_photo, number), model)
                    IconButton(onClick = { model.removePhoto(note.id, photo.id) }, modifier = Modifier.testTag("remove-photo-${photo.id}")) {
                        Icon(painterResource(R.drawable.ic_xmark), contentDescription = stringResource(R.string.notes_photo_remove, number), tint = NOTE_INK)
                    }
                }
            }
        }
        Box {
            OutlinedButton(onClick = { photoMenu = true }, enabled = !uploading && !photosFull, modifier = Modifier.testTag("add-photo")) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.notes_photo_add), modifier = Modifier.padding(start = 8.dp))
            }
            PhotoMenu(photoMenu, picker, note.id) { photoMenu = false }
        }
        if (photosFull) Text(stringResource(R.string.notes_photos_full, MAX_PHOTOS), style = MaterialTheme.typography.bodySmall, color = NOTE_INK)

        Text(stringResource(R.string.notes_recordings), style = MaterialTheme.typography.titleSmall, color = NOTE_INK)
        note.recordings.forEachIndexed { index, recording ->
            RecordingRow(screen, note.id, recording.id, index + 1, model)
        }
        // while recording, the bar at the bottom of every screen stops it (AN-23)
        OutlinedButton(
            onClick = { microphone(RecordingFor.Note(note.id)) },
            enabled = !uploading && screen.recording == null && !recordingsFull,
            modifier = Modifier.testTag("add-recording"),
        ) {
            Icon(painterResource(R.drawable.ic_microphone), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.notes_recording_add), modifier = Modifier.padding(start = 8.dp))
        }
        if (recordingsFull) Text(stringResource(R.string.notes_recordings_full, MAX_RECORDINGS), style = MaterialTheme.typography.bodySmall, color = NOTE_INK)
        if (uploading) Text(stringResource(R.string.notes_uploading), color = NOTE_INK)
        if (!screen.ai && (note.photos.isNotEmpty() || note.recordings.isNotEmpty())) {
            Text(stringResource(R.string.notes_discover_off), style = MaterialTheme.typography.bodySmall, color = NOTE_INK, modifier = Modifier.testTag("discover-off"))
        }
    }
}

// the robot that has the AI discover the text of a photo or a recording; disabled while the AI is off
// or while it discovers
@Composable
private fun Discover(
    screen: NotesScreen,
    noteId: Int,
    fileId: Int,
    description: String,
    model: NotesModel,
) {
    val discovering = fileId in screen.discovering
    IconButton(
        onClick = { model.discover(noteId, fileId) },
        enabled = screen.ai && !discovering,
        modifier = Modifier.testTag("discover-$fileId"),
    ) {
        if (discovering) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_robot), contentDescription = description, tint = if (screen.ai) NOTE_INK else NOTE_INK.copy(alpha = 0.38f))
        }
    }
}

// a recording of the note: it plays and pauses, its waveform shows; one plays at a time
@Composable
private fun RecordingRow(
    screen: NotesScreen,
    noteId: Int,
    fileId: Int,
    number: Int,
    model: NotesModel,
) {
    val player = LocalPlayer.current
    val playing = player.playing == fileId
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Thumbnail(fileId, model, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), recording = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.toggle(fileId) }, modifier = Modifier.testTag("play-$fileId")) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (playing) R.string.notes_recording_pause else R.string.notes_recording_play, number),
                    tint = NOTE_INK,
                )
            }
            Text(stringResource(R.string.notes_recording_of, number), color = NOTE_INK, modifier = Modifier.weight(1f))
            Discover(screen, noteId, fileId, stringResource(R.string.notes_discover_recording, number), model)
            IconButton(onClick = { model.removeRecording(noteId, fileId) }, modifier = Modifier.testTag("remove-recording-$fileId")) {
                Icon(painterResource(R.drawable.ic_xmark), contentDescription = stringResource(R.string.notes_recording_remove, number), tint = NOTE_INK)
            }
        }
    }
}

// the bars at the bottom of every screen of the notes: the recording running with its stop (AN-23), and
// the photo or recording just taken off with its undo (UX-4.2)
@Composable
private fun BottomBars(
    screen: NotesScreen,
    model: NotesModel,
) {
    Column {
        screen.recordingSince?.takeIf { screen.recording != null }?.let { RecordingBar(it, model) }
        screen.removal?.let { removal ->
            val text = uiText(if (removal.photo != null) R.string.notes_photo_removed else R.string.notes_recording_removed, removal.number)
            UndoBar(Undo(text, removal.serial), model.undoMs, model::undoRemoval)
        }
    }
}

// while recording: how long it records, counted from its start in the model (it stays across a
// rotation), and its stop; the time is read out as it changes (a live region)
@Composable
private fun RecordingBar(
    since: java.time.Instant,
    model: NotesModel,
) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(since) {
        while (true) {
            seconds = java.time.Duration.between(since, java.time.Instant.now()).seconds.toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("recording")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.notes_recording, "%d:%02d".format(seconds / 60, seconds % 60)),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(onClick = model::stopRecording, modifier = Modifier.testTag("stop-recording")) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.notes_recording_stop), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// an error or a notice with its close (dismissError, dismissNotice)
@Composable
private fun Message(
    text: io.pelle.sak.core.UiText,
    dismiss: () -> Unit,
    error: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (error) Error(text) else Text(text.resolve(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        IconButton(onClick = dismiss) {
            Icon(painterResource(R.drawable.ic_xmark), contentDescription = stringResource(R.string.notes_dismiss), modifier = Modifier.size(16.dp))
        }
    }
}

// a photo of the note in full, as large as the screen; a tap or back closes it
@Composable
private fun PhotoDialog(
    fileId: Int,
    model: NotesModel,
    close: () -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = close), contentAlignment = Alignment.Center) {
            Thumbnail(fileId, model, Modifier.fillMaxWidth(), full = true)
        }
    }
}

// plays one recording at a time from a file of the cache, fetched by the model
private class Player(
    val playing: Int?,
    val toggle: (Int) -> Unit,
    val stop: () -> Unit,
)

private val LocalPlayer = staticCompositionLocalOf { Player(null, {}, {}) }

// the recording playing: a second toggle pauses it, another one plays instead. It stops when the app goes
// to the background and ends with the app; its file in the cache goes when it stops (AN-28)
@Composable
private fun rememberPlayer(model: NotesModel): Player {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Int?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val file = remember { File(context.cacheDir, "recording.wav") }
    fun stop() {
        player[0]?.release()
        player[0] = null
        playing = null
        file.delete()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return Player(
        playing,
        toggle = { fileId ->
            if (playing == fileId) {
                stop()
            } else {
                stop()
                playing = fileId
                scope.launch {
                    val wav = model.file(fileId, thumbnail = false, recording = true)
                    if (wav == null || playing != fileId) {
                        if (playing == fileId) playing = null
                        return@launch
                    }
                    try {
                        // the recordings are served only with the session, the player reads a file of the cache
                        withContext(Dispatchers.IO) { file.writeBytes(wav) }
                        // another recording was tapped while this one was written (AN-28)
                        if (playing != fileId) return@launch
                        player[0] =
                            MediaPlayer().apply {
                                setDataSource(file.path)
                                setOnCompletionListener { stop() }
                                prepare()
                                start()
                            }
                    } catch (e: Exception) {
                        Log.w("sak-notes", "a recording could not be played", e)
                        stop()
                        model.recordingNotPlayed()
                    }
                }
            }
        },
        stop = ::stop,
    )
}
