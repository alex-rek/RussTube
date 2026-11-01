package ru.tubetv.app;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Version 1 application frames. Only use on an authenticated, pinned TLS socket. */
final class LocalLinkProtocol {
    static final int VERSION = 1;
    static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "HELLO", "PAIR_REQUEST", "PAIR_RESULT", "CAPABILITIES", "SYNC_REQUEST",
            "SYNC_CHANGES", "SYNC_ACK", "PLAYBACK_COMMAND", "PLAYBACK_STATE", "ERROR",
            "PING", "PONG"));

    static final class Message {
        final String id;
        final String deviceId;
        final String type;
        final long sequence;
        final JSONObject payload;

        Message(String id, String deviceId, String type, long sequence, JSONObject payload) {
            this.id = id;
            this.deviceId = deviceId;
            this.type = type;
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    static Message read(DataInputStream input) throws Exception {
        int size;
        try { size = input.readInt(); }
        catch (EOFException eof) { return null; }
        if (size < 2 || size > MAX_FRAME_BYTES)
            throw new IOException("Недопустимый размер локального сообщения");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("protocolVersion", -1) != VERSION)
            throw new IOException("Несовместимая версия локального протокола");
        String id = root.optString("messageId");
        String deviceId = root.optString("deviceId");
        String type = root.optString("type");
        long sequence = root.optLong("sequence", -1);
        if (!uuid(id) || !uuid(deviceId) || !TYPES.contains(type) || sequence < 1)
            throw new IOException("Некорректное локальное сообщение");
        JSONObject payload = root.optJSONObject("payload");
        if (payload == null) throw new IOException("Отсутствуют данные сообщения");
        return new Message(id, deviceId, type, sequence, payload);
    }

    static void write(DataOutputStream output, Message message) throws Exception {
        if (!uuid(message.id) || !uuid(message.deviceId)
                || !TYPES.contains(message.type) || message.sequence < 1
                || message.payload == null)
            throw new IOException("Некорректное исходящее сообщение");
        JSONObject root = new JSONObject().put("protocolVersion", VERSION)
                .put("messageId", message.id).put("deviceId", message.deviceId)
                .put("type", message.type).put("sequence", message.sequence)
                .put("payload", message.payload);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_BYTES)
            throw new IOException("Локальное сообщение слишком большое");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    static Message create(String deviceId, String type, long sequence, JSONObject payload) {
        return new Message(UUID.randomUUID().toString(), deviceId, type, sequence, payload);
    }

    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (Exception error) { return false; }
    }
}
