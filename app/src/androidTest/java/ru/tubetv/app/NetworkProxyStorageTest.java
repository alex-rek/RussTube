package ru.tubetv.app;

import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Runs on Android because AndroidKeyStore is unavailable in local JVM tests. */
@RunWith(AndroidJUnit4.class)
public final class NetworkProxyStorageTest {
    @Test public void encryptedPasswordAndLegacyMigration() throws Exception {
        String encrypted = (String) invoke("encryptPassword", String.class, "proxy-secret");
        assertFalse(encrypted.contains("proxy-secret"));
        assertEquals("proxy-secret", invoke("decryptPassword", String.class, encrypted));

        SharedPreferences prefs = InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences(
                "network_proxy_instrumentation_test", 0);
        prefs.edit().clear().putString("password", "old-password").commit();
        try {
            assertEquals("old-password", invoke("readPassword", SharedPreferences.class, prefs));
            assertFalse(prefs.contains("password"));
            String migrated = prefs.getString("password_v1", "");
            assertFalse(migrated.isEmpty());
            assertEquals("old-password", invoke("decryptPassword", String.class, migrated));
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static Object invoke(String name, Class<?> parameterType, Object argument)
            throws Exception {
        Method method = Class.forName("ru.tubetv.app.NetworkProxy")
                .getDeclaredMethod(name, parameterType);
        method.setAccessible(true);
        return method.invoke(null, argument);
    }
}
