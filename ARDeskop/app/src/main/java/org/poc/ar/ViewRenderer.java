package org.poc.ar;

import android.graphics.Canvas;
import android.util.Log;
import android.view.Surface;
import com.google.vrtoolkit.cardboard.CardboardView;

import org.poc.common.IViewRenderer;

/**
 * Created by joel on 4/19/15.
 */
public abstract class ViewRenderer implements CardboardView.StereoRenderer, IViewRenderer {

    private static final String TAG = ViewRenderer.class.getSimpleName();
    private Surface mSurface;
    private Canvas mSurfaceCanvas;

    @Override
    public Canvas onDrawViewBegin() {
        mSurfaceCanvas = null;
        if (mSurface != null) {
            try {
                mSurfaceCanvas = mSurface.lockCanvas(null);
            }catch (Exception e){
                Log.e(TAG, "error while rendering view to gl: " + e);
            }
        }
        return mSurfaceCanvas;
    }

    @Override
    public void onDrawViewEnd() {

        if(mSurfaceCanvas != null) {
            mSurface.unlockCanvasAndPost(mSurfaceCanvas);
        }
        mSurfaceCanvas = null;
    }
}
