package com.gathin.tvhome;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The phone remote's address (http://<the TV's address>:8080) as a QR code for the screensaver. One pixel per
 * module: the ImageView scales it up without filtering, so it stays sharp at any size and costs a few hundred
 * bytes. Built once and kept until the TV's address changes.
 */
final class RemoteQr {
    private static String cachedFor;
    private static Bitmap cached;

    private RemoteQr() {
    }

    /** The QR for this TV's current address, or null when it has none on the home network. */
    static synchronized Bitmap current() {
        String ip = address();
        if (ip == null) return null;
        String url = "http://" + ip + ":" + RemoteServer.PORT;
        if (url.equals(cachedFor) && cached != null) return cached;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF16161C : 0xFFD6D6DC;
            }
            cached = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            cachedFor = url;
            return cached;
        } catch (Exception e) {
            return null;
        }
    }

    /** The first private IPv4 address of an interface that is up (Wi-Fi or Ethernet). */
    static String address() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
