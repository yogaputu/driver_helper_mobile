package com.budimas.driverhelper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class LocationForegroundService extends Service {
    public static final String ACTION_START = "com.budimas.driverhelper.START_TRACKING";
    public static final String ACTION_STOP = "com.budimas.driverhelper.STOP_TRACKING";
    public static final String EXTRA_TRIP_ID = "trip_id";
    private static final String CHANNEL_ID = "budimas_trip_tracking";
    private static final int NOTIFICATION_ID = 4312;
    // A periodic vehicle position is enough for dispatch monitoring while
    // keeping battery and mobile data use appropriate for a full workday.
    private static final long TRACKING_INTERVAL_MS = 15 * 60_000L;
    private static final long MIN_TRACKING_INTERVAL_MS = TRACKING_INTERVAL_MS;
    // Fused Location can provide the same first fix through both the immediate
    // request and the scheduled callback. Keep one auditable point per cycle.
    private static final long MIN_SUBMIT_INTERVAL_MS = 14 * 60_000L;

    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private ExecutorService networkExecutor;
    private FleetApiClient api;
    private AppSession session;
    private OfflineQueue offlineQueue;
    private int tripId;
    private int lastSubmittedTripId;
    private long lastSubmittedElapsedMs;

    @Override
    public void onCreate() {
        super.onCreate();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        session = new AppSession(this);
        api = new FleetApiClient(session);
        offlineQueue = new OfflineQueue(this);
        networkExecutor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking(true);
            return START_NOT_STICKY;
        }
        tripId = intent == null ? 0 : intent.getIntExtra(EXTRA_TRIP_ID, 0);
        if (tripId <= 0) tripId = session.activeTripId();
        if (tripId <= 0 || !hasLocationPermission()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        session.setActiveTripId(tripId);
        startForeground(NOTIFICATION_ID, buildNotification());
        beginTracking();
        return START_STICKY;
    }

    private void beginTracking() {
        if (locationCallback != null) return;
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TRACKING_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_TRACKING_INTERVAL_MS)
                .setMaxUpdateDelayMillis(TRACKING_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null || result.getLastLocation() == null) return;
                submitLocation(result.getLastLocation());
            }
        };
        try {
            locationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
            // Do not leave dispatch without a first pin while waiting for the
            // first scheduled 15-minute update.
            requestInitialLocation();
        } catch (SecurityException ignored) {
            stopTracking(false);
        }
    }

    private void requestInitialLocation() {
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location != null) submitLocation(location);
                    });
        } catch (SecurityException ignored) {
            stopTracking(false);
        }
    }

    private void submitLocation(android.location.Location location) {
        if (location == null || networkExecutor == null || networkExecutor.isShutdown()) return;
        final int locationTripId = tripId;
        if (locationTripId <= 0 || session.activeTripId() != locationTripId) return;
        if (!reserveSubmissionSlot(locationTripId)) return;
        final int battery = batteryPercent();
        final long capturedAt = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        networkExecutor.execute(() -> {
            JSONObject payload;
            try {
                payload = api.locationPayload(
                        location.getLatitude(),
                        location.getLongitude(),
                        location.hasAccuracy() ? location.getAccuracy() : 0f,
                        location.hasSpeed() ? location.getSpeed() : 0f,
                        location.hasBearing() ? location.getBearing() : 0f,
                        battery,
                        capturedAt
                );
            } catch (Exception ignored) {
                return;
            }
            try {
                api.sendLocationPayload(locationTripId, payload);
            } catch (Exception error) {
                // Preserve the exact observed point, then let WorkManager
                // retry it when the connection returns.
                if (FleetApiClient.isRetryable(error)) {
                    try {
                        offlineQueue.enqueue(
                                session,
                                "GPS",
                                locationTripId,
                                "fleet-mobile/trips/" + locationTripId + "/location",
                                payload
                        );
                        FleetSyncWorker.schedule(LocationForegroundService.this);
                    } catch (Exception ignored) {
                        // Avoid crashing the foreground service if the local
                        // outbox is temporarily unavailable.
                    }
                }
            }
        });
    }

    private synchronized boolean reserveSubmissionSlot(int locationTripId) {
        long now = SystemClock.elapsedRealtime();
        if (lastSubmittedTripId == locationTripId
                && now - lastSubmittedElapsedMs < MIN_SUBMIT_INTERVAL_MS) {
            return false;
        }
        lastSubmittedTripId = locationTripId;
        lastSubmittedElapsedMs = now;
        return true;
    }

    private void stopTracking(boolean clearActiveTrip) {
        if (locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        if (clearActiveTrip) session.clearActiveTripId();
        tripId = 0;
        lastSubmittedTripId = 0;
        lastSubmittedElapsedMs = 0L;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private int batteryPercent() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) return 0;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? 0 : Math.round(level * 100f / scale);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.tracking_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Perjalanan aktif")
                .setContentText("Lokasi armada dikirim sekitar setiap 15 menit.")
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (locationCallback != null) locationClient.removeLocationUpdates(locationCallback);
        if (networkExecutor != null) networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, int tripId) {
        Intent intent = new Intent(context, LocationForegroundService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRIP_ID, tripId);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, LocationForegroundService.class).setAction(ACTION_STOP));
    }
}
