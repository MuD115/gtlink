package com.nmnd.gtlink.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nmnd.gtlink.GtApp
import com.nmnd.gtlink.service.CameraBus
import com.nmnd.gtlink.theme.GTLinkTheme
import com.nmnd.gtlink.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Full-screen camera the watch's remote shutter drives. It tells the watch it's open
 * while resumed, takes a picture on [CameraBus.shutter] and closes on [CameraBus.close].
 */
class CameraActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private var permissionGranted by mutableStateOf(false)
    private var cameraReady by mutableStateOf(false)
    private var timerEnabled by mutableStateOf(false)
    private var countdown by mutableIntStateOf(0)
    private var flashTick by mutableIntStateOf(0)
    private var thumbnail by mutableStateOf<Bitmap?>(null)
    private var savedUri by mutableStateOf<Uri?>(null)
    private var savedBadge by mutableStateOf(false)

    private var capturing = false
    private var resumed = false
    private var published = false
    private var countdownJob: Job? = null
    private var badgeJob: Job? = null
    private var sound: MediaActionSound? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionGranted = true
            publishVisibility()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CameraBus.shutter.collect { requestShot(fromScreen = false) } }
                launch { CameraBus.close.collect { finish() } }
            }
        }

        setContent {
            GTLinkTheme {
                if (permissionGranted) CameraContent() else PermissionRationale()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        publishVisibility()
    }

    override fun onPause() {
        resumed = false
        countdownJob?.cancel()
        publishVisibility()
        super.onPause()
    }

    override fun onDestroy() {
        sound?.release()
        sound = null
        super.onDestroy()
    }

    /** Keeps [CameraBus] and the watch's shutter screen in step with whether the camera is usable. */
    private fun publishVisibility() {
        val visible = resumed && permissionGranted
        if (visible == published) return
        published = visible
        CameraBus.visible = visible
        if (visible) {
            vm.cameraVisible(true)
        } else {
            // The view model may be cleared right after this on finish(), so use the app scope.
            val app = application as GtApp
            app.scope.launch { app.connection.setCameraOpen(false) }
        }
    }

    // --- taking pictures --------------------------------------------------------------------

    private fun requestShot(fromScreen: Boolean) {
        if (countdownJob?.isActive == true) {
            if (fromScreen) countdownJob?.cancel()
            return
        }
        if (!cameraReady || capturing) return
        if (timerEnabled) {
            countdownJob = lifecycleScope.launch {
                try {
                    for (s in 3 downTo 1) {
                        countdown = s
                        delay(1000)
                    }
                } finally {
                    countdown = 0
                }
                capture()
            }
        } else {
            capture()
        }
    }

    private fun capture() {
        if (!cameraReady || capturing) return
        capturing = true
        @Suppress("DEPRECATION")
        imageCapture.targetRotation = windowManager.defaultDisplay.rotation
        flashTick++
        playShutterSound()
        imageCapture.takePicture(
            outputOptions(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    onSaved(output.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(this@CameraActivity, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun outputOptions(): ImageCapture.OutputFileOptions {
        val name = "GTLink_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GT Link")
            }
            ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        } else {
            // App-specific storage needs no permission on API 26-28.
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            dir.mkdirs()
            ImageCapture.OutputFileOptions.Builder(File(dir, "$name.jpg")).build()
        }
    }

    private fun onSaved(uri: Uri?) {
        savedUri = uri
        savedBadge = true
        badgeJob?.cancel()
        badgeJob = lifecycleScope.launch {
            delay(2500)
            savedBadge = false
        }
        if (uri != null) {
            lifecycleScope.launch {
                thumbnail = withContext(Dispatchers.IO) { decodeThumbnail(uri) } ?: thumbnail
            }
        }
    }

    private fun decodeThumbnail(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
    }.getOrNull()

    private fun playShutterSound() {
        runCatching {
            val s = sound ?: MediaActionSound().also { sound = it }
            s.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun openInGallery() {
        val uri = savedUri ?: return
        if (uri.scheme != "content") return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(view) }
    }

    // --- UI ---------------------------------------------------------------------------------

    @Composable
    private fun PermissionRationale() {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp),
                    )
                }
                Text("Camera access", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "GT Link uses this phone's camera so your watch can work as a remote shutter. " +
                        "Photos are saved to your gallery and never leave the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Continue") }
                TextButton(onClick = { finish() }) { Text("Not now") }
            }
        }
    }

    @Composable
    private fun CameraContent() {
        val context = LocalContext.current
        val dark = isSystemInDarkTheme()

        // The preview is always dark, so the system bars need light icons whatever the theme says.
        LaunchedEffect(dark) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }

        var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
        var canFlip by remember { mutableStateOf(false) }
        var camera by remember { mutableStateOf<Camera?>(null) }
        var focusPoint by remember { mutableStateOf<Offset?>(null) }
        val previewView = remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }

        LaunchedEffect(lensFacing) {
            cameraReady = false
            val provider = runCatching { context.cameraProvider() }.getOrNull()
            if (provider == null) {
                Toast.makeText(context, "Couldn't start the camera", Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            fun has(facing: Int) = runCatching {
                provider.hasCamera(CameraSelector.Builder().requireLensFacing(facing).build())
            }.getOrDefault(false)

            val hasBack = has(CameraSelector.LENS_FACING_BACK)
            val hasFront = has(CameraSelector.LENS_FACING_FRONT)
            canFlip = hasBack && hasFront
            val facing = when {
                lensFacing == CameraSelector.LENS_FACING_BACK && hasBack -> CameraSelector.LENS_FACING_BACK
                lensFacing == CameraSelector.LENS_FACING_FRONT && hasFront -> CameraSelector.LENS_FACING_FRONT
                hasBack -> CameraSelector.LENS_FACING_BACK
                else -> CameraSelector.LENS_FACING_FRONT
            }
            runCatching {
                provider.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                camera = provider.bindToLifecycle(
                    this@CameraActivity,
                    CameraSelector.Builder().requireLensFacing(facing).build(),
                    preview,
                    imageCapture,
                )
                cameraReady = true
            }.onFailure {
                Toast.makeText(context, "Couldn't start the camera", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(focusPoint) {
            if (focusPoint != null) {
                delay(800)
                focusPoint = null
            }
        }

        val flash = remember { Animatable(0f) }
        LaunchedEffect(flashTick) {
            if (flashTick > 0) {
                flash.snapTo(0.9f)
                flash.animateTo(0f, tween(280))
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Tap to focus.
            Box(
                Modifier.fillMaxSize().pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        focusPoint = offset
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        runCatching { cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
                    }
                },
            )

            focusPoint?.let { p ->
                Box(
                    Modifier
                        .offset { IntOffset(p.x.roundToInt() - 32.dp.roundToPx(), p.y.roundToInt() - 32.dp.roundToPx()) }
                        .size(64.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                )
            }

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayButton(Icons.Rounded.Close, "Close camera", onClick = { finish() })
                    Spacer(Modifier.weight(1f))
                    TimerPill(timerEnabled, onClick = { timerEnabled = !timerEnabled })
                }

                Spacer(Modifier.weight(1f))

                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = savedBadge, label = "status") { saved ->
                        if (saved) StatusChip(Icons.Rounded.Check, "Saved")
                        else StatusChip(Icons.Rounded.Watch, "Press the shutter on your watch")
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Thumbnail(thumbnail, onClick = ::openInGallery)
                    }
                    ShutterButton(
                        enabled = cameraReady,
                        counting = countdown > 0,
                        onClick = { requestShot(fromScreen = true) },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (canFlip) {
                            OverlayButton(
                                Icons.Rounded.FlipCameraAndroid, "Switch camera",
                                size = 52.dp,
                                onClick = {
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                        CameraSelector.LENS_FACING_FRONT
                                    } else {
                                        CameraSelector.LENS_FACING_BACK
                                    }
                                },
                            )
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = countdown,
                transitionSpec = { (fadeIn(tween(150)) + scaleIn(tween(250), initialScale = 1.5f)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.align(Alignment.Center),
                label = "countdown",
            ) { n ->
                Text(
                    if (n > 0) n.toString() else "",
                    color = Color.White,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        }
    }
}

private val scrim = Color.Black.copy(alpha = 0.45f)

@Composable
private fun OverlayButton(icon: ImageVector, description: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Surface(onClick = onClick, shape = CircleShape, color = scrim, contentColor = Color.White, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TimerPill(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (enabled) Color.White else scrim,
        contentColor = if (enabled) Color.Black else Color.White,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (enabled) Icons.Rounded.Timer else Icons.Rounded.TimerOff,
                contentDescription = if (enabled) "3 second timer on" else "3 second timer off",
                modifier = Modifier.size(20.dp),
            )
            Text(if (enabled) "3s" else "Timer", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(CircleShape).background(scrim).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Thumbnail(bitmap: Bitmap?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(56.dp).clip(shape).border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
            .background(scrim).clickable(enabled = bitmap != null, role = Role.Button, onClickLabel = "Open photo", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, counting: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    val inner = when {
        counting -> MaterialTheme.colorScheme.error
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.4f)
    }
    Box(
        Modifier
            .size(80.dp)
            .scale(scale)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(inner)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (counting) "Cancel timer" else "Take photo",
                onClick = onClick,
            ),
    )
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
        {
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        ContextCompat.getMainExecutor(this),
    )
}
