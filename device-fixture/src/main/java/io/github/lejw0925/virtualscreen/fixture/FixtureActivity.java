package io.github.lejw0925.virtualscreen.fixture;

import android.app.Activity;
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
        scroll.addView(panel); setContentView(scroll);
    }
}
