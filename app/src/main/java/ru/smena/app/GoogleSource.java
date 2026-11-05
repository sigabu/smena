package ru.smena.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoogleSource {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private GoogleSource() {}

    public static String csvUrl(String input) {
        URI uri;
        try { uri = URI.create(input.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Некорректная ссылка"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Нужна ссылка HTTPS на Google-таблицу или CSV");
        if (!"docs.google.com".equalsIgnoreCase(uri.getHost())) return uri.toString();
        String path = uri.getPath();
        Matcher published = Pattern.compile("^/spreadsheets/d/e/([A-Za-z0-9_-]+)/pub(?:html)?/?$").matcher(path);
        String gid = queryValue(uri.getRawFragment(), "gid");
        if (gid == null) gid = queryValue(uri.getRawQuery(), "gid");
        if (gid != null && !gid.matches("\\d+")) throw new IllegalArgumentException("Некорректный gid листа");
        String gidQuery = gid == null ? "" : "&gid=" + gid;
        if (published.matches()) return "https://docs.google.com/spreadsheets/d/e/" + published.group(1) + "/pub?output=csv" + gidQuery;
        Matcher document = Pattern.compile("^/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?$").matcher(path);
        if (document.matches() && !document.group(1).equals("e"))
            return "https://docs.google.com/spreadsheets/d/" + document.group(1) + "/export?format=csv" + gidQuery;
        throw new IllegalArgumentException("Нужна ссылка Google Sheets, а не Google Docs");
    }

    private static String queryValue(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) return parts[1];
        }
        return null;
    }

    public static String fetch(String source) throws IOException {
        URI uri = URI.create(csvUrl(source));
        // Google exports redirect to googleusercontent. Never follow a downgrade to HTTP.
        for (int hop = 0; hop < 6; hop++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("Источник перенаправляет на недопустимую ссылку");
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "text/csv,text/plain;q=0.9");
            try {
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("Пустое перенаправление источника");
                    uri = uri.resolve(location); continue;
                }
                if (status == 401 || status == 403) throw new IOException("Нет доступа к таблице. Откройте доступ на чтение или импортируйте CSV-файл");
                if (status != 200) throw new IOException("Источник ответил HTTP " + status);
                if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("Файл больше 2 МБ");
                try (InputStream stream = c.getInputStream()) { return readLimited(stream); }
            } finally { c.disconnect(); }
        }
        throw new IOException("Слишком много перенаправлений. Проверьте доступ к таблице");
    }

    public static String readLimited(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Файл недоступен");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int length;
        while ((length = stream.read(buffer)) != -1) {
            if (out.size() + length > MAX_BYTES) throw new IOException("Файл больше 2 МБ");
            out.write(buffer, 0, length);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
