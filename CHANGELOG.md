# Changelog

## 0.5.1 (crash fix from crash report)

**Fixed**
- App closing when moving between lists with the remote ("Release should only be called once" in
  Compose's focus restorer, a known bug of the Compose version used). Zapp no longer uses that feature;
  it remembers the focused item itself, so returning to a list still lands where you were.
- Moving into the sidebar now always lands on the screen you're on.

## 0.5.0 (Stage 3: player)

**New**
- Player controls: press OK while watching for a controls bar with Play/Pause, Channels, Audio,
  Subtitles, Stream info and Favourite. It hides after a few seconds without key presses.
- Audio track choice (language, codec, stereo or 5.1) and subtitle choice, including Off.
- Subtitles are drawn by the app: size, style (white with shadow, white on a dark box, yellow)
  and position are set in Settings, Subtitles, with a live preview. Picture-based DVB subtitles are shown too.
- Stream info: resolution, frame rate, video and audio codec, bitrate, buffer and dropped frames.
- Seek bar with rewind and fast-forward (Left/Right, hold to go faster) for streams that allow seeking,
  such as catch-up or HLS streams with a rewind window.
- If a channel fails, the controls open with Try again and Next channel.
- Settings, Playback: preferred audio language and an optional bigger buffer for unsteady connections.
- DASH streams are supported in addition to HLS and MPEG-TS.

**Changed**
- In the player, OK now opens the controls; the channel list is on Left (and in the controls).

**Not yet (needs movies and series from Stage 4)**
- Resume playback, Continue watching for films and episodes, and next-episode autoplay.

## 0.4.1 (crash fix)

**Fixed**
- Likely cause of the app closing while the TV guide updates and you scroll categories or channels:
  guide downloads now go into a separate staging table and are swapped in at the end, so open
  channel lists reload once instead of every few seconds. Channel rows also stay the same on-screen
  element while they reload, so the item under the remote is never removed.
- Home rows use unique keys even when an M3U playlist lists the same stream twice.

**New**
- Crash reports: if Zapp closes unexpectedly, the error is saved (with logins removed). Settings,
  Updates and about shows it with a QR code that opens the full report on your phone to copy.

**Changed**
- Database version 5 (adds the staging table and clears leftovers of the old guide method).

## 0.4.0 (Stage 2: Live TV and guide)

**New**
- Search in the sidebar: finds channels across all playlists (current playlist first) and programmes in the
  TV guide. OK on a programme that's on now plays its channel; upcoming ones tell you when they start.
- Guide filter: All channels or On now only (remembered).
- Update guide button and "last updated" time on the guide screen.
- Clear guide messages: no guide link, not downloaded yet, downloading, or nothing matching.
- Programme artwork from the guide (XMLTV `<icon>`) in the guide info panel, Live TV preview,
  Home hero and the player's info bar.
- Settings, Sources: each playlist has a Guide link button to add or change its XMLTV link.

**Fixed**
- Guide channel ids are now matched without regard to upper/lower case, so more channels get programmes.

**Changed**
- Database version 4 (adds programme artwork). Upgrades keep all data.

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
