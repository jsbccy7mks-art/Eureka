# Changelog

## 0.3.0 (Stage 1: foundation and navigation)

**New**
- Collapsible sidebar (Home, Live TV, TV guide, Settings). It opens with labels when the remote moves onto it.
  Back from any screen jumps to the sidebar; Back twice on the sidebar exits.
- Home screen with a hero that previews the channel you rest on, plus rows for
  Continue watching, Favourites and On now. Returns to the card you were on after watching.
- Recently watched channels (counted after 10 seconds of viewing), shown on Home and as a category in Live TV.
- Settings reorganised into Sources, Live TV and guide, and Updates and about.
- Guide auto-update interval is now adjustable: off, 6, 12 or 24 hours (checked while the app is open).
- Every successful build is published as a GitHub release, so the newest APK is always at
  `github.com/jsbccy7mks-art/Eureka/releases/latest/download/zapp-tv.apk`.

- Built-in updater (Settings, Updates and about): checks GitHub for a newer build, downloads it and opens
  the installer. It also checks quietly every six hours and marks Settings with a dot when an update is ready.
- Builds are now release builds (faster on TV hardware than the old debug builds) and the build number
  becomes the app's version code.

**Security**
- Playlist logins and links are encrypted with a key in the Android Keystore.
- Xtream channel links no longer store your username and password; the full link is built only when playing.
- Existing data from 0.2 is upgraded automatically on first start (nothing to re-add).

**Changed**
- The top tab bar is replaced by the sidebar. Playlists moved to Settings, Sources.
- Database version 3 (adds recently watched). Upgrades from 0.1 and 0.2 keep playlists and favourites.

**Known limitations**
- M3U channel links are stored as the playlist provides them; if your provider puts a login inside
  each M3U link, those links still contain it.
- The player library may write a stream link into the device's system log when a stream fails to play.

## 0.2.0
- New look, phone setup by QR code, TV guide grid, favourites, preview window, in-player channel list.

## 0.1.0
- Xtream and M3U playlists, channel browser and fullscreen player.
