# Flow Launcher

A small ColorOS 17-inspired home launcher for Android 12+ (built for a Galaxy S24 Ultra).

## Build
1. Install Android Studio and open this folder (`FlowLauncher`).
2. Let Gradle sync (it downloads Gradle 8.9 and the Android plugin).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and install it
   (allow "Install unknown apps" for your file manager or browser when asked).

## Set as your launcher
Settings > Apps > Choose default apps > Home app > Flow Launcher.
(Switch back the same way to return to One UI Home.)

## Features
- Large thin clock, date and a frosted search pill on the home screen
- Glass dock (up to 5 apps). Defaults to your phone, messages, browser and camera apps
- Swipe up: app drawer with live background blur and squircle icons
- Swipe down on home: opens the notification shade
- Swipe down in the drawer (at the top) or tap the handle: closes it
- Long-press an app: add/remove from dock, app info, uninstall
- Long-press empty home area: wallpaper picker
- Back button closes the drawer

## Not included
Widgets, folders, multiple home pages, icon packs, notification badges.
