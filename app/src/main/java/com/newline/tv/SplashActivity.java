package com.newline.tv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.newline.tv.data.Channel;
import com.newline.tv.data.PlaylistRepository;
import com.newline.tv.data.Settings;

import java.util.List;

/**
 * Заставка с логотипом оператора. Держится на экране, пока грузится плейлист,
 * и передаёт готовый список каналов главному экрану.
 */
public class SplashActivity extends BaseActivity {

    private static final long MIN_SPLASH_MS = 1200;
    private static final long MAX_SPLASH_MS = 16000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated;
    private long startedAt;

    private final Runnable goNext = new Runnable() {
        @Override
        public void run() {
            if (navigated) {
                return;
            }
            navigated = true;
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            // При входе в приложение сразу открываем последний канал (или первый).
            intent.putExtra(MainActivity.EXTRA_AUTOSTART, true);
            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        startedAt = SystemClock.elapsedRealtime();

        final String key = Settings.getSourceKey(this);

        PlaylistRepository.load(this, new PlaylistRepository.Callback() {
            @Override
            public void onSuccess(List<Channel> channels, boolean demo) {
                PlaylistRepository.setPending(PlaylistRepository.success(channels, demo, key));
                navigateWhenReady();
            }

            @Override
            public void onError(String message) {
                PlaylistRepository.setPending(PlaylistRepository.failure(message, key));
                navigateWhenReady();
            }
        });

        // Страховка: не держим пользователя на заставке дольше MAX_SPLASH_MS.
        handler.postDelayed(goNext, MAX_SPLASH_MS);
    }

    private void navigateWhenReady() {
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        long delay = Math.max(0, MIN_SPLASH_MS - elapsed);
        handler.postDelayed(goNext, delay);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
