package com.newline.tv.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.newline.tv.BuildConfig;
import com.newline.tv.R;
import com.newline.tv.data.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Проверка новых релизов на GitHub, загрузка APK и запуск системного установщика.
 *
 * Версия определяется по редиректу страницы releases/latest — это не расходует
 * лимит GitHub API (60 запросов в час на IP, что критично для абонентов за NAT).
 * Если редирект недоступен, используется API как резервный вариант.
 *
 * APK скачивается по соглашению об имени: NewlineTV-<версия>.apk в assets релиза.
 * Перед установкой проверяются имя пакета и подпись: обновление должно быть
 * подписано тем же ключом, что и установленное приложение.
 */
public final class UpdateManager {

    private static final String TAG = "NewlineTV";

    private static final String REPO_OWNER = "jeysson89";
    private static final String REPO_NAME = "newline-tv";
    private static final String RELEASES_LATEST_URL =
            "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";
    private static final String API_LATEST_URL =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    private static final String USER_AGENT = "NewlineTV-Android";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    /** Повтор после неудачной проверки (нет сети и т.п.). */
    private static final long RETRY_INTERVAL_MS = 15L * 60L * 1000L;
    private static final long MIN_APK_BYTES = 1024L * 1024L;

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile boolean checking;
    private static volatile boolean downloading;
    private static volatile boolean cancelDownload;
    private static volatile Call currentCall;

    private static final WeakReference<Activity> NO_ACTIVITY = new WeakReference<>(null);
    private static WeakReference<Activity> currentActivity = NO_ACTIVITY;
    private static Release pendingRelease;
    private static File pendingApk;
    private static AlertDialog activeDialog;

    private UpdateManager() {
    }

    /** Экран стал активным: запоминаем его для показа результата проверки. */
    public static void onActivityResumed(Activity activity) {
        if (!isAlive(activity)) {
            return;
        }
        currentActivity = new WeakReference<>(activity);
        resumePendingInstall(activity);
        maybeShowPendingRelease(activity);
    }

    /** Проверить релизы. manual=true — показать результат, даже если обновлений нет. */
    public static void checkForUpdates(final Activity activity, final boolean manual) {
        if (!isAlive(activity)) {
            return;
        }
        currentActivity = new WeakReference<>(activity);
        if (checking) {
            if (manual) {
                toast(activity, R.string.update_checking);
            }
            return;
        }

        final Context appContext = activity.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!manual) {
            long lastSuccess = Settings.getLastUpdateCheck(appContext);
            long lastAttempt = Settings.getLastUpdateAttempt(appContext);
            if (lastSuccess > 0 && now - lastSuccess < AUTO_CHECK_INTERVAL_MS) {
                return;
            }
            if (lastAttempt > 0 && now - lastAttempt < RETRY_INTERVAL_MS) {
                return;
            }
        }
        Settings.setLastUpdateAttempt(appContext, now);
        checking = true;
        if (manual) {
            toast(activity, R.string.update_checking);
        }

        pool.execute(new Runnable() {
            @Override
            public void run() {
                Release release = null;
                try {
                    release = fetchLatest();
                } catch (Exception e) {
                    Log.w(TAG, "update check failed", e);
                }
                final Release result = release;
                // Результат показываем на том экране, который активен сейчас:
                // список каналов мог уже передать управление плееру.
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        Activity target = aliveActivity();
                        if (target == null && isAlive(activity)) {
                            target = activity;
                        }
                        if (result == null) {
                            if (manual && target != null) {
                                showError(target, R.string.update_check_failed);
                            }
                            return;
                        }
                        Settings.setLastUpdateCheck(appContext, System.currentTimeMillis());
                        if (compareVersions(result.version, BuildConfig.VERSION_NAME) <= 0) {
                            if (manual && target != null) {
                                toast(target, target.getString(
                                        R.string.update_is_latest, BuildConfig.VERSION_NAME));
                            }
                            return;
                        }
                        pendingRelease = result;
                        if (target != null) {
                            maybeShowPendingRelease(target);
                        }
                    }
                });
            }
        });
    }

    private static Activity aliveActivity() {
        Activity activity = currentActivity.get();
        return isAlive(activity) ? activity : null;
    }

    private static void maybeShowPendingRelease(Activity activity) {
        if (pendingRelease == null || !isAlive(activity)) {
            return;
        }
        if (activeDialog != null && activeDialog.isShowing()) {
            return;
        }
        Release release = pendingRelease;
        pendingRelease = null;
        if (compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) {
            return;
        }
        showUpdateDialog(activity, release);
    }

    /** После возврата из настроек «Установка неизвестных приложений» продолжить установку. */
    public static void resumePendingInstall(Activity activity) {
        if (pendingApk == null || !isAlive(activity)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        File apk = pendingApk;
        pendingApk = null;
        launchInstaller(activity, apk);
    }

    /** Закрыть диалог, привязанный к уничтожаемой активности, и остановить загрузку. */
    public static void onActivityDestroyed(Activity activity) {
        if (currentActivity.get() == activity) {
            currentActivity = NO_ACTIVITY;
        }
        if (activeDialog == null || activeDialog.getOwnerActivity() != activity) {
            return;
        }
        activeDialog.dismiss();
        activeDialog = null;
        if (downloading) {
            cancelDownload = true;
            Call call = currentCall;
            if (call != null) {
                call.cancel();
            }
        }
    }

    // ------------------------------------------------------------------ проверка

    private static Release fetchLatest() throws Exception {
        Release release = fetchLatestViaRedirect();
        if (release == null) {
            release = fetchLatestViaApi();
        }
        if (release != null) {
            Log.d(TAG, "latest release: v" + release.version + " -> " + release.apkUrl);
        }
        return release;
    }

    /** Основной способ: 302 с /releases/latest на /releases/tag/<tag>. */
    private static Release fetchLatestViaRedirect() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        Request request = new Request.Builder()
                .url(RELEASES_LATEST_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build();
        Call call = client.newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            if (response.code() < 300 || response.code() >= 400) {
                return null;
            }
            String location = response.header("Location");
            if (location == null || location.trim().isEmpty()) {
                return null;
            }
            HttpUrl target = response.request().url().resolve(location);
            if (target == null) {
                return null;
            }
            int tagIndex = target.pathSegments().indexOf("tag");
            if (tagIndex < 0 || tagIndex + 1 >= target.pathSegments().size()) {
                return null;
            }
            String tag = target.pathSegments().get(tagIndex + 1);
            String version = normalizeVersion(tag);
            if (version.isEmpty()) {
                return null;
            }
            // Имя файла в релизе: NewlineTV-<версия>.apk (см. README).
            HttpUrl downloadUrl = HttpUrl.parse("https://github.com")
                    .newBuilder()
                    .addPathSegment(REPO_OWNER)
                    .addPathSegment(REPO_NAME)
                    .addPathSegment("releases")
                    .addPathSegment("download")
                    .addPathSegment(tag)
                    .addPathSegment("NewlineTV-" + version + ".apk")
                    .build();
            return new Release(version, tag, "", downloadUrl.toString(), 0L);
        } catch (Exception e) {
            Log.d(TAG, "redirect check failed: " + e.getMessage());
            return null;
        } finally {
            currentCall = null;
        }
    }

    /** Резервный способ: GitHub API (может быть ограничен по лимиту запросов). */
    private static Release fetchLatestViaApi() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(API_LATEST_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .build();
        Call call = client.newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body.string());
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                return null;
            }
            String tag = json.optString("tag_name", "").trim();
            String version = normalizeVersion(tag);
            if (version.isEmpty()) {
                return null;
            }
            String notes = json.optString("body", "");
            String apkUrl = null;
            long apkSize = 0L;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "");
                        apkSize = asset.optLong("size", 0L);
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                return null;
            }
            return new Release(version, tag, notes, apkUrl, apkSize);
        } catch (Exception e) {
            Log.d(TAG, "API check failed: " + e.getMessage());
            return null;
        } finally {
            currentCall = null;
        }
    }

    // ------------------------------------------------------------------ диалоги

    private static void showUpdateDialog(final Activity activity, final Release release) {
        String message = activity.getString(
                R.string.update_message, BuildConfig.VERSION_NAME, release.version);
        String notes = cleanNotes(release.notes);
        if (!notes.isEmpty()) {
            message = message + "\n\n" + notes;
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_install, (d, which) ->
                        downloadAndInstall(activity, release))
                .setNegativeButton(R.string.update_later, null)
                .create();
        dialog.setOwnerActivity(activity);
        activeDialog = dialog;
        dialog.show();
    }

    private static void downloadAndInstall(final Activity activity, final Release release) {
        if (downloading || !isAlive(activity)) {
            return;
        }
        downloading = true;
        cancelDownload = false;

        final File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            downloading = false;
            showError(activity, R.string.update_download_failed);
            return;
        }
        deleteChildren(dir);

        final File target = new File(dir, "NewlineTV-" + release.version + ".apk");
        final File part = new File(dir, target.getName() + ".part");

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
        final ProgressBar bar = view.findViewById(R.id.updateProgress);
        final TextView progressText = view.findViewById(R.id.updateProgressText);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading)
                .setView(view)
                .setNegativeButton(R.string.update_cancel, (d, which) -> {
                    cancelDownload = true;
                    Call call = currentCall;
                    if (call != null) {
                        call.cancel();
                    }
                })
                .create();
        dialog.setCancelable(false);
        dialog.setOwnerActivity(activity);
        activeDialog = dialog;
        dialog.show();

        pool.execute(new Runnable() {
            @Override
            public void run() {
                Exception error = null;
                // GitHub CDN иногда отвечает ошибкой сразу после публикации релиза —
                // делаем несколько попыток, прежде чем показывать сообщение пользователю.
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        download(release.apkUrl, part, activity, release.apkSize, bar, progressText);
                        if (cancelDownload) {
                            throw new IOException("cancelled");
                        }
                        if (target.exists() && !target.delete()) {
                            throw new IOException("cannot replace " + target);
                        }
                        if (!part.renameTo(target)) {
                            throw new IOException("cannot rename " + part);
                        }
                        error = null;
                        break;
                    } catch (Exception e) {
                        error = e;
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        if (cancelDownload || attempt == 3) {
                            break;
                        }
                        Log.w(TAG, "update download attempt " + attempt + " failed", e);
                        try {
                            Thread.sleep(1200L * attempt);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                final Exception failure = error;
                runOnUi(activity, new Runnable() {
                    @Override
                    public void run() {
                        downloading = false;
                        if (activeDialog == dialog) {
                            activeDialog = null;
                        }
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        if (!isAlive(activity)) {
                            return;
                        }
                        if (cancelDownload) {
                            cancelDownload = false;
                            return;
                        }
                        if (failure != null) {
                            Log.w(TAG, "update download failed", failure);
                            showError(activity, R.string.update_download_failed);
                            return;
                        }
                        String problem = validateApk(activity, target);
                        if (problem != null) {
                            Log.w(TAG, "update APK rejected: " + problem);
                            //noinspection ResultOfMethodCallIgnored
                            target.delete();
                            showError(activity, R.string.update_invalid);
                            return;
                        }
                        install(activity, target);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ загрузка

    private static void download(String url, File file, Activity activity, long expectedSize,
                                 final ProgressBar bar, final TextView progressText)
            throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();
        Call call = client.newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response");
            }
            long total = body.contentLength();
            if (total <= 0 && expectedSize > 0) {
                total = expectedSize;
            }
            if (total > 0 && total < MIN_APK_BYTES) {
                throw new IOException("file too small: " + total);
            }

            final long contentLength = total;
            InputStream input = new BufferedInputStream(body.byteStream(), 64 * 1024);
            BufferedOutputStream output = new BufferedOutputStream(
                    new FileOutputStream(file), 64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0L;
            int lastPercent = -1;
            int read;
            try {
                while ((read = input.read(buffer)) != -1) {
                    if (cancelDownload) {
                        throw new IOException("cancelled");
                    }
                    output.write(buffer, 0, read);
                    downloaded += read;
                    int percent = contentLength > 0
                            ? (int) Math.min(100L, downloaded * 100L / contentLength)
                            : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long done = downloaded;
                        final long size = contentLength;
                        final int shown = percent;
                        runOnUi(activity, new Runnable() {
                            @Override
                            public void run() {
                                if (bar == null || progressText == null) {
                                    return;
                                }
                                if (shown >= 0) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(shown);
                                    progressText.setText(activity.getString(
                                            R.string.update_download_progress,
                                            shown, humanSize(done), humanSize(size)));
                                } else {
                                    bar.setIndeterminate(true);
                                    progressText.setText(R.string.update_downloading);
                                }
                            }
                        });
                    }
                }
            } finally {
                try {
                    output.flush();
                } catch (IOException ignored) {
                }
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }

            if (downloaded < MIN_APK_BYTES) {
                throw new IOException("downloaded too small: " + downloaded);
            }
            if (contentLength > 0 && downloaded != contentLength) {
                throw new IOException("incomplete: " + downloaded + "/" + contentLength);
            }
        } finally {
            currentCall = null;
        }
    }

    // ------------------------------------------------------------------ проверка APK

    /** @return null, если APK подходит; иначе краткая причина отказа. */
    private static String validateApk(Context context, File apk) {
        if (!apk.isFile() || apk.length() < MIN_APK_BYTES) {
            return "size";
        }
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive;
        PackageInfo installed;
        try {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            installed = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
        if (archive == null || archive.packageName == null) {
            return "not an APK";
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            return "package " + archive.packageName;
        }
        String archiveSigner = signerSha256(archive);
        String installedSigner = signerSha256(installed);
        if (archiveSigner == null || installedSigner == null) {
            return "no signature";
        }
        if (!archiveSigner.equals(installedSigner)) {
            return "signature mismatch";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signaturesOf(PackageInfo info) {
        if (info == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) {
                return null;
            }
            // Сравниваем актуальные подписанты APK, а не историю ротации ключей.
            Signature[] current = signingInfo.getApkContentsSigners();
            if (current != null && current.length > 0) {
                return current;
            }
            return signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private static String signerSha256(PackageInfo info) {
        Signature[] signatures = signaturesOf(info);
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ установка

    private static void install(final Activity activity, final File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_unknown_sources_title)
                    .setMessage(R.string.update_unknown_sources_text)
                    .setPositiveButton(R.string.update_open_settings, (d, which) -> {
                        try {
                            Intent intent = new Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            showError(activity, R.string.update_install_error);
                        }
                    })
                    .setNegativeButton(R.string.update_cancel, null)
                    .create();
            dialog.setOwnerActivity(activity);
            activeDialog = dialog;
            dialog.show();
            return;
        }
        launchInstaller(activity, apk);
    }

    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot start installer", e);
            showError(activity, R.string.update_install_error);
        }
    }

    // ------------------------------------------------------------------ утилиты

    private static void showError(Activity activity, int messageRes) {
        if (!isAlive(activity)) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(messageRes)
                .setPositiveButton(R.string.settings_close, null)
                .create();
        dialog.setOwnerActivity(activity);
        activeDialog = dialog;
        dialog.show();
    }

    private static void toast(Activity activity, int messageRes) {
        if (isAlive(activity)) {
            toast(activity, activity.getString(messageRes));
        }
    }

    private static void toast(Activity activity, CharSequence message) {
        if (isAlive(activity)) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }
    }

    private static void runOnUi(Activity activity, Runnable action) {
        if (isAlive(activity)) {
            activity.runOnUiThread(action);
        }
    }

    private static boolean isAlive(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    private static String normalizeVersion(String tag) {
        String value = tag == null ? "" : tag.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        return value;
    }

    static int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        String value = normalizeVersion(version);
        String[] parts = value.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int end = 0;
            while (end < parts[i].length() && Character.isDigit(parts[i].charAt(end))) {
                end++;
            }
            if (end == 0) {
                numbers[i] = 0;
            } else {
                try {
                    numbers[i] = Integer.parseInt(parts[i].substring(0, end));
                } catch (NumberFormatException e) {
                    numbers[i] = 0;
                }
            }
        }
        return numbers;
    }

    private static String cleanNotes(String notes) {
        if (notes == null) {
            return "";
        }
        String value = notes.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (value.length() > 700) {
            value = value.substring(0, 700).trim() + "…";
        }
        return value;
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) {
            return String.format(Locale.getDefault(), "%.1f МБ", mb);
        }
        return String.format(Locale.getDefault(), "%.0f КБ", bytes / 1024.0);
    }

    private static void deleteChildren(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class Release {
        final String version;
        final String tag;
        final String notes;
        final String apkUrl;
        final long apkSize;

        Release(String version, String tag, String notes, String apkUrl, long apkSize) {
            this.version = version;
            this.tag = tag;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }
}
