package com.newline.tv;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;

import com.newline.tv.data.Settings;
import com.newline.tv.update.UpdateManager;

public class SettingsActivity extends BaseActivity {

    private static final String STATE_SOURCE = "state_source";
    private static final String STATE_VIEW = "state_view";

    private SwitchCompat themeSwitch;
    private View btnSourcePortal;
    private View btnSourceM3u;
    private SeekBar scaleSeek;
    private TextView scaleValue;
    private TextView scalePreviewTitle;
    private TextView scalePreviewSub;
    private SeekBar epgScaleSeek;
    private TextView epgScaleValue;
    private TextView epgScalePreviewTime;
    private TextView epgScalePreviewName;
    private SwitchCompat panelSwitch;
    private SeekBar panelSeek;
    private TextView panelValue;
    private View btnViewCards;
    private View btnViewList;
    private TextView updateVersion;

    private String source;
    private String channelView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        bindViews();

        source = savedInstanceState == null
                ? Settings.getSource(this)
                : savedInstanceState.getString(STATE_SOURCE, Settings.getSource(this));
        channelView = savedInstanceState == null
                ? Settings.getChannelView(this)
                : savedInstanceState.getString(STATE_VIEW, Settings.getChannelView(this));

        fillValues();
        wireActions();
    }

    private void bindViews() {
        themeSwitch = findViewById(R.id.themeSwitch);
        btnSourcePortal = findViewById(R.id.btnSourcePortal);
        btnSourceM3u = findViewById(R.id.btnSourceM3u);
        scaleSeek = findViewById(R.id.scaleSeek);
        scaleValue = findViewById(R.id.scaleValue);
        scalePreviewTitle = findViewById(R.id.scalePreviewTitle);
        scalePreviewSub = findViewById(R.id.scalePreviewSub);
        epgScaleSeek = findViewById(R.id.epgScaleSeek);
        epgScaleValue = findViewById(R.id.epgScaleValue);
        epgScalePreviewTime = findViewById(R.id.epgScalePreviewTime);
        epgScalePreviewName = findViewById(R.id.epgScalePreviewName);
        panelSwitch = findViewById(R.id.panelSwitch);
        panelSeek = findViewById(R.id.panelSeek);
        panelValue = findViewById(R.id.panelValue);
        btnViewCards = findViewById(R.id.btnViewCards);
        btnViewList = findViewById(R.id.btnViewList);
        updateVersion = findViewById(R.id.updateVersion);
    }

    private void fillValues() {
        themeSwitch.setChecked(Settings.isDarkEffective(this));

        scaleSeek.setProgress(progressFor(Settings.getFontScale(this)));
        epgScaleSeek.setProgress(progressFor(Settings.getEpgFontScale(this)));

        panelSwitch.setChecked(Settings.isPanelEnabled(this));
        panelSeek.setProgress(Settings.getPanelTransparency(this));

        updateSourceUi();
        updateViewUi();
        updateScaleUi(scaleSeek.getProgress());
        updateEpgScaleUi(epgScaleSeek.getProgress());
        updatePanelUi(panelSeek.getProgress());
        updateVersion.setText(getString(R.string.settings_version_value, BuildConfig.VERSION_NAME));
    }

    private void wireActions() {
        btnSourcePortal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                source = Settings.SOURCE_PORTAL;
                updateSourceUi();
            }
        });

        btnSourceM3u.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                source = Settings.SOURCE_M3U;
                updateSourceUi();
            }
        });

        btnViewCards.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                channelView = Settings.VIEW_CARDS;
                updateViewUi();
            }
        });

        btnViewList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                channelView = Settings.VIEW_LIST;
                updateViewUi();
            }
        });

        findViewById(R.id.btnCheckUpdates).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UpdateManager.checkForUpdates(SettingsActivity.this, true);
            }
        });

        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateScaleUi(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        epgScaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateEpgScaleUi(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        panelSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updatePanelUi(panelSeek.getProgress());
            }
        });

        panelSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePanelUi(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btnSupport).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSupport();
            }
        });

        findViewById(R.id.btnApply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply();
            }
        });
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Слушатель ставим после восстановления состояния, чтобы пересоздание
        // из-за смены темы не вызывало повторную смену режима.
        themeSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                int mode = isChecked ? Settings.THEME_DARK : Settings.THEME_LIGHT;
                if (Settings.getThemeMode(SettingsActivity.this) == mode) {
                    return;
                }
                Settings.setThemeMode(SettingsActivity.this, mode);
                AppCompatDelegate.setDefaultNightMode(NewlineApp.nightMode(mode));
            }
        });
    }

    private void updateSourceUi() {
        boolean portal = Settings.SOURCE_PORTAL.equals(source);
        btnSourcePortal.setSelected(portal);
        btnSourceM3u.setSelected(!portal);
    }

    private void updateViewUi() {
        boolean list = Settings.VIEW_LIST.equals(channelView);
        btnViewCards.setSelected(!list);
        btnViewList.setSelected(list);
    }

    private void updateScaleUi(int progress) {
        float scale = scaleFor(progress);
        scaleValue.setText(Math.round(scale * 100f) + "%");

        // Показываем именно тот размер, который получится после «Применить»,
        // независимо от текущего масштаба экрана.
        float density = getResources().getDisplayMetrics().density;
        scalePreviewTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * density * scale);
        scalePreviewSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * density * scale);
    }

    private void updateEpgScaleUi(int progress) {
        float scale = scaleFor(progress);
        epgScaleValue.setText(Math.round(scale * 100f) + "%");

        // Предпросмотр повторяет размеры строки телепрограммы: 15sp и 16sp.
        float density = getResources().getDisplayMetrics().density;
        epgScalePreviewTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * density * scale);
        epgScalePreviewName.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * density * scale);
    }

    private void updatePanelUi(int transparency) {
        panelValue.setText(transparency + "%");

        // Ползунок прозрачности имеет смысл только при включённом списке.
        boolean enabled = panelSwitch.isChecked();
        float alpha = enabled ? 1f : 0.4f;
        panelSeek.setEnabled(enabled);
        panelSeek.setAlpha(alpha);
        panelValue.setAlpha(alpha);
    }

    private void showSupport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_support)
                .setMessage(R.string.settings_support_text)
                .setPositiveButton(R.string.settings_close, null)
                .show();
    }

    private void apply() {
        Settings.setSource(this, source);
        Settings.setFontScale(this, scaleFor(scaleSeek.getProgress()));
        Settings.setEpgFontScale(this, scaleFor(epgScaleSeek.getProgress()));
        Settings.setPanelEnabled(this, panelSwitch.isChecked());
        Settings.setPanelTransparency(this, panelSeek.getProgress());
        Settings.setChannelView(this, channelView);

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SOURCE, source);
        outState.putString(STATE_VIEW, channelView);
    }

    private static int progressFor(float scale) {
        int progress = Math.round((scale - Settings.FONT_SCALE_MIN) * 100f);
        return Math.max(0, Math.min(100, progress));
    }

    private static float scaleFor(int progress) {
        float scale = Settings.FONT_SCALE_MIN + progress / 100f;
        return Math.max(Settings.FONT_SCALE_MIN, Math.min(Settings.FONT_SCALE_MAX, scale));
    }
}
