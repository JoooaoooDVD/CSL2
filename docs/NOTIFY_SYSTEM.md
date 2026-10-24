# Announcements & notifications (`notify/`)

Admin-published content pulled by the launcher from a Supabase project (read-only anon key,
row level security exposes only live rows). Writes happen only from the private admin panel.

| Piece | Role |
|---|---|
| `NotifyConfig` | project URL, anon key, channel ids, job id, poll interval (3 h) |
| `NotifyApi` | tiny PostgREST client (`HttpURLConnection`): fetch rows, post delivery events, download images |
| `NotifyStore` | SharedPreferences `cs_notify`: anonymous device hash, shown/dismissed/delivered ids, last sync |
| `NotificationSync` | fetches `notifications`, posts real Android notifications (channel `cs_announcements`, HIGH); `update` kind is only delivered to builds older than `version_code`; tap → `LauncherActivity` (`handleIntent`: opens URL or the in-app update checker) |
| `NotifyJobService` | `JobScheduler` periodic job (persisted, any network) → `NotificationSync.sync` |
| `AnnouncementController` | fetches `announcements`, waits while onboarding/tutorial is busy, shows one `fullscreen` (Dialog, dimmed) and then one `card` (slide-in on home, auto-hide 18 s) per process |

Hooks: `LauncherActivity.onCreate` (after the first-launch chain, only once the policy is accepted)
and `onNewIntent`. `VirtualLanService.notifyNewPlayers()` posts a "X joined your room" heads-up
notification (channel `cs_virtual_lan_players`) for every `PLAYER_JOINED` event on the host.

Repeat rules: `show_once=true` → once per device; `show_once=false` → every launch until the user
dismisses or taps the button. Notifications are delivered once per device; items created more than
24 h before the launcher first ran with this module are skipped (no replay of old news).
