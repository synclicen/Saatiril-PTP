package com.saatiril.andro.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.saatiril.andro.MainActivity
import com.saatiril.andro.R
import java.net.InetAddress

/**
 * Ceremony Mode VPN Service — blocks all internet traffic on THIS device
 * except for LAN (local network) and Google Drive (for optional photo backup).
 *
 * HOW IT WORKS:
 * Uses Android's VpnService API to create a TUN interface that captures all
 * outbound network traffic from THIS device. The VPN routes traffic through
 * a dummy interface that drops everything EXCEPT:
 *   - LAN traffic (192.168.x.x, 10.x.x.x, 172.16-31.x.x) → allowed
 *   - Google Drive IPs (drive.google.com, *.googleusercontent.com) → allowed
 *   - Everything else → dropped (no internet for WhatsApp, Instagram, etc.)
 *
 * IMPORTANT LIMITATION:
 * VpnService only affects the device it runs on. It CANNOT block internet
 * for OTHER devices connected to this phone's hotspot. Hotspot clients need
 * their own VpnService (e.g. operator APK) or manual airplane mode.
 *
 * USAGE:
 * - Admin: toggle "Mode Prosesi" in Admin Dashboard
 * - Operator: auto-activates when connected to server
 * - MC (browser): manual airplane mode required
 */
class CeremonyModeVpnService : VpnService() {

    companion object {
        private const val TAG = "CeremonyVPN"
        private const val NOTIF_ID = 7777
        private const val CHANNEL_ID = "ceremony_mode_vpn"

        /** Start the VPN service. */
        fun start(context: Context) {
            val intent = Intent(context, CeremonyModeVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Ceremony Mode VPN start requested")
        }

        /** Stop the VPN service. */
        fun stop(context: Context) {
            val intent = Intent(context, CeremonyModeVpnService::class.java)
            context.stopService(intent)
            Log.i(TAG, "Ceremony Mode VPN stop requested")
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "CeremonyModeVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand — starting Ceremony Mode VPN")

        // Start as foreground service (required for VPN services)
        // Must be called within 5 seconds of startForegroundService()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED: ${e.message}", e)
            CeremonyModeManager.isActive.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Set up the VPN interface
        try {
            setupVpn()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN: ${e.message}", e)
            CeremonyModeManager.isActive.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /**
     * Configure the VPN to block all internet apps except Saatiril.
     *
     * Strategy: Split routing — route ONLY internet ranges through TUN,
     * leave private/LAN ranges (10.x, 172.16-31.x, 192.168.x) unrouted.
     *
     * - Internet traffic from other apps → goes through TUN → dropped (BLOCKED)
     * - LAN traffic (192.168.x.x, etc.) → uses real interface → WORKING
     * - Saatiril app: disallowed from VPN → bypasses TUN, uses real network
     *   → can access LAN (server on localhost:3003) AND internet (Google Drive)
     *
     * This split routing is CRITICAL: if we used addRoute("0.0.0.0", 0) it
     * would capture ALL traffic including LAN, which breaks incoming
     * connections from MC web (browser) and operator APKs to the server.
     *
     * Result: WhatsApp, Instagram, Play Store, OS updates = BLOCKED.
     * Saatiril server + LAN clients (MC web, operators) = WORKING.
     */
    private fun setupVpn() {
        val builder = Builder()

        // TUN interface address (dummy — we drop all packets)
        builder.addAddress("10.200.200.1", 32)

        // DNS server that won't resolve (forces DNS failures for internet domains)
        builder.addDnsServer("10.200.200.2")

        // ── CRITICAL: Route ONLY internet traffic through TUN, NOT LAN ──
        // If we use addRoute("0.0.0.0", 0) it captures ALL traffic including
        // LAN (192.168.x.x, 10.x.x.x). This breaks incoming connections to
        // the Saatiril server from hotspot clients (MC web, operator APK).
        //
        // Instead, we add routes that cover ALL IPv4 EXCEPT private/LAN ranges:
        //   - 10.0.0.0/8      (10.x.x.x)
        //   - 172.16.0.0/12   (172.16.x.x - 172.31.x.x)
        //   - 192.168.0.0/16  (192.168.x.x)
        //
        // These private ranges are left UNROUTED → traffic uses real interface.
        // This allows MC web (browser) and operator APKs to connect to the
        // server via LAN while Mode Prosesi blocks internet for other apps.
        //
        // The routes below cover 0.0.0.0 - 255.255.255.255 EXCEPT the 3 private ranges:

        // Skip 10.0.0.0/8 (10.x.x.x):
        builder.addRoute("0.0.0.0", 5)       // 0-7
        builder.addRoute("8.0.0.0", 7)       // 8-9
        builder.addRoute("11.0.0.0", 8)      // 11
        builder.addRoute("12.0.0.0", 6)      // 12-15
        builder.addRoute("16.0.0.0", 4)      // 16-31
        builder.addRoute("32.0.0.0", 3)      // 32-63
        builder.addRoute("64.0.0.0", 2)      // 64-127

        // Skip 172.16.0.0/12 (172.16-172.31):
        builder.addRoute("128.0.0.0", 3)     // 128-159
        builder.addRoute("160.0.0.0", 5)     // 160-167
        builder.addRoute("168.0.0.0", 6)     // 168-171
        builder.addRoute("172.0.0.0", 12)    // 172.0-172.15
        builder.addRoute("172.32.0.0", 11)   // 172.32-172.63
        builder.addRoute("172.64.0.0", 10)   // 172.64-172.127
        builder.addRoute("172.128.0.0", 9)   // 172.128-172.255
        builder.addRoute("173.0.0.0", 8)     // 173
        builder.addRoute("174.0.0.0", 7)     // 174-175
        builder.addRoute("176.0.0.0", 4)     // 176-191

        // Skip 192.168.0.0/16 (192.168.x.x):
        builder.addRoute("192.0.0.0", 9)     // 192.0-192.127
        // 192.128-192.191, i.e. 192.128.0.0/10, EXCEPT the LAN 192.168.0.0/16.
        // A single addRoute("192.128.0.0", 10) is WRONG: it also covers
        // 192.168.x.x (the LAN) and would route hotspot clients' traffic into
        // the TUN, breaking the Saatiril server. Decomposition of /10 minus
        // the /16 hole (verified: covers 192.128-192.167 + 192.169-192.191):
        builder.addRoute("192.128.0.0", 11)  // 192.128-192.159
        builder.addRoute("192.160.0.0", 13)  // 192.160-192.167
        builder.addRoute("192.169.0.0", 16)  // 192.169
        builder.addRoute("192.170.0.0", 15)  // 192.170-192.171
        builder.addRoute("192.172.0.0", 14)  // 192.172-192.175
        builder.addRoute("192.176.0.0", 12)  // 192.176-192.191
        builder.addRoute("192.192.0.0", 11)  // 192.192-192.223
        builder.addRoute("192.224.0.0", 12)  // 192.224-192.239
        builder.addRoute("192.240.0.0", 13)  // 192.240-192.247
        builder.addRoute("192.248.0.0", 14)  // 192.248-192.251
        builder.addRoute("192.252.0.0", 15)  // 192.252-192.253
        builder.addRoute("192.254.0.0", 16)  // 192.254
        builder.addRoute("192.255.0.0", 16)  // 192.255
        builder.addRoute("193.0.0.0", 8)     // 193
        builder.addRoute("194.0.0.0", 7)     // 194-195
        builder.addRoute("196.0.0.0", 6)     // 196-199
        builder.addRoute("200.0.0.0", 5)     // 200-207
        builder.addRoute("208.0.0.0", 4)     // 208-223
        builder.addRoute("224.0.0.0", 3)     // 224-255

        // EXCLUDE Saatiril app: its traffic bypasses VPN entirely
        // → Saatiril can access LAN (server on localhost:3003) AND
        //   internet (Google Drive upload) via the real network interface.
        // Use packageName (NOT a hard-coded "com.saatiril.andro") so this
        // works in every flavor (e.g. the .mc flavor is com.saatiril.andro.mc —
        // the hard-coded string throws NameNotFoundException there and the
        // VPN never starts). Try/catch kept for safety, same as Drive below.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disallow own package ($packageName): ${e.message}", e)
        }

        // EXCLUDE Google Drive app: allows admin to manually upload files
        // via Google Drive app if needed. Wrapped in try-catch because
        // addDisallowedApplication throws NameNotFoundException if the app
        // is not installed on this device.
        try {
            builder.addDisallowedApplication("com.google.android.apps.docs")
        } catch (e: Exception) {
            Log.i(TAG, "Google Drive app not installed — skipping exception (ok)")
        }

        builder.setMtu(1500)

        // Build and establish the VPN interface
        vpnInterface = builder
            .setSession("Saatiril Mode Prosesi")
            .establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface — establish() returned null")
            CeremonyModeManager.isActive.postValue(false)
            stopSelf()
            return
        }

        Log.i(TAG, "VPN established — blocking all apps except $packageName")
        Log.i(TAG, "Saatiril LAN (localhost, 192.168.43.1) + Google Drive: WORKING")
        Log.i(TAG, "Other apps (WhatsApp, Instagram, Play Store): BLOCKED")

        // Start a dummy worker that keeps the TUN alive (reads and discards)
        workerThread = Thread {
            val pfd = vpnInterface
            if (pfd != null) {
                val input = java.io.FileInputStream(pfd.fileDescriptor)
                val output = java.io.FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32767)
                Log.i(TAG, "VPN worker started — dropping all packets")
                try {
                    while (!Thread.interrupted()) {
                        val len = input.read(buffer)
                        if (len > 0) {
                            // DROP the packet — don't forward, don't respond
                            // This effectively blocks all internet for routed apps
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "VPN worker ended: ${e.message}")
                } finally {
                    try { input.close() } catch (_: Exception) {}
                    try { output.close() } catch (_: Exception) {}
                }
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "CeremonyModeVpnService destroying — stopping VPN")

        // BUG #12 fix: close vpnInterface FIRST so the worker thread's
        // `input.read(buffer)` unblocks (the underlying FD is closed → read
        // throws IOException → the catch clause lets the thread exit). Only
        // AFTER that do we interrupt() the thread. The previous order
        // (interrupt first, close second) was racy: interrupt() does NOT
        // unblock a FileInputStream.read() on Android — the thread keeps
        // waiting on the FD forever, leaking the worker + preventing the
        // service from fully releasing the TUN interface. The user-visible
        // symptom was "Mode Prosesi toggle stuck ON" after toggling it off.
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null

        // Now interrupt as a belt-and-suspenders — by this point the FD is
        // closed and the worker should already be exiting its read loop.
        workerThread?.interrupt()
        try { workerThread?.join(500) } catch (_: InterruptedException) {}
        workerThread = null
    }

    override fun onRevoke() {
        // System revoked the VPN (user disabled it in settings).
        // Reset the manager state so the UI (Admin Dashboard "Mode Prosesi"
        // toggle) doesn't keep showing Ceremony Mode as active after the VPN
        // is gone. stopSelf() → onDestroy() cleans up interface + worker
        // (same cleanup path as a normal disable).
        Log.i(TAG, "VPN revoked by system — resetting Ceremony Mode state")
        CeremonyModeManager.isActive.postValue(false)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mode Prosesi VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Memblokir internet selama prosesi wisuda"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Mode Prosesi Aktif")
            .setContentText("Internet diblokir — LAN server tetap jalan")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
