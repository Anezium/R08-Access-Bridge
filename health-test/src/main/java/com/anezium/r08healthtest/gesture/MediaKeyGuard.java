package com.anezium.r08healthtest.gesture;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.view.KeyEvent;

import java.util.List;

final class MediaKeyGuard {
    private static final int SAMPLE_RATE = 8000;
    private static final int FRAMES = 480;

    private final Context context;
    private final Handler handler;
    private AudioManager audioManager;
    private PowerManager powerManager;
    private MediaSession session;
    private AudioTrack silence;
    private boolean started;

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> configs) {
                    if (audioManager != null && audioManager.isMusicActive()) releaseClaim();
                    else if (!isInteractive()) claim();
                }
            };

    MediaKeyGuard(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    void start() {
        if (started) return;
        started = true;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        session = new MediaSession(context, "R08HealthMediaGuard");
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = keyEvent(intent);
                if (event == null || !GestureBridge.isR08(event.getDevice())) {
                    return super.onMediaButtonEvent(intent);
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    wakeScreen();
                    GestureBridge.get(context).handle(event, "media-session");
                }
                return true;
            }
        }, handler);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f).build());
        if (audioManager != null) audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        if (!isInteractive()) claim();
    }

    void stop() {
        started = false;
        releaseClaim();
        if (audioManager != null) audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        audioManager = null;
        if (session != null) session.release();
        session = null;
    }

    void onScreenOff() { if (started) claim(); }
    void onScreenOn() { releaseClaim(); }

    private void claim() {
        if (!started || session == null || audioManager == null || audioManager.isMusicActive()) return;
        session.setActive(true);
        int bytes = FRAMES * 2;
        try {
            silence = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(bytes).build();
            silence.write(new byte[bytes], 0, bytes);
            silence.setVolume(0f);
            silence.play();
            handler.postDelayed(this::releaseSilence, 400L);
        } catch (RuntimeException ignored) {
            releaseSilence();
        }
    }

    private void releaseClaim() {
        releaseSilence();
        if (session != null) session.setActive(false);
    }

    private void releaseSilence() {
        if (silence == null) return;
        try { silence.release(); } catch (RuntimeException ignored) {}
        silence = null;
    }

    private boolean isInteractive() { return powerManager == null || powerManager.isInteractive(); }

    @SuppressWarnings("deprecation")
    private KeyEvent keyEvent(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        if (powerManager == null || powerManager.isInteractive()) return;
        PowerManager.WakeLock lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "r08health:gesture-wake");
        lock.acquire(2_000L);
    }
}
