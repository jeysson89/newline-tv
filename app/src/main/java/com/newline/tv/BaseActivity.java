package com.newline.tv;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.newline.tv.update.UpdateManager;

/** Базовый экран: единый масштаб текста из настроек. */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(FontScale.wrap(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Если пользователь уходил разрешать установку из неизвестных источников,
        // продолжаем установку скачанного обновления.
        UpdateManager.resumePendingInstall(this);
    }

    @Override
    protected void onDestroy() {
        UpdateManager.onActivityDestroyed(this);
        super.onDestroy();
    }
}
