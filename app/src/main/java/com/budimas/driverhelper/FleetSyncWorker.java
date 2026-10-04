package com.budimas.driverhelper;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Sends the durable offline outbox once a network connection is available. */
public final class FleetSyncWorker extends Worker {
    private static final String UNIQUE_WORK = "budimas-fleet-offline-sync";

    public FleetSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FleetSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppSession session;
        try {
            session = new AppSession(getApplicationContext());
        } catch (Exception error) {
            return Result.retry();
        }
        if (!session.isLoggedIn()) return Result.success();

        OfflineQueue queue = new OfflineQueue(getApplicationContext());
        FleetApiClient api = new FleetApiClient(session);
        List<OfflineQueue.PendingOperation> rows = queue.pending(session);
        for (OfflineQueue.PendingOperation row : rows) {
            try {
                // A checklist captured by an older app version must never be
                // replayed into a manifest that WMS has not finalized (or that
                // has already departed). The live manifest is the canonical
                // source of truth, so hold the row for review instead.
                if ("CHECKLIST_MANIFEST".equals(row.type)) {
                    JSONObject manifestResponse = api.manifest(row.tripId);
                    if (!isLoadedManifest(manifestResponse)) {
                        queue.markAttention(row.id, "Checklist tidak dikirim: manifest WMS saat ini belum berstatus LOADED.");
                        continue;
                    }
                }
                api.sendQueuedPost(row.route, new JSONObject(row.payload));
                queue.delete(row.id);
            } catch (Exception error) {
                if (FleetApiClient.isRetryable(error)) {
                    queue.markRetry(row.id, error.getMessage());
                    return Result.retry();
                }
                // Keep a visible record for a supervisor/app user to resolve,
                // but do not spin WorkManager forever on a rejected payload.
                queue.markAttention(row.id, error.getMessage());
            }
        }
        return Result.success();
    }

    private static boolean isLoadedManifest(JSONObject response) {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        if (data == null) data = response;
        JSONObject manifest = data == null ? null : data.optJSONObject("manifest");
        return manifest != null && "LOADED".equalsIgnoreCase(manifest.optString("status", "").trim());
    }
}
