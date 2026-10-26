package ru.tubetv.app;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Media3 HTTP reader using an explicit proxy for playlists and media segments. */
@OptIn(markerClass = UnstableApi.class)
final class ProxyHttpDataSource extends BaseDataSource {
    private final Map<String, String> headers;
    private final String userAgent;
    private final String platform;
    private HttpURLConnection connection;
    private InputStream input;
    private Uri resolvedUri;
    private long remaining;
    private boolean opened;

    ProxyHttpDataSource(Map<String, String> headers, String userAgent, String platform) {
        super(true);
        this.headers = new HashMap<>(headers);
        this.userAgent = userAgent;
        this.platform = platform;
    }

    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        URL target = new URL(spec.uri.toString());
        try {
            for (int attempt = 0; attempt < 6; attempt++) {
                connection = NetworkProxy.openConnection(platform, target.toString());
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(10_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(spec.getHttpMethodString());
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> entry : headers.entrySet())
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                for (Map.Entry<String, String> entry : spec.httpRequestHeaders.entrySet())
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                if (spec.position > 0 || spec.length != C.LENGTH_UNSET) {
                    String range = "bytes=" + spec.position + "-";
                    if (spec.length != C.LENGTH_UNSET)
                        range += (spec.position + spec.length - 1);
                    connection.setRequestProperty("Range", range);
                }
                if (spec.httpBody != null && spec.httpBody.length > 0) {
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(spec.httpBody);
                    }
                }
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Прокси: перенаправление без адреса");
                    target = new URL(target, location);
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                resolvedUri = Uri.parse(target.toString());
                input = connection.getInputStream();
                if (code == 200 && spec.position > 0) skipFully(spec.position);
                remaining = spec.length;
                if (remaining == C.LENGTH_UNSET) {
                    String length = connection.getHeaderField("Content-Length");
                    try { remaining = length == null ? C.LENGTH_UNSET : Long.parseLong(length); }
                    catch (NumberFormatException ignored) { remaining = C.LENGTH_UNSET; }
                    if (code == 200 && remaining != C.LENGTH_UNSET)
                        remaining = Math.max(0L, remaining - spec.position);
                }
                opened = true;
                transferStarted(spec);
                return remaining;
            }
            throw new IOException("Слишком много перенаправлений видеопотока");
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    private void skipFully(long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        while (bytes > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, bytes));
            if (count < 0) throw new IOException("Видеопоток короче запрошенной позиции");
            bytes -= count;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int allowed = remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining);
        int count = input.read(buffer, offset, allowed);
        if (count < 0) return C.RESULT_END_OF_INPUT;
        if (remaining != C.LENGTH_UNSET) remaining -= count;
        bytesTransferred(count);
        return count;
    }

    @Override public Uri getUri() { return resolvedUri; }

    @Override public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? Collections.emptyMap() : connection.getHeaderFields();
    }

    @Override public void close() throws IOException {
        try {
            if (input != null) input.close();
        } finally {
            input = null;
            if (connection != null) { connection.disconnect(); connection = null; }
            resolvedUri = null;
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
