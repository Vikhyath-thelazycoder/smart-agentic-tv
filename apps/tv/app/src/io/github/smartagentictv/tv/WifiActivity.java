package io.github.smartagentictv.tv;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Nearby networks and joining one. Anything unusual (a saved network the system won't hand over) opens the TV's own screen. */
public class WifiActivity extends GlassActivity {
    private WifiManager wifi;
    private boolean registered;
    private final BroadcastReceiver scans = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            list();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        title("Wi-Fi", "Choose a network.");
        list();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(scans, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        registered = true;
        if (wifi != null) wifi.startScan();
    }

    @Override protected void onPause() {
        if (registered) unregisterReceiver(scans);
        registered = false;
        super.onPause();
    }

    private void list() {
        boolean hadFocus = rows.findFocus() != null;
        rows.removeAllViews();
        String current = GlassSettingsActivity.ssid(this);
        List<ScanResult> found = wifi == null ? new ArrayList<>() : new ArrayList<>(wifi.getScanResults());
        Collections.sort(found, (a, b) -> b.level - a.level);
        Set<String> seen = new HashSet<>();
        for (ScanResult r : found) {
            if (r.SSID == null || r.SSID.isEmpty() || !seen.add(r.SSID)) continue;
            final String name = r.SSID;
            final boolean secured = r.capabilities.contains("WPA") || r.capabilities.contains("WEP");
            String sub = name.equals(current) ? "Connected" : bars(r.level) + (secured ? "  ·  Password needed" : "  ·  Open");
            row(name, sub, () -> choose(name, secured, name.equals(current)));
        }
        if (seen.isEmpty()) note("Looking for networks…");
        row("More Wi-Fi options", "The TV's own Wi-Fi settings", this::advanced);
        focusFirst();
    }

    private static String bars(int level) {
        int n = WifiManager.calculateSignalLevel(level, 4);
        return n >= 3 ? "Strong" : n == 2 ? "Good" : "Weak";
    }

    private void choose(String name, boolean secured, boolean connected) {
        if (connected) {
            toast("Already connected to " + name);
            return;
        }
        WifiConfiguration saved = savedNetwork(name);
        if (saved != null) {
            join(saved.networkId);
            return;
        }
        if (!secured) {
            WifiConfiguration c = config(name);
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            join(wifi.addNetwork(c));
            return;
        }
        final EditText box = new EditText(this);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.setSingleLine(true);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Password for " + name)
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    WifiConfiguration c = config(name);
                    c.preSharedKey = '"' + box.getText().toString() + '"';
                    join(wifi.addNetwork(c));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static WifiConfiguration config(String name) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = '"' + name + '"';
        return c;
    }

    private WifiConfiguration savedNetwork(String name) {
        List<WifiConfiguration> all = wifi.getConfiguredNetworks();
        if (all == null) return null;
        for (WifiConfiguration c : all) if (('"' + name + '"').equals(c.SSID)) return c;
        return null;
    }

    private void join(int id) {
        if (id == -1 || !wifi.enableNetwork(id, true)) {
            toast("The TV needs its own Wi-Fi screen for this one");
            advanced();
            return;
        }
        wifi.reconnect();
        toast("Connecting…");
    }

    private void advanced() {
        open(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings");
    }
}
