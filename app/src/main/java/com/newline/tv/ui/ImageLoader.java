package com.newline.tv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Очень простой загрузчик логотипов каналов с кэшем в памяти. */
public final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    // OkHttp надёжнее HttpURLConnection: сервер оператора иногда обрывает соединение.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    public static void load(final String url, final Callback callback) {
        if (url == null || url.trim().isEmpty()) {
            callback.onLoaded(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = download(url);
                if (bitmap != null) {
                    CACHE.put(url, bitmap);
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onLoaded(bitmap);
                    }
                });
            }
        });
    }

    private static Bitmap download(String urlString) {
        // Одна повторная попытка: сервер оператора иногда обрывает первое соединение.
        for (int attempt = 0; attempt < 2; attempt++) {
            Bitmap bitmap = downloadOnce(urlString);
            if (bitmap != null) {
                return bitmap;
            }
        }
        return null;
    }

    private static Bitmap downloadOnce(String urlString) {
        Response response = null;
        try {
            Request request = new Request.Builder()
                    .url(urlString)
                    .header("User-Agent", "NewlineTV/1.0")
                    .build();
            response = CLIENT.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;

            InputStream stream = response.body().byteStream();
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }
}
