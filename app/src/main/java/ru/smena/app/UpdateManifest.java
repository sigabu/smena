package ru.smena.app;

/** Metadata for the public APK, pinned to one Git commit. */
public final class UpdateManifest {
    public static final long MAX_APK_BYTES = 32L * 1024 * 1024;
    public final int versionCode;
    public final String versionName, sha256, commit;
    public final long size;

    public UpdateManifest(int versionCode, String versionName, String sha256, long size, String commit) {
        if (versionCode < 1 || versionName == null || !versionName.matches("[0-9]+(?:\\.[0-9]+){1,3}"))
            throw new IllegalArgumentException("Некорректная версия обновления");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || size < 1 || size > MAX_APK_BYTES
                || commit == null || !commit.matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Некорректные данные обновления");
        this.versionCode = versionCode; this.versionName = versionName;
        this.sha256 = sha256; this.size = size; this.commit = commit;
    }
}
