package com.xiaojing.watermarkcamera.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Photo
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaojing.watermarkcamera.util.PlaceLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    var showPlacePicker by remember { mutableStateOf(false) }
    var showImagePreview by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 预览翻页：暂存模式用 pendingCount，相册模式用 Uri 列表
    var previewPending by remember { mutableStateOf(false) }
    var previewUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var lastGalleryBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastGalleryUri by remember { mutableStateOf<Uri?>(null) }
    val pendingThumb by vm.pendingThumb.collectAsState()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 相册预览：读取本应用最近保存的一张水印照片，点击直接查看大图
    fun refreshGalleryThumb() {
        val resolver = context.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val args = arrayOf("%WatermarkCamera%")
        // 按加入时间倒序取最新，避免 _ID 不连续/复用导致取到旧图或失效记录
        val cursor = resolver.query(uri, projection, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")
        val id = cursor?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)) else null
        }
        if (id == null) {
            lastGalleryBitmap = null
            lastGalleryUri = null
            return
        }
        val u = ContentUris.withAppendedId(uri, id)
        lastGalleryUri = u
        lastGalleryBitmap = runCatching {
            resolver.openInputStream(u)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, bounds)
                val sample = maxOf(1, bounds.outWidth / 240, bounds.outHeight / 240)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        }.getOrNull()
    }

    LaunchedEffect(Unit) { refreshGalleryThumb() }

    /** 查询本应用相册里最近保存的水印照片 Uri 列表（按时间倒序） */
    fun queryGalleryUris(limit: Int = 200): List<Uri> {
        val resolver = context.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val cursor = resolver.query(
            uri, arrayOf(MediaStore.Images.Media._ID), sel, arrayOf("%WatermarkCamera%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ) ?: return emptyList()
        return cursor.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val result = ArrayList<Uri>()
            while (c.moveToNext() && result.size < limit) {
                result.add(ContentUris.withAppendedId(uri, c.getLong(idx)))
            }
            result
        }
    }

    /** 加载第 index 张预览图并回收旧图 */
    fun loadPreviewAt(index: Int) {
        val total = if (previewPending) state.pendingCount else previewUris.size
        if (total == 0) return
        val safeIndex = index.coerceIn(0, total - 1)
        previewBitmap?.recycle()
        val bmp = if (previewPending) {
            vm.pendingBitmapAt(safeIndex)
        } else {
            val u = previewUris.getOrNull(safeIndex)
            u?.let { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(input, null, bounds)
                        val sample = maxOf(1, bounds.outWidth / 1600, bounds.outHeight / 1600)
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                }.getOrNull()
            }
        }
        if (bmp != null) {
            previewBitmap = bmp
            previewIndex = safeIndex
        }
    }

    /** 打开应用内图片预览器：拼图模式遍历暂存图，否则遍历相册照片 */
    fun openImagePreview() {
        if (state.stitching && state.pendingCount > 0) {
            previewPending = true
            previewUris = emptyList()
            loadPreviewAt(state.pendingCount - 1)
            showImagePreview = true
            return
        }
        val uris = queryGalleryUris()
        if (uris.isEmpty()) {
            vm.setToast("还没有可预览的照片")
            return
        }
        previewPending = false
        previewUris = uris
        loadPreviewAt(0)
        showImagePreview = true
    }

    // 取景框顶边：在上方按键下方留 4dp 间距，避免取景框覆盖按键
    val viewfinderTop = TOP_BAR_TOP + TOP_BUTTON + 4.dp

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
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        when {
            !hasCamera -> permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            hasLocation -> vm.refreshLocation()
            // 未授权定位时不弹「定位失败」，避免误导用户
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
        val provider = try {
            context.getCameraProvider()
        } catch (e: CancellationException) {
            // 组合离开/effect 被取消属正常流程，不弹错误提示
            return@LaunchedEffect
        } catch (e: Exception) {
            vm.setToast("相机初始化失败：" + (e.message ?: "未知错误"))
            return@LaunchedEffect
        }
        try {
            provider.unbindAll()

            val targetRatio = if (state.ratio == CaptureRatio.RATIO_4_3) {
                AspectRatio.RATIO_4_3
            } else {
                AspectRatio.RATIO_16_9
            }

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
            val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            camera = bound
            // 前置摄像头通常没有闪光灯，记录下来供按钮提示；
            // 这里不覆盖用户持久化的闪光灯设置，切回后置时仍保持原选择
            hasFlashUnit = bound.cameraInfo.hasFlashUnit()
            // 回填最大变焦倍数
            bound.cameraInfo.zoomState.value?.let { vm.setMaxZoom(it.maxZoomRatio) }
        } catch (e: Exception) {
            vm.setToast("相机绑定失败：" + (e.message ?: "未知错误"))
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

                // 边拍边拼：顶部横幅（含退出）+ 中间引导，样式对齐「今日水印相机」
                if (state.stitching) {
                    StitchBanner(
                        pendingCount = state.pendingCount,
                        onExit = { vm.toggleStitching() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    )
                    if (state.pendingCount == 0) {
                        StitchHint(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

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

                // 右下角：上面是边拍边拼开关，下面是拼图设置入口
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StitchToggleButton(
                        stitching = state.stitching,
                        pendingCount = state.pendingCount,
                        busy = state.busy,
                        onToggle = { vm.toggleStitching() }
                    )
                    StitchCornerButton(
                        stitching = state.stitching,
                        columns = state.stitchColumns,
                        busy = state.busy,
                        onSettings = {
                            showPanel = false
                            vm.setShowStitchPanel(true)
                        }
                    )
                }
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
                        RoundIconButton(size = TOP_BUTTON, squared = true, onClick = {
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
                                            fontSize = 15.sp,
                                            fontWeight = if (state.flashMode == mode) FontWeight.Bold else FontWeight.Medium,
                                            color = if (state.flashMode == mode) ACCENT else Color.White
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (mode == FlashMode.OFF) Icons.Filled.FlashOff else Icons.Filled.FlashOn,
                                            contentDescription = null,
                                            tint = if (state.flashMode == mode) ACCENT else Color(0xCCFFFFFF)
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
                    RoundIconButton(size = TOP_BUTTON, squared = true, onClick = {
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
                    RoundIconButton(size = TOP_BUTTON, squared = true, onClick = {
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
                    RoundIconButton(size = TOP_BUTTON, squared = true, onClick = { vm.refreshLocation() }) {
                        Icon(Icons.Filled.MyLocation, "刷新定位", tint = Color.White)
                    }
                    RoundIconButton(size = TOP_BUTTON, squared = true, onClick = {
                        vm.setShowStitchPanel(false)
                        showPanel = !showPanel
                    }) {
                        Icon(Icons.Filled.Settings, "水印设置", tint = Color.White)
                    }
                }
            }

            // 底部控制区：快门上移，下方留出模式键行
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
                        onLocationMode = vm::setLocationMode,
                        onPickPlace = { showPlacePicker = true },
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
                // 边拍边拼：有暂存时在快门上方显示「取消 / 完成（N张）」
                if (state.stitching && state.pendingCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    StitchActionsRow(
                        pendingCount = state.pendingCount,
                        busy = state.busy,
                        onCancel = {
                            vm.clearPending()
                            vm.toggleStitching()
                        },
                        onConfirm = {
                            vm.stitchAndSave()
                            refreshGalleryThumb()
                        }
                    )
                }
                // 与快门行拉开距离
                Spacer(Modifier.height(28.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 左侧：照片预览位——拍照时显示缓存成片，边拍边拼时显示最近暂存图（带张数角标）
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x66000000))
                            .clickable { openImagePreview() },
                        contentAlignment = Alignment.Center
                    ) {
                        val thumb = if (state.stitching && state.pendingCount > 0) pendingThumb else (pendingThumb ?: lastGalleryBitmap)
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = "照片预览",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Filled.Photo, "相册", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        if (state.stitching && state.pendingCount > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ACCENT)
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    state.pendingCount.toString() + "张",
                                    color = Color(0xFF0B1B2B),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 快门固定在正中；边拍边拼时为蓝色
                    ShutterButton(enabled = !state.shooting, active = state.stitching) {
                        val imageCapture = capture ?: return@ShutterButton
                        // 拍照前再下发一次闪光灯，避免重绑相机后状态丢失
                        imageCapture.flashMode = if (state.flashMode == FlashMode.ON && hasFlashUnit) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
                        // 记录按下快门的时刻，避免处理延迟导致水印时间与预览不一致
                        val capturedAt = System.currentTimeMillis()
                        val file = java.io.File(context.cacheDir, "wm_capture_$capturedAt.jpg")
                        val options = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(
                            options,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    vm.onPhotoCaptured(file, capturedAt)
                                    if (!state.stitching) refreshGalleryThumb()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
                                    vm.setToast("拍照失败：" + (exception.message ?: "未知错误"))
                                }
                            }
                        )
                    }

                    // 切换摄像头固定在右侧
                    RoundIconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                        },
                        size = 52.dp,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Filled.SwapHoriz, "切换摄像头", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }

                // 拍摄模式：视频 / 照片，默认照片
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (mode in CaptureMode.entries) {
                        val selected = state.captureMode == mode
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                mode.label,
                                color = if (selected) Color.White else Color(0x99FFFFFF),
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable { vm.setCaptureMode(mode) }
                            )
                            if (selected) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(ACCENT)
                                )
                            }
                        }
                    }
                }
            }

            if (state.shooting || state.busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        // 真正消费触摸事件，防止处理中点击穿透到撤销/清空/快门按钮
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
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

        // 选地点面板：框架占位，POI 数据稍后接入高德地图
        if (showPlacePicker) {
            PlacePickerDialog(onDismiss = { showPlacePicker = false })
        }

        // 应用内图片预览器：脱离系统相册，点空白处关闭
        if (showImagePreview) {
            val total = if (previewPending) state.pendingCount else previewUris.size
            ImagePreviewDialog(
                bitmap = previewBitmap,
                index = previewIndex,
                total = total,
                onPrev = { loadPreviewAt(previewIndex - 1) },
                onNext = { loadPreviewAt(previewIndex + 1) },
                onDismiss = {
                    showImagePreview = false
                    previewBitmap?.recycle()
                    previewBitmap = null
                }
            )
        }
    }
}

/** 应用内图片预览器：全屏大图 + 左右滑动/上一张/下一张翻页 + 页码指示 */
@Composable
private fun ImagePreviewDialog(
    bitmap: Bitmap?,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    // 水平位移超过该阈值才翻页，避免轻微抖动误触（单位 px）
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    // 翻页后 index/total 会变化，用 rememberUpdatedState 保证手势始终引用最新回调与页码
    val currentOnPrev by rememberUpdatedState(onPrev)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentIndex by rememberUpdatedState(index)
    val currentTotal by rememberUpdatedState(total)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
                // 左右滑动切换图片：左滑看下一张，右滑看上一张；到边界不响应。
                // 拖动会消费指针位移，因此不会误触发上面的「点空白关闭」。
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragCancel = { totalDx = 0f },
                        onDragEnd = {
                            when {
                                totalDx <= -swipeThresholdPx && currentIndex < currentTotal - 1 -> currentOnNext()
                                totalDx >= swipeThresholdPx && currentIndex > 0 -> currentOnPrev()
                            }
                            totalDx = 0f
                        },
                        onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "图片预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable(enabled = false) {},
                    contentScale = ContentScale.Fit
                )
            }
            // 顶部：关闭按钮 + 页码
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, "关闭预览", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (total > 1) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${index + 1} / $total",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        "左右滑动切换",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // 底部翻页：上一张 / 下一张
            if (total > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp)
                ) {
                    Text(
                        "上一张",
                        color = if (index > 0) Color.White else Color(0x66FFFFFF),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x99000000))
                            .clickable(enabled = index > 0, onClick = onPrev)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    Text(
                        "下一张",
                        color = if (index < total - 1) Color.White else Color(0x66FFFFFF),
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x99000000))
                            .clickable(enabled = index < total - 1, onClick = onNext)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** 选地点面板（框架占位）：分类与列表区域已预留，POI 搜索稍后接入合规地图 SDK */
@Composable
private fun PlacePickerDialog(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Color(0xF2101F2E))
                .clickable(enabled = false) {}
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("选择地点", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color(0x99FFFFFF), modifier = Modifier.size(18.dp))
                }
            }
            // 搜索框（占位）
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text("搜索附近地点（待接入地图）", color = Color(0x80FFFFFF), fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            // 分类标签（占位）：推荐 / 道路 / 小区 / 门店 / 写字楼 / 门牌号
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("推荐", "道路", "小区", "门店", "写字楼", "门牌号").forEachIndexed { index, label ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (index == 0) ACCENT else Color(0x33FFFFFF))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            label,
                            color = if (index == 0) Color(0xFF0B1B2B) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = null,
                        tint = Color(0x66FFFFFF),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("地点列表待接入高德地图", color = Color(0x80FFFFFF), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "POI 搜索将接入高德地图 SDK（需自行申请 Key），当前仅保留面板框架。",
                color = Color(0x66FFFFFF),
                fontSize = 11.sp
            )
        }
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

/** 拼图键上方的边拍边拼开关：点击切换状态，与拼图键同规格 */
@Composable
private fun StitchToggleButton(
    stitching: Boolean,
    pendingCount: Int,
    busy: Boolean,
    onToggle: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (stitching) ACCENT else Color(0x66000000))
            .clickable(enabled = !busy, onClick = onToggle)
            .padding(vertical = 7.dp)
    ) {
        Text(
            if (stitching) "拼图中" else "边拍边拼",
            color = if (stitching) Color(0xFF0B1B2B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            when {
                pendingCount > 0 -> pendingCount.toString() + " 张"
                stitching -> "进行中"
                else -> "关"
            },
            color = if (stitching) Color(0xFF0B1B2B) else ACCENT,
            fontSize = 10.sp
        )
    }
}

/** 取景框右下角：拼图设置入口（确定功能已移至取景框下方的「完成」键） */
@Composable
private fun StitchCornerButton(
    stitching: Boolean,
    columns: Int,
    busy: Boolean,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (stitching) ACCENT else Color(0x66000000))
            .clickable(enabled = !busy, onClick = onSettings)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "拼图",
            color = if (stitching) Color(0xFF0B1B2B) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (stitching) PhotoColumnsLabel(columns) else "设置",
            color = if (stitching) Color(0xFF0B1B2B) else ACCENT,
            fontSize = 10.sp
        )
    }
}

/** 边拍边拼：取景框下方的「取消 / 完成（N张）」操作行 */
@Composable
private fun StitchActionsRow(
    pendingCount: Int,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .clickable(enabled = !busy, onClick = onCancel)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("取消", color = Color(0xFF333333), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier
                .weight(1.4f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2196F3))
                .clickable(enabled = !busy && pendingCount > 0, onClick = onConfirm)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (pendingCount > 0) "完成（$pendingCount 张）" else "完成",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
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
            // 水印只显示到分钟，对齐到下一个整分钟刷新，避免每秒重组
            val delayMillis = 60_000L - (System.currentTimeMillis() % 60_000L)
            kotlinx.coroutines.delay(delayMillis)
        }
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    val weekFormat = remember { SimpleDateFormat("EEEE", Locale.CHINA) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateText = dateFormat.format(now)
    val weekText = weekFormat.format(now)
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
                        timeFormat.format(now),
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
                Spacer(Modifier.height(3.dp))
                Text(location, color = Color(0xE6FFFFFF), fontSize = 12.sp, maxLines = 2)
            }
            if (showNote && note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3FFD54F)) // 70% 不透明，柔和且深色文字仍清晰
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "备注：" + note,
                        color = Color(0xFF0B1B2B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
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
    onLocationMode: (LocationDisplayMode) -> Unit,
    onPickPlace: () -> Unit,
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
            // 显示方式：地址 / 坐标（地址优先，反解失败自动回退坐标）
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示为", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mode in LocationDisplayMode.entries) {
                        val selected = state.locationMode == mode
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) ACCENT else Color(0x33FFFFFF))
                                .clickable { onLocationMode(mode) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                mode.label,
                                color = if (selected) Color(0xFF0B1B2B) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            // 选择地点入口：面板框架已就绪，POI 搜索稍后接入地图 SDK
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = onPickPlace)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    tint = ACCENT,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("选择地点", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("地图选点 · 待接入", color = Color(0x99FFFFFF), fontSize = 10.sp)
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
    modifier: Modifier = Modifier,
    squared: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(if (squared) RoundedCornerShape(12.dp) else CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun ShutterButton(enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val ringColors = if (active) listOf(Color(0xFFB3D9FF), Color(0xFF64B5F6)) else listOf(Color.White, Color(0xFFE0E0E0))
    val innerColor = when {
        !enabled -> Color(0xFFAAAAAA)
        active -> Color(0xFF2196F3)
        else -> Color.White
    }
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(ringColors))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(innerColor))
    }
}

/** 边拍边拼顶部横幅：左侧标题与张数，右侧蓝色「退出」 */
@Composable
private fun StitchBanner(
    pendingCount: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "边拍边拼",
            color = Color(0xFF202020),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp)
        )
        Box(
            Modifier
                .size(width = 1.dp, height = 14.dp)
                .background(Color(0xFFDDDDDD))
        )
        Text(
            if (pendingCount > 0) "已拍 $pendingCount 张" else "连续拍照，合成一张",
            color = Color(0xFF666666),
            fontSize = 12.sp
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2196F3))
                .clickable(onClick = onExit)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("退出", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 边拍边拼未开拍时的中间引导 */
@Composable
private fun StitchHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.GridOn,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
        Text("连续拍照，合成一张", color = Color.White, fontSize = 14.sp)
    }
}

/**
 * 获取进程级单例 ProcessCameraProvider。
 * 用 CompletableDeferred 而非 suspendCancellableCoroutine：组合离开时 LaunchedEffect 被取消，
 * Deferred.await() 会抛 CancellationException（由调用侧吞掉），而 future 回调里的 complete()
 * 在 Deferred 已取消时安全返回 false，不会再恢复已离开 composition 的 continuation。
 * 注意：绝不 cancel 这个 future——它是进程级单例，取消会永久失效导致后续绑定黑屏。
 */
private suspend fun android.content.Context.getCameraProvider(): ProcessCameraProvider {
    val deferred = CompletableDeferred<ProcessCameraProvider>()
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(Runnable {
        try {
            deferred.complete(future.get())
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
        }
    }, ContextCompat.getMainExecutor(this))
    return deferred.await()
}
