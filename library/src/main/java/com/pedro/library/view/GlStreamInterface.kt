/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.library.view

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.common.newSingleThreadExecutor
import com.pedro.common.secureSubmit
import com.pedro.encoder.input.gl.FilterAction
import com.pedro.encoder.input.gl.SurfaceManager
import com.pedro.encoder.input.gl.render.MainRender
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.FpsLimiter
import com.pedro.encoder.utils.ViewPort
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.utils.gl.GlUtil
import com.pedro.library.util.Filter
import com.pedro.library.util.SensorRotationManager
import com.pedro.library.view.preview.MultiPreviewConfig
import com.pedro.library.view.preview.PreviewSurfaceInfo
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max


/**
 * Created by pedro on 14/3/22.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class GlStreamInterface(private val context: Context) : OnFrameAvailableListener, GlInterface {

    companion object {
        private const val TAG = "GlStreamInterface"
        private const val METRICS_LOG_INTERVAL_MS = 2000L
        private const val SLOW_DRAW_NS = 20_000_000L
        private const val SLOW_SWAP_NS = 20_000_000L
        private const val MAX_COALESCED_DRAINS_PER_RUN = 2
    }

    private var takePhotoCallback: TakePhotoCallback? = null

    private var photoWidth: Int = 0
    private var photoHeight: Int = 0
    private val running = AtomicBoolean(false)
    private val surfaceManager = SurfaceManager()
    private val surfaceManagerEncoder = SurfaceManager()
    private val surfaceManagerEncoderRecord = SurfaceManager()
    private val surfaceManagerPhoto = SurfaceManager()
    private val surfaceManagerPreview = SurfaceManager()
    private val multiPreviewSurfaceManagers = ConcurrentHashMap<Surface, PreviewSurfaceInfo>()
    private val mainRender = MainRender()

    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderRecordWidth = 0
    private var encoderRecordHeight = 0
    private var streamOrientation = 0
    private var previewWidth = 0
    private var previewHeight = 0
    private var isPortrait = false
    private var isPortraitPreview = false
    private var orientationForced = OrientationForced.NONE
    private val filterQueue: BlockingQueue<Filter> = LinkedBlockingQueue()
    private val threadQueue = LinkedBlockingQueue<Runnable>()
    private var muteVideo = false
    private var isPreviewHorizontalFlip: Boolean = false
    private var isPreviewVerticalFlip = false
    private var isStreamHorizontalFlip = false
    private var isStreamVerticalFlip = false
    private var aspectRatioMode = AspectRatioMode.Adjust
    private var executor: ExecutorService? = null
    private val fpsLimiter = FpsLimiter()
    private val forceRender = ForceRenderer()
    var autoHandleOrientation = false
    private val streamOnlyBackpressureEnabled = AtomicBoolean(false)
    private val renderJobRunning = AtomicBoolean(false)
    private val pendingFrame = AtomicBoolean(false)
    private val framesArrived = AtomicLong(0)
    private val renderJobsQueued = AtomicLong(0)
    private val framesCoalescedWhileBusy = AtomicLong(0)
    private val drawsSkippedByFpsLimiter = AtomicLong(0)
    private val drawCount = AtomicLong(0)
    private val slowDrawCount = AtomicLong(0)
    private val blockedSourceSwapCount = AtomicLong(0)
    private val blockedEncoderSwapCount = AtomicLong(0)
    private val blockedOtherSwapCount = AtomicLong(0)
    private val lastMetricsLogMs = AtomicLong(0)
    private val forcedRenderPending = AtomicBoolean(false)
    private var shouldHandleOrientation = true
    private var renderErrorCallback: RenderErrorCallback? = null
    private var previewViewPort: ViewPort? = null
    private var streamViewPort: ViewPort? = null

    private val sensorRotationManager =
        SensorRotationManager(context, true, true) { orientation, isPortrait ->
            if (autoHandleOrientation && shouldHandleOrientation) {
                setCameraOrientation(orientation)
                setIsPortrait(isPortrait)
            }
        }

    override fun setEncoderSize(width: Int, height: Int) {
        encoderWidth = width
        encoderHeight = height
    }

    override fun setEncoderRecordSize(width: Int, height: Int) {
        encoderRecordWidth = width
        encoderRecordHeight = height
    }

    override fun getEncoderSize(): Point {
        return Point(encoderWidth, encoderHeight)
    }

    override fun muteVideo() {
        muteVideo = true
    }

    override fun unMuteVideo() {
        muteVideo = false
    }

    override fun isVideoMuted(): Boolean = muteVideo

    override fun setForceRender(enabled: Boolean, fps: Int) {
        forceRender.setEnabled(enabled, fps)
    }

    override fun setForceRender(enabled: Boolean) {
        setForceRender(enabled, 5)
    }

    override fun isRunning(): Boolean = running.get()

    override fun setRenderErrorCallback(callback: RenderErrorCallback?) {
        this.renderErrorCallback = callback
    }

    override fun getSurfaceTexture(): SurfaceTexture {
        return mainRender.getSurfaceTexture()
    }

    override fun getSurface(): Surface {
        return mainRender.getSurface()
    }

    override fun addMediaCodecSurface(surface: Surface) {
        if (surfaceManager.isReady) {
            surfaceManagerEncoder.release()
            surfaceManagerEncoder.eglSetup(surface, surfaceManager)
        }
    }

    override fun removeMediaCodecSurface() {
        threadQueue.clear()
        surfaceManagerEncoder.release()
    }

    override fun addMediaCodecRecordSurface(surface: Surface) {
        if (surfaceManager.isReady) {
            surfaceManagerEncoderRecord.release()
            surfaceManagerEncoderRecord.eglSetup(surface, surfaceManager)
        }
    }

    override fun removeMediaCodecRecordSurface() {
        threadQueue.clear()
        surfaceManagerEncoderRecord.release()
    }

    override fun takePhoto(takePhotoCallback: TakePhotoCallback?) {
        this.takePhotoCallback = takePhotoCallback
        this.photoWidth = encoderWidth
        this.photoHeight = encoderHeight
    }

    override fun takePhoto(width: Int, height: Int, takePhotoCallback: TakePhotoCallback?) {
        this.takePhotoCallback = takePhotoCallback
        this.photoWidth = width
        this.photoHeight = height
    }

    override fun start() {
        threadQueue.clear()
        executor?.shutdownNow()
        executor = null
        executor = newSingleThreadExecutor(threadQueue)
        resetMetrics()
        renderJobRunning.set(false)
        pendingFrame.set(false)
        forcedRenderPending.set(false)
        val width = max(encoderWidth, encoderRecordWidth)
        val height = max(encoderHeight, encoderRecordHeight)
        surfaceManager.release()
        surfaceManager.eglSetup()
        surfaceManagerPhoto.release()
        surfaceManagerPhoto.eglSetup(width, height, surfaceManager)
        sensorRotationManager.start()
        executor?.secureSubmit {
            surfaceManager.makeCurrent()
            mainRender.initGl(context, width, height, width, height)
            running.set(true)
            mainRender.getSurfaceTexture().setOnFrameAvailableListener(this)
            forceRender.start {
                if (!streamOnlyBackpressureEnabled.get()) {
                    executor?.execute {
                        try {
                            draw(true)
                        } catch (e: RuntimeException) {
                            renderErrorCallback?.onRenderError(e) ?: throw e
                        }
                    }
                } else {
                    forcedRenderPending.set(true)
                    scheduleCoalescedRender()
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
        threadQueue.clear()
        executor?.shutdownNow()
        executor = null
        renderJobRunning.set(false)
        pendingFrame.set(false)
        forcedRenderPending.set(false)
        forceRender.stop()
        sensorRotationManager.stop()
        surfaceManagerPhoto.release()
        surfaceManagerEncoder.release()
        surfaceManagerEncoderRecord.release()
        multiPreviewSurfaceManagers.values.forEach { info ->
            info.surfaceManager.release()
        }
        multiPreviewSurfaceManagers.clear()
        surfaceManager.release()
        mainRender.release()
    }

    fun setStreamOnlyBackpressureEnabled(enabled: Boolean) {
        val changed = streamOnlyBackpressureEnabled.getAndSet(enabled) != enabled
        if (changed && enabled) resetMetrics()
        logBackpressureMode(enabled)
    }

    private fun resetMetrics() {
        framesArrived.set(0)
        renderJobsQueued.set(0)
        framesCoalescedWhileBusy.set(0)
        drawsSkippedByFpsLimiter.set(0)
        drawCount.set(0)
        slowDrawCount.set(0)
        blockedSourceSwapCount.set(0)
        blockedEncoderSwapCount.set(0)
        blockedOtherSwapCount.set(0)
        lastMetricsLogMs.set(0)
    }

    private fun logBackpressureMode(enabled: Boolean) {
        Log.i(
            TAG,
            "streamOnlyDiagnostic mode=${if (enabled) "backpressure_on" else "backpressure_off"} queue=${threadQueue.size}"
        )
    }

    private fun logRenderMetrics(
        event: String,
        drawNs: Long = -1L,
        sourceSwapNs: Long = -1L,
        encoderSwapNs: Long = -1L,
        otherSwapNs: Long = -1L
    ) {
        Log.i(
            TAG,
            "streamOnlyDiagnostic event=$event queue=${threadQueue.size} " +
                "arrived=${framesArrived.get()} jobs=${renderJobsQueued.get()} " +
                "coalescedWhileBusy=${framesCoalescedWhileBusy.get()} fpsLimitedDraws=${drawsSkippedByFpsLimiter.get()} " +
                "draws=${drawCount.get()} slowDraws=${slowDrawCount.get()} " +
                "blockedSource=${blockedSourceSwapCount.get()} blockedEncoder=${blockedEncoderSwapCount.get()} blockedOther=${blockedOtherSwapCount.get()} " +
                "drawMs=${if (drawNs >= 0) drawNs / 1_000_000.0 else -1.0} " +
                "sourceSwapMs=${if (sourceSwapNs >= 0) sourceSwapNs / 1_000_000.0 else -1.0} " +
                "encoderSwapMs=${if (encoderSwapNs >= 0) encoderSwapNs / 1_000_000.0 else -1.0} " +
                "otherSwapMs=${if (otherSwapNs >= 0) otherSwapNs / 1_000_000.0 else -1.0}"
        )
    }

    private fun maybeLogRenderMetrics(
        event: String,
        drawNs: Long = -1L,
        sourceSwapNs: Long = -1L,
        encoderSwapNs: Long = -1L,
        otherSwapNs: Long = -1L
    ) {
        if (!streamOnlyBackpressureEnabled.get()) return
        val now = SystemClock.elapsedRealtime()
        val shouldLog = event.contains("coalesced") ||
            event.startsWith("drop_") ||
            threadQueue.size > 0 ||
            drawNs >= SLOW_DRAW_NS ||
            sourceSwapNs >= SLOW_SWAP_NS ||
            encoderSwapNs >= SLOW_SWAP_NS ||
            otherSwapNs >= SLOW_SWAP_NS ||
            now - lastMetricsLogMs.get() >= METRICS_LOG_INTERVAL_MS
        if (!shouldLog) return
        lastMetricsLogMs.set(now)
        logRenderMetrics(event, drawNs, sourceSwapNs, encoderSwapNs, otherSwapNs)
    }

    private fun swapBufferMeasured(surfaceName: String, manager: SurfaceManager): Long {
        val startNs = System.nanoTime()
        manager.swapBuffer()
        val elapsedNs = System.nanoTime() - startNs
        if (elapsedNs >= SLOW_SWAP_NS) {
            when (surfaceName) {
                "source" -> blockedSourceSwapCount.incrementAndGet()
                "encoder" -> blockedEncoderSwapCount.incrementAndGet()
                else -> blockedOtherSwapCount.incrementAndGet()
            }
            Log.w(
                TAG,
                "streamOnlyDiagnostic slowSwap surface=$surfaceName ms=${elapsedNs / 1_000_000.0} queue=${threadQueue.size}"
            )
        }
        return elapsedNs
    }

    private fun scheduleCoalescedRender() {
        if (!renderJobRunning.compareAndSet(false, true)) return
        renderJobsQueued.incrementAndGet()
        val currentExecutor = executor
        if (currentExecutor == null) {
            renderJobRunning.set(false)
            return
        }
        try {
            currentExecutor.execute {
                runCoalescedRenderLoop()
            }
        } catch (_: RejectedExecutionException) {
            renderJobRunning.set(false)
        }
    }

    private fun runCoalescedRenderLoop() {
        try {
            var drains = 0
            while (isRunning && streamOnlyBackpressureEnabled.get() && drains < MAX_COALESCED_DRAINS_PER_RUN) {
                val hadPendingFrame = pendingFrame.getAndSet(false)
                val hadForcedRender = forcedRenderPending.getAndSet(false)
                if (!hadPendingFrame && !hadForcedRender) break
                // Real frames take precedence over forced renders. Forced renders are best-effort
                // wakeups in stream-only mode and can be absorbed when a real frame is already pending.
                if (hadPendingFrame) {
                    draw(false)
                } else {
                    draw(true)
                }
                drains++
            }
        } catch (e: RuntimeException) {
            renderErrorCallback?.onRenderError(e) ?: throw e
        } finally {
            renderJobRunning.set(false)
            if (isRunning && streamOnlyBackpressureEnabled.get() &&
                (pendingFrame.get() || forcedRenderPending.get())
            ) {
                scheduleCoalescedRender()
            }
        }
    }

    private fun draw(forced: Boolean) {
        if (!isRunning) return
        val drawStartNs = System.nanoTime()
        val limitFps = fpsLimiter.limitFPS()
        var sourceSwapNs = -1L
        var encoderSwapNs = -1L
        var otherSwapNs = -1L
        if (!forced) forceRender.frameAvailable()

        if (!filterQueue.isEmpty() && mainRender.isReady()) {
            try {
                if (surfaceManager.makeCurrent()) {
                    val filter = filterQueue.take()
                    mainRender.setFilterAction(
                        filter.filterAction,
                        filter.position,
                        filter.baseFilterRender
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        if (surfaceManager.isReady && mainRender.isReady()) {
            if (!surfaceManager.makeCurrent()) return
            mainRender.updateFrame()
            mainRender.drawSource()
            sourceSwapNs = swapBufferMeasured("source", surfaceManager)
        }

        val orientation = when (orientationForced) {
            OrientationForced.PORTRAIT -> true
            OrientationForced.LANDSCAPE -> false
            OrientationForced.NONE -> isPortrait
        }
        val orientationPreview = when (orientationForced) {
            OrientationForced.PORTRAIT -> true
            OrientationForced.LANDSCAPE -> false
            OrientationForced.NONE -> isPortraitPreview
        }
        if (surfaceManagerEncoder.isReady || surfaceManagerEncoderRecord.isReady || surfaceManagerPhoto.isReady) {
            mainRender.drawFilters(false)
        }
        // render VideoEncoder (stream and record)
        if (surfaceManagerEncoder.isReady && mainRender.isReady() && !limitFps) {
            val w = if (muteVideo) 0 else encoderWidth
            val h = if (muteVideo) 0 else encoderHeight
            if (surfaceManagerEncoder.makeCurrent()) {
                mainRender.drawScreenEncoder(
                    w, h, orientation, streamOrientation,
                    isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort
                )
                encoderSwapNs = swapBufferMeasured("encoder", surfaceManagerEncoder)
            }
        }
        // render VideoEncoder (record if the resolution is different than stream)
        if (surfaceManagerEncoderRecord.isReady && mainRender.isReady() && !limitFps) {
            val w = if (muteVideo) 0 else encoderRecordWidth
            val h = if (muteVideo) 0 else encoderRecordHeight
            if (surfaceManagerEncoderRecord.makeCurrent()) {
                mainRender.drawScreenEncoder(
                    w, h, orientation, streamOrientation,
                    isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort
                )
                otherSwapNs =
                    maxOf(otherSwapNs, swapBufferMeasured("encoderRecord", surfaceManagerEncoderRecord))
            }
        }
        //render surface photo if request photo
//        if (takePhotoCallback != null && surfaceManagerPhoto.isReady && mainRender.isReady()) {
//            if (surfaceManagerPhoto.makeCurrent()) {
//                mainRender.drawScreen(
//                    encoderWidth, encoderHeight, AspectRatioMode.NONE,
//                    streamOrientation, isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort
//                )
//                takePhotoCallback?.onTakePhoto(GlUtil.getBitmap(encoderWidth, encoderHeight))
//                takePhotoCallback = null
//                surfaceManagerPhoto.swapBuffer()
//            }
//        }


        //render surface photo if request photo
        if (takePhotoCallback != null && surfaceManagerPhoto.isReady && mainRender.isReady()) {
            if (surfaceManagerPhoto.makeCurrent()) {
                mainRender.drawScreen(
                    photoWidth, photoHeight, AspectRatioMode.NONE,
                    streamOrientation, isStreamVerticalFlip, isStreamHorizontalFlip, streamViewPort
                )
//                Log.d("GlStreamInterface", "takePhoto photoWidthxphotoHeight:  ${photoWidth}x${photoHeight}; " +
//                        "recordWidth: $encoderRecordWidth; encoderHeight: $encoderRecordHeight" +
//                        "; streamWidth: $encoderWidth; streamHeight: $encoderHeight;" +
//                        "")
                takePhotoCallback?.onTakePhoto(GlUtil.getBitmap(photoWidth, photoHeight))
                takePhotoCallback = null
                otherSwapNs = maxOf(otherSwapNs, swapBufferMeasured("photo", surfaceManagerPhoto))
            }
        }


        // render preview
        if (surfaceManagerPreview.isReady && mainRender.isReady() && !limitFps) {
            val w = if (previewWidth == 0) encoderWidth else previewWidth
            val h = if (previewHeight == 0) encoderHeight else previewHeight
            if (surfaceManager.makeCurrent()) {
                mainRender.drawFilters(true)
                otherSwapNs = maxOf(otherSwapNs, swapBufferMeasured("previewSource", surfaceManager))
            }
            if (surfaceManagerPreview.makeCurrent()) {
                mainRender.drawScreenPreview(
                    w, h, orientationPreview, aspectRatioMode, 0,
                    isPreviewVerticalFlip, isPreviewHorizontalFlip, previewViewPort
                )
                otherSwapNs = maxOf(otherSwapNs, swapBufferMeasured("preview", surfaceManagerPreview))
            }
        }
        // render extra multi-preview surfaces (using independent configuration from PreviewSurfaceInfo)
        if (multiPreviewSurfaceManagers.isNotEmpty() && mainRender.isReady() && !limitFps) {
            // Only draw filters if default preview is not active (to avoid double drawing)
            if (!surfaceManagerPreview.isReady) {
                if (surfaceManager.makeCurrent()) {
                    mainRender.drawFilters(true)
                    otherSwapNs = maxOf(otherSwapNs, swapBufferMeasured("multiPreviewSource", surfaceManager))
                }
            }
            val previewSnapshot = multiPreviewSurfaceManagers.values.toList()
            previewSnapshot.forEach { info ->
                if (info.surfaceManager.isReady) {
                    if (info.surfaceManager.makeCurrent()) {
                        // Each preview uses its own isPortrait and viewPort configuration
                        mainRender.drawScreenPreview(
                            info.config.width,
                            info.config.height,
                            info.config.isPortrait,
                            info.config.aspectRatioMode,
                            0,
                            info.config.verticalFlip,
                            info.config.horizontalFlip,
                            info.config.viewPort
                        )
                        otherSwapNs = maxOf(otherSwapNs, swapBufferMeasured("multiPreview", info.surfaceManager))
                    }
                }
            }
        }

        val drawNs = System.nanoTime() - drawStartNs
        drawCount.incrementAndGet()
        if (drawNs >= SLOW_DRAW_NS) slowDrawCount.incrementAndGet()
        if (limitFps) drawsSkippedByFpsLimiter.incrementAndGet()
        maybeLogRenderMetrics(
            event = if (forced) "forced_draw" else if (limitFps) "draw_fps_limited" else "draw",
            drawNs = drawNs,
            sourceSwapNs = sourceSwapNs,
            encoderSwapNs = encoderSwapNs,
            otherSwapNs = otherSwapNs
        )
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (!isRunning) return
        if (!streamOnlyBackpressureEnabled.get()) {
            executor?.execute {
                try {
                    draw(false)
                } catch (e: RuntimeException) {
                    renderErrorCallback?.onRenderError(e) ?: throw e
                }
            }
            return
        }
        framesArrived.incrementAndGet()
        pendingFrame.set(true)
        if (renderJobRunning.get()) {
            framesCoalescedWhileBusy.incrementAndGet()
            maybeLogRenderMetrics("frame_coalesced_while_busy")
            return
        }
        scheduleCoalescedRender()
    }

    fun setOrientationConfig(orientationConfig: OrientationConfig) {
        when (orientationConfig.forced) {
            OrientationForced.PORTRAIT, OrientationForced.LANDSCAPE -> {
                forceOrientation(orientationConfig.forced)
            }

            OrientationForced.NONE -> {
                if (orientationConfig.isPortrait == null && orientationConfig.cameraOrientation == null) {
                    forceOrientation(orientationConfig.forced)
                } else {
                    orientationConfig.isPortrait?.let { setIsPortrait(it) }
                    orientationConfig.cameraOrientation?.let { setCameraOrientation(it) }
                    shouldHandleOrientation = false
                    this.orientationForced = orientationConfig.forced
                }
            }
        }
    }

    fun forceOrientation(forced: OrientationForced) {
        when (forced) {
            OrientationForced.PORTRAIT -> {
                setCameraOrientation(90)
                shouldHandleOrientation = false
            }

            OrientationForced.LANDSCAPE -> {
                setCameraOrientation(0)
                shouldHandleOrientation = false
            }

            OrientationForced.NONE -> {
                val orientation = CameraHelper.getCameraOrientation(context)
                setCameraOrientation(if (orientation == 0) 270 else orientation - 90)
                shouldHandleOrientation = true
            }
        }
        this.orientationForced = forced
    }

    fun attachPreview(surface: Surface) {
        if (surfaceManager.isReady) {
            surfaceManagerPreview.release()
            surfaceManagerPreview.eglSetup(surface, surfaceManager)
        }
    }

    fun deAttachPreview() {
        surfaceManagerPreview.release()
    }

    /**
     * Add a multi-preview surface
     * @param surface the surface to add
     * @param config configuration for the preview surface
     */
    fun addMultiPreviewSurface(surface: Surface, config: MultiPreviewConfig) {
        if (surfaceManager.isReady) {
            multiPreviewSurfaceManagers.remove(surface)?.surfaceManager?.release()

            val w =
                if (config.width > 0) config.width else if (previewWidth == 0) encoderWidth else previewWidth
            val h =
                if (config.height > 0) config.height else if (previewHeight == 0) encoderHeight else previewHeight

            val surfaceManager = SurfaceManager()
            surfaceManager.eglSetup(surface, this@GlStreamInterface.surfaceManager)
            val finalConfig = MultiPreviewConfig(
                w,
                h,
                config.horizontalFlip,
                config.verticalFlip,
                config.aspectRatioMode,
                config.isPortrait,
                config.viewPort
            )
            multiPreviewSurfaceManagers[surface] = PreviewSurfaceInfo(surfaceManager, finalConfig)
        }
    }

    fun removeMultiPreviewSurface(surface: Surface) {
        multiPreviewSurfaceManagers.remove(surface)?.surfaceManager?.release()
    }

    fun removeAllMultiPreviewSurfaces() {
        multiPreviewSurfaceManagers.values.forEach { info ->
            info.surfaceManager.release()
        }
        multiPreviewSurfaceManagers.clear()
    }

    fun updateMultiPreviewConfig(surface: Surface, config: MultiPreviewConfig): Boolean {
        val info = multiPreviewSurfaceManagers[surface] ?: return false

        info.config.width =
            if (config.width > 0) config.width else if (previewWidth == 0) encoderWidth else previewWidth
        info.config.height =
            if (config.height > 0) config.height else if (previewHeight == 0) encoderHeight else previewHeight
        info.config.horizontalFlip = config.horizontalFlip
        info.config.verticalFlip = config.verticalFlip
        info.config.aspectRatioMode = config.aspectRatioMode
        info.config.isPortrait = config.isPortrait
        info.config.viewPort = config.viewPort

        return true
    }

    fun hasMultiPreviewSurface(surface: Surface): Boolean {
        return multiPreviewSurfaceManagers.containsKey(surface)
    }

    fun getMultiPreviewSurfaceCount(): Int = multiPreviewSurfaceManagers.size

    override fun setStreamRotation(orientation: Int) {
        this.streamOrientation = orientation
    }

    fun setPreviewResolution(width: Int, height: Int) {
        this.previewWidth = width
        this.previewHeight = height
    }

    fun setIsPortrait(isPortrait: Boolean) {
        setPreviewIsPortrait(isPortrait)
        setStreamIsPortrait(isPortrait)
    }

    fun setPreviewIsPortrait(isPortrait: Boolean) {
        this.isPortraitPreview = isPortrait
    }

    fun setStreamIsPortrait(isPortrait: Boolean) {
        this.isPortrait = isPortrait
    }

    fun setCameraOrientation(orientation: Int) {
        mainRender.setCameraRotation(orientation)
    }

    override fun setFilter(filterPosition: Int, baseFilterRender: BaseFilterRender) {
        filterQueue.add(Filter(FilterAction.SET_INDEX, filterPosition, baseFilterRender))
    }

    override fun addFilter(baseFilterRender: BaseFilterRender) {
        filterQueue.add(Filter(FilterAction.ADD, 0, baseFilterRender))
    }

    override fun addFilter(filterPosition: Int, baseFilterRender: BaseFilterRender) {
        filterQueue.add(Filter(FilterAction.ADD_INDEX, filterPosition, baseFilterRender))
    }

    override fun clearFilters() {
        filterQueue.add(Filter(FilterAction.CLEAR, 0, NoFilterRender()))
    }

    override fun removeFilter(filterPosition: Int) {
        filterQueue.add(Filter(FilterAction.REMOVE_INDEX, filterPosition, NoFilterRender()))
    }

    override fun removeFilter(baseFilterRender: BaseFilterRender) {
        filterQueue.add(Filter(FilterAction.REMOVE, 0, baseFilterRender))
    }

    override fun filtersCount(): Int {
        return mainRender.filtersCount()
    }

    override fun setRotation(rotation: Int) {
        setCameraOrientation(rotation)
    }

    override fun forceFpsLimit(fps: Int) {
        fpsLimiter.setFPS(fps)
    }

    override fun setIsStreamHorizontalFlip(flip: Boolean) {
        isStreamHorizontalFlip = flip
    }

    override fun setIsStreamVerticalFlip(flip: Boolean) {
        isStreamVerticalFlip = flip
    }

    override fun setIsPreviewHorizontalFlip(flip: Boolean) {
        isPreviewHorizontalFlip = flip
    }

    override fun setIsPreviewVerticalFlip(flip: Boolean) {
        isPreviewVerticalFlip = flip
    }

    override fun setFilter(baseFilterRender: BaseFilterRender) {
        filterQueue.add(Filter(FilterAction.SET, 0, baseFilterRender))
    }

    fun setAspectRatioMode(aspectRatioMode: AspectRatioMode) {
        this.aspectRatioMode = aspectRatioMode
    }

    fun setPreviewViewPort(viewPort: ViewPort?) {
        previewViewPort = viewPort
    }

    fun setStreamViewPort(viewPort: ViewPort?) {
        streamViewPort = viewPort
    }
}
