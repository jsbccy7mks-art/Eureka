# Zapp TV

IPTV player for Android TV and Fire TV.

**0.2.0:** new look, phone setup by QR code, TV guide, favourites, preview window and an in-player channel list.

## Get the APK (GitHub Actions)

1. Upload everything in this folder to your GitHub repository (keep the `.github` folder). If you're updating the
   Phase 1 repo, replace its files with these.
2. Open **Actions**, pick **Build APK**, then **Run workflow** (it also runs on every push).
3. Download **zapp-tv-debug-apk** from the finished run and unzip it to get `app-debug.apk`.

Installing over Phase 1 keeps your playlists.

## Adding playlists

- **From your phone:** open the Playlists tab (or the welcome screen) and scan the QR code. A page opens on your
  phone where you type the Xtream login or M3U link. Phone and TV must be on the same network.
- **With the remote:** choose Xtream login or M3U link. Press OK on a field to type; up and down move between fields.

## Remote controls

| Where | Key | Action |
|---|---|---|
| Anywhere | Up into the top bar | Switch tabs (the tab opens when you rest on it) |
| Anywhere | Back | Jump to the top bar; Back again exits |
| Live TV | Rest on a channel | Preview it in the corner |
| Live TV | OK | Watch fullscreen |
| Live TV | Hold OK | Add or remove favourite |
| Guide | Left / Right | Move through programmes; the grid scrolls through time |
| Guide | OK | Watch that channel |
| Player | Up / Down, Channel +/- | Change channel |
| Player | OK or Left | Channel list |
| Player | Right | Programme info |
| Player | Hold OK or Menu | Favourite |
| Player | Back | Return to where you were |

## TV guide

- Xtream playlists use the provider's own guide automatically.
- M3U playlists use the guide link inside the playlist, or one you enter when adding it.
- The guide updates in the background when it's older than 12 hours (Settings can turn this off).
- Channels without a guide id are matched to the guide by name.

## Project layout

```
app/src/main/
  assets/setup.html        phone setup page served by the TV
  java/com/zapp/tv/data/   database, Xtream and M3U import, guide parser, phone setup server
  java/com/zapp/tv/ui/     theme, components, screens, player, view model
```
