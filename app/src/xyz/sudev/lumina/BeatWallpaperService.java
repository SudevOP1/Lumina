package xyz.sudev.lumina;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import java.lang.reflect.Method;

public class BeatWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new BeatEngine();
    }

    class BeatEngine extends Engine implements Choreographer.FrameCallback {
        private final AudioAnalyzer analyzer = new AudioAnalyzer(BeatWallpaperService.this);
        private boolean micOk;
        private BorderRenderer renderer;
        private boolean visible;
        private int width, height;
        private Method hwLock;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            renderer = new BorderRenderer(getResources().getDisplayMetrics().density);
            try {
                hwLock = SurfaceHolder.class.getMethod("lockHardwareCanvas");
            } catch (Throwable t) {
                hwLock = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            Choreographer.getInstance().removeFrameCallback(this);
            if (v) {
                renderer.setCornerRadius(getSharedPreferences("beat", MODE_PRIVATE)
                        .getInt("radius", BorderRenderer.DEFAULT_RADIUS));
                micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (micOk) {
                    android.content.SharedPreferences p = getSharedPreferences("beat", MODE_PRIVATE);
                    analyzer.configure(p.getInt("sens", 50), p.getInt("mode", 0));
                    analyzer.start();
                    renderer.message = analyzer.error != null ? "Audio error – open Lumina app" : null;
                } else {
                    renderer.message = "Open Lumina app → allow microphone";
                }
                renderer.resetClock();
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                analyzer.stop();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            Choreographer.getInstance().removeFrameCallback(this);
            analyzer.stop();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            Choreographer.getInstance().removeFrameCallback(this);
            analyzer.stop();
            super.onDestroy();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!visible)
                return;
            if (micOk)
                analyzer.watchdog();
            drawFrame();
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                if (hwLock != null) {
                    try {
                        c = (Canvas) hwLock.invoke(holder);
                    } catch (Throwable t) {
                        hwLock = null;
                    }
                }
                if (c == null)
                    c = holder.lockCanvas();
                if (c != null) {
                    renderer.update(analyzer);
                    renderer.draw(c, width > 0 ? width : c.getWidth(), height > 0 ? height : c.getHeight());
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }
}
