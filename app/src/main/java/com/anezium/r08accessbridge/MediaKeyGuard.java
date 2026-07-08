package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Keeps ring media keys from reaching a paired phone while the display is off.
 *
 * The R08 emits HID consumer-control keys (KEYCODE_MEDIA_PLAY_PAUSE for taps,
 * MEDIA_PREVIOUS/NEXT for swipes). While the display is on, the accessibility
 * key filter consumes them before any app sees them. While the display is off,
 * PhoneWindowManager intercepts media keys before the accessibility filter and
 * dispatches them straight to the current media button session. On glasses that
 * are paired to a phone as a Bluetooth audio sink, that session is the AVRCP
 * controller, so a ring tap on a dark screen can become a phone-side PLAY.
 *
 * MediaSessionService routes media buttons to the session of the app that most
 * recently played audio. This guard claims that slot by playing a short burst
 * of inaudible silence, consumes ring media keys in its session callback, and
 * turns them into a display wake.
 *
 * The claim is scoped to the moment the keys mean "wake, not media": it is
 * taken when the screen turns off and released when the screen turns on, and it
 * backs off while other audio is actually playing so play/pause during real
 * playback keeps controlling that playback.
 */
final class MediaKeyGuard {
    interface WakeDelegate {
        boolean wakeForRingInput(String source);
    }

    private static final String TAG = "R08MediaGuard";

    private static final int SILENCE_SAMPLE_RATE = 8000;
    private static final int SILENCE_FRAMES = 480; // 60 ms of PCM silence.
    private static final long SILENCE_RELEASE_MS = 400L;
    private static final long SELF_PLAYBACK_SETTLE_MS = 1500L;
    private static final long RECLAIM_DELAY_MS = 500L;

    private final Context context;
    private final Handler handler;
    private final WakeDelegate wakeDelegate;

    private AudioManager audioManager;
    private PowerManager powerManager;
    private MediaSession session;
    private AudioTrack silence;
    private long lastClaimAt;
    private boolean started;

    private final Runnable reclaimCheck = () -> claim("playback_changed", false);
    private final Runnable releaseSilenceRunnable = this::releaseSilence;

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    handler.removeCallbacks(reclaimCheck);
                    if (isExternalMusicActive(configs)) {
                        releaseClaim("music_active");
                    }
                    handler.postDelayed(reclaimCheck, RECLAIM_DELAY_MS);
                }
            };

    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            KeyEvent event = keyEventFrom(mediaButtonIntent);
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                boolean woke = wakeDelegate.wakeForRingInput(
                        "mediaButton:" + event.getKeyCode());
                Log.d(TAG, "Consumed media key code=" + event.getKeyCode()
                        + " ring=" + isRingDevice(event.getDevice())
                        + " woke=" + woke);
            }
            return true;
        }
    };

    MediaKeyGuard(Context context, Handler handler, WakeDelegate wakeDelegate) {
        this.context = context;
        this.handler = handler;
        this.wakeDelegate = wakeDelegate;
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(context, "R08BridgeMediaKeyGuard");
        session.setCallback(sessionCallback, handler);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f)
                .build());
        if (audioManager != null) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        }
        if (!isInteractive()) {
            claim("start", true);
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        handler.removeCallbacks(reclaimCheck);
        handler.removeCallbacks(releaseSilenceRunnable);
        releaseSilence();
        if (audioManager != null) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
            audioManager = null;
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
    }

    void onScreenOff() {
        if (started) {
            claim("screen_off", true);
        }
    }

    void onScreenOn() {
        if (started) {
            releaseClaim("screen_on");
        }
    }

    private void claim(String reason, boolean force) {
        if (!started || audioManager == null || session == null) {
            return;
        }
        if (!force) {
            long sinceOwnClip = SystemClock.uptimeMillis() - lastClaimAt;
            if (silence != null
                    || (lastClaimAt != 0L && sinceOwnClip < SELF_PLAYBACK_SETTLE_MS)) {
                return;
            }
            if (isInteractive()) {
                return;
            }
        }
        if (audioManager.isMusicActive()) {
            Log.d(TAG, "Claim skipped, music active reason=" + reason);
            return;
        }
        try {
            session.setActive(true);
            playSilence();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not claim media button session reason=" + reason, exception);
            return;
        }
        lastClaimAt = SystemClock.uptimeMillis();
        Log.d(TAG, "Claimed media button session reason=" + reason);
    }

    private void releaseClaim(String reason) {
        if (session == null) {
            return;
        }
        if ("screen_on".equals(reason)) {
            handler.removeCallbacks(reclaimCheck);
        }
        releaseSilence();
        if (session.isActive()) {
            session.setActive(false);
            Log.d(TAG, "Released media button session reason=" + reason);
        }
    }

    private void playSilence() {
        releaseSilence();
        int bytes = SILENCE_FRAMES * 2;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        track.write(new byte[bytes], 0, bytes);
        track.setVolume(0f);
        track.play();
        silence = track;
        handler.removeCallbacks(releaseSilenceRunnable);
        handler.postDelayed(releaseSilenceRunnable, SILENCE_RELEASE_MS);
    }

    private void releaseSilence() {
        if (silence != null) {
            try {
                silence.release();
            } catch (RuntimeException ignored) {
                // Already released.
            }
            silence = null;
        }
    }

    private boolean isExternalMusicActive(List<AudioPlaybackConfiguration> configs) {
        if (!started || audioManager == null || session == null || !session.isActive()
                || !audioManager.isMusicActive()) {
            return false;
        }
        if (silence == null) {
            return true;
        }
        return mediaConfigCount(configs) > 1;
    }

    private int mediaConfigCount(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) {
            return 0;
        }
        int count = 0;
        for (AudioPlaybackConfiguration config : configs) {
            if (isMediaPlayback(config)) {
                count++;
            }
        }
        return count;
    }

    private boolean isMediaPlayback(AudioPlaybackConfiguration config) {
        AudioAttributes attributes = config.getAudioAttributes();
        if (attributes == null) {
            return false;
        }
        int usage = attributes.getUsage();
        return usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME;
    }

    private boolean isInteractive() {
        if (powerManager == null) {
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        }
        return powerManager == null || powerManager.isInteractive();
    }

    private KeyEvent keyEventFrom(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }
        @SuppressWarnings("deprecation")
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        return event;
    }

    private static boolean isRingDevice(InputDevice device) {
        if (device == null) {
            return false;
        }
        String name = device.getName();
        return name != null && name.toUpperCase(Locale.US).contains("R08");
    }
}
