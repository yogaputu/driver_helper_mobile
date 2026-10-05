package com.budimas.driverhelper;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_LOCATION = 301;
    private static final int REQUEST_NOTIFICATIONS = 302;
    private static final int REQUEST_CAMERA = 303;
    /**
     * The mobile app never promotes a WMS task itself. These are the only
     * canonical manifest states that may unlock the corresponding mobile step.
     */
    private static final String WMS_MANIFEST_LOADED = "LOADED";
    private static final String WMS_MANIFEST_ON_ROUTE = "ON_ROUTE";
    private static final String WMS_MANIFEST_DELIVERED = "DELIVERED";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final List<ManifestItem> manifestItems = new ArrayList<>();
    private AppSession session;
    private FleetApiClient api;
    private OfflineQueue offlineQueue;
    private FusedLocationProviderClient locationClient;
    private FrameLayout container;
    private Runnable afterLocationPermission;
    private Runnable afterCameraPermission;
    private ActivityResultLauncher<ScanOptions> qrScanner;
    private ActivityResultLauncher<Intent> podPhotoCapture;
    private String scannerTargetItemId = "";
    private int scannerTripId;
    private PodDraft activePodDraft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(color(R.color.app_background));
        getWindow().setNavigationBarColor(color(R.color.app_background));
        session = new AppSession(this);
        api = new FleetApiClient(session);
        offlineQueue = new OfflineQueue(this);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        qrScanner = registerForActivityResult(new ScanContract(), this::onQrScanResult);
        podPhotoCapture = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::onPodPhotoResult
        );
        container = new FrameLayout(this);
        container.setBackgroundColor(color(R.color.app_background));
        setContentView(container);
        requestInitialPermissions();
        if (session.isLoggedIn()) {
            FleetSyncWorker.schedule(this);
            loadTrips();
        } else {
            showLogin();
        }
    }

    private void showLogin() {
        LinearLayout page = beginPage();
        addSpace(page, 18);
        page.addView(brandHeader("BUDIMAS", "DRIVER HELPER", "OPERASIONAL ARMADA"));
        addSpace(page, 24);

        LinearLayout welcome = heroCard(color(R.color.blue));
        welcome.addView(label("RUANG KERJA PENGIRIMAN", 11, color(R.color.cyan), Typeface.BOLD));
        welcome.addView(title("Perjalanan yang rapi,\ndalam satu genggaman.", 27), withTop(7));
        welcome.addView(body("Kelola manifest, checklist muatan, pengantaran toko, dan bukti penerimaan dari satu aplikasi.", 15), withTop(10));
        page.addView(welcome, matchWidth());
        addSpace(page, 20);

        LinearLayout loginCard = card();
        loginCard.addView(label("AKSES PERSONEL", 11, color(R.color.cyan), Typeface.BOLD));
        loginCard.addView(title("Masuk ke operasional Anda", 22), withTop(5));
        loginCard.addView(body("Gunakan akun Driver atau Helper yang terdaftar pada manifest.", 14), withTop(5));
        loginCard.addView(label("IDENTITAS AKUN", 11, color(R.color.text_muted), Typeface.BOLD), withTop(18));
        EditText identity = input("Email, username, atau telepon", InputType.TYPE_CLASS_TEXT);
        loginCard.addView(identity, withTop(7));
        loginCard.addView(label("PASSWORD", 11, color(R.color.text_muted), Typeface.BOLD), withTop(14));
        EditText password = input("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        loginCard.addView(password, withTop(7));
        TextView feedback = body("", 14);
        feedback.setTextColor(color(R.color.red));
        loginCard.addView(feedback, withTop(12));
        Button login = primaryButton("Masuk ke Operasional");
        loginCard.addView(login, withTop(18));
        loginCard.addView(body("Akses dilindungi dan hanya menampilkan perjalanan yang ditugaskan ke akun Anda.", 12), withTop(13));
        page.addView(loginCard, matchWidth());

        login.setOnClickListener(view -> {
            String userIdentity = identity.getText().toString().trim();
            String userPassword = password.getText().toString();
            if (userIdentity.isEmpty() || userPassword.isEmpty()) {
                feedback.setText("Masukkan akun dan password terlebih dahulu.");
                return;
            }
            login.setEnabled(false);
            login.setText("Memeriksa akun...");
            feedback.setText("");
            networkExecutor.execute(() -> {
                try {
                    JSONObject response = api.login(userIdentity, userPassword);
                    JSONObject data = response.optJSONObject("data");
                    if (data == null || data.optString("token").isEmpty()) {
                        throw new Exception("Token sesi tidak diterima dari server.");
                    }
                    session.saveLogin(data);
                    runOnUiThread(this::loadTrips);
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        login.setEnabled(true);
                        login.setText("Masuk ke Operasional");
                        feedback.setText(messageOf(error));
                    });
                }
            });
        });
    }

    private void loadTrips() {
        showDashboardLoading();
        networkExecutor.execute(() -> {
            try {
                JSONObject response = api.trips();
                JSONObject profile = response.optJSONObject("profile");
                if (profile != null) session.updateProfile(profile);
                runOnUiThread(() -> {
                    reconcileActiveTrip(response);
                    showDashboard(response);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showDashboardError(messageOf(error)));
            }
        });
    }

    private void showDashboardLoading() {
        LinearLayout page = beginPage();
        page.addView(topBar("Operasional Armada", false));
        addSpace(page, 24);
        LinearLayout loading = heroCard(color(R.color.blue));
        loading.addView(label("SINKRONISASI OPERASIONAL", 11, color(R.color.cyan), Typeface.BOLD));
        loading.addView(title("Memuat perjalanan...", 25), withTop(6));
        loading.addView(body("Menyiapkan manifest dan status pengiriman yang ditugaskan ke akun Anda.", 15), withTop(7));
        page.addView(loading, matchWidth());
    }

    private void showDashboardError(String message) {
        LinearLayout page = beginPage();
        page.addView(topBar("Operasional Armada", false));
        addSpace(page, 24);
        LinearLayout errorCard = accentCard(color(R.color.red));
        errorCard.addView(label("KONEKSI BELUM SIAP", 11, color(R.color.red), Typeface.BOLD));
        errorCard.addView(title("Data belum dapat dimuat", 24), withTop(6));
        errorCard.addView(body(message, 15), withTop(8));
        page.addView(errorCard, matchWidth());
        Button retry = primaryButton("Coba Lagi");
        retry.setOnClickListener(view -> loadTrips());
        page.addView(retry, withTop(20));
    }

    private void showDashboard(JSONObject response) {
        LinearLayout page = beginPage();
        page.addView(topBar("Operasional Armada", false));
        addSpace(page, 22);

        LinearLayout profile = heroCard(color(R.color.blue));
        profile.addView(label("SELAMAT DATANG KEMBALI", 11, color(R.color.cyan), Typeface.BOLD));
        profile.addView(title(session.name(), 24), withTop(6));
        String role = session.isDriver() ? "DRIVER" : "HELPER";
        profile.addView(body("Pantau tugas yang sedang aktif dan selesaikan setiap tahap pengiriman dengan bukti yang lengkap.", 14), withTop(7));
        TextView roleView = chip(role + "  ·  " + (session.email().isEmpty() ? "Akun Budimas" : session.email()), color(R.color.cyan));
        profile.addView(roleView, withTop(13));
        page.addView(profile, matchWidth());

        if (session.isDriver()) {
            Button loading = primaryButton("Loading Armada · Barang Lolos Checker");
            loading.setOnClickListener(view -> loadLoadingAssignments());
            page.addView(loading, withTop(14));
        }

        int pendingOffline = offlineQueue.outstandingCount(session, 0);
        if (pendingOffline > 0) {
            LinearLayout offline = accentCard(color(R.color.orange));
            offline.addView(label("SINKRONISASI OFFLINE", 12, color(R.color.orange), Typeface.BOLD));
            offline.addView(body(pendingOffline + " data operasional menunggu koneksi. GPS, checklist, dan POD akan dikirim otomatis saat jaringan tersedia.", 14), withTop(6));
            page.addView(offline, withTop(14));
        }

        JSONObject summary = response.optJSONObject("summary");
        if (summary == null) summary = new JSONObject();
        page.addView(summaryRow(summary), withTop(14));

        LinearLayout section = horizontal();
        LinearLayout sectionText = new LinearLayout(this);
        sectionText.setOrientation(LinearLayout.VERTICAL);
        sectionText.addView(label("TUGAS AKTIF", 11, color(R.color.cyan), Typeface.BOLD));
        sectionText.addView(title("Perjalanan Saya", 22), withTop(2));
        section.addView(sectionText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = secondaryButton("Muat Ulang");
        refresh.setOnClickListener(view -> loadTrips());
        section.addView(refresh);
        page.addView(section, withTop(24));
        page.addView(body("Manifest hanya tampil bila Anda tercatat sebagai Driver atau Helper penugasan.", 14), withTop(6));

        JSONArray trips = FleetApiClient.array(response, "data");
        if (trips.length() == 0) {
            LinearLayout empty = accentCard(color(R.color.blue));
            empty.addView(label("BELUM ADA PENUGASAN", 11, color(R.color.cyan), Typeface.BOLD));
            empty.addView(title("Belum ada perjalanan", 19));
            empty.addView(body("Belum ada manifest yang ditugaskan ke akun ini. Hubungi admin gudang bila penugasan sudah dibuat.", 15), withTop(7));
            page.addView(empty, withTop(14));
            return;
        }
        for (int index = 0; index < trips.length(); index++) {
            JSONObject trip = trips.optJSONObject(index);
            if (trip != null) page.addView(tripCard(trip), withTop(12));
        }
    }

    private android.widget.Spinner loadingSpinner(java.util.List<String> labels) {
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private void loadLoadingAssignments() {
        showDashboardLoading();
        networkExecutor.execute(() -> {
            try {
                JSONArray assignments = FleetApiClient.array(api.loadingAssignments(), "data");
                runOnUiThread(() -> showLoadingAssignments(assignments));
            } catch (Exception error) { runOnUiThread(() -> showDashboardError(messageOf(error))); }
        });
    }

    private void showLoadingAssignments(JSONArray assignments) {
        LinearLayout page = beginPage(); page.addView(topBar("Loading Armada", true));
        page.addView(body("Pilih armada, driver, dan tanggal pengiriman. Hanya penugasan Anda yang tersedia; barang muncul setelah checker OK.", 15), withTop(14));
        java.util.ArrayList<String> fleetIds = new java.util.ArrayList<>(), fleetNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> driverIds = new java.util.ArrayList<>(), driverNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> dates = new java.util.ArrayList<>();
        fleetIds.add(""); fleetNames.add("Pilih armada"); driverIds.add(""); driverNames.add("Pilih driver"); dates.add("Pilih tanggal pengiriman");
        for (int i = 0; i < assignments.length(); i++) {
            JSONObject a = assignments.optJSONObject(i);
            if (!fleetIds.contains(a.optString("id_armada"))) { fleetIds.add(a.optString("id_armada")); fleetNames.add(a.optString("vehicle")); }
            if (!driverIds.contains(a.optString("id_driver"))) { driverIds.add(a.optString("id_driver")); driverNames.add(a.optString("driver")); }
            if (!dates.contains(a.optString("delivery_date"))) dates.add(a.optString("delivery_date"));
        }
        android.widget.Spinner fleets = loadingSpinner(fleetNames), drivers = loadingSpinner(driverNames), days = loadingSpinner(dates);
        page.addView(body("Armada", 14), withTop(14)); page.addView(fleets, matchWidth());
        page.addView(body("Driver", 14), withTop(14)); page.addView(drivers, matchWidth());
        page.addView(body("Tanggal Pengiriman", 14), withTop(14)); page.addView(days, matchWidth());
        Button show = primaryButton("Tampilkan Barang");
        show.setOnClickListener(v -> {
            for (int i = 0; i < assignments.length(); i++) {
                JSONObject a = assignments.optJSONObject(i);
                if (a.optString("id_armada").equals(fleetIds.get(fleets.getSelectedItemPosition()))
                        && a.optString("id_driver").equals(driverIds.get(drivers.getSelectedItemPosition()))
                        && a.optString("delivery_date").equals(dates.get(days.getSelectedItemPosition()))) {
                    loadReadyGoods(a); return;
                }
            }
            toast("Lengkapi pilihan sesuai jadwal penugasan.");
        });
        page.addView(show, withTop(16));
        if (assignments.length() == 0) page.addView(body("Belum ada jadwal loading untuk Anda.", 15), withTop(16));
    }

    private void loadReadyGoods(JSONObject selection) {
        showDashboardLoading();
        networkExecutor.execute(() -> {
            try {
                JSONArray rows = FleetApiClient.array(api.readyToLoad(selection), "data");
                runOnUiThread(() -> showReadyGoods(selection, rows));
            } catch (Exception error) { runOnUiThread(() -> showDashboardError(messageOf(error))); }
        });
    }

    private void showReadyGoods(JSONObject selection, JSONArray rows) {
        LinearLayout page = beginPage(); page.addView(topBar("Loading Armada", true));
        page.addView(title(selection.optString("vehicle") + " · " + selection.optString("delivery_date"), 20), withTop(14));
        page.addView(body("Driver: " + selection.optString("driver"), 15), withTop(6));
        Button change = secondaryButton("Ganti Armada / Driver / Tanggal"); change.setOnClickListener(v -> loadLoadingAssignments()); page.addView(change, withTop(14));
        java.util.LinkedHashMap<String, JSONArray> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); String key = row.optString("ScheduleKey");
            if (!groups.containsKey(key)) groups.put(key, new JSONArray()); groups.get(key).put(row);
        }
        if (rows.length() == 0) page.addView(body("Belum ada barang lolos checker, atau semua barang sudah dimuat.", 16), withTop(20));
        for (JSONArray items : groups.values()) {
            LinearLayout card = accentCard(color(R.color.blue));
            card.addView(body("Helper: " + items.optJSONObject(0).optString("HelperRencana"), 14));
            boolean allStarted = true;
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                card.addView(body(row.optString("ShipmentReference") + " · " + row.optString("Nota") + "\n" + row.optString("KodeBarang") + " · " + row.optString("NamaBarang") + "\n" + row.optInt("picked_quantity") + " PCS · " + row.optString("loading_status"), 15), withTop(12));
                if (!"LOADING".equals(row.optString("loading_status"))) allStarted = false;
            }
            final String action = allStarted ? "complete" : "start";
            Button submit = primaryButton(allStarted ? "Selesaikan Loading" : "Mulai Loading");
            submit.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle(submit.getText())
                    .setMessage("complete".equals(action) ? "Pastikan semua barang telah masuk armada sebelum membuat manifest." : "Mulai memuat barang penugasan ini?")
                    .setNegativeButton("Batal", null).setPositiveButton("Ya", (d, which) -> {
                        submit.setEnabled(false);
                        networkExecutor.execute(() -> {
                            try {
                                JSONObject result = api.processLoading(selection, items, action);
                                runOnUiThread(() -> { toast(result.optString("message", "Loading tersimpan")); loadReadyGoods(selection); });
                            } catch (Exception error) { runOnUiThread(() -> { submit.setEnabled(true); toast(messageOf(error)); }); }
                        });
                    }).show());
            card.addView(submit, withTop(16)); page.addView(card, withTop(16));
        }
    }

    private View summaryRow(JSONObject summary) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = horizontal();
        row.setPadding(0, 0, dp(6), 0);
        row.addView(metric("Total", summary.optInt("total"), color(R.color.blue)));
        row.addView(metric("Siap Jalan", summary.optInt("siap_jalan"), color(R.color.orange)), left(10));
        row.addView(metric("Berangkat", summary.optInt("berangkat"), color(R.color.green)), left(10));
        row.addView(metric("Selesai", summary.optInt("selesai"), color(R.color.text_secondary)), left(10));
        scroll.addView(row);
        return scroll;
    }

    private View metric(String caption, int value, int highlight) {
        LinearLayout box = accentCard(highlight);
        box.setMinimumWidth(dp(124));
        box.addView(label(caption.toUpperCase(Locale.ROOT), 11, color(R.color.text_secondary), Typeface.BOLD));
        TextView number = title(String.valueOf(value), 28);
        number.setTextColor(highlight);
        box.addView(number, withTop(5));
        return box;
    }

    private View tripCard(JSONObject trip) {
        String tripStatus = value(trip, "status", "");
        String manifestStatus = canonicalManifestStatus(null, trip);
        LinearLayout card = accentCard(statusColor(tripStatus));
        LinearLayout top = horizontal();
        LinearLayout manifestText = new LinearLayout(this);
        manifestText.setOrientation(LinearLayout.VERTICAL);
        manifestText.addView(label("MANIFEST PERJALANAN", 11, color(R.color.text_muted), Typeface.BOLD));
        manifestText.addView(title(value(trip, "no_manifest", "-"), 21), withTop(3));
        top.addView(manifestText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = chip(value(trip, "status", "-"), statusColor(tripStatus));
        top.addView(status);
        card.addView(top);
        card.addView(body(value(trip, "nama_armada", "Armada") + "  ·  " + value(trip, "no_pelat", "-"), 16), withTop(9));
        card.addView(body("Driver  " + value(trip, "nama_driver", "-") + "\nHelper  " + value(trip, "nama_helpers", "-"), 14), withTop(9));
        card.addView(body("Status WMS: " + manifestStatus, 14), withTop(7));
        if (!isManifestReadyForVerification(null, trip) && !isManifestInTransitOrDelivered(null, trip)) {
            card.addView(body("Menunggu proses WMS selesai sampai manifest berstatus LOADED. Aplikasi tidak dapat memulai Shipping dari status picking/dock.", 13), withTop(5));
        }
        LinearLayout stats = horizontal();
        stats.addView(tripStat(value(trip, "total_nota", "0"), "NOTA"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView itemStat = tripStat(value(trip, "total_item", "0"), "ITEM");
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemParams.leftMargin = dp(10);
        stats.addView(itemStat, itemParams);
        card.addView(stats, withTop(14));
        Button detail = primaryButton("Buka Detail Perjalanan");
        detail.setOnClickListener(view -> showTripDetail(trip));
        card.addView(detail, withTop(16));
        return card;
    }

    private void showTripDetail(JSONObject trip) {
        LinearLayout page = beginPage();
        page.addView(topBar("Detail Perjalanan", true));
        addSpace(page, 20);
        LinearLayout tripHero = heroCard(statusColor(value(trip, "status", "")));
        tripHero.addView(label("MANIFEST AKTIF", 11, color(R.color.cyan), Typeface.BOLD));
        tripHero.addView(title(value(trip, "no_manifest", "-"), 27), withTop(4));
        TextView status = chip(value(trip, "status", "-"), statusColor(value(trip, "status", "")));
        tripHero.addView(status, withTop(11));
        page.addView(tripHero, matchWidth());

        LinearLayout overview = card();
        overview.addView(label("ARMADA", 12, color(R.color.text_secondary), Typeface.BOLD));
        overview.addView(title(value(trip, "nama_armada", "-"), 21), withTop(4));
        overview.addView(body("Plat: " + value(trip, "no_pelat", "-") + "\nDriver: " + value(trip, "nama_driver", "-") + "\nHelper: " + value(trip, "nama_helpers", "-"), 16), withTop(10));
        overview.addView(body("Muatan: " + value(trip, "total_nota", "0") + " nota / " + value(trip, "total_item", "0") + " item", 15), withTop(10));
        page.addView(overview, withTop(18));

        int tripId = trip.optInt("id");
        boolean assignedDriver = "DRIVER".equalsIgnoreCase(value(trip, "app_role", ""));
        String state = value(trip, "status", "");
        String manifestStatus = canonicalManifestStatus(null, trip);
        boolean manifestLoaded = isManifestReadyForVerification(null, trip);

        if (!manifestLoaded && !isManifestInTransitOrDelivered(null, trip)) {
            LinearLayout blocked = accentCard(color(R.color.orange));
            blocked.addView(label("MENUNGGU FINALISASI WMS", 11, color(R.color.orange), Typeface.BOLD));
            blocked.addView(title("Manifest belum LOADED", 19), withTop(4));
            blocked.addView(body("Status WMS: " + manifestStatus + ". Picking, checker, dan loading harus diselesaikan di WMS terlebih dahulu. Driver/Helper tidak mengubah status PICKED atau Shipping dari aplikasi.", 14), withTop(7));
            page.addView(blocked, withTop(16));
        }

        LinearLayout manifestCheck = menuCard("01", "VERIFIKASI MUATAN", "Cek manifest, barang, dan tujuan", "Sebelum perjalanan dimulai, pastikan barang yang dimuat sesuai manifest. QR barang atau pallet dapat dipindai sebagai bukti pengecekan.", color(R.color.blue));
        Button inspectManifest = primaryButton(manifestLoaded ? "Verifikasi Muatan & Tujuan" : "Lihat Status Manifest WMS");
        inspectManifest.setOnClickListener(view -> openManifestVerification(trip));
        manifestCheck.addView(inspectManifest, withTop(14));
        page.addView(manifestCheck, withTop(18));

        LinearLayout delivery = menuCard("02", "PENGANTARAN PER TOKO", "Tujuan, tiba, dan bukti penerimaan", "Setiap toko harus dicatat terpisah: GPS/waktu tiba, jumlah aktual PCS dan UOM, retur atau selisih, penerima, foto, serta tanda tangan.", color(R.color.cyan));
        Button openStops = primaryButton("Buka Pengantaran per Toko");
        openStops.setOnClickListener(view -> openDeliveryStops(trip));
        delivery.addView(openStops, withTop(14));
        page.addView(delivery, withTop(14));

        page.addView(label("AKSI PERJALANAN", 12, color(R.color.text_secondary), Typeface.BOLD), withTop(22));

        if (assignedDriver && "SIAP_JALAN".equalsIgnoreCase(state)) {
            if (manifestLoaded) {
                Button start = primaryButton("Mulai Perjalanan & Aktifkan GPS");
                start.setOnClickListener(view -> checkManifestBeforeStart(trip));
                page.addView(start, withTop(10));
            } else {
                Button waitForWms = secondaryButton("Menunggu Manifest WMS LOADED");
                waitForWms.setOnClickListener(view -> openManifestVerification(trip));
                page.addView(waitForWms, withTop(10));
            }
        }
        if (assignedDriver && "BERANGKAT".equalsIgnoreCase(state)) {
            Button complete = primaryButton("Selesaikan Perjalanan");
            complete.setBackground(background(color(R.color.green), color(R.color.green), 12));
            complete.setOnClickListener(view -> checkStopsBeforeComplete(trip));
            page.addView(complete, withTop(10));
        }
        if (!assignedDriver) {
            page.addView(body("Anda tercatat sebagai Helper. Pembaruan GPS dan laporan kendala dapat dikirim selama perjalanan berjalan.", 15), withTop(10));
        }
        if ("BERANGKAT".equalsIgnoreCase(state)) {
            Button sendLocation = secondaryButton("Kirim Lokasi Saat Ini");
            sendLocation.setOnClickListener(view -> withLocationPermission(() -> captureAndSendLocation(tripId)));
            page.addView(sendLocation, withTop(10));
        }
        Button issue = dangerButton("Laporkan Kendala Armada");
        issue.setOnClickListener(view -> showIssueDialog(tripId));
        page.addView(issue, withTop(10));

        double latitude = trip.optDouble("latitude", 0d);
        double longitude = trip.optDouble("longitude", 0d);
        if (latitude != 0d || longitude != 0d) {
            LinearLayout gps = card();
            gps.addView(label("LOKASI TERAKHIR", 12, color(R.color.text_secondary), Typeface.BOLD));
            gps.addView(body(String.format(Locale.US, "%.6f, %.6f", latitude, longitude), 17), withTop(5));
            gps.addView(body("Diperbarui: " + value(trip, "gps_at", "-"), 14), withTop(5));
            Button map = secondaryButton("Buka Navigasi");
            map.setOnClickListener(view -> openMap(latitude, longitude));
            gps.addView(map, withTop(12));
            page.addView(gps, withTop(18));
        }
    }

    private void openManifestVerification(JSONObject trip) {
        int tripId = trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        showManifestLoading(trip);
        networkExecutor.execute(() -> {
            try {
                JSONObject response = api.manifest(tripId);
                runOnUiThread(() -> showManifestDetail(trip, response));
            } catch (Exception error) {
                runOnUiThread(() -> showManifestError(trip, messageOf(error)));
            }
        });
    }

    private void showManifestLoading(JSONObject trip) {
        LinearLayout page = beginPage();
        page.addView(topBar("Verifikasi Manifest", () -> showTripDetail(trip)));
        addSpace(page, 22);
        page.addView(title("Memuat muatan perjalanan...", 24));
        page.addView(body("Menyiapkan daftar barang, jumlah PCS, serta tujuan pengiriman dari manifest.", 16), withTop(8));
    }

    private void showManifestError(JSONObject trip, String message) {
        LinearLayout page = beginPage();
        page.addView(topBar("Verifikasi Manifest", () -> showTripDetail(trip)));
        addSpace(page, 22);
        page.addView(title("Manifest belum dapat dimuat", 24));
        page.addView(body(message, 16), withTop(8));
        Button retry = primaryButton("Muat Ulang Manifest");
        retry.setOnClickListener(view -> openManifestVerification(trip));
        page.addView(retry, withTop(20));
    }

    private void checkManifestBeforeStart(JSONObject trip) {
        int tripId = trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        toast("Memeriksa konfirmasi muatan...");
        networkExecutor.execute(() -> {
            try {
                JSONObject response = api.manifest(tripId);
                JSONObject data = manifestData(response);
                JSONObject confirmation = data.optJSONObject("confirmation");
                runOnUiThread(() -> {
                    if (!isManifestReadyForVerification(data, trip)) {
                        showWmsManifestBlocked(trip, data, "memulai perjalanan");
                    } else if (isManifestConfirmed(confirmation)) {
                        showStartDialog(trip);
                    } else {
                        toast("Verifikasi manifest harus berstatus SESUAI sebelum perjalanan dimulai.");
                        showManifestDetail(trip, response);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("Manifest belum dapat diverifikasi: " + messageOf(error)));
            }
        });
    }

    private void showManifestDetail(JSONObject trip, JSONObject response) {
        JSONObject data = manifestData(response);
        if (!isManifestReadyForVerification(data, trip)) {
            manifestItems.clear();
            scannerTargetItemId = "";
            scannerTripId = 0;
            showWmsManifestBlocked(trip, data, "verifikasi muatan");
            return;
        }
        JSONObject manifest = data.optJSONObject("manifest");
        if (manifest == null) manifest = trip;
        JSONObject confirmation = data.optJSONObject("confirmation");
        if (confirmation == null) confirmation = new JSONObject();
        JSONObject summary = data.optJSONObject("summary");
        if (summary == null) summary = new JSONObject();
        JSONArray destinations = FleetApiClient.array(data, "destinations");
        JSONArray items = FleetApiClient.array(data, "items");
        JSONArray confirmedItems = FleetApiClient.array(confirmation, "items");

        manifestItems.clear();
        scannerTargetItemId = "";
        scannerTripId = trip.optInt("id");

        LinearLayout page = beginPage();
        page.addView(topBar("Verifikasi Manifest", () -> showTripDetail(trip)));
        addSpace(page, 18);
        page.addView(label("MANIFEST", 12, color(R.color.green), Typeface.BOLD));
        page.addView(title(firstValue(manifest, value(trip, "no_manifest", "-"), "no_manifest", "nomor_manifest", "code"), 26), withTop(4));
        page.addView(chip("WMS · " + canonicalManifestStatus(data, trip), color(R.color.green)), withTop(8));

        String confirmationStatus = firstValue(confirmation, "BELUM DIKONFIRMASI", "status");
        page.addView(chip(confirmationStatus, confirmationColor(confirmationStatus)), withTop(10));

        LinearLayout summaryCard = card();
        summaryCard.addView(label("RINGKASAN MUATAN", 12, color(R.color.text_secondary), Typeface.BOLD));
        String totalNota = firstValue(summary, value(trip, "total_nota", ""), "total_nota", "nota_count", "total_invoices");
        String totalItem = firstValue(summary, value(trip, "total_item", ""), "total_item", "item_count", "total_items");
        String totalPcs = firstValue(summary, "", "total_qty_pcs", "total_pcs", "qty_pcs");
        StringBuilder totals = new StringBuilder();
        if (!totalNota.isEmpty()) totals.append(totalNota).append(" nota");
        if (!totalItem.isEmpty()) {
            if (totals.length() > 0) totals.append("  |  ");
            totals.append(totalItem).append(" item");
        }
        if (!totalPcs.isEmpty()) {
            if (totals.length() > 0) totals.append("  |  ");
            totals.append(totalPcs).append(" PCS");
        }
        summaryCard.addView(body(totals.length() == 0 ? "Ringkasan jumlah belum tersedia." : totals.toString(), 16), withTop(6));
        String savedNotes = firstValue(confirmation, "", "notes", "catatan");
        if (!savedNotes.isEmpty()) {
            summaryCard.addView(body("Catatan konfirmasi: " + savedNotes, 14), withTop(8));
        }
        page.addView(summaryCard, withTop(16));

        page.addView(label("TUJUAN PENGIRIMAN", 12, color(R.color.text_secondary), Typeface.BOLD), withTop(22));
        if (destinations.length() == 0) {
            page.addView(body("Tujuan toko belum tersedia pada manifest ini.", 15), withTop(7));
        } else {
            for (int index = 0; index < destinations.length(); index++) {
                JSONObject destination = destinations.optJSONObject(index);
                if (destination != null) page.addView(destinationCard(destination, index + 1), withTop(10));
            }
        }

        page.addView(label("CHECKLIST BARANG DIMUAT", 12, color(R.color.text_secondary), Typeface.BOLD), withTop(22));
        page.addView(body("Jumlah aktual selalu dicatat dalam PCS agar sama dengan konversi UOM pada WMS. Pindai QR bila label pallet/barang tersedia.", 15), withTop(7));
        if (items.length() == 0) {
            page.addView(body("Detail barang belum tersedia dari server, sehingga konfirmasi belum dapat dikirim.", 15), withTop(10));
        } else {
            Button markAll = secondaryButton("Tandai Semua Barang Sesuai");
            markAll.setOnClickListener(view -> markAllItemsChecked());
            page.addView(markAll, withTop(12));
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null) {
                    String itemId = manifestItemId(item, index);
                    page.addView(manifestItemCard(item, confirmationItem(confirmedItems, itemId), index), withTop(10));
                }
            }
        }

        if (!manifestItems.isEmpty()) {
            page.addView(label("KONFIRMASI", 12, color(R.color.text_secondary), Typeface.BOLD), withTop(24));
            page.addView(body("Pilih Manifest Sesuai hanya setelah seluruh barang telah dicek. Gunakan Ada Selisih bila ada kekurangan, kelebihan, atau barang tidak sesuai.", 15), withTop(7));
            Button confirmMatch = primaryButton("Konfirmasi: Manifest Sesuai");
            confirmMatch.setOnClickListener(view -> showManifestConfirmationDialog(trip, "SESUAI"));
            page.addView(confirmMatch, withTop(12));
            Button confirmDifference = dangerButton("Laporkan Ada Selisih Muatan");
            confirmDifference.setOnClickListener(view -> showManifestConfirmationDialog(trip, "ADA_SELISIH"));
            page.addView(confirmDifference, withTop(10));
        }
    }

    private View destinationCard(JSONObject destination, int number) {
        LinearLayout card = card();
        String customer = firstValue(destination, "Toko tujuan " + number, "customer_name", "nama_toko", "nama_customer", "customer", "name");
        String address = firstValue(destination, "Alamat belum tersedia", "address", "alamat", "alamat_tujuan", "customer_address");
        String invoice = firstValue(destination, "", "no_faktur", "no_nota", "invoice_no", "no_invoice");
        String sequence = firstValue(destination, String.valueOf(number), "sequence", "urutan", "stop_number");
        card.addView(label("TUJUAN " + sequence, 12, color(R.color.text_secondary), Typeface.BOLD));
        card.addView(title(customer, 19), withTop(4));
        card.addView(body(address, 15), withTop(7));
        if (!invoice.isEmpty()) card.addView(body("Faktur: " + invoice, 14), withTop(6));
        Button navigate = secondaryButton("Navigasi ke Toko");
        navigate.setOnClickListener(view -> openDestinationNavigation(destination));
        card.addView(navigate, withTop(12));
        return card;
    }

    private View manifestItemCard(JSONObject item, JSONObject savedItem, int index) {
        LinearLayout card = card();
        String itemId = manifestItemId(item, index);
        String productCode = firstValue(item, "", "kode_barang", "product_code", "kode_produk", "sku");
        String productName = firstValue(item, "Barang " + (index + 1), "nama_barang", "product_name", "nama_produk", "produk", "name");
        String heading = productCode.isEmpty() ? productName : productCode + " · " + productName;
        double plannedPcs = optionalNumberValue(item, "planned_qty_pcs", "qty_pcs", "total_pcs", "quantity_pcs", "qty_base");
        String uomQuantity = firstValue(item, "", "qty_uom_display", "quantity_display", "qty_display", "qty_uom", "jumlah_uom");
        String batch = firstValue(item, "", "batch", "no_batch", "batch_number");
        String pallet = firstValue(item, "", "no_pallet", "pallet_code", "kode_pallet");

        card.addView(title(heading, 18));
        if (item.has("stok_ready")) card.addView(body("Stok Ready Gudang: " + firstValue(item, "0", "stok_ready") + " PCS", 13), withTop(4));
        if (!uomQuantity.isEmpty()) card.addView(body("Rencana UOM: " + uomQuantity, 15), withTop(6));
        if (!Double.isNaN(plannedPcs)) card.addView(body("Rencana: " + formatQuantity(plannedPcs) + " PCS", 15), withTop(4));
        if (!batch.isEmpty() || !pallet.isEmpty()) {
            String meta = (batch.isEmpty() ? "" : "Batch: " + batch) + (!batch.isEmpty() && !pallet.isEmpty() ? "  |  " : "") + (pallet.isEmpty() ? "" : "Pallet: " + pallet);
            card.addView(body(meta, 13), withTop(5));
        }

        CheckBox checked = new CheckBox(this);
        checked.setText("Barang sudah dimuat dan sesuai");
        checked.setTextColor(color(R.color.text_primary));
        checked.setTextSize(15);
        checked.setChecked(savedItem.optBoolean("checked", false));
        card.addView(checked, withTop(10));

        EditText actualQty = input("Jumlah aktual (PCS)", InputType.TYPE_CLASS_NUMBER);
        String savedActual = firstValue(savedItem, "", "actual_qty_pcs", "qty_actual_pcs", "actual_pcs");
        if (!savedActual.isEmpty()) actualQty.setText(savedActual);
        else if (!Double.isNaN(plannedPcs)) actualQty.setText(formatQuantity(plannedPcs));
        card.addView(actualQty, withTop(6));

        TextView scanInfo = body("Belum ada QR tervalidasi untuk barang ini.", 13);
        String savedScan = firstValue(savedItem, "", "scan_payload", "qr_payload", "scan_result");
        boolean savedQrVerified = savedItem.optBoolean("scan_verified", false)
                || savedItem.optBoolean("qr_verified", false)
                || "VALID".equalsIgnoreCase(firstValue(savedItem, "", "qr_status", "scan_status"));
        if (!savedScan.isEmpty()) {
            scanInfo.setText(savedQrVerified
                    ? "QR tervalidasi server: " + abbreviate(savedScan, 70)
                    : "QR tercatat, belum tervalidasi server: " + abbreviate(savedScan, 70));
        }
        card.addView(scanInfo, withTop(8));
        Button scan = secondaryButton("Pindai QR Barang / Pallet");
        scan.setOnClickListener(view -> openQrScanner(itemId));
        card.addView(scan, withTop(8));

        manifestItems.add(new ManifestItem(itemId, plannedPcs, checked, actualQty, scanInfo, savedScan, savedQrVerified));
        return card;
    }

    private void markAllItemsChecked() {
        for (ManifestItem item : manifestItems) {
            item.checked.setChecked(true);
            if (!Double.isNaN(item.plannedPcs)) item.actualQty.setText(formatQuantity(item.plannedPcs));
        }
        toast("Semua barang ditandai sesuai. Periksa kembali jumlah aktual sebelum konfirmasi.");
    }

    private void showManifestConfirmationDialog(JSONObject trip, String status) {
        if (manifestItems.isEmpty()) {
            toast("Tidak ada detail barang untuk dikonfirmasi.");
            return;
        }
        boolean matching = "SESUAI".equalsIgnoreCase(status);
        if (matching && !allItemsChecked()) {
            toast("Checklist semua barang terlebih dahulu, atau laporkan selisih muatan.");
            return;
        }
        LinearLayout form = dialogForm();
        EditText notes = input(matching ? "Catatan (opsional)" : "Jelaskan selisih muatan", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle(matching ? "Konfirmasi manifest sesuai" : "Laporkan selisih muatan")
                .setMessage(matching
                        ? "Konfirmasi ini akan membuka izin untuk memulai perjalanan. Pastikan barang dan jumlah PCS sudah benar."
                        : "Status manifest akan dicatat sebagai ADA SELISIH dan catatan wajib diisi.")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton(matching ? "Konfirmasi" : "Kirim Selisih", (dialog, which) -> {
                    String note = notes.getText().toString().trim();
                    if (!matching && note.isEmpty()) {
                        toast("Catatan selisih wajib diisi.");
                        return;
                    }
                    submitManifestConfirmation(trip, status, note);
                })
                .show();
    }

    private boolean allItemsChecked() {
        for (ManifestItem item : manifestItems) {
            if (!item.checked.isChecked()) return false;
        }
        return !manifestItems.isEmpty();
    }

    private void submitManifestConfirmation(JSONObject trip, String status, String notes) {
        int tripId = trip == null ? 0 : trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        final JSONArray checkedItems;
        try {
            checkedItems = confirmationItems();
        } catch (Exception error) {
            toast(messageOf(error));
            return;
        }
        toast("Mengirim konfirmasi muatan...");
        networkExecutor.execute(() -> {
            try {
                // Re-read the authoritative WMS state immediately before a
                // write. A screen opened earlier must not authorize a confirm
                // after WMS has changed back to a pre-loading step.
                JSONObject currentResponse = api.manifest(tripId);
                JSONObject currentData = manifestData(currentResponse);
                if (!isManifestReadyForVerification(currentData, trip)) {
                    runOnUiThread(() -> showWmsManifestBlocked(trip, currentData, "konfirmasi muatan"));
                    return;
                }
            } catch (Exception error) {
                runOnUiThread(() -> toast("Konfirmasi tidak dikirim karena status WMS belum dapat diverifikasi: " + messageOf(error)));
                return;
            }

            try {
                api.confirmManifest(tripId, status, notes, checkedItems);
                runOnUiThread(() -> {
                    toast("Konfirmasi manifest berhasil disimpan.");
                    loadTrips();
                });
            } catch (Exception error) {
                if (FleetApiClient.isRetryable(error)) {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("status", status);
                        payload.put("notes", notes == null ? "" : notes.trim());
                        payload.put("items", checkedItems);
                        offlineQueue.enqueue(
                                session,
                                "CHECKLIST_MANIFEST",
                                tripId,
                                "fleet-mobile/trips/" + tripId + "/manifest/confirm",
                                payload
                        );
                        FleetSyncWorker.schedule(this);
                        runOnUiThread(() -> {
                            toast("Tidak ada koneksi. Checklist manifest disimpan dan akan dikirim otomatis.");
                            loadTrips();
                        });
                    } catch (Exception queueError) {
                        runOnUiThread(() -> toast("Checklist belum tersimpan: " + messageOf(queueError)));
                    }
                } else {
                    runOnUiThread(() -> toast(messageOf(error)));
                }
            }
        });
    }

    private JSONArray confirmationItems() throws Exception {
        JSONArray rows = new JSONArray();
        for (ManifestItem item : manifestItems) {
            String rawActual = item.actualQty.getText().toString().trim().replace(',', '.');
            if (rawActual.isEmpty()) rawActual = "0";
            double actual = Double.parseDouble(rawActual);
            if (actual < 0d) throw new Exception("Jumlah aktual tidak boleh negatif.");
            if (Math.rint(actual) != actual) {
                throw new Exception("Jumlah aktual PCS harus berupa bilangan bulat.");
            }
            JSONObject row = new JSONObject();
            row.put("manifest_detail_id", jsonIdentifier(item.id));
            row.put("actual_qty_pcs", (long) actual);
            row.put("checked", item.checked.isChecked());
            row.put("scan_payload", item.scanPayload == null ? "" : item.scanPayload);
            row.put("scan_verified", item.qrVerified);
            rows.put(row);
        }
        return rows;
    }

    private void openQrScanner(String itemId) {
        if (scannerTripId <= 0) {
            toast("QR hanya dapat dipindai setelah manifest WMS berstatus LOADED.");
            return;
        }
        scannerTargetItemId = itemId;
        withCameraPermission(() -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Arahkan kamera ke QR barang atau pallet");
            options.setBeepEnabled(true);
            options.setOrientationLocked(false);
            qrScanner.launch(options);
        });
    }

    private void onQrScanResult(ScanIntentResult result) {
        String scanPayload = result == null ? null : result.getContents();
        String targetId = scannerTargetItemId;
        int tripId = scannerTripId;
        scannerTargetItemId = "";
        if (scanPayload == null || scanPayload.trim().isEmpty()) {
            toast("Pemindaian QR dibatalkan.");
            return;
        }
        for (ManifestItem item : manifestItems) {
            if (item.id.equals(targetId)) {
                if (tripId <= 0) {
                    toast("QR tidak dapat divalidasi karena ID perjalanan tidak tersedia.");
                    return;
                }
                String rawPayload = scanPayload.trim();
                item.scanInfo.setText("Memvalidasi QR ke server...");
                networkExecutor.execute(() -> {
                    try {
                        JSONObject currentResponse = api.manifest(tripId);
                        JSONObject currentData = manifestData(currentResponse);
                        if (!isManifestReadyForVerification(currentData, null)) {
                            runOnUiThread(() -> {
                                item.qrVerified = false;
                                item.scanInfo.setText("QR tidak diproses: manifest WMS belum LOADED.");
                                toast("WMS belum final/LOADED. Pindai ulang setelah proses loading selesai.");
                            });
                            return;
                        }
                        JSONObject response = api.validateManifestQr(tripId, item.id, rawPayload);
                        JSONObject data = manifestData(response);
                        boolean valid = qrValidationAccepted(data);
                        String serverMessage = firstValue(data, "", "message", "validation_message", "reason");
                        runOnUiThread(() -> {
                            if (!valid) {
                                item.qrVerified = false;
                                item.scanInfo.setText("QR ditolak server" + (serverMessage.isEmpty() ? "." : ": " + serverMessage));
                                toast("QR tidak sesuai dengan barang, batch, atau pallet manifest.");
                                return;
                            }
                            item.scanPayload = firstValue(data, rawPayload, "normalized_payload", "qr_payload", "payload", "scan_payload");
                            item.qrVerified = true;
                            item.scanInfo.setText("QR tervalidasi server: " + abbreviate(item.scanPayload, 70));
                            toast("QR valid untuk barang manifest ini.");
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            item.qrVerified = false;
                            item.scanInfo.setText("QR belum tervalidasi. Sambungkan perangkat lalu pindai ulang.");
                            toast("Validasi QR wajib online: " + messageOf(error));
                        });
                    }
                });
                return;
            }
        }
        toast("Hasil QR diterima, tetapi item manifest sudah tidak aktif.");
    }

    private void withCameraPermission(Runnable action) {
        if (hasCameraPermission()) {
            action.run();
            return;
        }
        afterCameraPermission = action;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void openDestinationNavigation(JSONObject destination) {
        double latitude = optionalNumberValue(destination, "latitude", "lat", "customer_latitude");
        double longitude = optionalNumberValue(destination, "longitude", "lng", "lon", "customer_longitude");
        String name = firstValue(destination, "Toko tujuan", "customer_name", "nama_toko", "nama_customer", "customer", "name");
        String address = firstValue(destination, "", "address", "alamat", "alamat_tujuan", "customer_address");
        String query = !Double.isNaN(latitude) && !Double.isNaN(longitude)
                ? String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
                : (address.isEmpty() ? name : name + ", " + address);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)));
            intent.setPackage("com.google.android.apps.maps");
            startActivity(intent);
        } catch (Exception error) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))));
            } catch (Exception ignored) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + Uri.encode(query))));
            }
        }
    }

    private void openDeliveryStops(JSONObject trip) {
        int tripId = trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        showDeliveryStopsLoading(trip);
        networkExecutor.execute(() -> {
            try {
                JSONObject manifestResponse = api.manifest(tripId);
                JSONObject currentManifestData = manifestData(manifestResponse);
                if (!isManifestReadyForDelivery(currentManifestData, trip)) {
                    runOnUiThread(() -> showDeliveryBlocked(trip, currentManifestData));
                    return;
                }
                JSONObject response = api.stops(tripId);
                runOnUiThread(() -> showDeliveryStops(trip, response));
            } catch (Exception error) {
                runOnUiThread(() -> showDeliveryStopsError(trip, messageOf(error)));
            }
        });
    }

    private void showDeliveryStopsLoading(JSONObject trip) {
        LinearLayout page = beginPage();
        page.addView(topBar("Pengantaran per Toko", () -> showTripDetail(trip)));
        addSpace(page, 22);
        page.addView(title("Memuat tujuan pengiriman...", 24));
        page.addView(body("Menyiapkan urutan toko, barang per tujuan, dan status bukti penerimaan.", 16), withTop(8));
    }

    private void showDeliveryStopsError(JSONObject trip, String message) {
        LinearLayout page = beginPage();
        page.addView(topBar("Pengantaran per Toko", () -> showTripDetail(trip)));
        addSpace(page, 22);
        page.addView(title("Tujuan belum dapat dimuat", 24));
        page.addView(body(message, 16), withTop(8));
        page.addView(body("Data POD tidak boleh diganti dengan asumsi lokal. Sambungkan perangkat lalu muat ulang untuk memastikan toko dan barang yang benar.", 14), withTop(12));
        Button retry = primaryButton("Muat Ulang Tujuan");
        retry.setOnClickListener(view -> openDeliveryStops(trip));
        page.addView(retry, withTop(20));
    }

    private void showDeliveryStops(JSONObject trip, JSONObject response) {
        JSONArray stops = stopRows(response);
        LinearLayout page = beginPage();
        page.addView(topBar("Pengantaran per Toko", () -> showTripDetail(trip)));
        addSpace(page, 18);
        page.addView(label("MANIFEST", 12, color(R.color.green), Typeface.BOLD));
        page.addView(title(value(trip, "no_manifest", "-"), 25), withTop(4));
        page.addView(body("Selesaikan bukti penerimaan setiap toko sebelum menutup perjalanan. Jumlah PCS menjadi angka utama; angka UOM diverifikasi server memakai konversi WMS.", 15), withTop(10));

        int pending = offlineQueue.outstandingCount(session, trip.optInt("id"));
        if (pending > 0) {
            TextView pendingView = chip(pending + " data offline menunggu sinkronisasi", color(R.color.orange));
            page.addView(pendingView, withTop(12));
        }

        if (stops.length() == 0) {
            LinearLayout empty = card();
            empty.addView(title("Belum ada tujuan pengiriman", 19));
            empty.addView(body("Server belum mengirim stop toko untuk manifest ini. Perjalanan tidak dapat diselesaikan sampai tujuan dan POD tersedia.", 15), withTop(7));
            page.addView(empty, withTop(18));
            return;
        }

        int completed = 0;
        for (int index = 0; index < stops.length(); index++) {
            JSONObject stop = stops.optJSONObject(index);
            if (stop == null) continue;
            if (isStopCompleted(stop)) completed++;
            page.addView(deliveryStopCard(trip, stop, index + 1), withTop(12));
        }
        page.addView(body(completed + " dari " + stops.length() + " tujuan telah memiliki POD tersinkron.", 14), withTop(16));
        Button refresh = secondaryButton("Muat Ulang Status Pengantaran");
        refresh.setOnClickListener(view -> openDeliveryStops(trip));
        page.addView(refresh, withTop(12));
    }

    private View deliveryStopCard(JSONObject trip, JSONObject stop, int index) {
        LinearLayout card = card();
        String stopId = deliveryStopId(stop, index);
        boolean failedDelivery = "FAILED".equalsIgnoreCase(stop.optString("delivery_outcome"));
        String status = failedDelivery ? "FD · Gagal kirim" : deliveryStopStatus(stop);
        String sequence = firstValue(stop, String.valueOf(index), "sequence", "urutan", "stop_number", "no_urutan");
        String customer = firstValue(stop, "Toko tujuan " + sequence, "customer_name", "nama_toko", "nama_customer", "customer", "name");
        String address = firstValue(stop, "Alamat belum tersedia", "address", "alamat", "alamat_tujuan", "customer_address");
        String invoice = firstValue(stop, "", "no_faktur", "no_nota", "invoice_no", "no_invoice", "delivery_note_no");
        boolean complete = isStopCompleted(stop);
        boolean arrived = isStopArrived(stop);

        LinearLayout header = horizontal();
        header.addView(label("TUJUAN " + sequence, 12, color(R.color.text_secondary), Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(chip(status, complete ? color(R.color.green) : (arrived ? color(R.color.blue) : color(R.color.orange))));
        card.addView(header);
        card.addView(title(customer, 19), withTop(5));
        card.addView(body(address, 15), withTop(7));
        if (!invoice.isEmpty()) card.addView(body("Faktur: " + invoice, 14), withTop(5));
        String planned = firstValue(stop, "", "qty_uom_display", "total_uom_display", "planned_qty_display", "jumlah_uom");
        double plannedPcs = optionalNumberValue(stop, "planned_qty_pcs", "total_qty_pcs", "qty_pcs", "total_pcs");
        if (!planned.isEmpty() || !Double.isNaN(plannedPcs)) {
            String amount = (planned.isEmpty() ? "" : planned)
                    + (!planned.isEmpty() && !Double.isNaN(plannedPcs) ? "  |  " : "")
                    + (Double.isNaN(plannedPcs) ? "" : formatQuantity(plannedPcs) + " PCS");
            card.addView(body("Rencana: " + amount, 14), withTop(6));
        }

        Button navigate = secondaryButton("Navigasi ke Toko");
        navigate.setOnClickListener(view -> openDestinationNavigation(stop));
        card.addView(navigate, withTop(12));

        if (stopId.isEmpty()) {
            card.addView(body("ID tujuan belum dikirim server; kedatangan dan POD belum dapat dicatat aman.", 13), withTop(10));
        } else if (!complete) {
            Button arrival = secondaryButton(arrived ? "Perbarui Lokasi Tiba" : "Tandai Tiba dengan GPS");
            arrival.setOnClickListener(view -> captureStopArrival(trip, stop, stopId));
            card.addView(arrival, withTop(10));
            Button pod = primaryButton(arrived ? "Buka Bukti Penerimaan (POD)" : "Buka POD & Catat Kedatangan");
            pod.setOnClickListener(view -> openPodWithLocation(trip, stop, stopId));
            card.addView(pod, withTop(8));
        } else {
            String receiver = firstValue(stop, "-", "receiver_name", "nama_penerima", "received_by");
            String receivedAt = firstValue(stop, "-", "received_at", "delivered_at", "pod_at", "completed_at");
            String receipt = failedDelivery ? "Gagal kirim: " + firstValue(stop, "-", "failure_notes", "failure_reason") : "Diterima oleh: " + receiver;
            card.addView(body(receipt + "\nWaktu POD: " + receivedAt, 14), withTop(10));
        }
        return card;
    }

    private void captureStopArrival(JSONObject trip, JSONObject stop, String stopId) {
        withLocationPermission(() -> captureCurrentLocation("Mengambil lokasi tiba...", location -> submitStopArrival(trip, stop, stopId, location)));
    }

    private void submitStopArrival(JSONObject trip, JSONObject stop, String stopId, Location location) {
        final JSONObject payload;
        try {
            payload = arrivalPayload(location);
        } catch (Exception error) {
            toast(messageOf(error));
            return;
        }
        int tripId = trip.optInt("id");
        networkExecutor.execute(() -> {
            try {
                api.arriveStop(tripId, stopId, payload);
                runOnUiThread(() -> {
                    toast("Kedatangan di toko berhasil dicatat.");
                    openDeliveryStops(trip);
                });
            } catch (Exception error) {
                queueOrShow(
                        error,
                        "ARRIVAL",
                        tripId,
                        "fleet-mobile/trips/" + tripId + "/stops/" + Uri.encode(stopId) + "/arrival",
                        payload,
                        "Lokasi tiba disimpan offline dan akan dikirim otomatis.",
                        () -> openDeliveryStops(trip)
                );
            }
        });
    }

    private void openPodWithLocation(JSONObject trip, JSONObject stop, String stopId) {
        withLocationPermission(() -> captureCurrentLocation("Mengambil GPS untuk bukti penerimaan...", location -> {
            activePodDraft = new PodDraft(trip, stop, stopId, location, timestampNow());
            showPodPage(activePodDraft);
        }));
    }

    private void showPodPage(PodDraft draft) {
        LinearLayout page = beginPage();
        page.addView(topBar("Bukti Penerimaan", () -> openDeliveryStops(draft.trip)));
        addSpace(page, 18);
        String customer = firstValue(draft.stop, "Toko tujuan", "customer_name", "nama_toko", "nama_customer", "customer", "name");
        String invoice = firstValue(draft.stop, "", "no_faktur", "no_nota", "invoice_no", "no_invoice", "delivery_note_no");
        page.addView(label("POD / SERAH TERIMA", 12, color(R.color.green), Typeface.BOLD));
        page.addView(title(customer, 24), withTop(4));
        if (!invoice.isEmpty()) page.addView(body("Faktur: " + invoice, 15), withTop(5));
        page.addView(body("GPS tiba: " + locationText(draft.location) + "\nWaktu: " + draft.arrivedAt, 14), withTop(9));

        CheckBox failedToggle = new CheckBox(this);
        failedToggle.setText("Gagal kirim seluruh barang (FD)");
        page.addView(failedToggle, withTop(12));
        Button reasonButton = secondaryButton("Pilih alasan gagal kirim");
        reasonButton.setVisibility(View.GONE);
        String[] failureLabels = {"Toko tutup", "Ditolak pelanggan", "Alamat tidak ditemukan", "Lainnya"};
        String[] failureCodes = {"STORE_CLOSED", "REFUSED", "ADDRESS_NOT_FOUND", "OTHER"};
        reasonButton.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Alasan gagal kirim")
                .setItems(failureLabels, (dialog, which) -> {
                    draft.failureReason = failureCodes[which];
                    reasonButton.setText(failureLabels[which]);
                }).show());
        page.addView(reasonButton, withTop(8));

        LinearLayout receiverCard = card();
        receiverCard.addView(label("PENERIMA", 12, color(R.color.text_secondary), Typeface.BOLD));
        draft.receiverName = input("Nama penerima *", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        receiverCard.addView(draft.receiverName, withTop(9));
        draft.notes = input("Catatan penerimaan / gagal kirim (wajib untuk Lainnya)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        receiverCard.addView(draft.notes, withTop(10));
        page.addView(receiverCard, withTop(18));

        LinearLayout receivedItems = new LinearLayout(this);
        receivedItems.setOrientation(LinearLayout.VERTICAL);
        page.addView(receivedItems);
        receivedItems.addView(label("BARANG DITERIMA", 12, color(R.color.text_secondary), Typeface.BOLD), withTop(22));
        receivedItems.addView(body("Isi PCS aktual untuk setiap barang. UOM diverifikasi server menggunakan pengaturan WMS.", 14), withTop(6));
        JSONArray items = podItemRows(draft.stop);
        if (items.length() == 0) {
            LinearLayout warning = card();
            warning.addView(body("Detail barang tujuan belum tersedia. POD tidak dapat dikirim tanpa jumlah aktual PCS yang dapat diverifikasi server.", 15));
            receivedItems.addView(warning, withTop(12));
        } else {
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null) receivedItems.addView(podItemCard(draft, item, index), withTop(10));
            }
        }

        LinearLayout evidence = card();
        evidence.addView(label("FOTO & TANDA TANGAN", 12, color(R.color.text_secondary), Typeface.BOLD));
        TextView evidenceHelp = body("Foto bukti opsional. Tanda tangan wajib untuk barang yang diterima, tidak untuk gagal kirim.", 14);
        evidence.addView(evidenceHelp, withTop(6));
        draft.photoInfo = body("Belum ada foto bukti.", 14);
        evidence.addView(draft.photoInfo, withTop(10));
        Button photo = secondaryButton("Ambil Foto Bukti");
        photo.setOnClickListener(view -> launchPodCamera(draft));
        evidence.addView(photo, withTop(8));
        TextView signatureLabel = label("TANDA TANGAN PENERIMA *", 12, color(R.color.text_secondary), Typeface.BOLD);
        evidence.addView(signatureLabel, withTop(16));
        draft.signature = new SignaturePadView(this);
        draft.signature.setBackground(background(Color.WHITE, color(R.color.border), 12));
        LinearLayout.LayoutParams signatureParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        signatureParams.topMargin = dp(8);
        evidence.addView(draft.signature, signatureParams);
        Button clearSignature = secondaryButton("Hapus Tanda Tangan");
        clearSignature.setOnClickListener(view -> draft.signature.clearSignature());
        evidence.addView(clearSignature, withTop(8));
        page.addView(evidence, withTop(22));

        Button submit = primaryButton("Kirim Bukti Penerimaan");
        failedToggle.setOnCheckedChangeListener((button, checked) -> {
            draft.failedDelivery = checked;
            reasonButton.setVisibility(checked ? View.VISIBLE : View.GONE);
            draft.receiverName.setVisibility(checked ? View.GONE : View.VISIBLE);
            receivedItems.setVisibility(checked ? View.GONE : View.VISIBLE);
            draft.signature.setVisibility(checked ? View.GONE : View.VISIBLE);
            signatureLabel.setVisibility(checked ? View.GONE : View.VISIBLE);
            clearSignature.setVisibility(checked ? View.GONE : View.VISIBLE);
            evidenceHelp.setText(checked ? "Seluruh barang tujuan dicatat tidak diterima dan kembali untuk QC Karantina setelah perjalanan selesai. Foto bukti opsional; tidak perlu penerima/tanda tangan." : "Foto bukti opsional. Tanda tangan wajib untuk barang yang diterima.");
            submit.setText(checked ? "Kirim Laporan Gagal Kirim" : "Kirim Bukti Penerimaan");
        });
        submit.setOnClickListener(view -> submitPod(draft));
        page.addView(submit, withTop(22));
    }

    private View podItemCard(PodDraft draft, JSONObject item, int index) {
        LinearLayout card = card();
        String itemId = firstValue(item, "item-" + index, "stop_item_id", "delivery_stop_item_id", "id", "manifest_detail_id", "detail_id");
        String manifestDetailId = firstValue(item, "", "manifest_detail_id", "id_detail_manifest", "detail_id");
        String code = firstValue(item, "", "kode_barang", "product_code", "kode_produk", "sku");
        String name = firstValue(item, "Barang " + (index + 1), "nama_barang", "product_name", "nama_produk", "produk", "name");
        double plannedPcs = optionalNumberValue(item, "planned_qty_pcs", "qty_pcs", "total_pcs", "quantity_pcs", "qty_base");
        String plannedUom = firstValue(item, "", "qty_uom_display", "planned_uom_display", "quantity_display", "qty_display", "qty_uom", "jumlah_uom");
        String uomName = firstValue(item, "", "uom_name", "nama_uom", "uom", "unit_name");
        double uomLevel = optionalNumberValue(item, "uom_level", "level_uom", "conversion_level");
        double plannedUomValue = optionalNumberValue(item, "planned_qty_uom", "qty_uom_value", "quantity_uom");
        card.addView(title(code.isEmpty() ? name : code + " · " + name, 18));
        if (item.has("stok_ready")) card.addView(body("Stok Ready Gudang: " + firstValue(item, "0", "stok_ready") + " PCS", 13), withTop(4));
        if (!plannedUom.isEmpty()) card.addView(body("Rencana UOM: " + plannedUom, 14), withTop(5));
        if (!Double.isNaN(plannedPcs)) card.addView(body("Rencana PCS: " + formatQuantity(plannedPcs), 14), withTop(4));

        EditText actualPcs = input("Jumlah diterima aktual (PCS) *", InputType.TYPE_CLASS_NUMBER);
        if (!Double.isNaN(plannedPcs)) actualPcs.setText(formatQuantity(plannedPcs));
        card.addView(actualPcs, withTop(10));
        EditText actualUom = input("Jumlah diterima UOM (opsional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (!Double.isNaN(plannedUomValue)) actualUom.setText(formatQuantity(plannedUomValue));
        card.addView(actualUom, withTop(8));
        if (!uomName.isEmpty()) card.addView(body("Satuan UOM: " + uomName, 13), withTop(4));
        EditText returnPcs = input("Retur / ditolak (PCS, bila ada)", InputType.TYPE_CLASS_NUMBER);
        returnPcs.setText("0");
        card.addView(returnPcs, withTop(8));
        card.addView(body("Isi jumlah fisik yang kembali dari toko. Retur akan masuk antrean QC Karantina setelah perjalanan diselesaikan; stok belum bertambah sebelum QC menentukan GOOD atau BAD.", 13), withTop(4));
        EditText discrepancy = input("Alasan selisih / retur (bila ada)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        card.addView(discrepancy, withTop(8));

        draft.items.add(new PodItemDraft(itemId, manifestDetailId, plannedPcs, uomName, uomLevel, actualPcs, actualUom, returnPcs, discrepancy));
        return card;
    }

    private void launchPodCamera(PodDraft draft) {
        activePodDraft = draft;
        withCameraPermission(() -> {
            try {
                podPhotoCapture.launch(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            } catch (Exception error) {
                toast("Kamera tidak tersedia: " + messageOf(error));
            }
        });
    }

    private void onPodPhotoResult(ActivityResult result) {
        PodDraft draft = activePodDraft;
        if (draft == null) return;
        if (result == null || result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getExtras() == null) {
            toast("Pengambilan foto dibatalkan.");
            return;
        }
        Object raw = result.getData().getExtras().get("data");
        if (!(raw instanceof Bitmap)) {
            toast("Foto dari kamera tidak dapat dibaca.");
            return;
        }
        Bitmap bitmap = (Bitmap) raw;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int quality = 82;
        do {
            output.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
            quality -= 10;
        } while (output.size() > 350_000 && quality >= 42);
        draft.photoJpegBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        if (draft.photoInfo != null) {
            draft.photoInfo.setText("Foto bukti siap dikirim (" + Math.max(1, output.size() / 1024) + " KB).");
        }
        toast("Foto bukti ditambahkan.");
    }

    private void submitPod(PodDraft draft) {
        final JSONObject payload;
        try {
            payload = podPayload(draft);
        } catch (Exception error) {
            toast(messageOf(error));
            return;
        }
        int tripId = draft.trip.optInt("id");
        toast("Mengirim bukti penerimaan...");
        networkExecutor.execute(() -> {
            try {
                JSONObject response = api.submitPod(tripId, draft.stopId, payload);
                JSONObject responseData = manifestData(response);
                int returnQty = responseData.optInt("return_qty_pcs", 0);
                String successMessage = firstValue(response, "Bukti penerimaan berhasil disimpan.", "message");
                runOnUiThread(() -> {
                    activePodDraft = null;
                    toast(returnQty > 0
                            ? (successMessage.isEmpty()
                                ? returnQty + " PCS retur menunggu QC Karantina setelah perjalanan selesai."
                                : successMessage)
                            : "Bukti penerimaan berhasil disimpan.");
                    openDeliveryStops(draft.trip);
                });
            } catch (Exception error) {
                queueOrShow(
                        error,
                        "POD",
                        tripId,
                        "fleet-mobile/trips/" + tripId + "/stops/" + Uri.encode(draft.stopId) + "/pod",
                        payload,
                        "Tidak ada koneksi. POD lengkap disimpan offline dan akan dikirim otomatis.",
                        () -> {
                            activePodDraft = null;
                            openDeliveryStops(draft.trip);
                        }
                );
            }
        });
    }

    private JSONObject arrivalPayload(Location location) throws Exception {
        JSONObject body = new JSONObject();
        body.put("idempotency_key", UUID.randomUUID().toString());
        body.put("latitude", location.getLatitude());
        body.put("longitude", location.getLongitude());
        body.put("accuracy_m", location.hasAccuracy() ? location.getAccuracy() : 0f);
        body.put("arrived_at", timestampNow());
        body.put("captured_at", timestampNow());
        return body;
    }

    private JSONObject podPayload(PodDraft draft) throws Exception {
        String receiver = draft.receiverName == null ? "" : draft.receiverName.getText().toString().trim();
        if (!draft.failedDelivery && receiver.isEmpty()) throw new Exception("Nama penerima wajib diisi.");
        if (!draft.failedDelivery && (draft.signature == null || !draft.signature.hasSignature())) throw new Exception("Tanda tangan penerima wajib diisi.");
        if (draft.failedDelivery && draft.failureReason.isEmpty()) throw new Exception("Pilih alasan gagal kirim.");
        if (draft.items.isEmpty()) throw new Exception("Detail barang tujuan belum tersedia.");

        JSONObject body = new JSONObject();
        body.put("idempotency_key", draft.eventId);
        body.put("delivery_outcome", draft.failedDelivery ? "FAILED" : "DELIVERED");
        body.put("receiver_name", receiver);
        body.put("notes", draft.notes == null ? "" : draft.notes.getText().toString().trim());
        body.put("latitude", draft.location.getLatitude());
        body.put("longitude", draft.location.getLongitude());
        body.put("accuracy_m", draft.location.hasAccuracy() ? draft.location.getAccuracy() : 0f);
        body.put("arrived_at", draft.arrivedAt);
        body.put("received_at", timestampNow());
        if (!draft.failedDelivery) body.put("signature_png_base64", draft.signature.toBase64Png());
        if (draft.photoJpegBase64 != null && !draft.photoJpegBase64.isEmpty()) {
            body.put("photo_jpeg_base64", draft.photoJpegBase64);
        }

        if (draft.failedDelivery) {
            String failureNotes = draft.notes.getText().toString().trim();
            if ("OTHER".equals(draft.failureReason) && failureNotes.isEmpty()) throw new Exception("Jelaskan alasan gagal kirim lainnya.");
            body.put("failure_reason", draft.failureReason);
            body.put("failure_notes", failureNotes);
            return body;
        }
        JSONArray items = new JSONArray();
        for (PodItemDraft item : draft.items) {
            long actualPcs = wholePcs(item.actualPcs, "Jumlah aktual PCS");
            long returnPcs = wholePcsOrZero(item.returnPcs, "Jumlah retur PCS");
            JSONObject row = new JSONObject();
            row.put("stop_item_id", jsonIdentifier(item.id));
            if (!item.manifestDetailId.isEmpty()) row.put("manifest_detail_id", jsonIdentifier(item.manifestDetailId));
            row.put("actual_qty_pcs", actualPcs);
            row.put("return_qty_pcs", returnPcs);
            if (!Double.isNaN(item.plannedPcs)) row.put("difference_qty_pcs", Math.round(item.plannedPcs) - actualPcs);
            String reason = item.discrepancy.getText().toString().trim();
            if (!reason.isEmpty()) row.put("discrepancy_reason", reason);
            String rawUom = item.actualUom.getText().toString().trim().replace(',', '.');
            if (!rawUom.isEmpty()) {
                double uomValue;
                try {
                    uomValue = Double.parseDouble(rawUom);
                } catch (Exception error) {
                    throw new Exception("Jumlah UOM harus berupa angka.");
                }
                if (uomValue < 0d) throw new Exception("Jumlah UOM tidak boleh negatif.");
                JSONObject uom = new JSONObject();
                uom.put("value", uomValue);
                if (!item.uomName.isEmpty()) uom.put("uom_name", item.uomName);
                if (!Double.isNaN(item.uomLevel)) uom.put("uom_level", (int) Math.round(item.uomLevel));
                row.put("actual_qty_uom", uom);
            }
            items.put(row);
        }
        body.put("items", items);
        return body;
    }

    private void checkStopsBeforeComplete(JSONObject trip) {
        int tripId = trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        toast("Memeriksa seluruh POD toko...");
        networkExecutor.execute(() -> {
            try {
                JSONArray stops = stopRows(api.stops(tripId));
                int unfinished = 0;
                for (int index = 0; index < stops.length(); index++) {
                    JSONObject stop = stops.optJSONObject(index);
                    if (stop != null && !isStopCompleted(stop)) unfinished++;
                }
                int pending = offlineQueue.blockingCount(session, tripId);
                int finalUnfinished = unfinished;
                runOnUiThread(() -> {
                    if (stops.length() == 0) {
                        toast("Tujuan/POD belum tersedia. Perjalanan tidak dapat diselesaikan.");
                    } else if (finalUnfinished > 0) {
                        toast(finalUnfinished + " tujuan belum memiliki POD. Selesaikan serah terima tiap toko terlebih dahulu.");
                        openDeliveryStops(trip);
                    } else if (pending > 0) {
                        toast("Masih ada " + pending + " checklist atau POD offline yang menunggu sinkronisasi. Tunggu hingga terkirim sebelum menyelesaikan perjalanan.");
                    } else {
                        showCompleteDialog(tripId);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("POD harus diverifikasi server sebelum perjalanan selesai: " + messageOf(error)));
            }
        });
    }

    private void captureCurrentLocation(String loadingMessage, LocationAction action) {
        toast(loadingMessage);
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location == null) {
                            toast("Lokasi belum tersedia. Pastikan GPS perangkat aktif.");
                            return;
                        }
                        action.onLocation(location);
                    })
                    .addOnFailureListener(error -> toast("Lokasi gagal diambil: " + messageOf(error)));
        } catch (SecurityException error) {
            toast("Izin lokasi belum diberikan.");
        }
    }

    private void queueOrShow(Exception error, String type, int tripId, String route, JSONObject payload, String queuedMessage, Runnable afterQueued) {
        if (!FleetApiClient.isRetryable(error)) {
            runOnUiThread(() -> toast(messageOf(error)));
            return;
        }
        try {
            offlineQueue.enqueue(session, type, tripId, route, payload);
            FleetSyncWorker.schedule(this);
            runOnUiThread(() -> {
                toast(queuedMessage);
                if (afterQueued != null) afterQueued.run();
            });
        } catch (Exception queueError) {
            runOnUiThread(() -> toast("Data belum dapat disimpan offline: " + messageOf(queueError)));
        }
    }

    private JSONArray stopRows(JSONObject response) {
        JSONArray directRows = response == null ? null : response.optJSONArray("data");
        if (directRows != null) return directRows;
        JSONObject data = manifestData(response);
        JSONArray rows = FleetApiClient.array(data, "stops");
        if (rows.length() == 0) rows = FleetApiClient.array(data, "destinations");
        if (rows.length() == 0) rows = FleetApiClient.array(data, "delivery_stops");
        return rows;
    }

    private JSONArray podItemRows(JSONObject stop) {
        JSONArray rows = FleetApiClient.array(stop, "items");
        if (rows.length() == 0) rows = FleetApiClient.array(stop, "details");
        if (rows.length() == 0) rows = FleetApiClient.array(stop, "barang");
        if (rows.length() == 0) rows = FleetApiClient.array(stop, "products");
        return rows;
    }

    private String deliveryStopId(JSONObject stop, int index) {
        return firstValue(stop, "", "stop_id", "id_stop", "delivery_stop_id", "id", "delivery_note_id", "id_nota");
    }

    private String deliveryStopStatus(JSONObject stop) {
        return firstValue(stop, "MENUNGGU", "status", "delivery_status", "pod_status", "status_pengiriman");
    }

    private boolean isStopArrived(JSONObject stop) {
        String status = deliveryStopStatus(stop).toUpperCase(Locale.ROOT);
        return stop.optBoolean("arrived") || stop.optBoolean("is_arrived")
                || status.equals("TIBA") || status.equals("ARRIVED") || isStopCompleted(stop);
    }

    private boolean isStopCompleted(JSONObject stop) {
        String status = deliveryStopStatus(stop).toUpperCase(Locale.ROOT);
        return stop.optBoolean("pod_completed") || stop.optBoolean("is_completed")
                || status.equals("SELESAI") || status.equals("DELIVERED") || status.equals("TERKIRIM")
                || status.equals("POD") || status.equals("RECEIVED") || status.equals("DITERIMA")
                || status.equals("ISSUE") || status.equals("DELIVERED_WITH_ISSUE");
    }

    private boolean qrValidationAccepted(JSONObject data) {
        if (data == null) return false;
        if (data.has("valid")) return data.optBoolean("valid", false);
        if (data.has("is_valid")) return data.optBoolean("is_valid", false);
        String status = firstValue(data, "", "validation_status", "qr_status", "scan_status");
        return "VALID".equalsIgnoreCase(status) || "SESUAI".equalsIgnoreCase(status);
    }

    private long wholePcs(EditText field, String label) throws Exception {
        String value = field == null ? "" : field.getText().toString().trim().replace(',', '.');
        if (value.isEmpty()) throw new Exception(label + " wajib diisi.");
        double number;
        try {
            number = Double.parseDouble(value);
        } catch (Exception error) {
            throw new Exception(label + " harus berupa bilangan bulat.");
        }
        if (number < 0d || Math.rint(number) != number) throw new Exception(label + " harus berupa bilangan bulat tidak negatif.");
        return (long) number;
    }

    private long wholePcsOrZero(EditText field, String label) throws Exception {
        String value = field == null ? "" : field.getText().toString().trim();
        return value.isEmpty() ? 0L : wholePcs(field, label);
    }

    private String timestampNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private String locationText(Location location) {
        if (location == null) return "-";
        String accuracy = location.hasAccuracy() ? " ±" + Math.round(location.getAccuracy()) + " m" : "";
        return String.format(Locale.US, "%.6f, %.6f", location.getLatitude(), location.getLongitude()) + accuracy;
    }

    private void reconcileActiveTrip(JSONObject response) {
        int activeTripId = session.activeTripId();
        if (activeTripId <= 0) return;
        JSONArray trips = FleetApiClient.array(response, "data");
        for (int index = 0; index < trips.length(); index++) {
            JSONObject trip = trips.optJSONObject(index);
            if (trip == null || trip.optInt("id") != activeTripId) continue;
            if ("BERANGKAT".equalsIgnoreCase(value(trip, "status", ""))) {
                if (hasLocationPermission()) LocationForegroundService.start(this, activeTripId);
            } else {
                LocationForegroundService.stop(this);
                session.clearActiveTripId();
            }
            return;
        }
        // A local active-trip marker is never enough to keep background GPS
        // alive. Stop it if the server no longer assigns this trip to the user.
        LocationForegroundService.stop(this);
        session.clearActiveTripId();
    }

    private JSONObject manifestData(JSONObject response) {
        JSONObject data = response.optJSONObject("data");
        return data == null ? response : data;
    }

    /**
     * The WMS manifest state, not the local trip status, is the source of
     * truth for the hand-off to Driver/Helper. Never fall back to a picking or
     * fleet status here: an unknown value is intentionally treated as blocked.
     */
    private String canonicalManifestStatus(JSONObject data, JSONObject trip) {
        JSONObject manifest = data == null ? null : data.optJSONObject("manifest");
        String status = firstValue(manifest, "", "status", "manifest_status", "wms_status");
        if (status.isEmpty()) {
            status = firstValue(trip, "", "manifest_status", "wms_status", "status_manifest");
        }
        return status.isEmpty() ? "BELUM TERSEDIA" : status.toUpperCase(Locale.ROOT);
    }

    private boolean isManifestReadyForVerification(JSONObject data, JSONObject trip) {
        return WMS_MANIFEST_LOADED.equals(canonicalManifestStatus(data, trip));
    }

    private boolean isManifestInTransitOrDelivered(JSONObject data, JSONObject trip) {
        String status = canonicalManifestStatus(data, trip);
        return WMS_MANIFEST_ON_ROUTE.equals(status) || WMS_MANIFEST_DELIVERED.equals(status);
    }

    private boolean isManifestReadyForDelivery(JSONObject data, JSONObject trip) {
        return "BERANGKAT".equalsIgnoreCase(value(trip, "status", ""))
                && WMS_MANIFEST_ON_ROUTE.equals(canonicalManifestStatus(data, trip));
    }

    private void showWmsManifestBlocked(JSONObject trip, JSONObject data, String requestedAction) {
        String manifestStatus = canonicalManifestStatus(data, trip);
        LinearLayout page = beginPage();
        page.addView(topBar("Status Manifest WMS", () -> showTripDetail(trip)));
        addSpace(page, 22);
        LinearLayout blocked = accentCard(color(R.color.orange));
        blocked.addView(label("AKSI DIKUNCI", 11, color(R.color.orange), Typeface.BOLD));
        blocked.addView(title("Manifest belum dapat diproses", 23), withTop(5));
        blocked.addView(chip("WMS · " + manifestStatus, color(R.color.orange)), withTop(11));
        blocked.addView(body("Aksi " + requestedAction + " hanya boleh dilakukan ketika manifest kanonik WMS berstatus LOADED. Driver/Helper tidak akan membuat status PICKED atau Shipping sendiri.", 15), withTop(11));
        if (WMS_MANIFEST_ON_ROUTE.equals(manifestStatus) || WMS_MANIFEST_DELIVERED.equals(manifestStatus)) {
            blocked.addView(body("Manifest sudah melewati tahap muat. Kembali ke detail perjalanan untuk melanjutkan pengantaran atau melihat POD.", 14), withTop(8));
        } else {
            blocked.addView(body("Selesaikan picking, checker bila diperlukan, serta loading di WMS. Setelah status berubah menjadi LOADED, muat ulang halaman ini.", 14), withTop(8));
        }
        page.addView(blocked, matchWidth());
        Button refresh = primaryButton("Muat Ulang Status WMS");
        refresh.setOnClickListener(view -> openManifestVerification(trip));
        page.addView(refresh, withTop(20));
    }

    private void showDeliveryBlocked(JSONObject trip, JSONObject data) {
        String manifestStatus = canonicalManifestStatus(data, trip);
        String tripStatus = value(trip, "status", "BELUM DIMULAI");
        LinearLayout page = beginPage();
        page.addView(topBar("Pengantaran per Toko", () -> showTripDetail(trip)));
        addSpace(page, 22);
        LinearLayout blocked = accentCard(color(R.color.orange));
        blocked.addView(label("PENGANTARAN DIKUNCI", 11, color(R.color.orange), Typeface.BOLD));
        blocked.addView(title("Perjalanan belum siap untuk POD", 23), withTop(5));
        blocked.addView(chip("WMS · " + manifestStatus + "  |  Trip · " + tripStatus, color(R.color.orange)), withTop(11));
        if (WMS_MANIFEST_LOADED.equals(manifestStatus)) {
            blocked.addView(body("Muatan sudah LOADED, tetapi Driver masih harus menyelesaikan verifikasi manifest SESUAI dan memilih Mulai Perjalanan. POD tidak dapat diisi sebelum perjalanan berstatus ON_ROUTE.", 15), withTop(11));
        } else {
            blocked.addView(body("Status WMS belum menunjukkan manifest yang sedang diantar. Selesaikan proses WMS sampai LOADED, lalu mulai perjalanan dari akun Driver. Aplikasi tidak akan melewati tahap PICKED atau Shipping.", 15), withTop(11));
        }
        page.addView(blocked, matchWidth());
        Button refresh = primaryButton("Muat Ulang Status Perjalanan");
        refresh.setOnClickListener(view -> openDeliveryStops(trip));
        page.addView(refresh, withTop(20));
    }

    private boolean isManifestConfirmed(JSONObject confirmation) {
        return confirmation != null && "SESUAI".equalsIgnoreCase(firstValue(confirmation, "", "status"));
    }

    private int confirmationColor(String status) {
        if ("SESUAI".equalsIgnoreCase(status)) return color(R.color.green);
        if ("ADA_SELISIH".equalsIgnoreCase(status)) return color(R.color.red);
        return color(R.color.orange);
    }

    private String manifestItemId(JSONObject item, int index) {
        return firstValue(item, "item-" + index, "manifest_detail_id", "id_detail_manifest", "detail_id", "id");
    }

    private JSONObject confirmationItem(JSONArray rows, String id) {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && id.equals(firstValue(row, "", "manifest_detail_id", "id_detail_manifest", "detail_id", "id"))) return row;
        }
        return new JSONObject();
    }

    private String firstValue(JSONObject data, String fallback, String... keys) {
        if (data == null) return fallback;
        for (String key : keys) {
            String raw = data.optString(key, "").trim();
            if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) return raw;
        }
        return fallback;
    }

    private double optionalNumberValue(JSONObject data, String... keys) {
        if (data == null) return Double.NaN;
        for (String key : keys) {
            Object raw = data.opt(key);
            if (raw == null || JSONObject.NULL.equals(raw)) continue;
            try {
                String value = String.valueOf(raw).trim().replace(',', '.');
                if (!value.isEmpty()) return Double.parseDouble(value);
            } catch (Exception ignored) {
                // Try the next supported API field name.
            }
        }
        return Double.NaN;
    }

    private String formatQuantity(double value) {
        if (Double.isNaN(value)) return "";
        if (Math.rint(value) == value) return String.valueOf((long) value);
        String formatted = String.format(Locale.US, "%.3f", value);
        while (formatted.contains(".") && (formatted.endsWith("0") || formatted.endsWith("."))) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted;
    }

    private Object jsonIdentifier(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String abbreviate(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private void showStartDialog(JSONObject trip) {
        int tripId = trip == null ? 0 : trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        LinearLayout form = dialogForm();
        EditText odometer = input("Odometer awal (opsional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText notes = input("Catatan keberangkatan (opsional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(odometer);
        form.addView(notes, withTop(12));
        new AlertDialog.Builder(this)
                .setTitle("Mulai perjalanan")
                .setMessage("Manifest WMS akan diperiksa ulang sebelum berangkat. Hanya manifest LOADED dan terkonfirmasi SESUAI yang dapat mengubah perjalanan menjadi ON_ROUTE/Shipping.")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Mulai", (dialog, which) -> withLocationPermission(() -> startTrip(trip, odometer.getText().toString(), notes.getText().toString())))
                .show();
    }

    private void startTrip(JSONObject trip, String odometer, String notes) {
        int tripId = trip == null ? 0 : trip.optInt("id");
        if (tripId <= 0) {
            toast("ID perjalanan tidak tersedia.");
            return;
        }
        toast("Memulai perjalanan...");
        networkExecutor.execute(() -> {
            try {
                JSONObject manifestResponse = api.manifest(tripId);
                JSONObject currentManifest = manifestData(manifestResponse);
                JSONObject confirmation = currentManifest.optJSONObject("confirmation");
                if (!isManifestReadyForVerification(currentManifest, trip)) {
                    runOnUiThread(() -> showWmsManifestBlocked(trip, currentManifest, "memulai perjalanan"));
                    return;
                }
                if (!isManifestConfirmed(confirmation)) {
                    runOnUiThread(() -> {
                        toast("Konfirmasi manifest SESUAI wajib diselesaikan sebelum berangkat.");
                        showManifestDetail(trip, manifestResponse);
                    });
                    return;
                }
                api.startTrip(tripId, odometer, notes);
                runOnUiThread(() -> {
                    session.setActiveTripId(tripId);
                    LocationForegroundService.start(this, tripId);
                    toast("Perjalanan aktif. Posisi awal dikirim, lalu diperbarui sekitar setiap 15 menit.");
                    loadTrips();
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(messageOf(error)));
            }
        });
    }

    private void showCompleteDialog(int tripId) {
        LinearLayout form = dialogForm();
        EditText odometer = input("Odometer akhir (opsional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText destination = input("Catatan tujuan (opsional)", InputType.TYPE_CLASS_TEXT);
        EditText notes = input("Catatan perjalanan (opsional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(odometer);
        form.addView(destination, withTop(12));
        form.addView(notes, withTop(12));
        new AlertDialog.Builder(this)
                .setTitle("Selesaikan perjalanan")
                .setMessage("Pelacakan GPS akan dihentikan setelah perjalanan diselesaikan.")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Selesaikan", (dialog, which) -> completeTrip(tripId, odometer.getText().toString(), destination.getText().toString(), notes.getText().toString()))
                .show();
    }

    private void completeTrip(int tripId, String odometer, String destination, String notes) {
        toast("Menyelesaikan perjalanan...");
        networkExecutor.execute(() -> {
            try {
                api.completeTrip(tripId, odometer, destination, notes);
                runOnUiThread(() -> {
                    LocationForegroundService.stop(this);
                    session.clearActiveTripId();
                    toast("Perjalanan diselesaikan.");
                    loadTrips();
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(messageOf(error)));
            }
        });
    }

    private void showIssueDialog(int tripId) {
        LinearLayout form = dialogForm();
        EditText title = input("Judul kendala", InputType.TYPE_CLASS_TEXT);
        EditText notes = input("Detail kendala atau kondisi kendaraan", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText odometer = input("Odometer saat kendala (opsional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(title);
        form.addView(notes, withTop(12));
        form.addView(odometer, withTop(12));
        new AlertDialog.Builder(this)
                .setTitle("Lapor Kendala Armada")
                .setMessage("Laporan masuk sebagai catatan kendala di Manajemen Armada.")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kirim", (dialog, which) -> {
                    if (title.getText().toString().trim().isEmpty() || notes.getText().toString().trim().isEmpty()) {
                        toast("Judul dan detail kendala wajib diisi.");
                        return;
                    }
                    submitIssue(tripId, title.getText().toString(), notes.getText().toString(), odometer.getText().toString());
                })
                .show();
    }

    private void submitIssue(int tripId, String title, String notes, String odometer) {
        networkExecutor.execute(() -> {
            try {
                api.reportIssue(tripId, title, notes, odometer);
                runOnUiThread(() -> toast("Kendala armada berhasil dikirim."));
            } catch (Exception error) {
                runOnUiThread(() -> toast(messageOf(error)));
            }
        });
    }

    private void captureAndSendLocation(int tripId) {
        toast("Mengambil lokasi saat ini...");
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        if (location == null) {
                            toast("Lokasi belum tersedia. Pastikan GPS perangkat aktif.");
                            return;
                        }
                        sendLocation(tripId, location);
                    })
                    .addOnFailureListener(error -> toast("Lokasi gagal diambil: " + messageOf(error)));
        } catch (SecurityException error) {
            toast("Izin lokasi belum diberikan.");
        }
    }

    private void sendLocation(int tripId, Location location) {
        networkExecutor.execute(() -> {
            final JSONObject payload;
            try {
                payload = api.locationPayload(
                        location.getLatitude(),
                        location.getLongitude(),
                        location.hasAccuracy() ? location.getAccuracy() : 0f,
                        location.hasSpeed() ? location.getSpeed() : 0f,
                        location.hasBearing() ? location.getBearing() : 0f,
                        0,
                        location.getTime()
                );
            } catch (Exception error) {
                runOnUiThread(() -> toast(messageOf(error)));
                return;
            }
            try {
                api.sendLocationPayload(tripId, payload);
                runOnUiThread(() -> toast("Lokasi berhasil diperbarui."));
            } catch (Exception error) {
                queueOrShow(
                        error,
                        "GPS",
                        tripId,
                        "fleet-mobile/trips/" + tripId + "/location",
                        payload,
                        "Lokasi disimpan offline dan akan dikirim otomatis.",
                        null
                );
            }
        });
    }

    private void withLocationPermission(Runnable action) {
        if (hasLocationPermission()) {
            action.run();
            return;
        }
        afterLocationPermission = action;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestCameraPermissionAtLaunch();
    }

    private void requestCameraPermissionAtLaunch() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void showCameraPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Izin kamera diperlukan")
                .setMessage("Aktifkan izin kamera agar Driver/Helper dapat memindai QR barang/pallet dan mengambil foto bukti penerimaan.")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Buka Pengaturan", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            Runnable next = afterLocationPermission;
            afterLocationPermission = null;
            if (hasLocationPermission() && next != null) {
                next.run();
            } else {
                toast("Izin lokasi diperlukan untuk aksi ini.");
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            requestCameraPermissionAtLaunch();
        } else if (requestCode == REQUEST_CAMERA) {
            Runnable next = afterCameraPermission;
            afterCameraPermission = null;
            if (hasCameraPermission()) {
                if (next != null) next.run();
            } else {
                scannerTargetItemId = "";
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    showCameraPermissionSettingsDialog();
                } else {
                    toast("Izin kamera diperlukan untuk memindai QR dan mengambil foto bukti.");
                }
            }
        }
    }

    private void openMap(double latitude, double longitude) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude));
            startActivity(intent);
        } catch (Exception error) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + latitude + "," + longitude)));
        }
    }

    private View topBar(String heading, boolean back) {
        return topBar(heading, back ? this::loadTrips : null);
    }

    private View topBar(String heading, Runnable backAction) {
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (backAction != null) {
            Button backButton = compactButton("‹");
            backButton.setContentDescription("Kembali");
            backButton.setOnClickListener(view -> backAction.run());
            bar.addView(backButton);
        } else {
            bar.addView(brandMark());
        }
        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.addView(label("BUDIMAS · DRIVER HELPER", 10, color(R.color.text_muted), Typeface.BOLD));
        textBlock.addView(title(heading, 21), withTop(2));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(11);
        bar.addView(textBlock, textParams);
        if (backAction == null) {
            Button logout = compactButton("Keluar");
            logout.setOnClickListener(view -> {
                LocationForegroundService.stop(this);
                offlineQueue.clearOwner(session.queueOwner());
                session.clear();
                showLogin();
            });
            bar.addView(logout);
        }
        return bar;
    }

    private LinearLayout beginPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(36));
        page.setBackgroundColor(color(R.color.app_background));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.removeAllViews();
        container.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(gradientBackground(
                color(R.color.surface),
                color(R.color.surface_light),
                color(R.color.border),
                20
        ));
        box.setElevation(dp(2));
        return box;
    }

    private LinearLayout accentCard(int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(gradientBackground(
                mixColors(color(R.color.surface), accent, 0.20f),
                mixColors(color(R.color.surface_light), accent, 0.07f),
                withAlpha(accent, 132),
                20
        ));
        box.setElevation(dp(2));
        return box;
    }

    private LinearLayout heroCard(int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(21), dp(20), dp(21));
        box.setBackground(gradientBackground(
                mixColors(color(R.color.surface), accent, 0.42f),
                mixColors(color(R.color.surface_light), accent, 0.18f),
                withAlpha(accent, 170),
                24
        ));
        box.setElevation(dp(4));
        return box;
    }

    private View brandHeader(String brand, String product, String category) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(brandMark());
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(label(category, 10, color(R.color.cyan), Typeface.BOLD));
        words.addView(title(brand, 20), withTop(2));
        words.addView(body(product, 12), withTop(1));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(12);
        header.addView(words, wordsParams);
        return header;
    }

    private TextView brandMark() {
        TextView mark = text("B", 21, Color.WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setMinWidth(dp(46));
        mark.setMinHeight(dp(46));
        mark.setPadding(dp(10), dp(8), dp(10), dp(8));
        mark.setBackground(gradientBackground(color(R.color.blue), color(R.color.blue_deep), Color.TRANSPARENT, 15));
        return mark;
    }

    private LinearLayout menuCard(String number, String eyebrow, String heading, String description, int accent) {
        LinearLayout card = accentCard(accent);
        LinearLayout headingRow = horizontal();
        TextView numberView = text(number, 13, accent, Typeface.BOLD);
        numberView.setGravity(Gravity.CENTER);
        numberView.setPadding(dp(9), dp(7), dp(9), dp(7));
        numberView.setBackground(background(withAlpha(accent, 34), withAlpha(accent, 130), 12));
        headingRow.addView(numberView);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(label(eyebrow, 10, color(R.color.text_muted), Typeface.BOLD));
        words.addView(title(heading, 19), withTop(3));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordParams.leftMargin = dp(11);
        headingRow.addView(words, wordParams);
        card.addView(headingRow);
        card.addView(body(description, 14), withTop(11));
        return card;
    }

    private TextView tripStat(String value, String caption) {
        TextView stat = text(value + "\n" + caption, 14, color(R.color.text_primary), Typeface.BOLD);
        stat.setPadding(dp(12), dp(10), dp(12), dp(10));
        stat.setBackground(background(color(R.color.surface_input), color(R.color.border), 13));
        return stat;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);
        return form;
    }

    private TextView title(String value, float size) {
        return text(value, size, color(R.color.text_primary), Typeface.BOLD);
    }

    private TextView body(String value, float size) {
        return text(value, size, color(R.color.text_secondary), Typeface.NORMAL);
    }

    private TextView label(String value, float size, int textColor, int typeface) {
        return text(value, size, textColor, typeface);
    }

    private TextView text(String value, float size, int textColor, int typeface) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(textColor);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif", typeface));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(1), 1.12f);
        if (typeface == Typeface.BOLD && size <= 12f) view.setLetterSpacing(0.08f);
        return view;
    }

    private TextView chip(String value, int accent) {
        TextView chip = text(value, 11, accent, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(8), dp(11), dp(8));
        chip.setBackground(background(withAlpha(accent, 28), withAlpha(accent, 175), 18));
        return chip;
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(color(R.color.text_secondary));
        input.setTextColor(color(R.color.text_primary));
        input.setTextSize(16);
        input.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        input.setInputType(inputType);
        input.setMinHeight(dp(52));
        input.setPadding(dp(15), dp(12), dp(15), dp(12));
        input.setBackground(background(color(R.color.surface_input), color(R.color.border), 14));
        return input;
    }

    private Button primaryButton(String label) {
        Button button = button(label, color(R.color.blue), Color.TRANSPARENT);
        button.setBackground(gradientBackground(color(R.color.blue), color(R.color.blue_deep), Color.TRANSPARENT, 14));
        button.setTextColor(Color.WHITE);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = button(label, color(R.color.surface_light), color(R.color.border));
        button.setTextColor(color(R.color.text_primary));
        return button;
    }

    private Button dangerButton(String label) {
        Button button = button(label, withAlpha(color(R.color.red), 40), withAlpha(color(R.color.red), 160));
        button.setTextColor(color(R.color.red));
        return button;
    }

    private Button button(String label, int fill, int stroke) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setBackground(background(fill, stroke, 14));
        return button;
    }

    private Button compactButton(String label) {
        Button button = button(label, color(R.color.surface_light), color(R.color.border));
        button.setTextColor(color(R.color.text_primary));
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        if (label.length() <= 2) button.setTextSize(24);
        else button.setTextSize(12);
        return button;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private GradientDrawable background(int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable gradientBackground(int start, int end, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams withTop(int topDp) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams left(int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(leftDp);
        return params;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int resource) {
        return ContextCompat.getColor(this, resource);
    }

    private int withAlpha(int source, int alpha) {
        return Color.argb(alpha, Color.red(source), Color.green(source), Color.blue(source));
    }

    private int mixColors(int first, int second, float secondWeight) {
        float weight = Math.max(0f, Math.min(1f, secondWeight));
        return Color.rgb(
                Math.round(Color.red(first) * (1f - weight) + Color.red(second) * weight),
                Math.round(Color.green(first) * (1f - weight) + Color.green(second) * weight),
                Math.round(Color.blue(first) * (1f - weight) + Color.blue(second) * weight)
        );
    }

    private int statusColor(String status) {
        if ("BERANGKAT".equalsIgnoreCase(status)) return color(R.color.green);
        if ("SIAP_JALAN".equalsIgnoreCase(status)) return color(R.color.orange);
        if ("SELESAI".equalsIgnoreCase(status)) return color(R.color.text_secondary);
        return color(R.color.red);
    }

    private String value(JSONObject data, String key, String fallback) {
        String raw = data.optString(key, "").trim();
        return raw.isEmpty() || "null".equalsIgnoreCase(raw) ? fallback : raw;
    }

    private String messageOf(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? "Terjadi kendala saat menghubungi server." : value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private interface LocationAction {
        void onLocation(Location location);
    }

    private static final class PodDraft {
        final String eventId = UUID.randomUUID().toString();
        boolean failedDelivery = false;
        String failureReason = "";
        final JSONObject trip;
        final JSONObject stop;
        final String stopId;
        final Location location;
        final String arrivedAt;
        final List<PodItemDraft> items = new ArrayList<>();
        EditText receiverName;
        EditText notes;
        TextView photoInfo;
        SignaturePadView signature;
        String photoJpegBase64 = "";

        PodDraft(JSONObject trip, JSONObject stop, String stopId, Location location, String arrivedAt) {
            this.trip = trip;
            this.stop = stop;
            this.stopId = stopId;
            this.location = location;
            this.arrivedAt = arrivedAt;
        }
    }

    private static final class PodItemDraft {
        final String id;
        final String manifestDetailId;
        final double plannedPcs;
        final String uomName;
        final double uomLevel;
        final EditText actualPcs;
        final EditText actualUom;
        final EditText returnPcs;
        final EditText discrepancy;

        PodItemDraft(String id, String manifestDetailId, double plannedPcs, String uomName, double uomLevel,
                     EditText actualPcs, EditText actualUom, EditText returnPcs, EditText discrepancy) {
            this.id = id;
            this.manifestDetailId = manifestDetailId == null ? "" : manifestDetailId;
            this.plannedPcs = plannedPcs;
            this.uomName = uomName == null ? "" : uomName;
            this.uomLevel = uomLevel;
            this.actualPcs = actualPcs;
            this.actualUom = actualUom;
            this.returnPcs = returnPcs;
            this.discrepancy = discrepancy;
        }
    }

    private static final class ManifestItem {
        final String id;
        final double plannedPcs;
        final CheckBox checked;
        final EditText actualQty;
        final TextView scanInfo;
        String scanPayload;
        boolean qrVerified;

        ManifestItem(String id, double plannedPcs, CheckBox checked, EditText actualQty, TextView scanInfo, String scanPayload, boolean qrVerified) {
            this.id = id;
            this.plannedPcs = plannedPcs;
            this.checked = checked;
            this.actualQty = actualQty;
            this.scanInfo = scanInfo;
            this.scanPayload = scanPayload == null ? "" : scanPayload;
            this.qrVerified = qrVerified;
        }
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
