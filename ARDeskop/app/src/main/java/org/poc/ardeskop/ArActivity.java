package org.poc.ardeskop;

import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;

import org.jalasoft.ardeskop.R;
import org.poc.ar.Renderer;
import org.poc.widget.CardboardOverlayView;

import butterknife.ButterKnife;
import butterknife.InjectView;
import timber.log.Timber;

/**
 * Created by joel on 4/19/15.
 */
public class ArActivity extends CardboardActivity {

    @InjectView(R.id.overlay)
    CardboardOverlayView overlayView;

    @InjectView(R.id.cardboard_view)
    CardboardView cardboardView;

    private Renderer mRenderer;

    private Vibrator mVibrator;
    private int mScore = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_ar);

        // Inject views
        ButterKnife.inject(this);

        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Associate a CardboardView.StereoRenderer with cardboardView.
        mRenderer = new Renderer(this);
        cardboardView.setRenderer(mRenderer);

        // Associate the cardboardView with this activity.
        setCardboardView(cardboardView);

        overlayView.show3DToast("Welcome to CardAR.");
    }

    @Override
    public void onCardboardTrigger() {
        Timber.i("onCardboardTrigger");

        if (mRenderer.isLookingAtObject()) {
            mScore++;
            overlayView.show3DToast("Found it! Look around for another one.\nScore = " + mScore);
            mRenderer.hideObject();
        } else {
            overlayView.show3DToast("Look around to find the object!");
        }
        // Always give user feedback
        mVibrator.vibrate(50);
    }
}
