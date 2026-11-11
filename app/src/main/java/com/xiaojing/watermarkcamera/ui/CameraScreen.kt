package com.xiaojing.watermarkcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val ACCENT = Color(0xFFFFD54F)

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showPanel by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCamera = result[Manifest.permission.CAMERA] == true
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            vm.refreshLocation()
        }
    }

    LaunchedEffect(Unit) {
        if (hasCamera) {
            vm.refreshLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(state.toast) {
        val msg = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.consumeToast()
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    // 绑定相机
    LaunchedEffect(hasCamera, lensFacing, previewView) {
        val pv = previewView ?: return@LaunchedEffect
        if (!hasCamera) return@LaunchedEffect
        val provider = context.getCameraProvider()
        cameraProvider = provider
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pv.surfaceProvider)
        }
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        capture = imageCapture

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (e: Exception) {
            // 绑定失败时不崩溃，界面给出提示
        }
    }

    LaunchedEffect(flashOn) {
        capture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            WatermarkOverlay(
                showTime = state.showTime,
                showDate = state.showDate,
                showLocation = state.showLocation && state.location.isNotBlank(),
                showNote = state.showNote && state.note.isNotBlank(),
                location = state.location,
                note = state.note,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 150.dp)
            )

            // 顶部工具条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoundIconButton(onClick = { flashOn = !flashOn }) {
                    Icon(
                        imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "闪光灯",
                        tint = if (flashOn) ACCENT else Color.White
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundIconButton(onClick = { vm.refreshLocation() }) {
                        Icon(Icons.Filled.MyLocation, "刷新定位", tint = Color.White)
                    }
                    RoundIconButton(onClick = { showPanel = !showPanel }) {
                        Icon(Icons.Filled.Settings, "水印设置", tint = Color.White)
                    }
                }
            }

            // 底部控制区
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(visible = showPanel, enter = fadeIn(), exit = fadeOut()) {
                    WatermarkPanel(
                        state = state,
                        onToggleTime = vm::toggleTime,
                        onToggleDate = vm::toggleDate,
                        onToggleLocation = vm::toggleLocation,
                        onToggleNote = vm::toggleNote,
                        onNoteChange = vm::setNote
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(Modifier.size(48.dp))
                    ShutterButton(enabled = !state.shooting) {
                        val imageCapture = capture ?: return@ShutterButton
                        val file = java.io.File(context.cacheDir, "wm_capture_" + System.currentTimeMillis() + ".jpg")
                        val options = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(
                            options,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    vm.onPhotoCaptured(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
                                }
                            }
                        )
                    }
                    RoundIconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                        },
                        size = 48.dp
                    ) {
                        Icon(Icons.Filled.SwapHoriz, "切换摄像头", tint = Color.White)
                    }
                }
            }

            if (state.shooting) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ACCENT)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要相机权限", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "开启相机与定位权限后，即可拍照并自动烧录时间、地点水印。",
                    color = Color(0xB3FFFFFF), fontSize = 14.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text("授予权限") }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun WatermarkOverlay(
    showTime: Boolean,
    showDate: Boolean,
    showLocation: Boolean,
    showNote: Boolean,
    location: String,
    note: String,
    modifier: Modifier = Modifier
) {
    if (!showTime && !showDate && !showLocation && !showNote) return
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            kotlinx.coroutines.delay(1000)
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .width(4.dp)
                .height(if (showTime) 56.dp else 34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ACCENT)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            if (showTime) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showDate) {
                Text(
                    SimpleDateFormat("yyyy.MM.dd  EEEE", Locale.CHINA).format(now),
                    color = Color(0xE6FFFFFF),
                    fontSize = 13.sp
                )
            }
            if (showLocation) {
                Text(location, color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 1)
            }
            if (showNote) {
                Text(note, color = ACCENT, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WatermarkPanel(
    state: WatermarkUiState,
    onToggleTime: () -> Unit,
    onToggleDate: () -> Unit,
    onToggleLocation: () -> Unit,
    onToggleNote: () -> Unit,
    onNoteChange: (String) -> Unit
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC101F2E))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        ToggleRow("时间", state.showTime, onToggleTime)
        ToggleRow("日期", state.showDate, onToggleDate)
        ToggleRow("地点", state.showLocation, onToggleLocation)
        ToggleRow("备注", state.showNote, onToggleNote)
        if (state.showNote) {
            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                placeholder = { Text("输入备注文字", color = Color(0x80FFFFFF)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size).clip(CircleShape).background(Color(0x66000000))
    ) { content() }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White, Color(0xFFE0E0E0))))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(if (enabled) Color.White else Color(0xFFAAAAAA)))
    }
}

private suspend fun android.content.Context.getCameraProvider(): ProcessCameraProvider =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cont.resume(future.get()) {}
        }, ContextCompat.getMainExecutor(this))
    }
