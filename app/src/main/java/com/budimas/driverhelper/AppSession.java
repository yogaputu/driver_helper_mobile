package com.budimas.driverhelper;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Stores the Driver/Helper session in the Android Keystore-backed encrypted
 * preferences. The old plaintext preference is migrated once then erased.
 */
final class AppSession {
    private static final String PREFS = "driver_helper_secure_session";
    private static final String LEGACY_PREFS = "driver_helper_session";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_ROLE = "role";
    private static final String KEY_DRIVER_ID = "driver_id";
    private static final String KEY_HELPER_ID = "helper_id";
    private static final String KEY_ACTIVE_TRIP_ID = "active_trip_id";
    private final SharedPreferences preferences;

    AppSession(Context context) {
        Context appContext = context.getApplicationContext();
        preferences = createSecurePreferences(appContext);
        migrateLegacySession(appContext);
    }

    void saveLogin(JSONObject data) {
        preferences.edit()
                .putString(KEY_TOKEN, data.optString("token"))
                .putString(KEY_NAME, first(data, "nama_user", "nama", "name"))
                .putString(KEY_EMAIL, first(data, "user_email", "email", "username"))
                .putString(KEY_ROLE, data.optString("role"))
                .putInt(KEY_DRIVER_ID, data.optInt("id_driver"))
                .putInt(KEY_HELPER_ID, data.optInt("id_helper"))
                // A different authenticated account must never inherit an old trip.
                .remove(KEY_ACTIVE_TRIP_ID)
                .apply();
    }

    void updateProfile(JSONObject profile) {
        SharedPreferences.Editor editor = preferences.edit();
        if (profile.has("nama")) editor.putString(KEY_NAME, profile.optString("nama"));
        if (profile.has("email")) editor.putString(KEY_EMAIL, profile.optString("email"));
        if (profile.has("role")) editor.putString(KEY_ROLE, profile.optString("role"));
        if (profile.has("id_driver")) editor.putInt(KEY_DRIVER_ID, profile.optInt("id_driver"));
        if (profile.has("id_helper")) editor.putInt(KEY_HELPER_ID, profile.optInt("id_helper"));
        editor.apply();
    }

    String token() { return preferences.getString(KEY_TOKEN, ""); }
    String name() { return preferences.getString(KEY_NAME, "Pengguna"); }
    String email() { return preferences.getString(KEY_EMAIL, ""); }
    String role() { return preferences.getString(KEY_ROLE, ""); }
    boolean isDriver() { return "DRIVER".equalsIgnoreCase(role()) || preferences.getInt(KEY_DRIVER_ID, 0) > 0; }
    boolean isLoggedIn() { return !token().trim().isEmpty(); }

    int activeTripId() { return preferences.getInt(KEY_ACTIVE_TRIP_ID, 0); }
    void setActiveTripId(int tripId) { preferences.edit().putInt(KEY_ACTIVE_TRIP_ID, tripId).apply(); }
    void clearActiveTripId() { preferences.edit().remove(KEY_ACTIVE_TRIP_ID).apply(); }

    /**
     * A stable, non-secret owner tag lets offline records survive a process
     * restart without exposing a bearer token in SQLite.
     */
    String queueOwner() {
        String principal = email().trim().toLowerCase()
                + "|" + role().trim().toUpperCase()
                + "|" + preferences.getInt(KEY_DRIVER_ID, 0)
                + "|" + preferences.getInt(KEY_HELPER_ID, 0);
        if (principal.equals("||0|0")) principal = name().trim().toLowerCase();
        return sha256(principal);
    }

    void clear() { preferences.edit().clear().apply(); }

    private static SharedPreferences createSecurePreferences(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREFS,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception error) {
            // Falling back to plaintext would defeat the production security
            // requirement. Android 6+ devices support the required keystore.
            throw new IllegalStateException("Penyimpanan sesi aman tidak tersedia pada perangkat ini.", error);
        }
    }

    private void migrateLegacySession(Context context) {
        if (isLoggedIn()) return;
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String oldToken = legacy.getString(KEY_TOKEN, "").trim();
        if (oldToken.isEmpty()) return;
        boolean saved = preferences.edit()
                .putString(KEY_TOKEN, oldToken)
                .putString(KEY_NAME, legacy.getString(KEY_NAME, ""))
                .putString(KEY_EMAIL, legacy.getString(KEY_EMAIL, ""))
                .putString(KEY_ROLE, legacy.getString(KEY_ROLE, ""))
                .putInt(KEY_DRIVER_ID, legacy.getInt(KEY_DRIVER_ID, 0))
                .putInt(KEY_HELPER_ID, legacy.getInt(KEY_HELPER_ID, 0))
                .commit();
        if (saved) legacy.edit().clear().apply();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String first(JSONObject data, String... keys) {
        for (String key : keys) {
            String value = data.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
