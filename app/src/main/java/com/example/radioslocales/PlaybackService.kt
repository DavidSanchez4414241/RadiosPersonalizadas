package com.example.radioslocales

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SET_TIMER = "com.example.radioslocales.SET_TIMER"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_TIMER_END = "timer_end"
    }

    private var mediaSession: MediaSession? = null

    // ---- Temporizador de apagado ----
    private val handler = Handler(Looper.getMainLooper())
    private var timerEndMillis = 0L // 0 = sin temporizador

    private val timerFinished = Runnable {
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        setTimer(0)
    }

    /** minutes > 0 inicia (o reinicia) el temporizador; 0 lo cancela. */
    private fun setTimer(minutes: Int) {
        if (minutes <= 0 && timerEndMillis == 0L) return
        handler.removeCallbacks(timerFinished)
        timerEndMillis = if (minutes > 0) {
            handler.postDelayed(timerFinished, minutes * 60_000L)
            System.currentTimeMillis() + minutes * 60_000L
        } else {
            0L
        }
        // La app lee este valor para mostrar la cuenta atrás
        mediaSession?.setSessionExtras(Bundle().apply { putLong(EXTRA_TIMER_END, timerEndMillis) })
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_SET_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SET_TIMER) {
                setTimer(args.getInt(EXTRA_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Muchas URLs de radio redirigen de http a https (o al revés): lo permitimos
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)      // pausa si desconectas los auriculares
            .setWakeMode(C.WAKE_MODE_NETWORK)       // mantiene la conexión con la pantalla apagada
            .build()

        // De la última emisora se pasa a la primera y al revés
        player.repeatMode = Player.REPEAT_MODE_ALL

        // Si se detiene la radio y no queda nada en la lista, se cancela el temporizador
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (timeline.isEmpty) setTimer(0)
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(openApp)            // al tocar la notificación se abre la app
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    // Si cierras la app deslizándola y no está sonando nada, el servicio se detiene
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerFinished)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}