package ru.tubetv.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Ensures Android supplies archive signer metadata used by update verification. */
@RunWith(AndroidJUnit4.class)
public final class UpdateManagerSignatureTest {
    @Test public void installedApkMatchesItsOwnArchiveSignature() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packages = context.getPackageManager();
        int flag = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flag);
        PackageInfo archive = packages.getPackageArchiveInfo(
                context.getApplicationInfo().sourceDir, flag);
        assertNotNull(archive);
        Method compare = Class.forName("ru.tubetv.app.UpdateManager")
                .getDeclaredMethod("sameSigner", PackageInfo.class, PackageInfo.class);
        compare.setAccessible(true);
        assertTrue((Boolean) compare.invoke(null, installed, archive));
    }
}
