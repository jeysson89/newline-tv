package com.newline.tv;

import android.content.Context;
import android.content.res.Configuration;

import com.newline.tv.data.Settings;

/** Применяет сохранённый масштаб текста ко всем экранам приложения. */
public final class FontScale {

    private FontScale() {
    }

    public static Context wrap(Context base) {
        float scale = Settings.getFontScale(base);
        if (Math.abs(scale - 1.0f) < 0.005f) {
            return base;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = scale;
        return base.createConfigurationContext(configuration);
    }
}
