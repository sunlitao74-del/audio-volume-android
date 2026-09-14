package com.personal.audiovolume;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1001;
    private static final int CREATE_OUTPUT = 1002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri inputUri;
    private String inputName;
    private String extension;
    private int gainDb = 0;

    private TextView fileText;
    private TextView gainText;
    private TextView statusText;
    private Button chooseButton;
    private Button processButton;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(32), pad, pad);
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = text("音量调整", 30, Color.rgb(15, 23, 42));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("离线修改 MP3 / FLAC 的音量", 15, Color.rgb(71, 85, 105));
        subtitle.setPadding(0, dp(6), 0, dp(28));
        root.addView(subtitle);

        chooseButton = button("选择音频文件");
        chooseButton.setOnClickListener(v -> pickAudio());
        root.addView(chooseButton, matchWrap());

        fileText = text("尚未选择文件", 14, Color.rgb(100, 116, 139));
        fileText.setPadding(dp(4), dp(12), dp(4), dp(26));
        root.addView(fileText);

        gainText = text("0 dB · 原始音量", 22, Color.rgb(15, 23, 42));
        gainText.setGravity(Gravity.CENTER);
        gainText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(gainText, matchWrap());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(36);
        seekBar.setProgress(18);
        seekBar.setPadding(0, dp(16), 0, dp(6));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                gainDb = progress - 18;
                updateGainLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(seekBar, matchWrap());

        LinearLayout marks = new LinearLayout(this);
        marks.setOrientation(LinearLayout.HORIZONTAL);
        TextView low = text("−18 dB", 12, Color.rgb(100, 116, 139));
        TextView high = text("+18 dB", 12, Color.rgb(100, 116, 139));
        marks.addView(low, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        high.setGravity(Gravity.END);
        marks.addView(high, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(marks, matchWrap());

        TextView tip = text("增大音量过多可能产生削波失真。应用会创建新文件，不覆盖原文件。", 13, Color.rgb(100, 116, 139));
        tip.setPadding(dp(4), dp(18), dp(4), dp(24));
        root.addView(tip);

        processButton = button("另存为新文件");
        processButton.setEnabled(false);
        processButton.setOnClickListener(v -> createOutput());
        root.addView(processButton, matchWrap());

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(20);
        root.addView(progressBar, progressParams);

        statusText = text("", 14, Color.rgb(37, 99, 235));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(10), 0, 0);
        root.addView(statusText, matchWrap());

        setContentView(root);
    }

    private void updateGainLabel() {
        if (gainDb == 0) {
            gainText.setText("0 dB · 原始音量");
            return;
        }
        double multiplier = Math.pow(10.0, gainDb / 20.0);
        gainText.setText(String.format(Locale.CHINA, "%+d dB · %.2f 倍", gainDb, multiplier));
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/flac", "audio/x-flac", "application/flac"});
        startActivityForResult(intent, PICK_AUDIO);
    }

    private void createOutput() {
        if (inputUri == null) return;
        String base = inputName.substring(0, inputName.length() - extension.length());
        String gainPart = gainDb > 0 ? "+" + gainDb + "dB" : gainDb + "dB";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(extension.equals(".mp3") ? "audio/mpeg" : "audio/flac");
        intent.putExtra(Intent.EXTRA_TITLE, base + "_音量" + gainPart + extension);
        startActivityForResult(intent, CREATE_OUTPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_AUDIO) {
            Uri uri = data.getData();
            String name = queryName(uri);
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mp3") && !lower.endsWith(".flac")) {
                Toast.makeText(this, "请选择 MP3 或 FLAC 文件", Toast.LENGTH_LONG).show();
                return;
            }
            inputUri = uri;
            inputName = name;
            extension = lower.endsWith(".mp3") ? ".mp3" : ".flac";
            fileText.setText(name);
            processButton.setEnabled(true);
            statusText.setText("");
        } else if (requestCode == CREATE_OUTPUT) {
            process(data.getData());
        }
    }

    private void process(Uri outputUri) {
        setBusy(true, "正在处理，请勿关闭应用…");
        final Uri sourceUri = inputUri;
        final String sourceExt = extension;
        final int selectedGain = gainDb;
        worker.execute(() -> {
            File input = new File(getCacheDir(), "input" + sourceExt);
            File output = new File(getCacheDir(), "output" + sourceExt);
            try {
                copyUriToFile(sourceUri, input);
                if (output.exists() && !output.delete()) throw new Exception("无法清理临时输出文件");

                String[] command;
                String filter = String.format(Locale.US, "volume=%ddB", selectedGain);
                if (sourceExt.equals(".mp3")) {
                    command = new String[]{"-y", "-i", input.getAbsolutePath(), "-map_metadata", "0", "-vn", "-af", filter,
                            "-c:a", "libmp3lame", "-q:a", "2", output.getAbsolutePath()};
                } else {
                    command = new String[]{"-y", "-i", input.getAbsolutePath(), "-map_metadata", "0", "-vn", "-af", filter,
                            "-c:a", "flac", "-compression_level", "5", output.getAbsolutePath()};
                }

                FFmpegSession session = FFmpegKit.executeWithArguments(command);
                if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.isFile() || output.length() == 0) {
                    String log = session.getAllLogsAsString();
                    throw new Exception(log == null || log.isEmpty() ? "音频处理失败" : lastUsefulLine(log));
                }
                copyFileToUri(output, outputUri);
                runOnUiThread(() -> setBusy(false, "完成：新文件已保存"));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "未知错误" : e.getMessage();
                runOnUiThread(() -> {
                    setBusy(false, "处理失败");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (input.exists()) input.delete();
                if (output.exists()) output.delete();
            }
        });
    }

    private String queryName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        }
        return "audio.mp3";
    }

    private void copyUriToFile(Uri uri, File file) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new Exception("无法读取所选文件");
            copy(in, out);
        }
    }

    private void copyFileToUri(File file, Uri uri) throws Exception {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new Exception("无法写入目标文件");
            copy(in, out);
        }
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private String lastUsefulLine(String log) {
        String[] lines = log.trim().split("\\r?\\n");
        String line = lines.length == 0 ? "音频处理失败" : lines[lines.length - 1].trim();
        return line.length() > 180 ? line.substring(0, 180) : line;
    }

    private void setBusy(boolean busy, String status) {
        chooseButton.setEnabled(!busy);
        processButton.setEnabled(!busy && inputUri != null);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(status);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
