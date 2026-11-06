package ru.smena.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

public final class AppUpdate {
    private static final String API = "https://api.github.com/repos/sigabu/smena/commits/main";
    private static final String RAW = "https://raw.githubusercontent.com/sigabu/smena/";
    private AppUpdate() {}

    public static File apkFile(Context context) { return new File(context.getCacheDir(), "smena-update.apk"); }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    private static PackageInfo installed(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0); }
        catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
    }

    public static String installedName(Context context) { return installed(context).versionName; }
    public static long installedCode(Context context) { return version(installed(context)); }

    /** Resolve main once so the metadata and APK always come from the same commit. */
    public static UpdateManifest check() throws Exception {
        byte[] commitBytes = fetchSmall(API);
        if (commitBytes == null) return null;
        String commit = new JSONObject(new String(commitBytes, StandardCharsets.UTF_8)).getString("sha");
        if (!commit.matches("[a-f0-9]{40}")) throw new IOException("GitHub вернул некорректный ответ");
        byte[] manifestBytes = fetchSmall(RAW + commit + "/dist/update.json");
        if (manifestBytes == null) return null;
        JSONObject json = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        return new UpdateManifest(json.getInt("versionCode"), json.getString("versionName"),
                json.getString("sha256"), json.getLong("size"), commit);
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "Smena-Android");
        return connection;
    }

    private static byte[] fetchSmall(String url) throws IOException {
        HttpURLConnection connection = connect(url);
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            requireOk(status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Проверка прервана");
                    if (output.size() + length > 256 * 1024) throw new IOException("Ответ GitHub слишком большой");
                    output.write(buffer, 0, length);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private static void requireOk(int status) throws IOException {
        if (status == 403 || status == 429) throw new IOException("GitHub временно ограничил запросы. Попробуйте позже");
        if (status != 200) throw new IOException("GitHub ответил HTTP " + status);
    }

    public static File download(Context context, UpdateManifest update) throws Exception {
        File part = new File(context.getCacheDir(), "smena-update.part");
        HttpURLConnection connection = connect(RAW + update.commit + "/dist/smena-public.apk");
        try {
            requireOk(connection.getResponseCode());
            if (connection.getContentLengthLong() > UpdateManifest.MAX_APK_BYTES) throw new IOException("Файл обновления слишком большой");
            long received = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Загрузка прервана");
                    received += length;
                    if (received > update.size) throw new IOException("Размер обновления не совпадает");
                    output.write(buffer, 0, length);
                }
                output.getFD().sync();
            }
            verifyIntegrity(part, update);
            verifyApk(context, part, update);
            File target = apkFile(context);
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { connection.disconnect(); Files.deleteIfExists(part.toPath()); }
    }

    public static void verifyIntegrity(File file, UpdateManifest update) throws IOException {
        if (file.length() != update.size || !sha256(file).equals(update.sha256))
            throw new IOException("Проверка файла обновления не пройдена. Повторите загрузку");
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    @SuppressWarnings("deprecation")
    public static void verifyApk(Context context, File file, UpdateManifest update) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null || !current.packageName.equals(candidate.packageName)
                || version(candidate) != update.versionCode || !update.versionName.equals(candidate.versionName)
                || version(candidate) <= version(current))
            throw new IOException("APK не подходит для обновления этой версии приложения");
        Signature[] trusted, incoming;
        if (Build.VERSION.SDK_INT >= 28) {
            trusted = current.signingInfo == null ? null : current.signingInfo.getApkContentsSigners();
            incoming = candidate.signingInfo == null ? null : candidate.signingInfo.getApkContentsSigners();
        } else { trusted = current.signatures; incoming = candidate.signatures; }
        if (trusted == null || incoming == null || trusted.length == 0 || trusted.length != incoming.length
                || !Arrays.asList(trusted).containsAll(Arrays.asList(incoming)))
            throw new IOException("Подпись обновления отличается. Нужен APK, подписанный тем же ключом");
    }
}
