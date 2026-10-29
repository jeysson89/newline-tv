package com.newline.tv;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    /** Масштаб текста, с которым создан экран: при изменении пересоздаём активность. */
    private float appliedFontScale;

    private List<EpgProgram> epg;
    /** Открытая телепрограмма: пока EPG грузится, диалог показывает ожидание. */
    private Dialog epgDialog;
    private RecyclerView epgDialogList;
    private TextView epgDialogTitle;
    private TextView epgDialogEmpty;
    private int epgDialogChannel = -1;
    private final ExecutorService epgPool = Executors.newSingleThreadExecutor();
    private final Handler epgPollHandler = new Handler(Looper.getMainLooper());

    /** Позиция выбора в списке каналов: навигация пультом не зависит от фокуса Android. */
    private int panelIndex = -1;
    /** true — пульт сейчас на кнопке настроек в шапке списка каналов. */
    private boolean panelHeaderFocus;

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

    // Stop/play у libVLC блокируют поток, пока VLC закрывает сетевой поток. На медленном
    // канале это занимало больше 8 секунд, и при быстром переключении пультом система
    // показывала «Приложение не отвечает». Поэтому все блокирующие вызовы плеера идут
    // в отдельном потоке, строго по очереди.
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor();
    private volatile int streamSeq;
    private volatile boolean streamStarting;

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
        dismissEpgDialog();
        // Уходя с экрана, полностью отпускаем медиаплеер: его поверхность всё равно разрушается.
        osdHandler.removeCallbacks(videoCheck);
        osdHandler.removeCallbacks(watchdog);
        osdHandler.removeCallbacks(recoverTask);
        releasePlayer();
    }

    /**
     * Полный останов плеера. detachViews() трогает SurfaceView, поэтому идёт на главном
     * потоке, а блокирующие stop()/release() — в потоке streamExecutor.
     */
    private void releasePlayer() {
        final MediaPlayer player = vlcPlayer;
        if (player == null) {
            return;
        }
        vlcPlayer = null;
        // Отменяем ещё не выполненные запуски потока.
        streamSeq++;
        player.setEventListener(null);
        try {
            player.detachViews();
        } catch (RuntimeException e) {
            Log.w(TAG, "detachViews: " + e);
        }
        try {
            streamExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        player.stop();
                    } catch (RuntimeException e) {
                        Log.w(TAG, "stop: " + e);
                    }
                    player.release();
                }
            });
        } catch (RuntimeException e) {
            // Экран уже уничтожен и очередь закрыта — плеер освободим здесь.
            Log.w(TAG, "releasePlayer: " + e);
            player.release();
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
        appliedFontScale = Settings.getFontScale(this);
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
        //  - свайп справа налево — телепрограмма (EPG);
        //  - свайп вверх/вниз — следующий/предыдущий канал, как на пульте.
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
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);

                // Вертикальный свайп повторяет вверх/вниз на пульте:
                // вверх — следующий канал, вниз — предыдущий.
                if (absDy >= minDistance && absDy > absDx) {
                    switchChannel(dy < 0 ? 1 : -1);
                    return true;
                }

                if (absDx < minDistance || absDx <= absDy) {
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
        if (!Settings.isPortalSource(this)) {
            // Телепрограмма грузится заранее: к открытию диалога она уже готова.
            XmltvEpg.warmUp(this, channels);
        }
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
                refreshEpgDialog();
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
        if (epgDialog != null && epgDialog.isShowing()) {
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_epg);
        dialog.setCanceledOnTouchOutside(true);

        epgDialog = dialog;
        epgDialogChannel = channelIndex;
        epgDialogTitle = dialog.findViewById(R.id.epgDialogTitle);
        epgDialogList = dialog.findViewById(R.id.epgList);
        epgDialogEmpty = dialog.findViewById(R.id.epgEmpty);
        TextView close = dialog.findViewById(R.id.epgClose);

        // Шрифт телепрограммы настраивается отдельным ползунком и не зависит
        // от общего масштаба текста: задаём размеры в px.
        float epgScale = Settings.getEpgFontScale(this);
        float density = getResources().getDisplayMetrics().density;
        epgDialogTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * density * epgScale);
        epgDialogEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * density * epgScale);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * density * epgScale);

        epgDialogList.setLayoutManager(new LinearLayoutManager(this));
        epgDialogList.setHasFixedSize(true);

        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        // Пульт: вверх/вниз листают программу, ОК и «Назад» закрывают.
        // Без этого диалог управлялся только мышью.
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (!isEpgDialogKey(keyCode)) {
                    return false;
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return true;
                }
                int rowHeight = Math.round(
                        56f * getResources().getDisplayMetrics().density);
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        scrollEpgBy(-rowHeight);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        scrollEpgBy(rowHeight);
                        return true;
                    case KeyEvent.KEYCODE_PAGE_UP:
                        scrollEpgBy(-rowHeight * 5);
                        return true;
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                        scrollEpgBy(rowHeight * 5);
                        return true;
                    default:
                        dialog.dismiss();
                        return true;
                }
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                epgPollHandler.removeCallbacks(epgPoll);
                if (epgDialog == dialog) {
                    epgDialog = null;
                    epgDialogList = null;
                    epgDialogTitle = null;
                    epgDialogEmpty = null;
                    epgDialogChannel = -1;
                }
            }
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = Math.min((int) (metrics.widthPixels * 0.92f),
                    (int) (640f * metrics.density));
            int height = (int) (metrics.heightPixels * 0.82f);
            // Окно делаем непрозрачным: иначе SurfaceView с видео просвечивает
            // сквозь диалог и на части телевизоров картинка мерцает.
            window.setFormat(PixelFormat.OPAQUE);
            window.setBackgroundDrawable(new ColorDrawable(0xFF0B0D10));
            window.setLayout(width, height);
            window.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.65f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        renderEpgDialog();

        // Пока данных нет, диалог сам подхватит их, как только разбор
        // нужного канала закончится, — без повторного открытия.
        epgPollHandler.removeCallbacks(epgPoll);
        if (epg == null) {
            epgPollHandler.post(epgPoll);
        }
    }

    /** Клавиши, которые в диалоге телепрограммы обрабатываем сами. */
    private static boolean isEpgDialogKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    private void scrollEpgBy(int dy) {
        if (epgDialogList != null) {
            epgDialogList.scrollBy(0, dy);
        }
    }

    /** Ждёт готовности телепрограммы, пока открыт диалог. */
    private final Runnable epgPoll = new Runnable() {
        @Override
        public void run() {
            if (epgDialog == null || !epgDialog.isShowing()) {
                return;
            }
            if (epg == null && channels != null
                    && channelIndex >= 0 && channelIndex < channels.size()) {
                List<EpgProgram> ready = XmltvEpg.peek(channels.get(channelIndex));
                if (!ready.isEmpty()) {
                    epg = ready;
                    updateProgramUi();
                    renderEpgDialog();
                    return;
                }
            }
            if (epg == null) {
                epgPollHandler.postDelayed(this, 500);
            }
        }
    };

    /** Закрыть открытую телепрограмму (например, при уходе с экрана). */
    private void dismissEpgDialog() {
        if (epgDialog != null) {
            epgDialog.dismiss();
        }
    }

    /** Обновить открытую телепрограмму: данные могли только что загрузиться. */
    private void refreshEpgDialog() {
        if (epgDialog == null || !epgDialog.isShowing()
                || epgDialogChannel != channelIndex) {
            return;
        }
        renderEpgDialog();
    }

    private void renderEpgDialog() {
        if (epgDialog == null || epgDialogList == null
                || epgDialogTitle == null || epgDialogEmpty == null) {
            return;
        }

        epgDialogTitle.setText(getString(R.string.player_epg_dialog_title,
                channelName == null ? "" : channelName));

        List<EpgProgram> programs = epg;
        if (programs == null) {
            epgDialogList.setVisibility(View.GONE);
            epgDialogEmpty.setVisibility(View.VISIBLE);
            epgDialogEmpty.setText(R.string.player_epg_loading);
            return;
        }
        if (programs.isEmpty()) {
            epgDialogList.setVisibility(View.GONE);
            epgDialogEmpty.setVisibility(View.VISIBLE);
            epgDialogEmpty.setText(R.string.player_epg_none);
            return;
        }

        epgDialogEmpty.setVisibility(View.GONE);
        epgDialogList.setVisibility(View.VISIBLE);

        long now = System.currentTimeMillis();
        int currentIndex = -1;
        for (int i = 0; i < programs.size(); i++) {
            if (programs.get(i).isCurrent(now)) {
                currentIndex = i;
                break;
            }
        }

        float epgScale = Settings.getEpgFontScale(this);
        epgDialogList.setAdapter(new EpgAdapter(programs, currentIndex, epgScale));
        scrollEpgTo(currentIndex);
    }

    /** Прокрутить список так, чтобы текущая передача была первой видимой. */
    private void scrollEpgTo(final int position) {
        if (position < 0 || epgDialogList == null) {
            return;
        }
        final RecyclerView list = epgDialogList;
        list.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.LayoutManager manager = list.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) manager)
                            .scrollToPositionWithOffset(position, 0);
                } else {
                    list.scrollToPosition(position);
                }
            }
        });
    }

    /** Строка телепрограммы: время и название передачи. */
    private static final class EpgAdapter extends RecyclerView.Adapter<EpgAdapter.Holder> {

        private final List<EpgProgram> programs;
        private final int currentIndex;
        private final float textScale;

        EpgAdapter(List<EpgProgram> programs, int currentIndex, float textScale) {
            this.programs = programs;
            this.currentIndex = currentIndex;
            this.textScale = textScale;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_epg, parent, false);
            Holder holder = new Holder(view);
            float density = parent.getResources().getDisplayMetrics().density;
            holder.marker.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * density * textScale);
            holder.time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * density * textScale);
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * density * textScale);
            return holder;
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            EpgProgram program = programs.get(position);
            boolean current = position == currentIndex;
            holder.time.setText(program.timeRange());
            holder.name.setText(program.title);
            holder.marker.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setBackgroundResource(
                    current ? R.drawable.bg_epg_current : android.R.color.transparent);
        }

        @Override
        public int getItemCount() {
            return programs.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView marker;
            final TextView time;
            final TextView name;

            Holder(View itemView) {
                super(itemView);
                marker = itemView.findViewById(R.id.epgMarker);
                time = itemView.findViewById(R.id.epgTime);
                name = itemView.findViewById(R.id.epgName);
            }
        }
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
        panelIndex = next;
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
            return handlePanelKey(event);
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

    /**
     * Колесо мыши и аэропульт в режиме мыши присылают прокрутку, а не клавиши.
     * Без этого «вверх/вниз» на таких пультах не переключает каналы.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && channelPanel.getVisibility() != View.VISIBLE) {
            float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (scroll < -0.1f) {
                switchChannel(1);
                return true;
            }
            if (scroll > 0.1f) {
                switchChannel(-1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private int panelColumns() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        if (Settings.VIEW_LIST.equals(Settings.getChannelView(this))) {
            return 1;
        }
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

    /**
     * Клавиши пульта в открытом списке каналов. Навигация считается вручную:
     * на части приставок фокус не попадает в RecyclerView, и список остаётся
     * неуправляемым, хотя мышью канал выбрать можно.
     */
    private boolean handlePanelKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            // Отпускание тоже перехватываем, иначе Android начнёт свою навигацию.
            return true;
        }

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            hideChannelPanel();
            overlayShownOnce = true;
            return true;
        }

        boolean confirm = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;

        // Фокус на кнопке настроек в шапке списка: вниз — обратно к каналам.
        if (panelHeaderFocus) {
            if (confirm) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                panelHeaderFocus = false;
                if (channels != null && !channels.isEmpty()) {
                    panelIndex = Math.max(0, Math.min(panelIndex, channels.size() - 1));
                    focusPanelItem(panelIndex, 0);
                }
            }
            return true;
        }

        int columns = panelColumns();

        // С верхней строки списка «вверх» уводит на кнопку настроек.
        if (code == KeyEvent.KEYCODE_DPAD_UP && panelIndex < columns) {
            View gear = findViewById(R.id.btnPanelSettings);
            if (gear != null) {
                panelHeaderFocus = true;
                gear.requestFocus();
                return true;
            }
        }

        int step;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                step = -columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                step = columns;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (columns == 1) {
                    // В режиме списка «влево» уводит на кнопку настроек.
                    View gear = findViewById(R.id.btnPanelSettings);
                    if (gear != null) {
                        panelHeaderFocus = true;
                        gear.requestFocus();
                    }
                    return true;
                }
                step = -1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (columns == 1) {
                    return true;
                }
                step = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (channels != null && panelIndex >= 0 && panelIndex < channels.size()) {
                    selectChannelFromPanel(channels.get(panelIndex));
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }

        movePanelSelection(step);
        return true;
    }

    private void movePanelSelection(int step) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        int next = panelIndex + step;
        if (next < 0) {
            next = 0;
        } else if (next >= channels.size()) {
            next = channels.size() - 1;
        }
        if (next == panelIndex) {
            return;
        }
        panelIndex = next;
        focusPanelItem(next, 0);
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
        panelHeaderFocus = false;
        panelIndex = channelIndex < 0
                ? 0
                : Math.min(channelIndex, channels.size() - 1);
        channelList.requestFocus();
        focusPanelItem(panelIndex, 0);
    }

    /** Ставит фокус на выбранный канал и прокручивает список к нему. */
    private void focusPanelItem(final int position, final int attempt) {
        if (channels == null || channels.isEmpty() || panelHeaderFocus) {
            return;
        }
        channelList.scrollToPosition(position);
        channelList.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (channelPanel.getVisibility() != View.VISIBLE || panelHeaderFocus) {
                    return;
                }
                RecyclerView.ViewHolder holder = channelList.findViewHolderForAdapterPosition(position);
                if (holder != null) {
                    holder.itemView.requestFocus();
                } else if (attempt < 6) {
                    // После смены LayoutManager список может ещё не успеть разложиться.
                    focusPanelItem(position, attempt + 1);
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
        if (channelPanel.getVisibility() == View.VISIBLE && panelIndex >= 0) {
            focusPanelItem(panelIndex, 0);
        }
    }

    private void hideChannelPanel() {
        panelHeaderFocus = false;
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
        panelIndex = index;
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
        final MediaPlayer player = vlcPlayer;
        final LibVLC lib = libVLC;
        if (player == null || lib == null || streamUrl == null) {
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

        final String url = streamUrl;
        final int seq = ++streamSeq;
        streamStarting = true;
        try {
            streamExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Пока задача стояла в очереди, пользователь выбрал другой канал — она уже не нужна.
                        if (seq != streamSeq || player != vlcPlayer) {
                            return;
                        }
                        Media media = null;
                        try {
                            media = new Media(lib, Uri.parse(url));
                            // Аппаратный декодер включаем, но разрешаем автоматический откат к программному —
                            // именно так воспроизводятся каналы в MPEG-2, которые не умеет устройство.
                            media.setHWDecoderEnabled(true, false);
                            // Буфер и авто-переподключение HTTP: сглаживает обрывы и «залипание» живого потока.
                            media.addOption(":network-caching=1000");
                            media.addOption(":live-caching=1000");
                            media.addOption(":http-reconnect=true");
                            // clock-jitter/clock-synchro НЕ отключаем: VLC сам синхронизирует звук и видео.

                            // Полный сброс предыдущего потока, иначе после обрыва VLC может остаться в «залипшем» состоянии.
                            player.stop();
                            if (seq != streamSeq) {
                                return;
                            }
                            player.setMedia(media);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "не удалось подготовить поток: " + e);
                            return;
                        } finally {
                            if (media != null) {
                                media.release();
                            }
                        }
                        player.play();
                    } finally {
                        streamStarting = false;
                    }
                }
            });
        } catch (RuntimeException e) {
            streamStarting = false;
            Log.w(TAG, "startStream: " + e);
        }
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
        // Масштаб текста применяется пересозданием экрана с новым контекстом:
        // иначе OSD и диалог телепрограммы останутся в старом размере.
        if (Math.abs(Settings.getFontScale(this) - appliedFontScale) > 0.005f) {
            recreate();
            return;
        }
        hideSystemUi();
        applyPanelScrim();
        applyChannelView();
        wantPlay = true;
        osdHandler.removeCallbacks(watchdog);
        osdHandler.postDelayed(watchdog, WATCHDOG_MS);
        if (vlcPlayer != null && !streamStarting && !vlcPlayer.isPlaying()) {
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
        epgPollHandler.removeCallbacks(epgPoll);
        osdHandler.removeCallbacks(hideOsd);
        osdHandler.removeCallbacks(videoCheck);
        osdHandler.removeCallbacks(watchdog);
        osdHandler.removeCallbacks(recoverTask);
        releasePlayer();
        final LibVLC lib = libVLC;
        libVLC = null;
        if (lib != null) {
            // Освобождаем libVLC после задач плеера — они в одной очереди streamExecutor.
            try {
                streamExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        lib.release();
                    }
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "libVLC release: " + e);
                lib.release();
            }
        }
        streamExecutor.shutdown();
        super.onDestroy();
    }
}
