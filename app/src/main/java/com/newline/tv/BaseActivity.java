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
        // Запоминаем активный экран: сюда придёт результат проверки обновлений,
        // а после разрешения установки из неизвестных источников продолжится установка.
        UpdateManager.onActivityResumed(this);
    }

    @Override
    protected void onDestroy() {
        UpdateManager.onActivityDestroyed(this);
        super.onDestroy();
    }
}
