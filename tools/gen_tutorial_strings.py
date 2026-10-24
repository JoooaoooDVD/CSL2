#!/usr/bin/env python3
"""Generates res/values/strings_tutorial.xml for the onboarding / tutorial system."""
import os, re, sys
S = {}
# ── engine / tooltip ──
S.update({
"tut_help_button": "Help and tutorials",
"tut_close": "Close",
"tut_back": "Back",
"tut_next": "Next",
"tut_done": "Done",
"tut_continue": "Continue",
"tut_retry": "Retry",
"tut_skip_step": "Skip step",
"tut_step_counter": "Step %1$d of %2$d · %3$s",
"tut_guided_hint": "Guided mode: %s You can also just tap Next.",
"tut_fallback_unavailable": "This part is not available right now (for example the feature needs a profile or an installed version). You can skip it and continue.",
"tut_fallback_navigation": "The screen for this step could not be opened automatically. Open it yourself and the tutorial continues, or skip this step.",
"tut_fallback_target": "\"%s\" is not visible at the moment. Scroll or open the screen it belongs to and tap Retry, or skip this step.",
"tut_fallback_cannot_start": "This tutorial cannot start right now.",
"tut_exit_title": "Leave the tutorial?",
"tut_exit_message": "Your position is saved. You can continue later from Settings → Miscellaneous → Tutorials, or from the ? button.",
"tut_exit_confirm": "Leave for now",
"tut_exit_keep": "Keep going",
"tut_module_done_toast": "%s completed",
"tut_help_subtitle": "%d steps · interactive",
"tut_help_start": "Start tutorial",
"tut_help_all": "All tutorials",
"tut_help_already_running": "A tutorial is already running.",
"tut_tip_learn": "New here? Learn \"%s\" in a short tutorial.",
"tut_tip_start": "Start",
"tut_tip_resume": "Continue the \"%s\" tutorial where you left off?",
})
# ── setup wizard ──
S.update({
"tut_setup_eyebrow": "First-time setup",
"tut_setup_skip": "Skip tutorial",
"tut_welcome_title": "Welcome to %s",
"tut_welcome_subtitle": "A short, hands-on tour that explains the launcher while you use it. Nothing is downloaded or changed without you.",
"tut_welcome_heading": "What you get",
"tut_welcome_text": "The launcher runs Minecraft: Java Edition on your device. Here is what this tour covers:",
"tut_welcome_f1_t": "Profiles for every setup",
"tut_welcome_f1_b": "Each profile has its own version, mod loader, memory, renderer and appearance.",
"tut_welcome_f2_t": "Mods, modpacks, resource packs, shaders and worlds",
"tut_welcome_f2_b": "Browse and install from Modrinth or CurseForge, or import your own files.",
"tut_welcome_f3_t": "Appearance you control",
"tut_welcome_f3_b": "Light, dark or system theme for the launcher, and a Vanilla or CS Custom look for Minecraft per profile.",
"tut_welcome_f4_t": "Tools when you need them",
"tut_welcome_f4_b": "Virtual LAN multiplayer, launcher logs, Discord Rich Presence, control layouts and more.",
"tut_welcome_note": "Interactive steps ask you to tap the real control and check what happened. You can skip any step, leave at any time and come back later from Settings → Tutorials.",
"tut_welcome_start": "Start setup",
"tut_welcome_explore": "Explore on my own",
"tut_options_title": "How would you like to learn?",
"tut_options_subtitle": "You can change these choices later in the Tutorial Center.",
"tut_opt_section_mode": "Setup type",
"tut_opt_quick_t": "Quick setup",
"tut_opt_quick_b": "The essentials only: home screen, account, profile, version and the PLAY button. About 5 minutes.",
"tut_opt_complete_t": "Complete tour",
"tut_opt_complete_b": "Everything in Quick plus appearance, content browser, settings, performance, tools and multiplayer. About 15 minutes.",
"tut_opt_section_style": "Tutorial style",
"tut_opt_interactive_t": "Interactive",
"tut_opt_interactive_b": "Some steps ask you to tap the real button, then check the result. The best way to remember where things are.",
"tut_opt_guided_t": "Guided",
"tut_opt_guided_b": "Each element is highlighted and explained; you move with Next. Nothing has to be tapped.",
"tut_opt_tips_title": "Show helpful tips",
"tut_opt_tips_text": "A small, dismissible hint the first time you open a screen that has a tutorial.",
"tut_theme_title": "Choose your look",
"tut_theme_subtitle": "This is the real launcher theme setting; the change is applied immediately and can be changed any time in Settings → Appearance.",
"tut_theme_section": "Launcher theme",
"tut_theme_dark_t": "Dark",
"tut_theme_dark_b": "Deep dark surfaces. Easiest on the eyes and the default.",
"tut_theme_light_t": "Light",
"tut_theme_light_b": "Bright surfaces with dark text.",
"tut_theme_system_t": "Follow system",
"tut_theme_system_b": "Switches with your device\u2019s light or dark mode.",
"tut_theme_note": "The launcher has no separate \u201cVanilla\u201d or \u201cSmart\u201d launcher skin; the theme above is the real appearance setting.",
"tut_theme_mc_eyebrow": "Also good to know",
"tut_theme_mc_title": "Minecraft Appearance is set per profile",
"tut_theme_mc_text": "In each profile\u2019s editor the \u201cMinecraft Appearance\u201d row offers Vanilla Experience (unchanged game) or CS Custom Experience (a CS Launcher Plus resource pack: menus, loading screen, panorama \u2013 needs Minecraft 1.21 or newer). The tour shows this row on a real profile.",
"tut_theme_start_tour": "Start the tour",
"tut_done_title": "You\u2019re set",
"tut_done_subtitle": "The launcher is ready to use. Every tutorial can be replayed from Settings → Miscellaneous → Tutorials or from the ? button.",
"tut_done_heading": "Setup complete",
"tut_done_count": "%1$d of %2$d tour modules completed",
"tut_done_row_theme": "Launcher theme",
"tut_done_row_profile": "Selected profile",
"tut_done_row_account": "Account",
"tut_done_row_modules": "Completed modules",
"tut_done_no_profile": "No profile yet \u2013 create one from the home screen",
"tut_done_no_account": "No account yet \u2013 add one from the account button",
"tut_done_no_modules": "None yet \u2013 open the Tutorial Center any time",
"tut_done_hint": "Launch Minecraft from the PLAY button once a profile with a version and an account are set up.",
"tut_done_hint_ready": "Your selected profile has a version and an account is signed in, so you can launch right away. The first start downloads the game files.",
"tut_done_open": "Open launcher",
"tut_done_explore": "Explore tutorials",
"tut_done_launch": "Launch Minecraft",
})
# ── tutorial center ──
S.update({
"tut_pref_category": "Help",
"tut_pref_title": "Tutorials",
"tut_pref_summary": "Interactive tours of every feature, setup wizard, progress and tips",
"tut_center_title": "Tutorial Center",
"tut_center_stats": "%1$d of %2$d tutorials completed",
"tut_center_more": "More options",
"tut_center_search_hint": "Search tutorials",
"tut_center_empty": "No tutorial matches this search or filter.",
"tut_center_filter_all": "All",
"tut_center_filter_recommended": "Recommended",
"tut_center_filter_beginner": "Beginner",
"tut_center_filter_todo": "Not completed",
"tut_center_filter_done": "Completed",
"tut_category_basics": "Basics",
"tut_category_play": "Play",
"tut_category_content": "Content",
"tut_category_settings": "Settings",
"tut_category_tools": "Tools",
"tut_center_badge_recommended": "Recommended",
"tut_center_badge_beginner": "Beginner",
"tut_center_status_done": "Completed",
"tut_center_status_partial": "Stopped at step %1$d of %2$d",
"tut_center_status_new": "Not started",
"tut_center_minutes": "about %d min",
"tut_center_start": "Start",
"tut_center_continue": "Continue",
"tut_center_resume": "Resume",
"tut_center_replay": "Replay",
"tut_center_replay_all": "Replay all tutorials",
"tut_center_restart_setup": "Restart first-time setup",
"tut_center_restart_message": "Shows the welcome wizard again (setup type, tutorial style, theme and the tour). Your launcher settings, profiles and accounts are not touched.",
"tut_center_restart_confirm": "Restart setup",
"tut_center_reset": "Reset tutorial progress",
"tut_center_reset_message": "Marks every tutorial as not started and forgets the saved position. Launcher settings, profiles, accounts and files are not affected.",
"tut_center_reset_confirm": "Reset progress",
"tut_center_reset_done": "Tutorial progress reset",
"tut_center_tips_on": "Turn on helpful tips",
"tut_center_tips_off": "Turn off helpful tips",
"tut_center_mode_interactive": "Switch to interactive mode",
"tut_center_mode_guided": "Switch to guided mode",
"tut_center_prereq": "Recommended first: %s",
})
# ── modules ──
def mod(p, title, summary, desc, steps):
    S["tut_%s_title"%p]=title; S["tut_%s_summary"%p]=summary; S["tut_%s_desc"%p]=desc
    for sid,(t,b,*i) in steps.items():
        S["tut_%s_%s_t"%(p,sid)]=t; S["tut_%s_%s_b"%(p,sid)]=b
        if i: S["tut_%s_%s_i"%(p,sid)]=i[0]

mod("home","Home screen tour","Where everything lives: profile card, PLAY, content cards, dock and account.",
"A guided walk across the home screen. Every control is highlighted in place and explained; nothing is changed.",{
"intro":("Welcome to the home screen","This is where you start the game, switch profiles and reach every other screen. Let\u2019s look at each part."),
"banner":("Profile banner","The banner shows the selected profile with its version chip. Its background follows the profile\u2019s icon or banner image."),
"profile":("Profile card","Tap this card to open the profile picker: switch between your profiles or create a new one. The selected profile decides which version, loader and settings PLAY uses."),
"edit":("Edit profile","The pencil opens the profile editor: name, icon, Minecraft version, Java runtime, memory, renderer, appearance, control layout and the files folder."),
"play":("PLAY","Starts the selected profile. Missing files are downloaded first (\u201cDownloading\u201d), then the game starts (\u201cLaunching\u201d). An account must be signed in."),
"browse":("Browse resources","Opens the content browser: mods, modpacks, resource packs, shaders and worlds from Modrinth or CurseForge."),
"manage":("Manage content","Lists what is installed in the selected profile by type, with enable/disable and delete, plus the worlds manager."),
"dock":("Top dock","Quick navigation: Home, Browse, Files, Virtual LAN multiplayer, Controls editor and Logs. The active page is highlighted."),
"account":("Account","Shows the signed-in player with the skin preview. Tap it to switch accounts, add one (Microsoft or local) or open the skin editor."),
"settings":("Settings","Opens the settings hub: Video, Appearance, Controls, Java, Miscellaneous and Experimental, plus the Tutorial Center."),
"progress":("Download progress","While something downloads or unpacks, progress appears here. PLAY shows the stage; tapping cancel on a notification really cancels."),
"done":("That\u2019s the home screen","Next tutorials go deeper: accounts, profiles, versions and launching. Open any of them from the ? button or the Tutorial Center."),
})
mod("acc","Accounts","Add a Microsoft or local account, switch between them and manage skins.",
"Explains the account button: account types, how switching works, what is stored, and the skin editor. The interactive step only opens the menu; it never signs in for you.",{
"intro":("The account button","Everything about who you play as lives here. The button shows the current player name and skin head."),
"open":("Open the account menu","Tap the account button to open its menu.","Tap the account button."),
"types":("Account types","Microsoft accounts sign in through Microsoft (needed for online servers). Local accounts work offline with a name you choose \u2013 your username is shown exactly as typed."),
"switch":("Switching accounts","Pick a different entry in the menu to make it the active player. PLAY always uses the active account."),
"none":("No account yet","Choose \u201cAdd account\u201d in the menu to create one. A local account takes seconds; a Microsoft login opens the sign-in flow."),
"skins":("Skins","The skin editor (from the account menu) previews the skin on a 3D model and lets you load local skins when \u201cLocal skins\u201d is enabled in Miscellaneous settings."),
"privacy":("What is stored","Tokens stay on this device in the launcher\u2019s private storage. Removing an account deletes them."),
})
mod("prof","Profiles","Create, pick and edit profiles: version, runtime, memory, renderer and files.",
"A profile is one complete setup. This tutorial opens the profile picker and the editor on a real profile and explains each row. Saving is up to you.",{
"what":("What a profile is","Each profile keeps its own Minecraft version, mod loader, Java runtime, memory, renderer, control layout, appearance and game folder."),
"picker":("Open the profile picker","Tap the profile card to open the picker.","Tap the profile card."),
"picked":("The picker","The list shows every profile with its version and loader. Tapping one selects it; the last entry creates a new profile (Vanilla, Fabric, Forge, NeoForge, Quilt, OptiFine or a modpack)."),
"create":("Creating a profile","\u201cNew profile\u201d asks for the loader and the Minecraft version, then creates and selects it. Nothing is downloaded until you press PLAY."),
"edit":("Open the editor","Tap the pencil to open the profile editor.","Tap the edit (pencil) button."),
"name":("Name","The profile name as shown on the home screen. Renaming does not move files."),
"icon":("Icon and banner","Pick an icon or banner image; the home banner follows it."),
"version":("Minecraft version","Opens the version picker: releases, snapshots and installed loader versions. Changing it switches the files PLAY downloads."),
"runtime":("Java runtime","Which installed Java runtime runs this profile. \u201cAuto\u201d picks the one matching the Minecraft version."),
"jvm":("Memory and JVM arguments","Per-profile memory allocation and extra JVM arguments. Leave empty to use the global Java settings."),
"renderer":("Renderer","The OpenGL renderer used for this profile. The default works on most devices; other options help with specific mods or GPUs."),
"controls":("Control layout","A custom on-screen control layout for this profile only."),
"shared":("Shared game folder","Profiles can share one game folder or keep their own saves, options and packs."),
"files":("Open folder","Opens the profile\u2019s game folder in a file manager for manual changes."),
"shortcut":("Home screen shortcut","Adds a shortcut on your device\u2019s home screen that starts this profile directly."),
"save":("Save","Changes apply only after Save. Leaving without saving keeps the old values."),
"delete":("Delete","Removes the profile and, if it is not shared, its folder. A confirmation is shown first; this tutorial never deletes anything."),
})
mod("app","Appearance","Vanilla or CS Custom look for Minecraft, launcher theme, colors and animations.",
"Shows the per-profile \u201cMinecraft Appearance\u201d setting (Vanilla Experience vs CS Custom Experience) in the editor, then the launcher theme in Settings → Appearance. You choose; nothing is applied automatically.",{
"intro":("Minecraft Appearance","This row decides how Minecraft itself looks for this profile. It is separate from the launcher theme."),
"vanilla":("Vanilla vs CS Custom","Vanilla Experience leaves the game untouched. CS Custom Experience installs a CS Launcher Plus resource pack (menus, loading screen, panorama) and needs Minecraft 1.21 or newer."),
"open":("Open the appearance picker","Tap the row to see both options.","Tap \u201cMinecraft Appearance\u201d."),
"result":("Your choice","The row shows the selected experience. With CS Custom you can also pick which parts (menus, loading screen, panorama) are included."),
"save":("Save to apply","The appearance is written to the profile when you Save. The pack is placed in the profile\u2019s resource packs on the next launch."),
"back":("Switching back","Open the row again any time and pick Vanilla Experience to remove the pack.","Tap \u201cMinecraft Appearance\u201d again (optional)."),
"theme":("Launcher theme","The launcher itself has three themes: Dark, Light and Follow system. This is the real setting the setup wizard used."),
"theme_pick":("Try the theme","Pick a theme. The launcher applies it instantly; this tutorial continues on the new look.","Choose a theme (the current one is fine too \u2013 or skip)."),
"color":("Accent colors","Color source: the launcher\u2019s own palette, a custom color, or dynamic colors from your wallpaper on Android 12+."),
"anim":("Animations","Pick which launcher animations run, or reduce them on slower devices."),
})
mod("ver","Versions and installation","Pick a Minecraft version or loader, understand downloads and what to do when they fail.",
"Explains the version picker, loaders, what PLAY downloads and where to look if an install fails. It opens the picker for you to look at; it never installs anything.",{
"intro":("Version chip","The chip on the banner shows the version the selected profile will run."),
"row":("Version row","In the editor the version row opens the picker. The chosen version is downloaded on the next PLAY."),
"pick":("Open the version picker","Tap the row to see the list.","Tap \u201cMinecraft version\u201d."),
"loaders":("Loaders","Fabric, Forge, NeoForge, Quilt and OptiFine versions appear once installed through \u201cNew profile\u201d or the jar installer (Files dock → Install .jar)."),
"install":("Downloads happen on PLAY","PLAY fetches the version files, libraries, assets and the right Java runtime. The button shows \u201cDownloading\u201d only while real downloads run."),
"progress":("Progress","Progress is shown here and in a notification. Cancel on the notification really cancels."),
"failed":("If it fails","Open Logs from the dock: the last error is at the bottom. Common causes are network, low storage, or a loader that does not match the version."),
})
mod("play","Launching the game","What PLAY checks, the download and launch stages, and what happens after.",
"Walks through the checklist PLAY uses (profile, version, account), its stages and where logs go. The final interactive step is a real launch that you trigger \u2013 skip it if you are not ready.",{
"check":("Before PLAY","PLAY needs a selected profile with a version and a signed-in account. Missing pieces are reported with a message instead of starting."),
"account":("Add an account first","There is no account yet. Use the account button to add a Microsoft or local account, then come back."),
"stages":("Stages","1. Downloading (only when files are missing) 2. Launching \u2013 the rocket animation 3. The game window opens. The launcher shows a small indicator; there is no full-screen overlay."),
"after":("After the game","When Minecraft closes you return here. Logs keeps the full game output for troubleshooting."),
"go":("Launch now","Tap PLAY to launch the selected profile. This is a real launch and may start downloads.","Tap PLAY (or skip this step)."),
})
mod("br","Browse resources","Find and install mods, modpacks, resource packs, shaders and worlds.",
"Opens the content browser and explains the type tabs, source, search, filters, sorting, project pages and local import. Installing is always your explicit choice.",{
"open":("Open the browser","Tap the Browse resources card.","Tap \u201cBrowse resources\u201d."),
"tabs":("Content types","Mods, modpacks, resource packs, shaders and worlds each have a tab. Mods require a loader profile; OptiFine profiles cannot install mods."),
"context":("Target profile","Installs go into the selected profile. The pill shows which one and its version so results match."),
"source":("Source","Modrinth or CurseForge. Switch any time; the search runs again."),
"search":("Search","Type a name and press search. Results match the profile\u2019s version and loader when possible."),
"filters":("Filters","Narrow by category, version or loader."),
"sort":("Sorting","Relevance, downloads, newest or recently updated."),
"grid":("Results","Each card opens the project page with description, versions and the download icon. Nothing is installed by just opening it."),
"install":("Installing","On a project page pick a file version and tap the download icon. Modpacks create a new profile; other types go into the selected profile."),
"import":("Import local files","Have a .jar, .zip or .mrpack already? Import installs it into the selected profile."),
"where":("Where files go","Installed content lands in the profile\u2019s folder (mods, resourcepacks, shaderpacks, saves). Manage content lists it."),
})
mod("mg","Manage content","See, enable, disable and delete installed content; open worlds.",
"Explains the Manage content card, its type chooser, the Files dock entry and the worlds manager. Deleting is never done by the tutorial.",{
"intro":("Manage content","Shows what is installed in the selected profile, grouped by type."),
"open":("Open the chooser","Tap the Manage content card.","Tap \u201cManage content\u201d."),
"types":("Choose a type","Mods, resource packs, shaders or worlds. The list that opens lets you toggle or delete items with confirmation."),
"dock":("Files in the dock","The Files entry of the dock opens the profile folder directly and has the .jar installer."),
"worlds":("Worlds","Worlds get their own manager with sizes and last played dates; backups are your responsibility before deleting."),
})
mod("wd","Worlds","Your singleplayer worlds for the selected profile.",
"A short explanation of the worlds manager: what is listed, what the actions do and how to stay safe.",{
"intro":("Worlds manager","Lists the saves folder of the selected profile. Worlds from the browser or imports appear here too."),
"list":("Entries","Each entry shows name, version and size. Actions are delete and open folder."),
"safety":("Stay safe","Deleting is permanent. Copy the world folder (open folder) before experimenting with mods or versions."),
})
mod("set","Settings tour","The settings hub and what each category changes.",
"Opens Settings and visits each category: Video, Appearance, Controls, Java, Miscellaneous and Experimental. Explanation only; nothing is changed.",{
"open":("Open Settings","Tap the settings button.","Tap the gear button."),
"rail":("Categories","The left rail switches categories; the page opens on the right. Pages remember their scroll position."),
"video":("Video \u2013 renderer","Global renderer default for profiles that use \u201cDefault\u201d. Profiles can override it."),
"res":("Video \u2013 resolution","Resolution scale: lower values render fewer pixels and raise FPS at the cost of sharpness."),
"app":("Appearance","Launcher theme, accent color source and animations."),
"ctl":("Controls","Button size, gestures, mouse speed, gyro and gamepad settings for the on-screen controls."),
"java":("Java","Memory allocation, Java runtimes and global JVM arguments."),
"misc":("Miscellaneous","Download source, local skins, Discord Rich Presence, update checks and channel, and the Tutorial Center."),
"lang":("Language","Launcher language. English, Hinglish, Hindi and other major languages are available."),
"exp":("Experimental","Options under test. They can break launches; the defaults are safe."),
"tut":("Tutorials","You are here: every tutorial, progress, tips and the setup wizard."),
})
mod("perf","Performance and troubleshooting","Memory, renderer, resolution and where to read what went wrong.",
"The settings that affect FPS and stability, plus how to read Logs. Suggestions only; you decide what to change.",{
"mem":("Memory","Allocation sets how much RAM Java gets. Too little crashes modded games; too much starves Android. Around half of device RAM is a safe start."),
"warn":("Memory warning","Shows a warning before launching when the allocation is risky for this device."),
"renderer":("Renderer","If the game shows black screens or artifacts, try another renderer here (or per profile)."),
"res":("Resolution scale","The biggest FPS lever on most phones. 70\u201385% is barely visible but noticeably faster."),
"sus":("Sustained performance","Asks Android for a steady clock instead of short bursts; helps long sessions on throttling devices."),
"vsync":("VSync","Caps to the display rate for smoother frame pacing at the cost of peak FPS."),
"profile":("Per-profile memory","A profile can override memory and JVM arguments \u2013 useful for one heavy modpack."),
"logs":("Read the log","The end of the log has the last error. Share it from the Logs screen when asking for help."),
"retry":("Try again","After a change, PLAY again. Downloads are not repeated."),
})
mod("ctl","Controls","On-screen controls, gestures, mouse, gyro, gamepad and custom layouts.",
"Explains the control settings and the layout editor. Open the editor yourself from the dock when you want to customize.",{
"touch":("Button size","Scales all on-screen buttons. Larger is easier to hit; smaller shows more of the game."),
"gestures":("Gestures","Long-press to break, tap to place and similar touch gestures can be disabled when they conflict with mods."),
"mouse":("Mouse speed","Sensitivity of camera movement from touch drags (and physical mice)."),
"gyro":("Gyroscope","Aim by tilting the device. Sensitivity and inversion are configurable."),
"gamepad":("Gamepad","Deadzone and mapping for controllers. The Controls dock entry has the gamepad remapper."),
"layout":("Per-profile layout","A profile can use its own control layout file."),
"editor":("Layout editor","The Controls dock entry opens the editor: move, resize, add and remove buttons, then save as a layout."),
"ingame":("In game","The same layout is used in game; the in-game menu lets you switch layouts without leaving."),
})
mod("log","Launcher logs","Read, refresh and share the launcher log.",
"Opens the Logs screen and explains the text, refresh, share and where crash reports go.",{
"open":("Open Logs","Tap the Logs entry in the dock.","Tap \u201cLogs\u201d in the dock."),
"text":("The log","Launcher and last game output. Scroll to the bottom for the newest lines."),
"reload":("Refresh","Reloads the file; useful after a failed launch."),
"share":("Share","Shares the log file with another app \u2013 attach it when reporting a problem."),
"crash":("Crashes","A crash shows a dedicated screen with the report; the latest crash is kept in latestcrash.txt in the game folder."),
})
mod("mp","Virtual LAN multiplayer","Play LAN worlds with friends over the internet (Beta).",
"Opens the Virtual LAN screen and explains room creation, joining by code, recent rooms, the in-game part and how to send logs. Nothing connects until you choose to.",{
"open":("Open Virtual LAN","Tap the multiplayer entry in the dock.","Tap \u201cVirtual LAN\u201d in the dock."),
"beta":("Beta","Virtual LAN is in Beta. It creates a private network between devices so \u201cOpen to LAN\u201d worlds become visible to friends."),
"create":("Create a room","Starts the network and gives you a code to share. Then open your world to LAN in game."),
"join":("Join with a code","Enter a friend\u2019s code to join their network. Their LAN world appears in Multiplayer in game."),
"recent":("Recent rooms","Rooms you used before, for one-tap reconnecting."),
"ingame":("In game","Host: Esc → Open to LAN. Guests: Multiplayer → the world appears under LAN, or Direct Connect to the host address shown here."),
"logs":("Send logs","If something does not connect, Send logs bundles the diagnostics for support."),
})
mod("dc","Discord Rich Presence","Show what you play on your Discord profile.",
"Explains Rich Presence in Miscellaneous settings: it works without linking an account, what it shows and how to turn it off.",{
"intro":("Rich Presence","With the Discord app installed and signed in, your profile shows the launcher or the game you are playing, including the profile name and icon. No account linking is needed."),
"preview":("Preview","Shows how the card looks right now \u2013 home screen when idle, the profile and server while playing."),
"privacy":("Turn it off","This switch disables the presence completely; nothing is sent while it is off."),
})
mod("tools","Other tools","Help button, updates, download source, skins and shortcuts.",
"Smaller features worth knowing: the ? help button, update checks, download source, local skins, profile shortcuts and this Tutorial Center.",{
"helpbtn":("Help button","The ? in the top bar explains the screen you are on and offers its tutorial. Tap it on any screen."),
"updates":("Updates","Checks for a new launcher version and shows only the version string. Choose the update channel below it."),
"source":("Download source","Which mirror serves game files. Change it if downloads are slow in your region."),
"skins":("Local skins","Allows the skin editor to load skin files from the device."),
"shortcut":("Profile shortcuts","Each profile can add a home-screen shortcut that launches it directly."),
"help":("Tutorial Center","Replay any tutorial, resume a stopped one, restart the setup wizard or reset progress."),
})

PLURALS = {"tut_center_steps": {"one":"%d step","other":"%d steps"}}

def esc(v):
    v = v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    return v

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app_cs", "src", "main")
keys=set()
for dp,_,fs in os.walk(root):
    for f in fs:
        if f.endswith((".java",".xml")) and "strings_tutorial" not in f:
            t=open(os.path.join(dp,f),encoding="utf-8",errors="ignore").read()
            keys.update(re.findall(r"R\.string\.(tut_[a-z0-9_]+)",t))
            keys.update(re.findall(r"@string/(tut_[a-z0-9_]+)",t))
missing = sorted(k for k in keys if k not in S)
unused = sorted(k for k in S if k not in keys)
if missing:
    print("MISSING:", missing); sys.exit(1)
if unused:
    print("unused (kept):", unused)
out=['<?xml version="1.0" encoding="utf-8"?>','<!-- Generated by tools/gen_tutorial_strings.py: onboarding wizard, tutorial engine, Tutorial Center and all tutorial modules. -->','<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">']
for k in S:
    out.append('    <string name="%s">%s</string>' % (k, esc(S[k])))
for k,q in PLURALS.items():
    out.append('    <plurals name="%s">' % k)
    for qk,qv in q.items(): out.append('        <item quantity="%s">%s</item>' % (qk, esc(qv)))
    out.append('    </plurals>')
out.append('</resources>')
open(os.path.join(root,"res","values","strings_tutorial.xml"),"w",encoding="utf-8").write("\n".join(out)+"\n")
print("ok", len(S), "strings")
