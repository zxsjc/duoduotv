package xiao.bu.tv;

import android.graphics.*;
import android.os.*;
import android.view.*;

/** One small video snapshot per panel opening; never samples on a frame timer.
 *  Samples either a SurfaceView (PixelCopy) or a TextureView (getBitmap). */
public final class FrostedBackdrop {
    private interface Result { void done(int result); }
    private final View owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private BitmapShader shader;
    private final Matrix transform = new Matrix();
    private final RectF target = new RectF();
    private final int[] location = new int[2];
    private Bitmap image;
    private int generation;
    private int retries;
    private boolean pending;
    public FrostedBackdrop(View owner) { this.owner = owner; }
    public void clear() { generation++; pending=false; retries=0; image=null; shader=null; }
    public void capture() {
        if (Build.VERSION.SDK_INT < 24 || pending || !owner.isShown()) return;
        SurfaceView surface = null; TextureView texture = null;
        View output = output(owner.getRootView());
        if (output instanceof SurfaceView) surface = (SurfaceView) output;
        else if (output instanceof TextureView) texture = (TextureView) output;
        if (surface == null && texture == null) return;
        int w = surface != null ? surface.getWidth() : texture.getWidth();
        int h = surface != null ? surface.getHeight() : texture.getHeight();
        if (w <= 0 || h <= 0) return;
        int token=++generation;pending=true;
        output.getLocationOnScreen(location);
        int sx=location[0],sy=location[1],sw=w,sh=h;
        int bw=128,bh=Math.max(1,128*sh/sw);
        if (texture != null) {
            // TextureView must be sampled on the UI thread; getBitmap() is synchronous.
            Bitmap sample = texture.getBitmap(bw, bh);
            if (sample == null) {
                pending=false;
                if (retries++ < 2) owner.postDelayed(() -> { if (token==generation) capture(); }, 200);
                return;
            }
            publish(sample, token, sx, sy, sw, sh);
            return;
        }
        Bitmap sample=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);
        Api24.copy(surface,sample,result->{
            if(result!=0){
                sample.recycle();if(token!=generation)return;pending=false;
                if(retries++<2)owner.postDelayed(()->{if(token==generation)capture();},200);
                return;
            }
            publish(sample, token, sx, sy, sw, sh);
        },new Handler(Looper.getMainLooper()));
    }
    private void publish(Bitmap sample, int token, int sx, int sy, int sw, int sh) {
        new Thread(()->{
            blur(sample);
            owner.post(()->{
                if(token!=generation){sample.recycle();return;}
                pending=false;image=sample;
                owner.getLocationOnScreen(location);
                target.set(sx-location[0],sy-location[1],sx-location[0]+sw,sy-location[1]+sh);
                shader=new BitmapShader(sample,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                transform.setScale(target.width()/sample.getWidth(),target.height()/sample.getHeight());
                transform.postTranslate(target.left,target.top);shader.setLocalMatrix(transform);
                owner.invalidate();
            });
        },"theme-glass").start();
    }
    public boolean draw(Canvas canvas,RectF bounds,float radius) {
        if(shader==null)return false;
        paint.setShader(shader);canvas.drawRoundRect(bounds,radius,radius,paint);paint.setShader(null);
        return true;
    }
    private static View output(View view) {
        if(view instanceof SurfaceView && view.isShown())return view;
        if(view instanceof TextureView && view.isShown())return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=output(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static void blur(Bitmap bitmap) {
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] src=new int[w*h],out=new int[w*h];
        bitmap.getPixels(src,0,w,0,0,w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int red=0,green=0,blue=0,count=0;
            for(int yy=Math.max(0,y-4);yy<=Math.min(h-1,y+4);yy++)for(int xx=Math.max(0,x-4);xx<=Math.min(w-1,x+4);xx++){
                int color=src[yy*w+xx];red+=(color>>16)&255;green+=(color>>8)&255;blue+=color&255;count++;
            }
            // Precompose the dark glass tint here, so its outer antialiased edge
            // is drawn once rather than accumulating several translucent rims.
            int sheen=5*(w+h-x-y)/Math.max(1,w+h);
            red=33+sheen+red/count*3/10;
            green=32+sheen+green/count*3/10;
            blue=32+sheen+blue/count*3/10;
            out[y*w+x]=0xff000000|(red<<16)|(green<<8)|blue;
        }
        bitmap.setPixels(out,0,w,0,0,w,h);
    }
    @android.annotation.TargetApi(24) private static final class Api24 {
        static void copy(SurfaceView view,Bitmap bitmap,Result listener,Handler handler) {
            try {android.view.PixelCopy.request(view,bitmap,listener::done,handler);}
            catch(IllegalArgumentException error){listener.done(-1);}
        }
    }
}
