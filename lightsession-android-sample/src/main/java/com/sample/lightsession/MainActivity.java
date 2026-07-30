package com.sample.lightsession;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.lightsession.LightSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The sample's first screen, and deliberately still Java.
 *
 * The SDK is Kotlin and its config is built in Kotlin next door, but a consumer writing Java has to
 * be able to call the rest of the API — so something here keeps that honest at compile time.
 *
 * Four of these buttons used to do nothing. They built a properties map, wrote a line to logcat and
 * returned: `identify` was never called, the recording toggle had an empty body, and the "track
 * event" button called an API the SDK does not have. A sample whose buttons only log is worse than
 * one with fewer buttons, because it reads as proof that the integration works.
 */
public class MainActivity extends AppCompatActivity {
    private View colorChangingView;
    private final Handler colorChangeHandler = new Handler();
    private final Random random = new Random();
    private Runnable colorChangeRunnable;

    /** Whether the modal sub-screen is currently declared. Toggled by its button. */
    private boolean subScreenOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button recordingButton = findViewById(R.id.capture_button);
        Button goToSecondButton = findViewById(R.id.go_to_second_button);
        Button identifyUserButton = findViewById(R.id.identify_user_button);
        Button subScreenButton = findViewById(R.id.track_event_button);
        Button crashButton = findViewById(R.id.crash_button);
        Button softErrorButton = findViewById(R.id.soft_error_button);

        colorChangingView = findViewById(R.id.colorChangingView);

        goToSecondButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SecondActivity.class)));

        // One entry per shape the mapper decides between. They are separate screens rather than one
        // configurable screen on purpose: what is being exercised is the decision made at
        // `onActivityResumed`, and that is per Activity.
        open(R.id.go_legacy_button, LegacyActivity.class);
        open(R.id.go_fragment_nav_button, FragmentNavActivity.class);
        open(R.id.go_hybrid_nav_button, HybridNavActivity.class);
        open(R.id.go_compose_simple_button, ComposeActivity.class);
        open(R.id.go_compose_navhost_button, ComposeWithNavigationActivity.class);
        open(R.id.go_compose_untracked_button, UntrackedComposeNavActivity.class);
        open(R.id.go_compose_nested_button, NestedComposeNavActivity.class);
        open(R.id.go_tabs_modal_button, TabsAndModalActivity.class);

        // Start and stop recording, which is what this button always said it did.
        //
        // The label is set from the SDK's own state rather than from a local boolean, so it cannot
        // drift out of step with what is actually being recorded — `stopRecording` also flushes,
        // and a button claiming "stop" while nothing is running is how you end up debugging the
        // wrong end of a missing session.
        updateRecordingLabel(recordingButton);
        recordingButton.setOnClickListener(v -> {
            if (LightSession.getInstance().isRecording()) {
                LightSession.getInstance().stopRecording();
            } else {
                LightSession.getInstance().startRecording();
            }
            updateRecordingLabel(recordingButton);
        });

        identifyUserButton.setOnClickListener(v -> {
            Map<String, Object> traits = new HashMap<>();
            traits.put("name", "John Doe");
            traits.put("email", "john.doe@example.com");
            traits.put("subscription_tier", "premium");
            // Everything this install recorded before now, including this screen, becomes
            // attributable to this person.
            LightSession.getInstance().identify("user_12345", traits);
            Log.d("MainActivity", "identified user_12345");
        });

        // Declares a part of this screen as a screen of its own — what a modal or a tab is. The
        // map records `MainActivity › checkout` while it is set, so a dialog stops being invisible
        // in the flow. This replaces a button that called a `trackEvent` API the SDK never had.
        subScreenButton.setOnClickListener(v -> {
            if (subScreenOpen) {
                LightSession.getInstance().clearSubScreen("checkout");
            } else {
                LightSession.getInstance().setSubScreen("checkout");
            }
            subScreenOpen = !subScreenOpen;
            Log.d("MainActivity", "sub-screen checkout " + (subScreenOpen ? "set" : "cleared"));
        });

        crashButton.setOnClickListener(v -> {
            Log.e("MainActivity", "Simulating app crash...");
            // Uncaught, on purpose: the session up to this point still has to arrive.
            String nullString = null;
            //noinspection ConstantConditions,DataFlowIssue
            nullString.length();
        });

        softErrorButton.setOnClickListener(v -> {
            try {
                Log.w("MainActivity", "Simulating a handled exception...");
                @SuppressWarnings({"unused", "divzero", "NumericOverflow"})
                int result = 10 / 0;
            } catch (ArithmeticException e) {
                // Caught, so the app keeps running and the session keeps recording. Here to show
                // the difference from the button above, which does not.
                Log.e("MainActivity", "handled: " + e.getMessage());
            }
        });

        // A view that repaints every second, so the recorder has real motion to capture on a
        // screen that would otherwise be static.
        colorChangeRunnable = new Runnable() {
            @Override
            public void run() {
                colorChangingView.setBackgroundColor(Color.rgb(
                        random.nextInt(256),
                        random.nextInt(256),
                        random.nextInt(256)
                ));
                colorChangeHandler.postDelayed(this, 1000);
            }
        };
        colorChangeHandler.post(colorChangeRunnable);
    }

    /** Wires a hub button to the Activity it demonstrates. */
    private void open(int buttonId, Class<?> target) {
        findViewById(buttonId).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, target)));
    }

    private void updateRecordingLabel(Button button) {
        button.setText(LightSession.getInstance().isRecording()
                ? R.string.stop_recording
                : R.string.start_recording);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        colorChangeHandler.removeCallbacks(colorChangeRunnable);
    }
}
