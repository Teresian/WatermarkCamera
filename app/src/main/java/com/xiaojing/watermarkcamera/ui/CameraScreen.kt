package com.xiaojing.watermarkcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaojing.watermarkcamera.util.PlaceLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

private val ACCENT = Color(0xFFFFD54F)

/** 顶部工具条按钮直径 */
private val TOP_BUTTON = 44.dp

/** 顶部工具条距屏幕顶部的距离（比上一版上移 6dp） */
private val TOP_BAR_TOP = 38.dp

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showPanel by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var hasFlashUnit by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var flashMenuOpen by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 取景框顶边：贴在上方按键下面，再留 5 像素
    val viewfinderTop = TOP_BAR_TOP + TOP_BUTTON + with(density) { 5.toDp() }

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
            val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            camera = bound
            // 前置摄像头通常没有闪光灯，记录下来供按钮提示
            hasFlashUnit = bound.cameraInfo.hasFlashUnit()
            if (!hasFlashUnit) vm.setFlashMode(FlashMode.OFF)
            // 回填最大变焦倍数
            bound.cameraInfo.zoomState.value?.let { vm.setMaxZoom(it.maxZoomRatio) }
        } catch (e: Exception) {
            // 绑定失败时不崩溃，界面给出提示
        }
    }

    // 闪光灯：重绑相机会新建 ImageCapture，flashMode 会回到默认，需重新下发；常亮走 cameraControl
    LaunchedEffect(state.flashMode, capture, hasFlashUnit, camera) {
        val cap = capture ?: return@LaunchedEffect
        cap.flashMode = if (state.flashMode == FlashMode.ON && hasFlashUnit) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(state.flashMode == FlashMode.TORCH && hasFlashUnit)
    }

    // 变焦：按钮和双指手势都写进 state，这里统一下发给相机
    LaunchedEffect(state.zoomRatio, camera) {
        val cam = camera ?: return@LaunchedEffect
        runCatching { cam.cameraControl.setZoomRatio(state.zoomRatio) }
    }

    DisposableEffect(Unit) {
        onDispose { camera?.cameraControl?.enableTorch(false) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            // 取景框：移到上方按键下方
            val viewfinderModifier = if (state.ratio == CaptureRatio.RATIO_4_3) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .align(Alignment.TopCenter)
                    .offset(y = viewfinderTop)
            } else {
                Modifier.fillMaxSize()
            }

            Box(modifier = viewfinderModifier) {
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

                // 双指缩放层：盖在预览上，单指不消费事件，点击照常穿透到水印
                ZoomGestureLayer(
                    onZoom = { vm.zoomBy(it) },
                    modifier = Modifier.fillMaxSize()
                )

                // 参考线
                GridOverlay(mode = state.grid, modifier = Modifier.fillMaxSize())

                // 水印预览：只有水印本体可点，空白处不响应
                WatermarkOverlay(
                    showTime = state.showTime,
                    showDate = state.showDate,
                    showWeather = state.showWeather,
                    showLocation = state.showLocation && state.location.isNotBlank(),
                    showNote = state.showNote && state.note.isNotBlank(),
                    location = state.location,
                    weather = state.weather,
                    note = state.note,
                    onClick = {
                        vm.setShowStitchPanel(false)
                        showPanel = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        // 右边留出右下角拼图键的位置，避免长地名被按钮压住
                        .padding(start = 18.dp, end = 78.dp, bottom = 20.dp)
                )

                // 右侧 1x / 2x 快捷变焦
                ZoomShortcuts(
                    ratio = state.zoomRatio,
                    onPick = { vm.setZoom(it) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                )

                // 右下角：没拍照时是拼图设置，有暂存后是拼图确定
                StitchCornerButton(
                    pendingCount = state.pendingCount,
                    stitching = state.stitching,
                    columns = state.stitchColumns,
                    busy = state.busy,
                    onSettings = {
                        showPanel = false
                        vm.setShowStitchPanel(true)
                    },
                    onConfirm = { vm.stitchAndSave() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                )
            }

            // 面板打开时点空白关闭（只盖住取景框，顶部按键仍可用）
            if (showPanel || state.showStitchPanel) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x33000000))
                        .clickable {
                            showPanel = false
                            vm.setShowStitchPanel(false)
                        }
                )
            }

            // 顶部工具条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TOP_BAR_TOP, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 闪光灯：菜单式，点开选 关 / 闪光 / 常亮
                    Box {
                        RoundIconButton(size = TOP_BUTTON, onClick = {
                            if (!hasFlashUnit) vm.setToast("当前摄像头没有闪光灯")
                            else flashMenuOpen = true
                        }) {
                            Icon(
                                imageVector = if (state.flashMode == FlashMode.OFF) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                                contentDescription = "闪光灯",
                                tint = if (state.flashMode == FlashMode.OFF) Color.White else ACCENT
                            )
                            if (state.flashMode == FlashMode.TORCH) {
                                // 常亮模式右下角标「亮」
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ACCENT)
                                        .padding(horizontal = 3.dp)
                                ) {
                                    Text("亮", color = Color(0xFF0B1B2B), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = flashMenuOpen,
                            onDismissRequest = { flashMenuOpen = false }
                        ) {
                            for (mode in FlashMode.entries) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.label,
                                            fontSize = 14.sp,
                                            fontWeight = if (state.flashMode == mode) FontWeight.Bold else FontWeight.Normal,
                                            color = if (state.flashMode == mode) Color(0xFFFFB300) else Color(0xFF202020)
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (mode == FlashMode.OFF) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                                            contentDescription = null,
                                            tint = if (state.flashMode == mode) Color(0xFFFFB300) else Color(0xFF666666)
                                        )
                                    },
                                    onClick = {
                                        vm.setFlashMode(mode)
                                        flashMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    RoundIconButton(size = TOP_BUTTON, onClick = {
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
                    RoundIconButton(size = TOP_BUTTON, onClick = {
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
                    RoundIconButton(size = TOP_BUTTON, onClick = { vm.refreshLocation() }) {
                        Icon(Icons.Filled.MyLocation, "刷新定位", tint = Color.White)
                    }
                    RoundIconButton(size = TOP_BUTTON, onClick = {
                        vm.setShowStitchPanel(false)
                        showPanel = !showPanel
                    }) {
                        Icon(Icons.Filled.Settings, "水印设置", tint = Color.White)
                    }
                }
            }

            // 底部控制区
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
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
                        onPickNote = vm::pickNote,
                        onAddPreset = vm::addNotePreset,
                        onRemovePreset = vm::removeNotePreset,
                        onPlaceLevel = vm::setPlaceLevel,
                        onClose = { showPanel = false }
                    )
                }
                AnimatedVisibility(visible = state.showStitchPanel, enter = fadeIn(), exit = fadeOut()) {
                    StitchPanel(
                        state = state,
                        onToggleStitching = vm::toggleStitching,
                        onColumns = vm::setStitchColumns,
                        onUndo = { vm.undoPending() },
                        onClear = { vm.clearPending() },
                        onConfirm = {
                            vm.setShowStitchPanel(false)
                            vm.stitchAndSave()
                        },
                        onClose = { vm.setShowStitchPanel(false) }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 边拍边拼开关
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
                                state.pendingCount.toString() + " 张",
                                color = if (state.stitching) Color(0xFF0B1B2B) else ACCENT,
                                fontSize = 10.sp
                            )
                        }
                    }

                    ShutterButton(enabled = !state.shooting) {
                        val imageCapture = capture ?: return@ShutterButton
                        // 拍照前再下发一次闪光灯，避免重绑相机后状态丢失
                        imageCapture.flashMode = if (state.flashMode == FlashMode.ON && hasFlashUnit) {
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

                    // 切换摄像头
                    RoundIconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                        },
                        size = 52.dp
                    ) {
                        Icon(Icons.Filled.SwapHoriz, "切换摄像头", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
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

/** 双指缩放层：只有两根手指同时按下时才消费事件，单指放行 */
@Composable
private fun ZoomGestureLayer(onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f && zoom.isFinite()) {
                            onZoom(zoom)
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    )
}

/** 取景框右下角：没拍照=拼图设置，有暂存=拼图确定（长按始终打开设置） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StitchCornerButton(
    pendingCount: Int,
    stitching: Boolean,
    columns: Int,
    busy: Boolean,
    onSettings: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasPending = pendingCount > 0
    Column(
        modifier = modifier
            .width(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (hasPending) ACCENT else Color(0x66000000))
            .combinedClickable(
                enabled = !busy,
                onClick = { if (hasPending) onConfirm() else onSettings() },
                onLongClick = { onSettings() }
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (hasPending) "确定" else "拼图",
            color = if (hasPending) Color(0xFF0B1B2B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                hasPending -> pendingCount.toString() + " 张"
                stitching -> PhotoColumnsLabel(columns)
                else -> "设置"
            },
            color = if (hasPending) Color(0xFF0B1B2B) else ACCENT,
            fontSize = 10.sp
        )
    }
}

/** 取景框右侧 1x / 2x 快捷变焦 */
@Composable
private fun ZoomShortcuts(
    ratio: Float,
    onPick: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (z in listOf(1f, 2f)) {
            val selected = abs(ratio - z) < 0.05f
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (selected) ACCENT else Color(0x66000000))
                    .clickable { onPick(z) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    zoomLabel(z),
                    color = if (selected) Color(0xFF0B1B2B) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // 手势缩放后显示当前倍数
        if (ratio > 2.05f) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(zoomLabel(ratio), color = ACCENT, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun zoomLabel(z: Float): String =
    if (abs(z - z.toInt()) < 0.05f) z.toInt().toString() + "x"
    else String.format(Locale.US, "%.1fx", z)

/** 列数对应的短标签，用于底部按钮 */
private fun PhotoColumnsLabel(columns: Int): String = when (columns) {
    1 -> "单列"
    2 -> "两列"
    3 -> "三列"
    else -> "四列"
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
    onClick: () -> Unit,
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
        showDate && weatherText.isNotEmpty() -> weekText + "  " + weatherText
        showDate -> weekText
        else -> weatherText
    }

    Column(modifier = modifier) {
        // 只有水印本体可点，周围空白不响应
        Column(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() }
        ) {
            // 头行：时间在黄线左侧，高度与右侧两行小字对齐
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Bottom
            ) {
                if (showTime) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Bottom)
                    )
                    Spacer(Modifier.width(9.dp))
                }
                if (showDate || weekLine.isNotBlank()) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(ACCENT)
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        if (showDate) {
                            Text(dateText, color = Color(0xE6FFFFFF), fontSize = 15.sp)
                        }
                        if (weekLine.isNotBlank()) {
                            Text(weekLine, color = Color(0xE6FFFFFF), fontSize = 14.sp)
                        }
                    }
                }
            }
            // 地点、备注整行左对齐
            if (showLocation && location.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(location, color = Color(0xE6FFFFFF), fontSize = 14.sp, maxLines = 2)
            }
            if (showNote && note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("备注： " + note, color = ACCENT, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

/** 拼图设置面板：边拍边拼开关、列数、暂存管理 */
@Composable
private fun StitchPanel(
    state: WatermarkUiState,
    onToggleStitching: () -> Unit,
    onColumns: (Int) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2101F2E))
            .clickable(enabled = false) {}
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("拼图设置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "关闭", tint = Color(0x99FFFFFF), modifier = Modifier.size(18.dp))
            }
        }

        ToggleRow("边拍边拼", state.stitching, onToggleStitching)

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("拼图列数", color = Color(0xCCFFFFFF), fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..4) {
                    val selected = state.stitchColumns == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ACCENT else Color(0x33FFFFFF))
                            .clickable { onColumns(n) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            n.toString() + "列",
                            color = if (selected) Color(0xFF0B1B2B) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.pendingCount > 0) "已暂存 " + state.pendingCount + " 张" else "还没有暂存照片",
                color = Color(0x99FFFFFF),
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "撤销",
                color = if (state.pendingCount > 0) ACCENT else Color(0x4DFFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = state.pendingCount > 0) { onUndo() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Text(
                "清空",
                color = if (state.pendingCount > 0) Color(0x99FFFFFF) else Color(0x4DFFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = state.pendingCount > 0) { onClear() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onConfirm,
            enabled = state.pendingCount > 0 && !state.busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color(0xFF0B1B2B))
        ) {
            Text(
                if (state.pendingCount > 0) "拼成" + PhotoColumnsLabel(state.stitchColumns) + "长图" else "先拍几张再拼",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

/** 水印设置面板：点面板外或右上角 × 关闭 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatermarkPanel(
    state: WatermarkUiState,
    onToggleTime: () -> Unit,
    onToggleDate: () -> Unit,
    onToggleWeather: () -> Unit,
    onToggleLocation: () -> Unit,
    onToggleNote: () -> Unit,
    onNoteChange: (String) -> Unit,
    onPickNote: (String) -> Unit,
    onAddPreset: (String) -> Unit,
    onRemovePreset: (String) -> Unit,
    onPlaceLevel: (PlaceLevel) -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2101F2E))
            .clickable(enabled = false) {}   // 吞掉点击，避免点到面板内部就关闭
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("水印设置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "关闭", tint = Color(0x99FFFFFF), modifier = Modifier.size(18.dp))
            }
        }

        ToggleRow("时间", state.showTime, onToggleTime)
        ToggleRow("日期", state.showDate, onToggleDate)
        ToggleRow("天气", state.showWeather, onToggleWeather)
        ToggleRow("地点", state.showLocation, onToggleLocation)
        ToggleRow("备注", state.showNote, onToggleNote)

        // 定位粒度：省 / 市 / 区 / 街道 / 地点
        if (state.showLocation) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("定位到", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (level in PlaceLevel.entries) {
                        val selected = state.placeLevel == level
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) ACCENT else Color(0x33FFFFFF))
                                .clickable { onPlaceLevel(level) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                level.label,
                                color = if (selected) Color(0xFF0B1B2B) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            if (state.location.isNotBlank()) {
                Text(
                    state.location,
                    color = Color(0x80FFFFFF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 备注：快捷选项（长按删除）+ 自定义添加
        if (state.showNote) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (preset in state.notePresets) {
                    val selected = state.note == preset
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ACCENT else Color(0x33FFFFFF))
                            .combinedClickable(
                                onClick = { onPickNote(if (selected) "" else preset) },
                                onLongClick = { onRemovePreset(preset) }
                            )
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
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.note,
                    onValueChange = onNoteChange,
                    placeholder = { Text("输入备注内容", color = Color(0x80FFFFFF), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // 把当前内容存成快捷项
                IconButton(
                    onClick = { onAddPreset(state.note) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                ) {
                    Icon(Icons.Filled.Add, "添加为快捷备注", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "长按快捷备注可删除",
                color = Color(0x66FFFFFF),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
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
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
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
