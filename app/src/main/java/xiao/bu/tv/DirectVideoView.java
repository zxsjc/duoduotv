package xiao.bu.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.FrameLayout;

/** Video output that renders through either a SurfaceView (legacy/box compatibility)
 *  or a TextureView (texture rendering so UI overlays stay visible above the video).
 *  Render mode is auto by default: texture on modern devices, SurfaceView on legacy
 *  boxes that need PUSH_BUFFERS. The output Surface is published through the same
 *  SurfaceCallback so the playback binding in MainActivity is unchanged. */
public final class DirectVideoView extends FrameLayout
        implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {
    public static final String RENDER_AUTO = "auto";
    public static final String RENDER_TEXTURE = "texture";
    public static final String RENDER_SURFACE = "surface";

    private final SurfaceView surfaceView;
    private final TextureView textureView;
    private android.view.View frameCover;
    private SurfaceCallback callback;
    private SurfaceHolder activeHolder;
    private SurfaceTexture activeTexture;
    private Surface activeTextureSurface;
    private boolean textureActive;
    private String renderMode = RENDER_AUTO;
    private float aspectRatio;
    void setAspectRatio(float value){if(aspectRatio!=value){aspectRatio=value;requestLayout();}}
    private int videoWidth, videoHeight, sarNum = 1, sarDen = 1;
    private boolean stretchVideo, fillVideo, legacySurfaceMode, viewportBufferMode, appliedViewportBufferMode;

    public DirectVideoView(Context context) { this(context, null); }

    public DirectVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        surfaceView = new SurfaceView(context);
        surfaceView.getHolder().setFormat(PixelFormat.OPAQUE);
        applySurfaceType();
        surfaceView.getHolder().addCallback(this);
        addView(surfaceView, new LayoutParams(-1, -1));
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(this);
        textureView.setVisibility(GONE);
        addView(textureView, new LayoutParams(-1, -1));
        applyRenderMode();
        setKeepScreenOn(true);
    }

    SurfaceView getSurfaceView() { return surfaceView; }
    TextureView getTextureView() { return textureView; }
    boolean isTextureRender() { return textureActive; }

    void setRenderMode(String mode) {
        if (mode == null) mode = RENDER_AUTO;
        if (renderMode.equals(mode)) return;
        renderMode = mode;
        applyRenderMode();
    }

    private boolean renderAsTexture() {
        if (RENDER_SURFACE.equals(renderMode)) return false;
        if (RENDER_TEXTURE.equals(renderMode)) return true;
        // AUTO: prefer texture so UI overlays stay visible; legacy boxes (PUSH_BUFFERS)
        // and pre-Lollipop devices keep the SurfaceView path.
        return !legacySurfaceMode && Build.VERSION.SDK_INT >= 21;
    }

    private void applyRenderMode() {
        if (surfaceView == null || textureView == null) return;
        boolean texture = renderAsTexture();
        if (texture == textureActive) {
            updateVisibility(texture);
            return;
        }
        textureActive = texture;
        // Detach the previously active output before switching containers.
        if (callback != null) {
            SurfaceHolder holder = textureActive ? null : activeHolder;
            Surface surface = textureActive ? activeTextureSurface
                    : (activeHolder == null ? null : activeHolder.getSurface());
            callback.onVideoSurfaceDestroyed(holder, surface);
        }
        activeHolder = null;
        if (activeTextureSurface != null) { activeTextureSurface.release(); activeTextureSurface = null; }
        activeTexture = null;
        updateVisibility(texture);
        requestLayout();
    }

    private void updateVisibility(boolean texture) {
        textureView.setVisibility(texture ? VISIBLE : GONE);
        surfaceView.setVisibility(texture ? GONE : VISIBLE);
    }

    boolean isFrameCovered() { return frameCover != null && frameCover.getVisibility() == VISIBLE; }
    void setFrameCovered(boolean covered) {
        if (frameCover == null) {
            if (!covered) return;
            frameCover = new android.view.View(getContext());
            frameCover.setBackgroundColor(android.graphics.Color.BLACK);
            addView(frameCover, new LayoutParams(-1, -1));
        }
        // Keep the Surface attached: INVISIBLE/GONE can destroy its native output.
        frameCover.setVisibility(covered ? VISIBLE : GONE);
    }
    void setLegacySurfaceMode(boolean enabled) {
        if (legacySurfaceMode == enabled) return;
        legacySurfaceMode = enabled;
        applySurfaceType();
        applyRenderMode();
    }

    private void applySurfaceType() {
        if (surfaceView == null) return;
        //noinspection deprecation
        surfaceView.getHolder().setType(legacySurfaceMode
                ? SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS : SurfaceHolder.SURFACE_TYPE_NORMAL);
    }

    void setSurfaceCallback(SurfaceCallback listener) {
        callback = listener;
        if (listener != null && isSurfaceReady())
            listener.onVideoSurfaceCreated(getVideoSurfaceHolder(), getVideoSurface());
    }

    boolean isSurfaceReady() {
        if (textureActive) return activeTexture != null;
        Surface surface = getVideoSurface();
        return surface != null && surface.isValid();
    }

    SurfaceHolder getVideoSurfaceHolder() { return textureActive ? null : activeHolder; }
    Surface getVideoSurface() {
        if (textureActive) return activeTextureSurface;
        return activeHolder == null ? null : activeHolder.getSurface();
    }

    void setStretchVideo(boolean enabled) {
        if (stretchVideo == enabled) return;
        stretchVideo = enabled;
        updateBufferSize();
        requestLayout();
    }

    void setFillVideo(boolean enabled) {
        if (fillVideo == enabled) return;
        fillVideo = enabled;
        updateBufferSize();
        requestLayout();
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        width = Math.max(0, width);
        height = Math.max(0, height);
        sarNum = sarNum > 0 ? sarNum : 1;
        sarDen = sarDen > 0 ? sarDen : 1;
        // Size and SAR often arrive separately, followed by onPrepared. Avoid
        // repeating the same native-window resize and layout before first frame.
        if (videoWidth == width && videoHeight == height
                && this.sarNum == sarNum && this.sarDen == sarDen
                && appliedViewportBufferMode == viewportBufferMode) return;
        videoWidth = width;
        videoHeight = height;
        this.sarNum = sarNum;
        this.sarDen = sarDen;
        appliedViewportBufferMode = viewportBufferMode;
        updateBufferSize();
        requestLayout();
    }

    void setViewportBufferMode(boolean enabled) { viewportBufferMode = enabled; }

    private void updateBufferSize() {
        // TextureView is a normal View: its surface is sized by layout, no fixed size.
        if (textureActive) return;
        if (viewportBufferMode && fillVideo && !stretchVideo && videoWidth > 0 && videoHeight > 0
                && getParent() instanceof android.view.View) {
            android.view.View parent = (android.view.View) getParent();
            if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                setFillBufferSize(parent.getWidth(), parent.getHeight());
                return;
            }
        }
        if (!viewportBufferMode && videoWidth > 0 && videoHeight > 0)
            surfaceView.getHolder().setFixedSize(videoWidth, videoHeight);
        else surfaceView.getHolder().setSizeFromLayout();
    }

    private void setFillBufferSize(int width, int height) {
        // Cropped portrait video can extend far beyond the screen. Limit low-RAM
        // software output independently of the displayed Surface rectangle.
        float ratio = (float) videoWidth * sarNum / ((float) videoHeight * sarDen);
        surfaceView.getHolder().setFixedSize(
                Math.min(width, Math.max(1, Math.round(height * ratio))),
                Math.min(height, Math.max(1, Math.round(width / ratio))));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
        int width = availableWidth, height = availableHeight;
        int fillWidth = 0, fillHeight = 0;
        if (!stretchVideo && videoWidth > 0 && videoHeight > 0
                && availableWidth > 0 && availableHeight > 0) {
            float videoAspect = aspectRatio>0?aspectRatio:(float) videoWidth * sarNum / ((float) videoHeight * sarDen);
            if (fillVideo) {
                // Resize the actual Surface rectangle: View transforms do not move
                // SurfaceView on older Android. Keep its parent at viewport size
                // so swipe animations and the frame cover still use screen bounds.
                if ((float) availableWidth / availableHeight > videoAspect)
                    fillHeight = (int) Math.ceil(availableWidth / (double) videoAspect);
                else fillWidth = (int) Math.ceil(availableHeight * (double) videoAspect);
                fillWidth = Math.max(availableWidth, fillWidth);
                fillHeight = Math.max(availableHeight, fillHeight);
                // Also handle a changed viewport (rotation / window resize).
                if (viewportBufferMode && !textureActive) setFillBufferSize(availableWidth, availableHeight);
            } else {
                if ((float) availableWidth / availableHeight > videoAspect)
                    width = Math.round(availableHeight * videoAspect);
                else height = Math.round(availableWidth / videoAspect);
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int childWidth = width, childHeight = height;
        if (fillWidth > 0 && fillHeight > 0) { childWidth = fillWidth; childHeight = fillHeight; }
        surfaceView.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        textureView.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = (getMeasuredWidth() - surfaceView.getMeasuredWidth()) / 2;
        int y = (getMeasuredHeight() - surfaceView.getMeasuredHeight()) / 2;
        surfaceView.layout(x, y, x + surfaceView.getMeasuredWidth(), y + surfaceView.getMeasuredHeight());
        int tx = (getMeasuredWidth() - textureView.getMeasuredWidth()) / 2;
        int ty = (getMeasuredHeight() - textureView.getMeasuredHeight()) / 2;
        textureView.layout(tx, ty, tx + textureView.getMeasuredWidth(), ty + textureView.getMeasuredHeight());
        if (frameCover != null && frameCover.getVisibility() != GONE)
            frameCover.layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    // SurfaceView callbacks
    @Override public void surfaceCreated(SurfaceHolder holder) {
        activeHolder = holder;
        if (!textureActive && callback != null)
            callback.onVideoSurfaceCreated(holder, holder.getSurface());
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (!textureActive && callback != null)
            callback.onVideoSurfaceDestroyed(holder, holder.getSurface());
        if (activeHolder == holder) activeHolder = null;
    }

    // TextureView callbacks
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        activeTexture = st;
        if (activeTextureSurface == null || !activeTextureSurface.isValid())
            activeTextureSurface = new Surface(st);
        if (textureActive && callback != null)
            callback.onVideoSurfaceCreated(null, activeTextureSurface);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (textureActive && callback != null)
            callback.onVideoSurfaceDestroyed(null, activeTextureSurface);
        if (activeTextureSurface != null) { activeTextureSurface.release(); activeTextureSurface = null; }
        activeTexture = null;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    void releaseOutput() {
        callback = null;
        activeHolder = null;
        surfaceView.getHolder().removeCallback(this);
        if (activeTextureSurface != null) { activeTextureSurface.release(); activeTextureSurface = null; }
        activeTexture = null;
    }

    interface SurfaceCallback {
        void onVideoSurfaceCreated(SurfaceHolder holder, Surface surface);
        void onVideoSurfaceDestroyed(SurfaceHolder holder, Surface surface);
    }
}
