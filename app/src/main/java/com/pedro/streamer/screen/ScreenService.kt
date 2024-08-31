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

package com.pedro.streamer.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.EncoderErrorCallback
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.sources.audio.AudioSource
import com.pedro.library.util.sources.audio.InternalAudioSource
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.NoVideoSource
import com.pedro.library.util.sources.video.ScreenSource
import com.pedro.streamer.R
import com.pedro.streamer.utils.toast


/**
 * Basic Screen service streaming implementation
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: Service(), ConnectChecker {

  companion object {
    private const val TAG = "DisplayService"
    private const val channelId = "rtpDisplayStreamChannel"
    const val notifyId = 123456
    var INSTANCE: ScreenService? = null
  }

  private var notificationManager: NotificationManager? = null
  private lateinit var genericStream: GenericStream
  private var mediaProjection: MediaProjection? = null
  private val mediaProjectionManager: MediaProjectionManager by lazy {
    applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  }
  private var callback: ConnectChecker? = null
  private val width = 640
  private val height = 480
  private val vBitrate = 1200 * 1000
  private var rotation = 0 //0 for landscape or 90 for portrait
  private val sampleRate = 32000
  private val isStereo = true
  private val aBitrate = 128 * 1000
  private var prepared = false

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "RTP Display service create")
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
    }
    genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource()).apply {
      //This is important to keep a constant fps because media projection only produce fps if the screen change
      getGlInterface().setForceRender(true, 15)
    }
    prepared = try {
      genericStream.prepareVideo(width, height, vBitrate, rotation = rotation) &&
          genericStream.prepareAudio(sampleRate, isStereo, aBitrate)
    } catch (e: IllegalArgumentException) {
      false
    }
    if (prepared) INSTANCE = this
    else toast("Invalid audio or video parameters, prepare failed")
  }

  private fun keepAliveTrick() {
    val notification = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.notification_icon)
      .setSilent(true)
      .setOngoing(false)
      .build()
    startForeground(1, notification)
  }

  override fun onBind(p0: Intent?): IBinder? {
    return null
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "RTP Display service started")
    return START_STICKY
  }

  fun sendIntent(): Intent {
    return mediaProjectionManager.createScreenCaptureIntent()
  }

  fun isStreaming(): Boolean {
    return genericStream.isStreaming
  }

  fun isRecording(): Boolean {
    return genericStream.isRecording
  }

  fun stopStream() {
    if (genericStream.isStreaming) {
      genericStream.stopStream()
      notificationManager?.cancel(notifyId)
    }
  }

  fun setCallback(connectChecker: ConnectChecker?) {
    callback = connectChecker
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "RTP Display service destroy")
    stopStream()
    INSTANCE = null
    //release stream and media projection properly
    genericStream.release()
    mediaProjection?.stop()
    mediaProjection = null
  }

  fun prepareStream(resultCode: Int, data: Intent): Boolean {
    keepAliveTrick()
    stopStream()
    mediaProjection?.stop()
    val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    this.mediaProjection = mediaProjection
    val screenSource = ScreenSource(applicationContext, mediaProjection)
    return try {
      //ScreenSource need use always setCameraOrientation(0) because the MediaProjection handle orientation.
      //You also need remove autoHandleOrientation if you are using it.
      //You need to call it after prepareVideo to override the default value.
      genericStream.getGlInterface().setCameraOrientation(0)
      genericStream.changeVideoSource(screenSource)
      true
    } catch (ignored: IllegalArgumentException) {
      false
    }
  }

  fun toggleAudioSource(): AudioSource {
      if (genericStream.audioSource is InternalAudioSource) {
        genericStream.changeAudioSource(MicrophoneSource())
      } else {
        mediaProjection?.let {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            genericStream.changeAudioSource(InternalAudioSource(it))
          }
        }
      }
    return genericStream.audioSource
  }

  fun startStream(endpoint: String) {
    if (!genericStream.isStreaming) genericStream.startStream(endpoint)
  }

  override fun onConnectionStarted(url: String) {
    callback?.onConnectionStarted(url)
  }

  override fun onConnectionSuccess() {
    callback?.onConnectionSuccess()
  }

  override fun onNewBitrate(bitrate: Long) {
    callback?.onNewBitrate(bitrate)
  }

  override fun onConnectionFailed(reason: String) {
    callback?.onConnectionFailed(reason)
  }

  override fun onDisconnect() {
    callback?.onDisconnect()
  }

  override fun onAuthError() {
    callback?.onAuthError()
  }

  override fun onAuthSuccess() {
    callback?.onAuthSuccess()
  }
}