package ru.tubetv.app;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class LocalLinkProtocolTest {
    @Test public void roundTrip() throws Exception {
        String device = UUID.randomUUID().toString();
        LocalLinkProtocol.Message sent = LocalLinkProtocol.create(device, "PING", 8,
                new JSONObject().put("nonce", "abc"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LocalLinkProtocol.write(new DataOutputStream(bytes), sent);
        LocalLinkProtocol.Message received = LocalLinkProtocol.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(sent.id, received.id);
        assertEquals(device, received.deviceId);
        assertEquals(8, received.sequence);
        assertEquals("abc", received.payload.getString("nonce"));
    }

    @Test(expected = IOException.class) public void rejectsOversizedFrameBeforeAllocation()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(LocalLinkProtocol.MAX_FRAME_BYTES + 1);
        LocalLinkProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test(expected = IOException.class) public void rejectsUnknownType() throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] json = new JSONObject().put("protocolVersion", 1).put("messageId", id)
                .put("deviceId", id).put("type", "DELETE_ALL").put("sequence", 1)
                .put("payload", new JSONObject()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(json.length);
        out.write(json);
        LocalLinkProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
