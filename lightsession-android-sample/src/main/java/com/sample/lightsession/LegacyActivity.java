package com.sample.lightsession;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * An Activity that predates AndroidX: it extends `android.app.Activity` directly.
 *
 * Deliberately not `AppCompatActivity`, and that is the whole point of the screen. The mapper wrapped
 * its entire decision in `activity is ComponentActivity`, so a class like this fell straight through
 * and was recorded as nothing. Plenty of long-lived apps still have a few — a splash, an about box,
 * something a third party contributed years ago.
 *
 * Removing that gate was not enough on its own, which is worth knowing when reading the fix: every
 * send read `(activity as? ComponentActivity)?.lifecycleScope ?: return`, so even past the gate this
 * screen was built in the SDK's local state and posted nowhere. There is a fallback scope now.
 *
 * Expected in the map: a screen named `LegacyActivity`, with an edge from whatever opened it.
 *
 * No Compose and no `setContentView` from a layout, because this class cannot use a Compose
 * `setContent` and a layout would add nothing — the view here exists only so there is something to
 * capture a wireframe of.
 */
public class LegacyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Legacy Activity");
        title.setTextSize(24f);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(
                "Extends android.app.Activity, not AppCompatActivity. It has no lifecycleScope, "
                        + "so the SDK sends its screen on a fallback scope of its own.");
        body.setTextSize(14f);
        body.setTextColor(Color.DKGRAY);
        body.setPadding(0, 32, 0, 0);
        root.addView(body);

        setContentView(root);
    }
}
