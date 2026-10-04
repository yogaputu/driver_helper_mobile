package com.budimas.driverhelper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A small durable outbox for operational records. It intentionally stores the
 * request body only (never the access token); session ownership is a hash held
 * in {@link AppSession}.
 */
final class OfflineQueue extends SQLiteOpenHelper {
    private static final String DB_NAME = "driver_helper_offline.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "pending_operation";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ATTENTION = "ATTENTION";

    OfflineQueue(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner TEXT NOT NULL,"
                + "type TEXT NOT NULL,"
                + "trip_id INTEGER NOT NULL,"
                + "route TEXT NOT NULL,"
                + "payload TEXT NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'PENDING',"
                + "attempts INTEGER NOT NULL DEFAULT 0,"
                + "last_error TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX idx_pending_operation_owner_status ON " + TABLE + " (owner, status, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // The first version is intentionally additive. Future migrations must
        // preserve records that were captured while devices were offline.
    }

    long enqueue(AppSession session, String type, int tripId, String route, JSONObject payload) throws Exception {
        JSONObject safePayload = payload == null ? new JSONObject() : new JSONObject(payload.toString());
        if (!safePayload.has("idempotency_key") || safePayload.optString("idempotency_key").trim().isEmpty()) {
            safePayload.put("idempotency_key", UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("owner", session.queueOwner());
        values.put("type", type);
        values.put("trip_id", tripId);
        values.put("route", route);
        values.put("payload", safePayload.toString());
        values.put("status", STATUS_PENDING);
        values.put("created_at", now);
        values.put("updated_at", now);
        return getWritableDatabase().insertOrThrow(TABLE, null, values);
    }

    List<PendingOperation> pending(AppSession session) {
        List<PendingOperation> output = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"id", "type", "trip_id", "route", "payload", "attempts"},
                "owner = ? AND status = ?",
                new String[]{session.queueOwner(), STATUS_PENDING},
                null,
                null,
                "id ASC"
        )) {
            while (cursor.moveToNext()) {
                output.add(new PendingOperation(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getInt(5)
                ));
            }
        }
        return output;
    }

    int outstandingCount(AppSession session, int tripId) {
        String where = "owner = ?" + (tripId > 0 ? " AND trip_id = ?" : "");
        String[] args = tripId > 0
                ? new String[]{session.queueOwner(), String.valueOf(tripId)}
                : new String[]{session.queueOwner()};
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE " + where,
                args
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** GPS points may safely sync after trip close; POD/checklist records may not. */
    int blockingCount(AppSession session, int tripId) {
        String where = "owner = ? AND type <> 'GPS'" + (tripId > 0 ? " AND trip_id = ?" : "");
        String[] args = tripId > 0
                ? new String[]{session.queueOwner(), String.valueOf(tripId)}
                : new String[]{session.queueOwner()};
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE " + where,
                args
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    void delete(long id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    void markRetry(long id, String message) {
        // ContentValues cannot represent an SQL expression. Increment safely
        // in SQL, then attach the human-readable last error separately.
        getWritableDatabase().execSQL("UPDATE " + TABLE + " SET attempts = attempts + 1, last_error = ?, updated_at = ? WHERE id = ?",
                new Object[]{trimError(message), System.currentTimeMillis(), id});
    }

    void markAttention(long id, String message) {
        ContentValues values = statusValues(STATUS_ATTENTION, message);
        getWritableDatabase().update(TABLE, values, "id = ?", new String[]{String.valueOf(id)});
    }

    void clearOwner(String owner) {
        getWritableDatabase().delete(TABLE, "owner = ?", new String[]{owner});
    }

    private ContentValues statusValues(String status, String message) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("last_error", trimError(message));
        values.put("updated_at", System.currentTimeMillis());
        return values;
    }

    private static String trimError(String message) {
        if (message == null) return "";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    static final class PendingOperation {
        final long id;
        final String type;
        final int tripId;
        final String route;
        final String payload;
        final int attempts;

        PendingOperation(long id, String type, int tripId, String route, String payload, int attempts) {
            this.id = id;
            this.type = type;
            this.tripId = tripId;
            this.route = route;
            this.payload = payload;
            this.attempts = attempts;
        }
    }
}
