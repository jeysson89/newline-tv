package com.newline.tv;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.newline.tv.data.Settings;

/** Применяет выбранную тему до создания первого экрана. */
public class NewlineApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(nightMode(Settings.getThemeMode(this)));
    }

    public static int nightMode(int themeMode) {
        if (themeMode == Settings.THEME_DARK) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        if (themeMode == Settings.THEME_LIGHT) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
