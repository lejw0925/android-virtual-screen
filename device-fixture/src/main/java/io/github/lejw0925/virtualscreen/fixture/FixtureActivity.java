package io.github.lejw0925.virtualscreen.fixture;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.*;

/** Installed only for device verification; never bundled in the product APK. */
public class FixtureActivity extends Activity {
    int count;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.test_scroll);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(32, 80, 32, 40);
        TextView title = new TextView(this);
        title.setText("Virtual display fixture"); title.setTextSize(22);
        panel.addView(title);
        Button button = new Button(this);
        button.setId(R.id.test_counter);
        button.setText("Tap counter: 0");
        button.setOnClickListener(v -> button.setText("Tap counter: " + ++count));
        panel.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));
        EditText editor = new EditText(this);
        editor.setId(R.id.test_editor);
        editor.setSingleLine(true); editor.setHint("Unicode editor"); editor.setTextSize(20);
        panel.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 110));
        for (int i = 0; i < 80; i++) {
            TextView row = new TextView(this); row.setText("Scrollable row " + i); row.setTextSize(18); row.setPadding(12, 20, 12, 20); panel.addView(row);
        }
        Button longPress = new Button(this);
        longPress.setId(R.id.test_long_press);
        longPress.setText("Long press target");
        longPress.setOnLongClickListener(v -> true);
        panel.addView(longPress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));

        LinearLayout clickContainer = new LinearLayout(this);
        clickContainer.setId(R.id.test_click_container);
        clickContainer.setOrientation(LinearLayout.VERTICAL);
        clickContainer.setClickable(true);
        clickContainer.setOnClickListener(v -> Toast.makeText(this, "Container clicked", Toast.LENGTH_SHORT).show());
        TextView containerLabel = new TextView(this);
        containerLabel.setText("Unnamed clickable container label");
        clickContainer.addView(containerLabel);
        panel.addView(clickContainer);

        Button stateButton = new Button(this);
        stateButton.setId(R.id.test_state_description);
        stateButton.setText("Toggle state description");
        stateButton.setStateDescription("Collapsed");
        stateButton.setOnClickListener(v -> stateButton.setStateDescription(
            "Collapsed".contentEquals(stateButton.getStateDescription()) ? "Expanded" : "Collapsed"));
        panel.addView(stateButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));

        Button disabled = new Button(this);
        disabled.setId(R.id.test_disabled);
        disabled.setText("Disabled readable control");
        disabled.setEnabled(false);
        panel.addView(disabled, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));

        Button dialog = new Button(this);
        dialog.setId(R.id.test_dialog);
        dialog.setText("Open dialog window");
        dialog.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Fixture dialog")
            .setMessage("Dialog window content")
            .setPositiveButton("Close", (d, which) -> d.dismiss())
            .show());
        panel.addView(dialog, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));
        scroll.addView(panel); setContentView(scroll);
    }
}
