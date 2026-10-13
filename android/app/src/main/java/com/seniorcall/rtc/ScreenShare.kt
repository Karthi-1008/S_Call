package com.seniorcall.rtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import org.webrtc.*

class ScreenShare(
    private val context: Context,
    private val rtcEngine: RtcEngine,
    private val callback: Callback
) {
    interface Callback {
        fun onScreenShareStarted(track: VideoTrack)
        fun onScreenShareStopped()
    }

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null

    fun start(resultCode: Int, resultData: Intent) {
        val mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }

        screenCapturer = ScreenCapturerAndroid(resultData, mediaProjectionCallback)
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", rtcEngine.eglBase.eglBaseContext)
        videoSource = rtcEngine.createVideoSource(isScreencast = true)

        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

        // 720p @ 15 fps per §9.2 S5
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        val width = 720
        val height = 1280
        val fps = 15

        screenCapturer?.startCapture(width, height, fps)

        // Track ID prefixed with screen- (§10 #4)
        val trackId = "screen-${System.currentTimeMillis()}"
        screenVideoTrack = rtcEngine.peerConnectionFactory.createVideoTrack(trackId, videoSource).apply {
            setEnabled(true)
        }

        callback.onScreenShareStarted(screenVideoTrack!!)
    }

    fun stop() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            screenVideoTrack?.dispose()
            screenVideoTrack = null

            videoSource?.dispose()
            videoSource = null

            callback.onScreenShareStopped()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
