package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the screensaver may see what is playing (NowPlaying): Android hands out media sessions to an app
 * with notification access. No notification is read. Enabled over adb:
 * cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
