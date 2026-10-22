package ru.tubetv.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UpdateManagerTest {
    private static final String DIGEST =
            "sha256:8f1ffeb105747a3ea675226886879917479ac858403ba242a312614cadeee582";

    @Test public void selectsNewestMatchingFlavorIncludingPrerelease() throws Exception {
        String releases = "[" + release("v0.5.2", false,
                asset("RussTube-TV-0.5.2-debug.apk", DIGEST)) + ","
                + release("v0.5.3", true,
                asset("RussTube-Mobile-0.5.3-debug.apk", DIGEST)) + ","
                + release("v0.5.1", true,
                asset("RussTube-Mobile-0.5.1-debug.apk", DIGEST)) + "]";
        UpdateManager.Release mobile = UpdateManager.findUpdate(releases, true, "0.5.1");
        UpdateManager.Release tv = UpdateManager.findUpdate(releases, false, "0.5.1");
        assertEquals("0.5.3", mobile.version);
        assertEquals("0.5.2", tv.version);
        assertEquals(DIGEST.substring(7), mobile.digest);
    }

    @Test public void ignoresUnverifiedOrWrongAssets() throws Exception {
        String releases = "[" + release("v0.5.3", false,
                asset("RussTube-TV-0.5.3-debug.apk", "")) + ","
                + release("v0.5.2", false,
                asset("RussTube-Mobile-0.5.2-debug.apk", DIGEST)) + "]";
        assertNull(UpdateManager.findUpdate(releases, false, "0.5.1"));
        assertNull(UpdateManager.findUpdate(releases, true, "0.5.2"));
    }

    private static String release(String tag, boolean prerelease, String asset) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":"
                + prerelease + ",\"assets\":[" + asset + "]}";
    }

    private static String asset(String name, String digest) {
        String version = name.contains("0.5.3") ? "v0.5.3"
                : name.contains("0.5.2") ? "v0.5.2" : "v0.5.1";
        return "{\"name\":\"" + name + "\",\"state\":\"uploaded\",\"size\":5242880,"
                + "\"digest\":\"" + digest + "\",\"browser_download_url\":\""
                + "https://github.com/alex-rek/RussTube/releases/download/" + version + "/" + name + "\"}";
    }
}
