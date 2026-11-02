package ru.tubetv.app;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** Refreshes home-screen rows when the system grants background network time. */
public final class TvHomeRefreshJob extends JobService {
    private static final int PERIODIC_ID = 5261;
    private static final int INITIAL_ID = 5262;
    private static final long PERIOD_MS = 6L * 60 * 60 * 1000;
    private Thread worker;

    @SuppressLint("NewApi") // TvHomeChannels.supported() guards API 26+.
    static void schedule(Context context) {
        if (!TvHomeChannels.supported()) return;
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) return;
        ComponentName service = new ComponentName(context, TvHomeRefreshJob.class);
        try {
            if (jobs.getPendingJob(PERIODIC_ID) == null)
                jobs.schedule(new JobInfo.Builder(PERIODIC_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(PERIOD_MS).build());
            long last = context.getSharedPreferences("tv_home_channels", MODE_PRIVATE)
                    .getLong("last_network_refresh", 0L);
            if (System.currentTimeMillis() - last > PERIOD_MS && jobs.getPendingJob(INITIAL_ID) == null)
                jobs.schedule(new JobInfo.Builder(INITIAL_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(2 * 60_000L)
                        .setOverrideDeadline(10 * 60_000L).build());
        } catch (RuntimeException error) {
            Log.w("RussTubeTvHome", "Could not schedule refresh", error);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!TvHomeChannels.supported()) return false;
        worker = new Thread(() -> {
            try {
                TvHomeChannels.refreshFromSources(getApplicationContext());
                getSharedPreferences("tv_home_channels", MODE_PRIVATE).edit()
                        .putLong("last_network_refresh", System.currentTimeMillis()).apply();
            } catch (RuntimeException error) {
                Log.w("RussTubeTvHome", "Background refresh failed", error);
            } finally {
                jobFinished(params, false);
            }
        }, "tv-home-refresh");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
