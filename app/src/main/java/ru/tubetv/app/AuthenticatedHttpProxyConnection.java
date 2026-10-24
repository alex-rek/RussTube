package ru.tubetv.app;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.JavaNetCookieJar;

/** HttpURLConnection facade for authenticated HTTP proxies on Android.
 * OkHttp adds Proxy-Authorization to CONNECT before the proxy sends a 407 response. */
final class AuthenticatedHttpProxyConnection extends HttpURLConnection {
    private final OkHttpClient baseClient;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private ByteArrayOutputStream requestBody;
    private Call call;
    private Response response;

    AuthenticatedHttpProxyConnection(URL url, NetworkProxy.Config config) {
        super(url);
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(config.host, config.port));
        String credentials = Credentials.basic(config.user, config.password);
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(proxy)
                .proxyAuthenticator((route, challenge) -> {
                    if (credentials.equals(challenge.request().header("Proxy-Authorization")))
                        return null;
                    return challenge.request().newBuilder()
                            .header("Proxy-Authorization", credentials).build();
                });
        CookieHandler cookies = CookieHandler.getDefault();
        if (cookies != null) builder.cookieJar(new JavaNetCookieJar(cookies));
        baseClient = builder.build();
    }

    @Override public void connect() throws IOException {
        if (connected) return;
        OkHttpClient client = baseClient.newBuilder()
                .followRedirects(getInstanceFollowRedirects())
                .followSslRedirects(getInstanceFollowRedirects())
                .connectTimeout(getConnectTimeout() > 0 ? getConnectTimeout() : 10_000,
                        TimeUnit.MILLISECONDS)
                .readTimeout(getReadTimeout() > 0 ? getReadTimeout() : 10_000,
                        TimeUnit.MILLISECONDS)
                .build();
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : headers.entrySet())
            request.header(header.getKey(), header.getValue());
        String type = getRequestProperty("Content-Type");
        RequestBody body = requestBody == null ? null : RequestBody.create(
                type == null ? null : MediaType.parse(type), requestBody.toByteArray());
        String verb = method;
        if (doOutput && "GET".equals(verb)) verb = "POST";
        if (body == null && ("POST".equals(verb) || "PUT".equals(verb)
                || "PATCH".equals(verb)))
            body = RequestBody.create(null, new byte[0]);
        request.method(verb, body);
        call = client.newCall(request.build());
        response = call.execute();
        connected = true;
    }

    @Override public OutputStream getOutputStream() throws IOException {
        if (connected) throw new ProtocolException("Request already sent");
        if (!doOutput) throw new ProtocolException("Output is disabled");
        if (requestBody == null) requestBody = new ByteArrayOutputStream();
        return requestBody;
    }

    @Override public InputStream getInputStream() throws IOException {
        connect();
        if (response.code() >= HTTP_BAD_REQUEST)
            throw new FileNotFoundException("HTTP " + response.code() + " for " + url);
        ResponseBody body = response.body();
        return body == null ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
    }

    @Override public InputStream getErrorStream() {
        try {
            connect();
            ResponseBody body = response.body();
            return response.code() >= HTTP_BAD_REQUEST && body != null ? body.byteStream() : null;
        } catch (IOException ignored) { return null; }
    }

    @Override public int getResponseCode() throws IOException { connect(); return response.code(); }
    @Override public String getResponseMessage() throws IOException {
        connect(); return response.message();
    }
    @Override public String getHeaderField(String name) {
        try { connect(); return response.header(name); }
        catch (IOException ignored) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try { connect(); return response.headers().toMultimap(); }
        catch (IOException ignored) { return Collections.emptyMap(); }
    }
    @Override public String getContentType() { return getHeaderField("Content-Type"); }
    @Override public int getContentLength() {
        long value = getContentLengthLong();
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }
    @Override public long getContentLengthLong() {
        String value = getHeaderField("Content-Length");
        try { return value == null ? -1 : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
    @Override public URL getURL() {
        return response == null ? url : response.request().url().url();
    }
    @Override public void setRequestProperty(String key, String value) {
        if (connected) throw new IllegalStateException("Request already sent");
        headers.put(key, value);
    }
    @Override public void addRequestProperty(String key, String value) {
        setRequestProperty(key, value);
    }
    @Override public String getRequestProperty(String key) { return headers.get(key); }
    @Override public Map<String, List<String>> getRequestProperties() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet())
            result.put(entry.getKey(), Collections.singletonList(entry.getValue()));
        return result;
    }
    @Override public void setRequestMethod(String method) throws ProtocolException {
        if (connected) throw new ProtocolException("Request already sent");
        super.setRequestMethod(method);
    }
    @Override public void disconnect() {
        if (call != null) call.cancel();
        if (response != null) response.close();
        response = null;
        connected = false;
    }
    @Override public boolean usingProxy() { return true; }
}
