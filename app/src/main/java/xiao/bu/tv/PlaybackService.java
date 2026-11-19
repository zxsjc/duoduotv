package xiao.bu.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Foreground service that keeps TV playback alive on the lock screen. It holds a
 *  lock-screen MediaSession and a MediaStyle notification, and exposes an off-screen
 *  Surface (consumed by an EGL thread) so the decoder keeps producing frames and audio
 *  keeps playing while the window surface is gone. */
public final class PlaybackService extends Service {
    public interface Controller {
        void onTogglePlay();
        void onPrevious();
        void onNext();
    }
    public static final String ACTION_TOGGLE = "xiao.bu.tv.playback.toggle";
    public static final String ACTION_PREVIOUS = "xiao.bu.tv.playback.previous";
    public static final String ACTION_NEXT = "xiao.bu.tv.playback.next";
    private static final String CHANNEL_ID = "nTv playback";
    private static final int NOTIFICATION_ID = 1;

    private static volatile Controller controller;
    private static volatile PlaybackService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    /** Current playback state reflected in the notification icon and the media
     *  session, kept in sync by MainActivity through {@link #updateState}. */
    private volatile boolean playing = true;
    /** Keeps the CPU running while the screen is off so decoding and audio
     *  output are not frozen during lock-screen/background playback. */
    private PowerManager.WakeLock wakeLock;

    private volatile Surface offscreenSurface;
    private HandlerThread offscreenThread;
    private volatile boolean offscreenClosed;

    public static void setController(Controller value) { controller = value; }

    /** Off-screen decoder output; null until the EGL consumer is ready (or unavailable). */
    public static Surface offscreenSurface() {
        PlaybackService service = instance;
        return service == null ? null : service.offscreenSurface;
    }

    /** Reflects play/pause in the media session and the notification so the
     *  notification button and lock-screen controls no longer stay stuck on
     *  "playing". Safe to call from any thread; no-op when the service is down. */
    public static void updateState(final boolean isPlaying) {
        PlaybackService service = instance;
        if (service == null) return;
        service.main.post(new Runnable() {
            @Override public void run() { service.setPlaying(isPlaying); }
        });
    }

    private void setPlaying(boolean isPlaying) {
        if (session == null || playing == isPlaying) return;
        playing = isPlaying;
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, isPlaying ? 1f : 0f)
                .build());
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void dispatch(final String action) {
        final Controller c = controller;
        android.util.Log.i("PlaybackService", "dispatch action=" + action
                + " controller=" + (c != null));
        if (c == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (action == null) return;
                if (ACTION_TOGGLE.equals(action)) c.onTogglePlay();
                else if (ACTION_PREVIOUS.equals(action)) c.onPrevious();
                else if (ACTION_NEXT.equals(action)) c.onNext();
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        acquireWakeLock();
        startOffscreen();
        session = new MediaSession(this, "nTv background");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatch(ACTION_TOGGLE); }
            @Override public void onPause() { dispatch(ACTION_TOGGLE); }
            @Override public void onSkipToPrevious() { dispatch(ACTION_PREVIOUS); }
            @Override public void onSkipToNext() { dispatch(ACTION_NEXT); }
        }, main);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        session.setActive(true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            android.util.Log.i("PlaybackService", "onStartCommand action=" + action
                    + " flags=" + flags + " startId=" + startId);
            if (action != null) dispatch(action);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        offscreenClosed = true;
        if (offscreenThread != null) {
            offscreenThread.quitSafely();
            offscreenThread = null;
        }
        offscreenSurface = null;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (instance == this) instance = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "后台播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return;
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nTv:background");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (RuntimeException error) {
            wakeLock = null;
            android.util.Log.w("PlaybackService", "Unable to acquire background wake lock", error);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable ignored) { }
        wakeLock = null;
    }

    private Notification buildNotification() {
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL_ID)
                : new android.app.Notification.Builder(this);
        builder.setSmallIcon(playing ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play)
                .setContentTitle(playing ? "朵朵电视 正在播放" : "朵朵电视 已暂停")
                .setContentText("锁屏后台播放中")
                .setOngoing(true)
                .setContentIntent(pi(ACTION_TOGGLE));
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setStyle(new android.app.Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(session.getSessionToken()));
            builder.addAction(new Notification.Action(android.R.drawable.ic_media_previous,
                    "上一台", pi(ACTION_PREVIOUS)));
            builder.addAction(new Notification.Action(playing ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play,
                    "播放/暂停", pi(ACTION_TOGGLE)));
            builder.addAction(new Notification.Action(android.R.drawable.ic_media_next,
                    "下一台", pi(ACTION_NEXT)));
        }
        return builder.build();
    }

    private PendingIntent pi(String action) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 0, intent, flags);
    }

    /** Builds an off-screen SurfaceTexture consumed by an EGL thread so decoding
     *  keeps running without a visible window. Blocks the caller briefly for readiness. */
    private void startOffscreen() {
        if (Build.VERSION.SDK_INT < 17) return; // EGL14 requires API 17+.
        offscreenThread = new HandlerThread("nTv offscreen");
        offscreenThread.start();
        final CountDownLatch ready = new CountDownLatch(1);
        new Handler(offscreenThread.getLooper()).post(new Runnable() {
            @Override public void run() {
                try { initGl(); }
                catch (Throwable error) {
                    android.util.Log.w("PlaybackService", "offscreen consumer unavailable", error);
                    releaseGl();
                } finally { ready.countDown(); }
                consume();
            }
        });
        try { ready.await(1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { }
    }

    private android.opengl.EGLDisplay eglDisplay;
    private android.opengl.EGLContext eglContext;
    private android.opengl.EGLSurface eglSurface;
    private int textureId;
    private SurfaceTexture texture;

    private void initGl() {
        eglDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!android.opengl.EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            throw new IllegalStateException("eglInitialize");
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] count = new int[1];
        android.opengl.EGL14.eglChooseConfig(eglDisplay, new int[]{
                        android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                        android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                        android.opengl.EGL14.EGL_NONE},
                0, configs, 0, 1, count, 0);
        if (count[0] == 0) throw new IllegalStateException("eglChooseConfig");
        eglContext = android.opengl.EGL14.eglCreateContext(eglDisplay, configs[0],
                android.opengl.EGL14.EGL_NO_CONTEXT,
                new int[]{android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        android.opengl.EGL14.EGL_NONE}, 0);
        eglSurface = android.opengl.EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                new int[]{android.opengl.EGL14.EGL_WIDTH, 1,
                        android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE}, 0);
        if (eglContext == android.opengl.EGL14.EGL_NO_CONTEXT
                || eglSurface == android.opengl.EGL14.EGL_NO_SURFACE
                || !android.opengl.EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw new IllegalStateException("eglMakeCurrent");
        int[] textures = new int[1];
        android.opengl.GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(1920, 1080);
        offscreenSurface = new Surface(texture);
    }

    private void consume() {
        if (offscreenSurface == null) return;
        while (!offscreenClosed) {
            try {
                texture.updateTexImage();
                Thread.sleep(10);
            } catch (Throwable error) {
                android.util.Log.w("PlaybackService", "offscreen consumer stopped", error);
                break;
            }
        }
        releaseGl();
    }

    private void releaseGl() {
        if (texture != null) { try { texture.release(); } catch (Throwable ignored) { } texture = null; }
        if (offscreenSurface != null) { try { offscreenSurface.release(); } catch (Throwable ignored) { } offscreenSurface = null; }
        if (eglDisplay != android.opengl.EGL14.EGL_NO_DISPLAY) {
            try {
                android.opengl.EGL14.eglMakeCurrent(eglDisplay, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                if (eglSurface != android.opengl.EGL14.EGL_NO_SURFACE)
                    android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != android.opengl.EGL14.EGL_NO_CONTEXT)
                    android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext);
                android.opengl.EGL14.eglTerminate(eglDisplay);
            } catch (Throwable ignored) { }
            eglDisplay = android.opengl.EGL14.EGL_NO_DISPLAY;
            eglSurface = android.opengl.EGL14.EGL_NO_SURFACE;
            eglContext = android.opengl.EGL14.EGL_NO_CONTEXT;
        }
        textureId = 0;
    }
}
