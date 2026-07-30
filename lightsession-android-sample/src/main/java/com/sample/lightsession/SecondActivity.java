package com.sample.lightsession;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.lightsession.LightSession;

import java.util.Random;

/**
 * The second Activity, reached from the first.
 *
 * There is nothing to call here beyond what the SDK does on its own: the navigation between the two
 * appears in the map without either screen asking for it, which is most of the point of the sample.
 * A commented-out `trackEvent` call used to sit in the back button, for an API the SDK does not have.
 *
 * `reset` is the one thing worth showing, because it is the counterpart to the `identify` on the
 * screen before and its ordering matters.
 */
public class SecondActivity extends AppCompatActivity {
    private View colorChangingView;
    private final Handler colorChangeHandler = new Handler();
    private final Random random = new Random();
    private Runnable colorChangeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_segunda);

        colorChangingView = findViewById(R.id.colorChangingViewSegunda);
        Button backButton = findViewById(R.id.backButton);
        Button goToComposeButton = findViewById(R.id.goToComposeButton);

        backButton.setOnClickListener(v -> {
            // Before finishing, so the last thing recorded under whoever was identified is still
            // theirs. `reset` mints a new anonymous id and starts a new session, so the next person
            // to use this device does not inherit their history — which is what a sign-out does.
            LightSession.getInstance().reset();
            finish();
        });

        // The simple Compose case, matching this button's label. It used to jump straight to the
        // NavHost variant, which left `ComposeActivity` in the manifest with nothing opening it.
        goToComposeButton.setOnClickListener(v ->
                startActivity(new Intent(SecondActivity.this, ComposeActivity.class)));

        // Repaints twice as fast as the first screen and in lighter tones, so the two are told
        // apart at a glance in a replay.
        colorChangeRunnable = new Runnable() {
            @Override
            public void run() {
                colorChangingView.setBackgroundColor(Color.rgb(
                        150 + random.nextInt(106),
                        150 + random.nextInt(106),
                        150 + random.nextInt(106)
                ));
                colorChangeHandler.postDelayed(this, 500);
            }
        };
        colorChangeHandler.post(colorChangeRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        colorChangeHandler.removeCallbacks(colorChangeRunnable);
    }
}
