@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*

private val imageClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

internal fun remoteImage(reference: String): ByteArray {
    var url = ImageReference.network(reference)
    repeat(6) {
        require(url.startsWith("https://", true)) { "Remote images require HTTPS" }
        imageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                url =
                    ImageReference.network(
                        response.request.url
                            .resolve(response.header("Location") ?: error("Missing redirect"))
                            ?.toString() ?: error("Invalid redirect")
                    )
            } else {
                require(response.isSuccessful) { "Image request failed (${response.code})" }
                val body = response.body ?: error("Empty image")
                require(body.contentLength() <= 20 * 1024 * 1024) { "Image exceeds 20 MiB" }
                val bytes = body.byteStream().readNBytes(20 * 1024 * 1024 + 1)
                require(bytes.size <= 20 * 1024 * 1024) { "Image exceeds 20 MiB" }
                return bytes
            }
        }
    }
    error("Too many image redirects")
}

@Composable
fun PreviewImage(model: OrgendaViewModel, document: OrgDocument, reference: ImageReference) {
    val context = LocalContext.current
    val workspace by model.workspace.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    var presentedImage by remember { mutableStateOf<Pair<Any, String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val data by
        produceState<Any?>(null, workspace.workspace?.id, document, reference, retry) {
            value = null
            failure = null
            try {
                value =
                    withContext(Dispatchers.IO) {
                        val paths =
                            reference.candidates(
                                document,
                                workspace.configuration.files.attachments,
                            )
                        val bytes = paths.firstNotNullOf { path ->
                            ensureActive()
                            try {
                                if (path.startsWith("https://") || path.startsWith("http://"))
                                    remoteImage(path)
                                else model.repository.attachment(path)
                            } catch (e: StorageFailure.Missing) {
                                if (path == paths.last()) throw e
                                null
                            }
                        }
                        PreviewImageDecoder.prepare(bytes)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message
            }
        }
    val request =
        remember(data, retry) {
            ImageRequest.Builder(context).data(data).size(2048).precision(Precision.INEXACT).build()
        }
    Box {
        when {
            failure != null ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.fillMaxWidth().padding(UiSpacing.md)) {
                        Text(
                            tr("image_failed"),
                            style =
                                DocumentTypography.subheadline.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                        )
                        Text(reference.label, style = DocumentTypography.footnote)
                        Text(failure.orEmpty(), style = DocumentTypography.footnote)
                        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { retry++ }) {
                            Icon(Icons.Default.Refresh, null)
                            Text(tr("retry"), style = DocumentTypography.footnote)
                        }
                    }
                }
            data == null ->
                Box(
                    Modifier.fillMaxWidth().height(88.dp).semantics {
                        contentDescription = context.label("loading_image")
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            else ->
                AsyncImage(
                    request,
                    reference.label,
                    onError = {
                        failure = it.result.throwable.message ?: context.label("image_failed")
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp, max = 360.dp)
                            .testTag("image.inline.${reference.start}")
                            .combinedClickable(
                                role = Role.Button,
                                onClickLabel = tr("open_image"),
                                onLongClickLabel = tr("reload_image"),
                                onClick = { data?.let { presentedImage = it to reference.label } },
                                onLongClick = { menu = true },
                            ),
                )
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr("reload_image")) },
                onClick = {
                    menu = false
                    retry++
                },
            )
        }
    }
    presentedImage?.let { (image, label) -> ImageViewer(image, label) { presentedImage = null } }
}

@Composable
private fun ImageViewer(data: Any, label: String, close: () -> Unit) {
    ModalPresence()
    val context = LocalContext.current
    val request =
        remember(data) {
            ImageRequest.Builder(context).data(data).size(2048).precision(Precision.INEXACT).build()
        }
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        var imageSize by remember { mutableStateOf(Size.Zero) }
        fun constrain(value: Offset, zoom: Float): Offset {
            if (
                !imageSize.width.isFinite() ||
                    !imageSize.height.isFinite() ||
                    imageSize.width <= 0f ||
                    imageSize.height <= 0f ||
                    viewport.width <= 0 ||
                    viewport.height <= 0
            )
                return Offset.Zero
            val fit = minOf(viewport.width / imageSize.width, viewport.height / imageSize.height)
            val maxX = ((imageSize.width * fit * zoom - viewport.width) / 2f).coerceAtLeast(0f)
            val maxY = ((imageSize.height * fit * zoom - viewport.height) / 2f).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }
        fun zoom(value: Float) {
            scale = value.coerceIn(1f, 5f)
            offset = constrain(offset, scale)
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = close) { Icon(Icons.Default.Close, tr("close")) }
                    },
                    actions = {
                        IconButton(onClick = { zoom(scale / 1.5f) }, enabled = scale > 1f) {
                            Icon(Icons.Default.ZoomOut, tr("zoom_out"))
                        }
                        IconButton(onClick = { zoom(scale * 1.5f) }, enabled = scale < 5f) {
                            Icon(Icons.Default.ZoomIn, tr("zoom_in"))
                        }
                    },
                )
            }
        ) { insets ->
            Box(
                Modifier.fillMaxSize()
                    .padding(insets)
                    .clipToBounds()
                    .testTag("image.viewport")
                    .onSizeChanged {
                        if (viewport != it) {
                            viewport = it
                            scale = 1f
                            offset = Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoom(if (scale > 1f) 1f else 2.5f)
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, factor, _ ->
                            scale = (scale * factor).coerceIn(1f, 5f)
                            offset = constrain(offset + pan, scale)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    request,
                    label,
                    onSuccess = {
                        imageSize = it.painter.intrinsicSize
                        offset = constrain(offset, scale)
                    },
                    modifier =
                        Modifier.fillMaxSize()
                            .semantics {
                                stateDescription =
                                    java.text.NumberFormat.getPercentInstance()
                                        .format(scale.toDouble())
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                )
            }
        }
    }
}
