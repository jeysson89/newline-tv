package com.newline.tv;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.newline.tv.data.Channel;
import com.newline.tv.data.PlaylistRepository;
import com.newline.tv.data.Settings;
import com.newline.tv.data.XmltvEpg;
import com.newline.tv.ui.ChannelAdapter;
import com.newline.tv.update.UpdateManager;

import java.util.List;

public class MainActivity extends BaseActivity {

    /** true — экран открыт при входе в приложение, нужно сразу запустить канал. */
    public static final String EXTRA_AUTOSTART = "extra_autostart";

    private RecyclerView grid;
    private ProgressBar progress;
    private View errorPanel;
    private TextView errorText;
    private TextView counter;
    private TextView demoHint;

    private ChannelAdapter adapter;
    private String loadedKey;
    private float appliedFontScale;
    private String appliedChannelView;
    private boolean loading;
    private boolean wideLayout;

    /** Список каналов, показанный сейчас в сетке (для поиска последнего канала). */
    private List<Channel> items;
    /** Одноразовый флаг: автозапуск выполняется только при входе, не при возврате. */
    private boolean autoStartPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        wideLayout = isWideLayout();
        appliedFontScale = Settings.getFontScale(this);

        // Автозапуск только для «свежего» входа, не для поворота/пересоздания экрана.
        autoStartPending = savedInstanceState == null
                && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTOSTART, false);

        grid = findViewById(R.id.channelGrid);
        progress = findViewById(R.id.progress);
        errorPanel = findViewById(R.id.errorPanel);
        errorText = findViewById(R.id.errorText);
        counter = findViewById(R.id.counter);
        demoHint = findViewById(R.id.demoHint);

        grid.setLayoutManager(new GridLayoutManager(this, columns()));
        adapter = new ChannelAdapter(new ChannelAdapter.Listener() {
            @Override
            public void onChannelClick(Channel channel) {
                openChannel(channel);
            }
        });
        grid.setAdapter(adapter);
        appliedChannelView = Settings.getChannelView(this);
        applyChannelView();

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });

        // Автопроверка обновлений при старте (не чаще раза в 6 часов).
        UpdateManager.checkForUpdates(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Масштаб текста применяется пересозданием экрана с новым контекстом.
        if (Math.abs(Settings.getFontScale(this) - appliedFontScale) > 0.005f) {
            recreate();
            return;
        }
        // Вид списка тоже пересобираем пересозданием, чтобы не терять позицию и фокус.
        if (!Settings.getChannelView(this).equals(appliedChannelView)) {
            recreate();
            return;
        }

        PlaylistRepository.Result pending = PlaylistRepository.takePending();
        if (pending != null) {
            loadedKey = pending.key;
            if (pending.error != null) {
                showError(pending.error);
                // Даже если плейлист не загрузился, пробуем открыть последний канал.
                maybeAutoStartStored();
            } else {
                render(pending.channels, pending.demo);
            }
            return;
        }

        String key = Settings.getSourceKey(this);
        boolean keyChanged = !key.equals(loadedKey == null ? "" : loadedKey);
        if (!loading && (adapter.getItemCount() == 0 || keyChanged)) {
            List<Channel> cached = PlaylistRepository.getCached(key);
            if (cached != null) {
                // Список уже загружен: при повороте/возврате не скачиваем заново.
                loadedKey = key;
                render(cached, PlaylistRepository.isCachedDemo(), false);
            } else if (keyChanged || errorPanel.getVisibility() != View.VISIBLE) {
                load();
            }
        }
    }

    private void load() {
        loading = true;
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        demoHint.setVisibility(View.GONE);

        final String requestedKey = Settings.getSourceKey(this);
        PlaylistRepository.load(this, new PlaylistRepository.Callback() {
            @Override
            public void onSuccess(List<Channel> channels, boolean demo) {
                loading = false;
                loadedKey = requestedKey;
                render(channels, demo);
            }

            @Override
            public void onError(String message) {
                loading = false;
                loadedKey = requestedKey;
                showError(message);
            }
        });
    }

    private void render(List<Channel> channels, boolean demo) {
        render(channels, demo, true);
    }

    private void render(List<Channel> channels, boolean demo, boolean resetScroll) {
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        items = channels;
        adapter.setItems(channels);
        counter.setText(getString(R.string.channels_count, channels.size()));
        demoHint.setVisibility(demo ? View.VISIBLE : View.GONE);

        // Телепрограмму качаем заранее — к моменту открытия плеера она уже готова.
        if (!demo && !Settings.isPortalSource(this) && channels != null
                && !channels.isEmpty()) {
            XmltvEpg.warmUp(this, channels);
        }

        maybeAutoStart();

        if (!resetScroll) {
            return;
        }

        grid.scrollToPosition(0);
        focusFirstChannel(0);
    }

    /** Ставит фокус на первый канал: без него пульт не управляет списком. */
    private void focusFirstChannel(final int attempt) {
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (adapter.getItemCount() == 0) {
                    return;
                }
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
                if (holder != null) {
                    holder.itemView.requestFocus();
                } else if (attempt < 6) {
                    focusFirstChannel(attempt + 1);
                } else {
                    grid.requestFocus();
                }
            }
        }, attempt == 0 ? 120 : 80);
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText(getString(R.string.channels_error) + "\n" + message);
        counter.setText("");
        demoHint.setVisibility(View.GONE);
        adapter.setItems(null);
    }

    private void openChannel(Channel channel) {
        if (channel.url == null || channel.url.trim().isEmpty()) {
            Toast.makeText(this, R.string.player_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, channel.url);
        intent.putExtra(PlayerActivity.EXTRA_NAME, channel.name);
        startActivity(intent);
    }

    /** При входе открываем последний просмотренный канал, иначе — первый в списке. */
    private void maybeAutoStart() {
        if (!autoStartPending) {
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        autoStartPending = false;

        Channel target = null;
        String lastUrl = Settings.getLastChannelUrl(this);
        String lastName = Settings.getLastChannelName(this);
        if (!lastUrl.isEmpty()) {
            for (Channel channel : items) {
                if (!lastUrl.equals(channel.url)) {
                    continue;
                }
                if (target == null) {
                    target = channel;
                }
                if (!lastName.isEmpty() && lastName.equals(channel.name)) {
                    target = channel;
                    break;
                }
            }
        }
        if (target == null) {
            target = items.get(0);
        }
        openChannel(target);
    }

    /** Последний канал из памяти — открываем его даже без загруженного плейлиста. */
    private void maybeAutoStartStored() {
        if (!autoStartPending) {
            return;
        }
        autoStartPending = false;
        String lastUrl = Settings.getLastChannelUrl(this);
        if (lastUrl.isEmpty()) {
            return;
        }
        openChannel(new Channel("", Settings.getLastChannelName(this), lastUrl, "", ""));
    }

    /** Применяет вид списка из настроек: плитки или компактные строки. */
    private void applyChannelView() {
        boolean compact = Settings.VIEW_LIST.equals(Settings.getChannelView(this));
        adapter.setCompact(compact);
        if (compact) {
            grid.setLayoutManager(new LinearLayoutManager(this));
        } else {
            grid.setLayoutManager(new GridLayoutManager(this, columns()));
        }
    }

    private int columns() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        if (widthDp >= 1600f) {
            return 6;
        }
        if (widthDp >= 1280f) {
            return 5;
        }
        if (widthDp >= 960f) {
            return 4;
        }
        if (widthDp >= 720f) {
            return 3;
        }
        if (widthDp >= 600f) {
            return 2;
        }
        return 1;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // При переходе через границу «большого экрана» нужны другие dimens — пересоздаём экран.
        if (isWideLayout() != wideLayout) {
            recreate();
            return;
        }
        // Поворот не пересоздаёт экран: просто пересчитываем сетку и сохраняем позицию.
        if (!(grid.getLayoutManager() instanceof GridLayoutManager)) {
            return;
        }
        GridLayoutManager manager = (GridLayoutManager) grid.getLayoutManager();
        if (manager != null) {
            int spanCount = columns();
            if (manager.getSpanCount() != spanCount) {
                manager.setSpanCount(spanCount);
            }
        }
    }

    private boolean isWideLayout() {
        return getResources().getConfiguration().screenWidthDp >= 960;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
