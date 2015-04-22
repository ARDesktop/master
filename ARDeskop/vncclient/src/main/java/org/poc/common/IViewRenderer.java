package org.poc.common;

import android.graphics.Canvas;

/**
 * Created by joel on 4/20/15.
 */
public interface IViewRenderer {
    Canvas onDrawViewBegin();
    void onDrawViewEnd();
}

