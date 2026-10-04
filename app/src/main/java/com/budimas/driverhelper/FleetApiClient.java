package com.budimas.driverhelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

final class FleetApiClient {
    private static final int TIMEOUT_MS = 20000;
    private final AppSession session;

    FleetApiClient(AppSession session) {
        this.session = session;
    }

    JSONObject login(String identity, String password) throws Exception {
        JSONObject body = new JSONObject();
        // The existing Driver/Helper API accepts username, email, or telephone
        // through its username field.
        body.put("username", identity.trim());
        body.put("password", password);
        return request("POST", "helper-driver/login", body, false);
    }

    JSONObject trips() throws Exception {
        return request("GET", "fleet-mobile/trips", null, true);
    }

    JSONObject loadingAssignments() throws Exception {
        return request("GET", "fleet-mobile/loading/assignments", null, true);
    }

    JSONObject readyToLoad(JSONObject selection) throws Exception {
        String query = "?id_armada=" + selection.getInt("id_armada") + "&id_driver=" + selection.getInt("id_driver")
                + "&delivery_date=" + URLEncoder.encode(selection.getString("delivery_date"), "UTF-8");
        return request("GET", "fleet-mobile/loading/ready-to-load" + query, null, true);
    }

    JSONObject processLoading(JSONObject selection, JSONArray items, String action) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("id_armada", selection.getInt("id_armada")); payload.put("id_driver", selection.getInt("id_driver"));
        payload.put("delivery_date", selection.getString("delivery_date")); payload.put("items", items); payload.put("action", action);
        return request("POST", "fleet-mobile/loading/process", payload, true);
    }

    /**
     * Returns the loading-manifest detail assigned to a trip.  The endpoint is
     * deliberately separate from the trip list because a manifest can contain
     * many delivery notes and item rows.
     */
    JSONObject manifest(int tripId) throws Exception {
        return request("GET", "fleet-mobile/trips/" + tripId + "/manifest", null, true);
    }

    /**
     * Records the driver's or helper's physical loading check.  Item rows are
     * sent in PCS so the server can validate them against the canonical WMS
     * conversion instead of a display UOM value on the handset.
     */
    JSONObject confirmManifest(int tripId, String status, String notes, JSONArray items) throws Exception {
        JSONObject body = new JSONObject();
        body.put("status", status);
        body.put("notes", notes == null ? "" : notes.trim());
        body.put("items", items == null ? new JSONArray() : items);
        return request("POST", "fleet-mobile/trips/" + tripId + "/manifest/confirm", body, true);
    }

    /**
     * QR is not trusted merely because the camera decoded it. The server checks
     * ownership against this manifest, item, pallet/batch, and WMS status.
     */
    JSONObject validateManifestQr(int tripId, String manifestItemId, String qrPayload) throws Exception {
        JSONObject body = new JSONObject();
        body.put("manifest_detail_id", manifestItemId);
        body.put("qr_payload", qrPayload == null ? "" : qrPayload.trim());
        return request("POST", "fleet-mobile/trips/" + tripId + "/manifest/qr/validate", body, true);
    }

    /** Each store/invoice stop is separately delivered and acknowledged. */
    JSONObject stops(int tripId) throws Exception {
        return request("GET", "fleet-mobile/trips/" + tripId + "/stops", null, true);
    }

    JSONObject arriveStop(int tripId, String stopId, JSONObject body) throws Exception {
        return request("POST", stopRoute(tripId, stopId) + "/arrival", body, true);
    }

    JSONObject submitPod(int tripId, String stopId, JSONObject body) throws Exception {
        return request("POST", stopRoute(tripId, stopId) + "/pod", body, true);
    }

    JSONObject startTrip(int tripId, String odometer, String notes) throws Exception {
        JSONObject body = new JSONObject();
        body.put("odometer", odometer.trim());
        body.put("notes", notes.trim());
        return request("POST", "fleet-mobile/trips/" + tripId + "/start", body, true);
    }

    JSONObject completeTrip(int tripId, String odometer, String destinationNote, String notes) throws Exception {
        JSONObject body = new JSONObject();
        body.put("odometer", odometer.trim());
        body.put("destination_note", destinationNote.trim());
        body.put("notes", notes.trim());
        return request("POST", "fleet-mobile/trips/" + tripId + "/complete", body, true);
    }

    JSONObject sendLocation(int tripId, double latitude, double longitude, float accuracy, float speedMps, float heading, int battery) throws Exception {
        return sendLocationPayload(tripId, locationPayload(latitude, longitude, accuracy, speedMps, heading, battery));
    }

    JSONObject sendLocationPayload(int tripId, JSONObject body) throws Exception {
        return request("POST", "fleet-mobile/trips/" + tripId + "/location", body, true);
    }

    JSONObject locationPayload(double latitude, double longitude, float accuracy, float speedMps, float heading, int battery) throws Exception {
        return locationPayload(latitude, longitude, accuracy, speedMps, heading, battery, System.currentTimeMillis());
    }

    JSONObject locationPayload(double latitude, double longitude, float accuracy, float speedMps, float heading, int battery, long capturedAtMillis) throws Exception {
        JSONObject body = new JSONObject();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("latitude", latitude);
        body.put("longitude", longitude);
        body.put("accuracy_m", accuracy);
        body.put("speed_kmh", Math.round(speedMps * 3.6f * 100f) / 100f);
        body.put("heading", heading);
        body.put("battery_pct", battery);
        body.put("captured_at", isoTimestamp(capturedAtMillis));
        return body;
    }

    private static String isoTimestamp(long timestampMillis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date(timestampMillis > 0 ? timestampMillis : System.currentTimeMillis()));
    }

    JSONObject reportIssue(int tripId, String title, String notes, String odometer) throws Exception {
        JSONObject body = new JSONObject();
        body.put("title", title.trim());
        body.put("notes", notes.trim());
        body.put("odometer", odometer.trim());
        return request("POST", "fleet-mobile/trips/" + tripId + "/report", body, true);
    }

    /** Used only by the encrypted-session, durable offline outbox. */
    JSONObject sendQueuedPost(String path, JSONObject body) throws Exception {
        if (path == null || path.trim().isEmpty() || path.startsWith("http://") || path.startsWith("https://") || path.contains("..")) {
            throw new ApiException("Rute sinkronisasi offline tidak valid.", 400);
        }
        return request("POST", path, body, true);
    }

    static boolean isRetryable(Exception error) {
        if (!(error instanceof ApiException)) return true;
        int status = ((ApiException) error).status;
        return status == 0 || status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private JSONObject request(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        if (!BuildConfig.ALLOW_CLEARTEXT && BuildConfig.API_BASE_URL.toLowerCase().startsWith("http://")) {
            throw new ApiException("Rilis produksi hanya dapat terhubung ke API HTTPS.", 0);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint(path)).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + session.token());
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String payload = read(source);
        JSONObject response = payload.isBlank() ? new JSONObject() : new JSONObject(payload);
        if (status >= 400 || "ERROR".equalsIgnoreCase(response.optString("status"))) {
            throw new ApiException(response.optString("message", "Permintaan ke server gagal."), status);
        }
        return response;
    }

    private String endpoint(String path) {
        String base = BuildConfig.API_BASE_URL.endsWith("/") ? BuildConfig.API_BASE_URL : BuildConfig.API_BASE_URL + "/";
        return base + (path == null ? "" : path.replaceFirst("^/+", ""));
    }

    private String stopRoute(int tripId, String stopId) throws Exception {
        if (stopId == null || stopId.trim().isEmpty()) throw new ApiException("ID tujuan pengiriman tidak tersedia.", 400);
        return "fleet-mobile/trips/" + tripId + "/stops/" + URLEncoder.encode(stopId.trim(), StandardCharsets.UTF_8.name());
    }

    private String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    static JSONArray array(JSONObject response, String key) {
        return response.optJSONArray(key) != null ? response.optJSONArray(key) : new JSONArray();
    }

    static final class ApiException extends Exception {
        final int status;
        ApiException(String message, int status) {
            super(message);
            this.status = status;
        }
    }
}
