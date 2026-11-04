package org.telegram.cogram;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shows why the app last stopped, with buttons to pass the report on. */
public class CrashReportActivity extends Activity {

    private static final int MAX_SHARED_CHARS = 100000;

    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(createButton("نسخ", this::copyReport), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(createButton("مشاركة", this::shareReport), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        TextView text = new TextView(this);
        text.setText("...");
        text.setTextSize(11);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        int padding = (int) (8 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        Context context = getApplicationContext();
        new Thread(() -> {
            String built = CrashReporter.buildReport(context);
            runOnUiThread(() -> {
                report = built;
                text.setText(built);
            });
        }).start();
    }

    private Button createButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private String limitedReport() {
        return report.substring(0, Math.min(report.length(), MAX_SHARED_CHARS));
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Cogram Log", limitedReport()));
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, limitedReport());
        startActivity(Intent.createChooser(intent, "Cogram Log"));
    }
}
