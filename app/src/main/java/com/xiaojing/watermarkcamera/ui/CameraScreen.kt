package com.xiaojing.watermarkcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    var showStitchPanel by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var hasFlashUnit by remember { mutableStateOf(true) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
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

    // 绑定相机：画幅切换时重新绑定，保证预览与成片比例一致
    LaunchedEffect(hasCamera, lensFacing, previewView, state.ratio) {
        val pv = previewView ?: return@LaunchedEffect
        if (!hasCamera) return@LaunchedEffect
        val provider = context.getCameraProvider()
        provider.unbindAll()

        val targetRatio = if (state.ratio == CaptureRatio.RATIO_4_3) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9

        val preview = Preview.Builder()
            .setTargetAspectRatio(targetRatio)
            .build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(targetRatio)
            .build()
        capture = imageCapture

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            // 前置摄像头通常没有闪光灯，记录下来供按钮提示
            hasFlashUnit = camera.cameraInfo.hasFlashUnit()
            if (!hasFlashUnit) flashOn = false
        } catch (e: Exception) {
            // 绑定失败时不崩溃，界面给出提示
        }
    }

    // 闪光灯：绑定相机或切换开关后都要重新下发（重绑相机会新建 ImageCapture，flashMode 会回到默认）
    LaunchedEffect(flashOn, capture, hasFlashUnit) {
        val cap = capture ?: return@LaunchedEffect
        cap.flashMode = if (flashOn && hasFlashUnit) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            // 取景框：4:3 时居中留边，全屏时铺满
            Box(
                modifier = if (state.ratio == CaptureRatio.RATIO_4_3) {
                    Modifier.fillMaxWidth().aspectRatio(3f / 4f).align(Alignment.Center)
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
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

                // 参考线
                GridOverlay(mode = state.grid, modifier = Modifier.fillMaxSize())

                WatermarkOverlay(
                    showTime = state.showTime,
                    showDate = state.showDate,
                    showWeather = state.showWeather,
                    showLocation = state.showLocation && state.location.isNotBlank(),
                    showNote = state.showNote && state.note.isNotBlank(),
                    location = state.location,
                    weather = state.weather,
                    note = state.note,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 20.dp)
                )
            }

            // 顶部工具条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 44.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundIconButton(onClick = {
                        if (!hasFlashUnit) {
                            vm.setToast("当前摄像头没有闪光灯")
                        } else {
                            flashOn = !flashOn
                        }
                    }) {
                        Icon(
                            imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "闪光灯",
                            tint = if (flashOn) ACCENT else Color.White
                        )
                    }
                    RoundIconButton(onClick = {
                        vm.setGrid(
                            when (state.grid) {
                                GridMode.NONE -> GridMode.GRID
                                GridMode.GRID -> GridMode.CROSS
                                GridMode.CROSS -> GridMode.NONE
                            }
                        )
                    }) {
                        Icon(
                            Icons.Filled.GridOn,
                            "参考线",
                            tint = if (state.grid != GridMode.NONE) ACCENT else Color.White
                        )
                    }
                    RoundIconButton(onClick = {
                        vm.setRatio(
                            if (state.ratio == CaptureRatio.RATIO_4_3) CaptureRatio.RATIO_FULL
                            else CaptureRatio.RATIO_4_3
                        )
                    }) {
                        Text(
                            if (state.ratio == CaptureRatio.RATIO_4_3) "4:3" else "全",
                            color = ACCENT,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                        onToggleWeather = vm::toggleWeather,
                        onToggleLocation = vm::toggleLocation,
                        onToggleNote = vm::toggleNote,
                        onNoteChange = vm::setNote,
                        onPickNote = vm::pickNote
                    )
                }
                AnimatedVisibility(visible = showStitchPanel, enter = fadeIn(), exit = fadeOut()) {
                    StitchPanel(
                        state = state,
                        onColumns = vm::setStitchColumns,
                        onStitch = { vm.stitchAndSave() },
                        onUndo = { vm.undoPending() },
                        onClear = { vm.clearPending() }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 边拍边拼开关 + 张数
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (state.stitching) ACCENT else Color(0x66000000))
                            .clickable { vm.toggleStitching() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (state.stitching) "拼图中" else "边拍边拼",
                            color = if (state.stitching) Color(0xFF0B1B2B) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.pendingCount > 0) {
                            Text(
                                "${state.pendingCount} 张",
                                color = if (state.stitching) Color(0xFF0B1B2B) else ACCENT,
                                fontSize = 10.sp
                            )
                        }
                    }

                    ShutterButton(enabled = !state.shooting) {
                        val imageCapture = capture ?: return@ShutterButton
                        // 拍照前再下发一次闪光灯，避免重绑相机后状态丢失
                        imageCapture.flashMode = if (flashOn && hasFlashUnit) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
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
                                    vm.setToast("拍照失败：" + (exception.message ?: "未知错误"))
                                }
                            }
                        )
                    }

                    // 拼图面板入口
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x66000000))
                            .clickable { showStitchPanel = !showStitchPanel }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.GridOn, "拼图", tint = if (state.pendingCount > 0) ACCENT else Color.White)
                        Text("拼接", color = Color.White, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                RoundIconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    size = 40.dp
                ) {
                    Icon(Icons.Filled.SwapHoriz, "切换摄像头", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            if (state.shooting || state.busy) {
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

/** 取景参考线：九宫格或十字线 */
@Composable
private fun GridOverlay(mode: GridMode, modifier: Modifier = Modifier) {
    if (mode == GridMode.NONE) return
    Canvas(modifier = modifier) {
        val line = Color(0x80FFFFFF)
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        if (mode == GridMode.GRID) {
            for (i in 1..2) {
                drawLine(line, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), stroke)
                drawLine(line, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), stroke)
            }
        } else {
            drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
            drawLine(line, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
        }
    }
}

@Composable
private fun WatermarkOverlay(
    showTime: Boolean,
    showDate: Boolean,
    showWeather: Boolean,
    showLocation: Boolean,
    showNote: Boolean,
    location: String,
    weather: String,
    note: String,
    modifier: Modifier = Modifier
) {
    if (!showTime && !showDate && !showLocation && !showNote && !(showWeather && weather.isNotBlank())) return
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            kotlinx.coroutines.delay(1000)
        }
    }
    val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(now)
    val weekText = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
    val weatherText = if (showWeather) weather.trim() else ""
    val weekLine = when {
        showDate && weatherText.isNotEmpty() -> "$weekText  $weatherText"
        showDate -> weekText
        else -> weatherText
    }

    Column(modifier = modifier) {
        // 头行：时间在黄线左侧，高度与右侧两行小字对齐
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Bottom
        ) {
            if (showTime) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Bottom)
                )
                Spacer(Modifier.width(8.dp))
            }
            if (showDate || weekLine.isNotBlank()) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(ACCENT)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    if (showDate) {
                        Text(dateText, color = Color(0xE6FFFFFF), fontSize = 13.sp)
                    }
                    if (weekLine.isNotBlank()) {
                        Text(weekLine, color = Color(0xE6FFFFFF), fontSize = 12.sp)
                    }
                }
            }
        }
        // 地点、备注整行左对齐
        if (showLocation && location.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(location, color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 2)
        }
        if (showNote && note.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text("备注： " + note, color = ACCENT, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** 边拍边拼面板：列数选择 + 拼接/撤销/清空 */
@Composable
private fun StitchPanel(
    state: WatermarkUiState,
    onColumns: (Int) -> Unit,
    onStitch: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC101F2E))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("拼图列数", color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..4) {
                    val selected = state.stitchColumns == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ACCENT else Color(0x33FFFFFF))
                            .clickable { onColumns(n) }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "${n}列",
                            color = if (selected) Color(0xFF0B1B2B) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "已暂存 ${state.pendingCount} 张",
            color = Color(0x99FFFFFF),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStitch,
                enabled = state.pendingCount > 0 && !state.busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT)
            ) {
                Text("拼接保存", color = Color(0xFF0B1B2B), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onUndo, enabled = state.pendingCount > 0) { Text("撤销") }
            OutlinedButton(onClick = onClear, enabled = state.pendingCount > 0) { Text("清空") }
        }
    }
}

@Composable
private fun WatermarkPanel(
    state: WatermarkUiState,
    onToggleTime: () -> Unit,
    onToggleDate: () -> Unit,
    onToggleWeather: () -> Unit,
    onToggleLocation: () -> Unit,
    onToggleNote: () -> Unit,
    onNoteChange: (String) -> Unit,
    onPickNote: (String) -> Unit
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
        ToggleRow("天气", state.showWeather, onToggleWeather)
        ToggleRow("地点", state.showLocation, onToggleLocation)
        ToggleRow("备注", state.showNote, onToggleNote)

        // 备注快捷选项：总表 / 电采暖A项 / 电采暖B项 / 电采暖C项
        if (state.showNote) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (preset in NOTE_PRESETS) {
                    val selected = state.note == preset
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ACCENT else Color(0x33FFFFFF))
                            .clickable { onPickNote(if (selected) "" else preset) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            preset,
                            color = if (selected) Color(0xFF0B1B2B) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                placeholder = { Text("以上都没有，自行填写", color = Color(0x80FFFFFF)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
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