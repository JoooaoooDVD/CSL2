package com.craftstudio.cslauncher.notify;

/**
 * Supabase project used for announcements and notifications (read-only anon key; rows are
 * filtered by row level security so only live items are visible). Writes happen from the
 * private admin panel with the service key, never from the launcher.
 */
final class NotifyConfig {
    static final String URL = "https://vslinmrisevqdlpxjihu.supabase.co";
    static final String ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZzbGlubXJpc2V2cWRscHhqaWh1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI4NTA2MDYsImV4cCI6MjEwODQyNjYwNn0.jYnZOgyuW2fl3knJBMv-WMOpRrTbWRNItBkbUej6210";
    static final String CHANNEL_ID = "cs_announcements";
    static final String CHANNEL_LAN_ID = "cs_virtual_lan_players";
    /** How often the background job looks for new notifications */
    static final long POLL_INTERVAL_MS = 3L * 60 * 60 * 1000;
    static final int JOB_ID = 7731;
    static final int NOTIFICATION_BASE_ID = 7000;

    private NotifyConfig() {}
}
