package com.newline.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.newline.tv.data.Channel;
import com.newline.tv.data.EpgProgram;
import com.newline.tv.data.PlaylistRepository;
import com.newline.tv.data.PortalClient;
import com.newline.tv.data.Settings;
import com.newline.tv.data.XmltvEpg;
import com.newline.tv.ui.ChannelAdapter;
import com.newline.tv.update.UpdateManager;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends BaseActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_NAME = "extra_name";

    private static final String TAG = "NewlineTV";

    private static final long OSD_TIMEOUT_MS = 5000L;
    private static final long VIDEO_CHECK_MS = 4000L;
    /** Сколько хранить мини-телепрограмму строки, прежде чем обновить её. */
    private static final long NOW_CACHE_TTL_MS = 10 * 60 * 1000L;

    private VLCVideoLayout videoLayout;
    private ProgressBar buffering;
    private View errorPanel;
    private TextView errorText;
    private TextView formatNotice;
    private View topBar;
    private TextView channelTitle;
    private TextView channelCounter;
    private View programRow;
    private TextView programText;
    private View btnProgram;
    private View btnPrev;
    private View btnNext;

    // Полупрозрачный список каналов поверх видео (открывается кнопкой «Назад»).
    private View channelPanel;
    private RecyclerView channelList;
    private ChannelAdapter channelAdapter;
    private boolean overlayShownOnce;
    /** Текущий вид списка (null — ещё не применяли). */
    private Boolean panelCompact;

    private String streamUrl;
    private String channelName;
    /** URL, уже сохранённый как «последний канал» — чтобы не писать в настройки повторно. */
    private String savedChannelUrl;

    private List<Channel> channels;
    private int channelIndex = -1;

    private List<EpgProgram> epg;
    private final ExecutorService epgPool = Executors.newSingleThreadExecutor();

    private GestureDetector touchGestures;

    // Мини-телепрограмма строк списка: кэш «что сейчас идёт» по каналам.
    private final ExecutorService panelEpgPool = Executors.newFixedThreadPool(3);
    private final Map<String, String> nowCache = new HashMap<>();
    private final Map<String, Long> nowCachedAt = new HashMap<>();
    private final Set<String> nowLoading = new HashSet<String>();

    private final Handler osdHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            topBar.setVisibility(View.GONE);
        }
    };

    // libVLC: встроенный движок с программными декодерами (MPEG-2, MPEG-4, H.264/HEVC и др.).
    private LibVLC libVLC;
    private MediaPlayer vlcPlayer;
    private boolean videoOutputStarted;
    private boolean wantPlay = true;

    // Авто-восстановление потока: следим за прогрессом и переподключаемся при обрыве.
    private static final long WATCHDOG_MS = 4000L;
    private static final long STALL_MS = 12000L;
    private static final long HEALTHY_MS = 15000L;
    private static final long BACKOFF_MAX_MS = 8000L;
    private long lastPos = -1;
    private long lastPosAt = 0;
    private long healthyFrom = 0;
    private int reconnect;
    private boolean recovering;

    /** Переподключение к каналу, если поток оборвался (HLS/видео зависло). */
    private final Runnable recoverTask = new Runnable() {
        @Override
        public void run() {
            recovering = false;
            if (wantPlay && streamUrl != null && vlcPlayer != null) {
                startStream();
            }
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        // После возврата из другого экрана видеоповерхность новая и «старая» уже не работает.
        if (vlcPlayer == null && streamUrl != null && !streamUrl.trim().isEmpty()) {
            initPlayer();
            prepare();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Уходя с экрана, полностью отпускаем медиаплеер: его поверхность всё равно разрушается.
        osdHandler.removeCallbacks(videoCheck);
        osdHandler.removeCallbacks(watchdog);
        osdHandler.removeCallbacks(recoverTask);
        if (vlcPlayer != null) {
            vlcPlayer.setEventListener(null);
            vlcPlayer.stop();
            vlcPlayer.detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }
    }

    /** Watchdog: если позиция воспроизведения не двигается — поток завис, переподключаемся. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            osdHandler.postDelayed(this, WATCHDOG_MS);
            if (vlcPlayer == null || !wantPlay || recovering) {
                return;
            }

            long now = System.currentTimeMillis();
            long pos = vlcPlayer.getTime();
            if (pos >= 0 && pos != lastPos) {
                lastPos = pos;
                lastPosAt = now;
                if (healthyFrom == 0) {
                    healthyFrom = now;
                }
            } else {
                healthyFrom = 0;
                if (lastPosAt == 0) {
                    lastPosAt = now;
                }
            }

            if (healthyFrom > 0 && now - healthyFrom > HEALTHY_MS && reconnect != 0) {
                // Канал стабильно играет — сбрасываем счётчик переподключений.
                reconnect = 0;
            }

            if (lastPosAt > 0 && now - lastPosAt > STALL_MS) {
                Log.w(TAG, "watchdog: нет прогресса " + (now - lastPosAt) + " мс на " + channelName);
                lastPos = -1;
                lastPosAt = 0;
                recover();
            }
        }
    };

    /** Если видеовыход так и не появился — показываем пояснение вместо «чёрного экрана». */
    private final Runnable videoCheck = new Runnable() {
        @Override
        public void run() {
            if (vlcPlayer == null || !wantPlay) {
                return;
            }
            if (errorPanel.getVisibility() == View.VISIBLE) {
                return;
            }
            if (!videoOutputStarted) {
                Log.d(TAG, "no video output on " + channelName
                        + ", videoTracks=" + vlcPlayer.getVideoTracksCount());
                formatNotice.setText(R.string.player_no_video_stream);
                formatNotice.setVisibility(View.VISIBLE);
            }
        }
    };

    private final MediaPlayer.EventListener vlcListener = new MediaPlayer.EventListener() {
        @Override
        public void onEvent(MediaPlayer.Event event) {
            onVlcEvent(event);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        videoLayout = findViewById(R.id.videoLayout);
        buffering = findViewById(R.id.buffering);
        errorPanel = findViewById(R.id.errorPanel);
        errorText = findViewById(R.id.errorText);
        formatNotice = findViewById(R.id.formatNotice);
        topBar = findViewById(R.id.topBar);
        channelTitle = findViewById(R.id.channelTitle);
        channelCounter = findViewById(R.id.channelCounter);
        programRow = findViewById(R.id.programRow);
        programText = findViewById(R.id.programText);
        btnProgram = findViewById(R.id.btnProgram);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);

        channelPanel = findViewById(R.id.channelPanel);
        channelList = findViewById(R.id.channelList);
        channelAdapter = new ChannelAdapter(new ChannelAdapter.Listener() {
            @Override
            public void onChannelClick(Channel channel) {
                selectChannelFromPanel(channel);
            }
        });
        channelAdapter.setNowListener(new ChannelAdapter.NowListener() {
            @Override
            public void onChannelVisible(Channel channel) {
                loadChannelNow(channel);
            }
        });
        channelList.setAdapter(channelAdapter);
        applyChannelView();

        // Сенсорное управление видео:
        //  - один тап — информация о канале и что идёт;
        //  - свайп слева направо — список каналов;
        //  - свайп справа налево — телепрограмма (EPG).
        touchGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (channelPanel.getVisibility() != View.VISIBLE) {
                    showOsd();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || channelPanel.getVisibility() == View.VISIBLE) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float minDistance = 90f * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) < minDistance || Math.abs(dx) <= Math.abs(dy)) {
                    return false;
                }
                if (dx < 0) {
                    // Свайп справа налево — телепрограмма канала.
                    showProgramDialog();
                } else {
                    // Свайп слева направо — список каналов.
                    if (channels != null && !channels.isEmpty()) {
                        showChannelPanel();
                    }
                }
                return true;
            }
        });
        videoLayout.setClickable(true);
        videoLayout.setFocusable(false);
        videoLayout.setFocusableInTouchMode(false);
        videoLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return touchGestures.onTouchEvent(event);
            }
        });

        findViewById(R.id.btnPanelSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PlayerActivity.this, SettingsActivity.class));
            }
        });

        streamUrl = getIntent().getStringExtra(EXTRA_URL);
        channelName = getIntent().getStringExtra(EXTRA_NAME);

        loadChannelList();
        loadEpg();
        showOsd();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        btnProgram.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgramDialog();
            }
        });
        programRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProgramDialog();
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchChannel(-1);
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchChannel(1);
            }
        });

        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                errorPanel.setVisibility(View.GONE);
                prepare();
            }
        });

        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            buffering.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
            errorText.setText(R.string.player_no_url);
            return;
        }

        initPlayer();
        prepare();
        osdHandler.postDelayed(watchdog, WATCHDOG_MS);
    }

    /** Список каналов берём из общего кэша, чтобы листать их прямо в плеере. */
    private void loadChannelList() {
        List<Channel> cached = PlaylistRepository.getCached(Settings.getSourceKey(this));
        if (cached == null || cached.isEmpty()) {
            updateChannelUi();
            return;
        }

        channels = cached;
        channelIndex = 0;
        boolean found = false;
        int urlIndex = -1;
        for (int i = 0; i < cached.size(); i++) {
            Channel channel = cached.get(i);
            if (!channel.url.equals(streamUrl)) {
                continue;
            }
            if (urlIndex < 0) {
                urlIndex = i;
            }
            if (channel.name.equals(channelName)) {
                channelIndex = i;
                urlIndex = -1;
                found = true;
                break;
            }
        }
        if (urlIndex >= 0) {
            channelIndex = urlIndex;
            found = true;
        }
        if (!found) {
            // Канал не из текущего списка — показываем его без листания.
            channels = null;
            updateChannelUi();
            return;
        }

        Channel current = channels.get(channelIndex);
        streamUrl = current.url;
        channelName = current.name;
        updateChannelUi();
    }

    /** Инфопанель канала: показываем и прячем через 5 секунд без действий. */
    private void showOsd() {
        if (topBar == null) {
            return;
        }
        topBar.setVisibility(View.VISIBLE);
        osdHandler.removeCallbacks(hideOsd);
        osdHandler.postDelayed(hideOsd, OSD_TIMEOUT_MS);
    }

    private void updateChannelUi() {
        channelTitle.setText(channelName == null ? "" : channelName);

        boolean many = channels != null && channels.size() > 1;
        int visibility = many ? View.VISIBLE : View.GONE;
        channelCounter.setVisibility(visibility);
        btnPrev.setVisibility(visibility);
        btnNext.setVisibility(visibility);
        if (many) {
            channelCounter.setText((channelIndex + 1) + " / " + channels.size());
        }
    }

    /** Телепрограмма канала: портал отдаёт её по ID, M3U — из внешнего XMLTV. */
    private void loadEpg() {
        final int index = channelIndex;
        final List<Channel> channelList = channels;

        epg = null;
        programRow.setVisibility(View.GONE);

        if (channelList == null || index < 0 || index >= channelList.size()) {
            return;
        }
        final Channel channel = channelList.get(index);
        final boolean usePortal = Settings.isPortalSource(this) && channel.epgId > 0;

        programText.setText(R.string.player_epg_loading);
        programRow.setVisibility(View.VISIBLE);

        epgPool.execute(new Runnable() {
            @Override
            public void run() {
                List<EpgProgram> loaded = null;
                if (usePortal) {
                    try {
                        loaded = PortalClient.loadEpg(
                                Settings.getPortalDomain(PlayerActivity.this), channel.epgId);
                    } catch (IOException ignored) {
                    }
                }
                if (loaded == null || loaded.isEmpty()) {
                    loaded = XmltvEpg.loadFor(PlayerActivity.this, channelList, channel);
                }
                postEpg(index, loaded);
            }
        });
    }

    private void postEpg(final int index, final List<EpgProgram> loaded) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (channelIndex != index || isFinishing() || isDestroyed()) {
                    return;
                }
                epg = loaded;
                updateProgramUi();
            }
        });
    }

    private void updateProgramUi() {
        if (epg == null || epg.isEmpty()) {
            programRow.setVisibility(View.GONE);
            return;
        }

        EpgProgram current = currentProgram(epg);
        if (current == null) {
            programRow.setVisibility(View.GONE);
            return;
        }
        int currentIndex = epg.indexOf(current);

        StringBuilder text = new StringBuilder(
                getString(R.string.player_epg_now, current.title, current.timeRange()));

        if (currentIndex + 1 < epg.size()) {
            EpgProgram next = epg.get(currentIndex + 1);
            text.append('\n')
                    .append(getString(R.string.player_epg_next, next.title, next.timeRange()));
        }

        programText.setText(text.toString());
        programRow.setVisibility(View.VISIBLE);
    }

    /** Текущая передача (или ближайшая следующая), если в списке есть подходящая. */
    private static EpgProgram currentProgram(List<EpgProgram> programs) {
        if (programs == null || programs.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (EpgProgram program : programs) {
            if (program.isCurrent(now)) {
                return program;
            }
        }
        for (EpgProgram program : programs) {
            if (program.start > now) {
                return program;
            }
        }
        return null;
    }

    /**
     * Ленивая загрузка мини-телепрограммы для строки списка: не больше трёх
     * одновременных запросов, результат кэшируется и обновляется раз в 10 минут.
     */
    private void loadChannelNow(final Channel channel) {
        if (channel == null) {
            return;
        }
        final String key = nowKey(channel);
        String cached = nowCache.get(key);
        Long cachedAt = nowCachedAt.get(key);
        if (cached != null && cachedAt != null
                && System.currentTimeMillis() - cachedAt < NOW_CACHE_TTL_MS) {
            return;
        }
        if (nowLoading.contains(key)) {
            return;
        }

        final List<Channel> channelList = channels;
        final boolean usePortal = Settings.isPortalSource(this) && channel.epgId > 0;
        nowLoading.add(key);
        panelEpgPool.execute(new Runnable() {
            @Override
            public void run() {
                EpgProgram current = null;
                if (usePortal) {
                    try {
                        current = currentProgram(PortalClient.loadEpg(
                                Settings.getPortalDomain(PlayerActivity.this), channel.epgId));
                    } catch (IOException ignored) {
                    }
                }
                if (current == null) {
                    current = currentProgram(
                            XmltvEpg.loadFor(PlayerActivity.this, channelList, channel));
                }
                final EpgProgram result = current;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        nowLoading.remove(key);
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        String text = result == null ? ""
                                : getString(R.string.player_epg_now,
                                result.title, result.timeRange());
                        long loadedAt = System.currentTimeMillis();
                        // Один канал может быть в списке несколько раз (SD/HD, (+2)).
                        if (channelList != null) {
                            for (Channel item : channelList) {
                                if (!sameEpgChannel(item, channel)) {
                                    continue;
                                }
                                String itemKey = nowKey(item);
                                nowCache.put(itemKey, text);
                                nowCachedAt.put(itemKey, loadedAt);
                                if (channelAdapter != null) {
                                    channelAdapter.setNow(item, text);
                                }
                            }
                        } else {
                            nowCache.put(key, text);
                            nowCachedAt.put(key, loadedAt);
                            if (channelAdapter != null) {
                                channelAdapter.setNow(channel, text);
                            }
                        }
                    }
                });
            }
        });
    }

    /** Каналы с общей телепрограммой: тот же channel_id или то же название. */
    private static boolean sameEpgChannel(Channel a, Channel b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.epgId > 0 && b.epgId > 0) {
            return a.epgId == b.epgId;
        }
        return XmltvEpg.normalizeName(a.name).equals(XmltvEpg.normalizeName(b.name));
    }

    private static String nowKey(Channel channel) {
        return channel.url + "\n" + channel.name;
    }

    private void showProgramDialog() {
        if (epg == null) {
            // Программа ещё загружается: портал отвечает или качается внешний XMLTV.
            new AlertDialog.Builder(this)
                    .setMessage(R.string.player_epg_loading)
                    .setPositiveButton(R.string.settings_close, null)
                    .show();
            return;
        }
        if (epg.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.player_epg_none)
                    .setPositiveButton(R.string.settings_close, null)
                    .show();
            return;
        }

        long now = System.currentTimeMillis();
        String[] items = new String[epg.size()];
        for (int i = 0; i < epg.size(); i++) {
            EpgProgram program = epg.get(i);
            String prefix = program.isCurrent(now) ? getString(R.string.player_epg_marker) : "";
            items[i] = prefix + program.timeRange() + "   " + program.title;
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.player_epg_dialog_title,
                        channelName == null ? "" : channelName))
                .setItems(items, null)
                .setPositiveButton(R.string.settings_close, null)
                .show();
    }

    private void switchChannel(int delta) {
        if (channels == null || channels.size() < 2) {
            return;
        }
        overlayShownOnce = false;

        int next = channelIndex + delta;
        if (next < 0) {
            next = channels.size() - 1;
        } else if (next >= channels.size()) {
            next = 0;
        }

        channelIndex = next;
        Channel channel = channels.get(next);
        streamUrl = channel.url;
        channelName = channel.name;

        updateChannelUi();
        errorPanel.setVisibility(View.GONE);
        loadEpg();
        showOsd();

        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            buffering.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
            errorText.setText(R.string.player_no_url);
            return;
        }

        if (vlcPlayer == null) {
            initPlayer();
        }
        prepare();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();

        // Пока открыт список каналов — клавиши работают с ним, видео продолжает идти.
        if (channelPanel.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) {
                hideChannelPanel();
                overlayShownOnce = true;
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // «Назад»: открыть список каналов; повторно — закрыть; затем выйти из приложения.
            if (code == KeyEvent.KEYCODE_BACK) {
                boolean panelEnabled = Settings.isPanelEnabled(this);
                if (!panelEnabled || overlayShownOnce || channels == null || channels.isEmpty()) {
                    finishAffinity();
                    return true;
                }
                showChannelPanel();
                return true;
            }

            // Кнопка «Меню» на пульте открывает тот же список каналов.
            if (code == KeyEvent.KEYCODE_MENU) {
                if (channelPanel.getVisibility() != View.VISIBLE) {
                    showChannelPanel();
                }
                return true;
            }

            // Любая клавиша снова показывает инфопанель канала.
            showOsd();

            // Кнопки каналов на пульте.
            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT
                    || code == KeyEvent.KEYCODE_PAGE_UP) {
                switchChannel(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    || code == KeyEvent.KEYCODE_PAGE_DOWN) {
                switchChannel(-1);
                return true;
            }

            // Влево/вправо — переключение каналов, как на приставке.
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchChannel(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchChannel(-1);
                return true;
            }

            // Вверх/вниз — переключение каналов.
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                switchChannel(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                switchChannel(-1);
                return true;
            }

            // ОК — информация о канале: что идёт сейчас и дальше (телепрограмма).
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                showProgramDialog();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int panelColumns() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        if (widthDp >= 1280f) {
            return 4;
        }
        if (widthDp >= 960f) {
            return 3;
        }
        if (widthDp >= 600f) {
            return 2;
        }
        return 1;
    }

    /** Показывает полупрозрачный список каналов поверх видео (видео не останавливается). */
    private void showChannelPanel() {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        applyChannelView();
        applyPanelScrim();
        topBar.setVisibility(View.GONE);
        osdHandler.removeCallbacks(hideOsd);
        channelAdapter.setItems(channels);
        channelPanel.setVisibility(View.VISIBLE);
        channelList.requestFocus();
        focusCurrentChannel();
    }

    /** Ставит фокус на текущий канал и прокручивает список к нему. */
    private void focusCurrentChannel() {
        focusCurrentChannel(0);
    }

    private void focusCurrentChannel(final int attempt) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        final int position = channelIndex < 0
                ? 0
                : Math.min(channelIndex, channels.size() - 1);
        channelList.scrollToPosition(position);
        channelList.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (channelPanel.getVisibility() != View.VISIBLE) {
                    return;
                }
                RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(position);
                if (holder != null) {
                    holder.itemView.requestFocus();
                } else if (attempt < 5) {
                    // После смены LayoutManager список может ещё не успеть разложиться.
                    focusCurrentChannel(attempt + 1);
                } else {
                    channelList.requestFocus();
                }
            }
        }, attempt == 0 ? 0 : 80);
    }

    /**
     * Применяет выбранный в настройках вид списка: плитки или компактные строки.
     * Вызывается при старте и при возврате из настроек.
     */
    private void applyChannelView() {
        boolean compact = Settings.VIEW_LIST.equals(Settings.getChannelView(this));
        if (panelCompact != null && panelCompact == compact) {
            return;
        }
        panelCompact = compact;
        if (channelAdapter != null) {
            channelAdapter.setCompact(compact);
        }
        if (channelList == null) {
            return;
        }
        if (compact) {
            channelList.setLayoutManager(new LinearLayoutManager(this));
        } else {
            channelList.setLayoutManager(new GridLayoutManager(this, panelColumns()));
        }
        if (channelPanel.getVisibility() == View.VISIBLE) {
            focusCurrentChannel();
        }
    }

    private void hideChannelPanel() {
        channelPanel.setVisibility(View.GONE);
    }

    /** Применяет выбранную в настройках прозрачность фона списка каналов. */
    private void applyPanelScrim() {
        int rgb = ContextCompat.getColor(this, R.color.player_scrim) & 0x00FFFFFF;
        int transparency = Settings.getPanelTransparency(this);
        int alpha = Math.round(255f * (100 - transparency) / 100f);
        channelPanel.setBackgroundColor((alpha << 24) | rgb);
        // Плитки гаснут вместе с фоном: видео просвечивает и сквозь них.
        if (channelAdapter != null) {
            channelAdapter.setOverlayAlpha(alpha);
        }
    }

    /** Выбор канала из списка: переключаем поток и закрываем список. */
    private void selectChannelFromPanel(Channel channel) {
        hideChannelPanel();
        overlayShownOnce = false;
        if (channels == null || channel == null) {
            return;
        }

        int index = -1;
        int urlIndex = -1;
        for (int i = 0; i < channels.size(); i++) {
            Channel item = channels.get(i);
            if (!item.url.equals(channel.url)) {
                continue;
            }
            if (urlIndex < 0) {
                urlIndex = i;
            }
            if (item.name.equals(channel.name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            index = urlIndex;
        }
        if (index < 0) {
            return;
        }
        if (index == channelIndex && channel.url.equals(streamUrl)) {
            return;
        }

        channelIndex = index;
        Channel selected = channels.get(index);
        streamUrl = selected.url;
        channelName = selected.name;

        updateChannelUi();
        errorPanel.setVisibility(View.GONE);
        loadEpg();
        showOsd();

        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            buffering.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
            errorText.setText(R.string.player_no_url);
            return;
        }

        if (vlcPlayer == null) {
            initPlayer();
        }
        prepare();
    }

    private void initPlayer() {
        if (libVLC == null) {
            ArrayList<String> args = new ArrayList<>();
            args.add("--audio-time-stretch");
            args.add("--network-caching=1000");
            args.add("--live-caching=1000");
            libVLC = new LibVLC(this, args);
        }
        vlcPlayer = new MediaPlayer(libVLC);
        vlcPlayer.setEventListener(vlcListener);
        vlcPlayer.attachViews(videoLayout, null, false, false);
        vlcPlayer.setVolume(100);
    }

    /** Старт канала: полный сброс состояния восстановления + запуск потока. */
    private void prepare() {
        reconnect = 0;
        recovering = false;
        osdHandler.removeCallbacks(recoverTask);
        osdHandler.removeCallbacks(videoCheck);
        lastPos = -1;
        lastPosAt = 0;
        healthyFrom = 0;
        videoOutputStarted = false;
        wantPlay = true;
        if (formatNotice != null) {
            formatNotice.setVisibility(View.GONE);
        }
        startStream();
    }

    /** Создаёт поток заново (используется и при старте, и при переподключении). */
    private void startStream() {
        if (vlcPlayer == null || streamUrl == null) {
            return;
        }
        // Запоминаем канал, чтобы при следующем входе открыть его сразу (пишем только при смене).
        if (!streamUrl.equals(savedChannelUrl)) {
            savedChannelUrl = streamUrl;
            Settings.setLastChannel(this, streamUrl, channelName);
        }
        lastPos = -1;
        lastPosAt = 0;
        videoOutputStarted = false;
        buffering.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);

        Media media = new Media(libVLC, Uri.parse(streamUrl));
        // Аппаратный декодер включаем, но разрешаем автоматический откат к программному —
        // именно так воспроизводятся каналы в MPEG-2, которые не умеет устройство.
        media.setHWDecoderEnabled(true, false);
        // Буфер и авто-переподключение HTTP: сглаживает обрывы и «залипание» живого потока.
        media.addOption(":network-caching=1000");
        media.addOption(":live-caching=1000");
        media.addOption(":http-reconnect=true");
        // clock-jitter/clock-synchro НЕ отключаем: VLC сам синхронизирует звук и видео.

        // Полный сброс предыдущего потока, иначе после обрыва VLC может остаться в «залипшем» состоянии.
        vlcPlayer.stop();
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
    }

    /** Переподключение к текущему каналу. Пробуем бесконечно, пауза растёт до 8 секунд. */
    private void recover() {
        if (recovering) {
            return;
        }
        recovering = true;
        reconnect++;
        long delay = Math.min(1000L * reconnect, BACKOFF_MAX_MS);
        Log.i(TAG, "reconnect #" + reconnect + " для " + channelName + " через " + delay + " мс");

        // Пока идёт восстановление, показываем статус вместо «мёртвой» панели ошибки.
        errorPanel.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
        formatNotice.setText(reconnect > 10 ? R.string.player_stream_lost : R.string.player_reconnecting);
        formatNotice.setVisibility(View.VISIBLE);

        osdHandler.removeCallbacks(recoverTask);
        osdHandler.postDelayed(recoverTask, delay);
    }

    private void onVlcEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                videoOutputStarted = false;
                errorPanel.setVisibility(View.GONE);
                buffering.setVisibility(View.VISIBLE);
                break;
            case MediaPlayer.Event.Buffering:
                buffering.setVisibility(event.getBuffering() < 100f ? View.VISIBLE : View.GONE);
                break;
            case MediaPlayer.Event.Playing:
                buffering.setVisibility(View.GONE);
                errorPanel.setVisibility(View.GONE);
                osdHandler.removeCallbacks(videoCheck);
                osdHandler.postDelayed(videoCheck, VIDEO_CHECK_MS);
                break;
            case MediaPlayer.Event.Vout:
                if (event.getVoutCount() > 0) {
                    videoOutputStarted = true;
                    if (!recovering) {
                        formatNotice.setVisibility(View.GONE);
                    }
                }
                break;
            case MediaPlayer.Event.EndReached:
                // Для «живого» канала конец потока означает обрыв — переподключаемся.
                Log.w(TAG, "поток завершился, переподключение: " + channelName);
                recover();
                break;
            case MediaPlayer.Event.EncounteredError:
                buffering.setVisibility(View.GONE);
                Log.e(TAG, "VLC error on " + channelName + " (" + streamUrl + ")");
                osdHandler.removeCallbacks(videoCheck);
                recover();
                break;
            default:
                break;
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        applyPanelScrim();
        applyChannelView();
        wantPlay = true;
        osdHandler.removeCallbacks(watchdog);
        osdHandler.postDelayed(watchdog, WATCHDOG_MS);
        if (vlcPlayer != null && !vlcPlayer.isPlaying()) {
            vlcPlayer.play();
        }
        // Автопроверка новых релизов на GitHub (не чаще раза в 6 часов).
        UpdateManager.checkForUpdates(this, false);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // После поворота система снова показывает панели — прячем их.
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        wantPlay = false;
        osdHandler.removeCallbacks(videoCheck);
        osdHandler.removeCallbacks(watchdog);
        osdHandler.removeCallbacks(recoverTask);
        if (vlcPlayer != null && vlcPlayer.isPlaying()) {
            vlcPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        epgPool.shutdownNow();
        panelEpgPool.shutdownNow();
        osdHandler.removeCallbacks(hideOsd);
        osdHandler.removeCallbacks(videoCheck);
        osdHandler.removeCallbacks(watchdog);
        osdHandler.removeCallbacks(recoverTask);
        if (vlcPlayer != null) {
            vlcPlayer.setEventListener(null);
            vlcPlayer.stop();
            vlcPlayer.detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        super.onDestroy();
    }
}
