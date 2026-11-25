# Changelog

## 0.13.2

**Changed**
- Colour from artwork now tints the whole screen's glow, sidebar included, and the sidebar's
  selection bar takes the title's colour too, so the sidebar matches the page.
- The header artwork fades out itself (instead of being covered with page colour), so it blends
  evenly into the glow behind it.


## 0.13.1

**Changed**
- Ranked landscape cards: a small number with a soft drop shadow, and the title logo kept tiny in the
  bottom right.
- Trailers: choose when they start (2, 4, 8 or 15 seconds) and whether they play with sound
  (Settings, Appearance, Trailers and motion).
- Settings switches are clear on every theme: On is a coloured track, Off an empty outline, with the
  words On and Off beside them.

**Fixed**
- Opening Settings from the sidebar now always moves into Settings, so the sidebar closes.


## 0.13.0 (faster, tidier settings)

**Faster**
- The app is now optimised when it's built (R8) and ships its startup profiles, so scrolling and
  screens are noticeably quicker, especially on slower TVs. The download is smaller too.
- Cheaper drawing for Home rows, no blur behind the sidebar, trailers start after 4 seconds, and a
  "Moving artwork" switch (Settings, Appearance) for TVs that still feel slow.

**Settings**
- One "Phone setup" page holds the phone code; everything else sends you there, on the right tab.
- Long pages fold into sections you open and close (Accounts, Appearance, Playback).
- Theme tiles fit the page, so Custom is reachable.

**Phone page**
- Redesigned: Playlist, Add-on, Accounts (Stremio, Simkl, Debrid) and Help, with clearer forms.

**Top 10**
- "1" stays in view. Ranked rows can be landscape too, with the number set into the left of the
  picture. Hold OK on a row: Cards (Posters or Landscape) and Ranked (Off or Top 10).


## 0.12.1

**New**
- Stremio sync (Settings, Accounts and sync): sign in on your phone (Zapp keeps only Stremio's sign-in
  key, never your password) and your Stremio library and Continue watching come into your first
  profile, when Zapp opens and every 30 minutes, or with Sync now.

**Fixed**
- Home: text from the row above no longer shows under the synopsis, and the edge of the previous
  card no longer peeks out to the left of the focused one.
- Home header titles without a logo sit on one line, sized to fit, instead of wrapping oddly.
- Details page: the Details block now comes before More like this when you scroll down.
- Home header: no cast line, so the synopsis gets three lines.
- "Add profile" is now a full row at the top of Settings, Profiles, so it's easy to reach.


## 0.12.0 (polish)

**New**
- Colour from artwork: the background glow takes its colour from the title you're on (Settings,
  Appearance).
- Artwork slowly drifts (a gentle zoom and pan) on Home, Movies, Series and details.
- Trailers: after a moment on a title, its trailer plays silently behind the header (Home, Movies,
  Series) and on the details page. It only appears once it's actually playing. Switch off in
  Settings, Appearance.
- Top 10 rows: big outlined numbers beside the first ten posters. Your first "Top" or "Trending"
  catalogue becomes one automatically; hold OK on any row to choose Posters, Landscape or Top 10.
- Genre chips on Movies and Series: choose one and a row of that genre appears below.
- Loading shimmer instead of blank boxes while rows load.
- Frosted glass: the page blurs behind the open sidebar and behind dialogs (Android 12 and newer).
- Clock (and your profile, with more than one) in the top right of Home, Movies and Series.
- Buttons and cards dip slightly when you press OK.


## 0.11.1

**Fixed**
- Right from the sidebar always returns to where you were on the page.

**New**
- Custom theme (Settings, Appearance): pick your own two colours.
- Smoother motion: pages fade and settle in when you switch, details glide in and out, artwork fades
  in as it loads, and the focus outline fades rather than snapping.

**Changed**
- Monochrome uses true black.


## 0.11.0 (profiles)

**New**
- Profiles (Settings, Profiles): each has its own Continue watching, library and Home rows, a name
  and a colour. Everything watched so far belongs to your first profile, which is the one Simkl syncs.
- "Who's watching?" when Zapp opens (switch it off in Settings, Profiles), and a profile button at the
  bottom of the sidebar to switch at any time.
- Optional four-digit PIN per profile, asked when switching to it (remote number keys or the on-screen
  pad).
- Kids profiles: only titles marked animation, family or kids (never horror, thriller, crime or war),
  no Live TV or guide, no channels in search, and Settings asks for the main profile's PIN if it has one.


## 0.10.3

**Fixed**
- Left and Up could jump to the sidebar (or to the hidden page underneath) instead of moving to the
  previous card or row. The card before the one you're on, and the row above, are now always laid
  out (just hidden), so the move always lands where you expect. Details, episodes and sources keep
  focus to themselves, and Up on the top row of Home stays put.


## 0.10.2

**Changed**
- Card sizes and row spacing are back to how they were in 0.10.0.
- Home, Movies and Series header: a compact block low in the header, just above the rows, set in from
  the sidebar and a third of the screen wide. Every part has a fixed size, so nothing moves as you go
  between titles. The synopsis no longer runs across the whole screen.
- Series details: one button per season. Choosing a season opens its episodes over the page, with
  large episode cards and the focused episode's story below; Back returns to the seasons.

**Fixed**
- Continue watching could stay empty when a title had more than one progress record; it now picks
  the latest unfinished one. Settings, Home screen shows how many titles are in progress.


## 0.10.1 (refinements)

**Fixed**
- Up (and other moves) sometimes did nothing, including on the details page: focusing some buttons
  and menu items rebuilt them and dropped focus. Focus now stays put.

**Changed**
- Home, Movies and Series: the header shows the title's logo again, and is smaller. Its parts stay in
  fixed places, so moving between titles only changes what's shown, never where it sits. Smaller
  cards and row titles.
- Details page: smaller, finer type with more of the picture showing; poster lettering for the title.
- Focus matches your theme: buttons light up in a tint of the theme, and cards get a theme-coloured
  outline. Settings, Appearance: outline thickness (thin, medium, thick) and a pulsing glow (on/off).


## 0.10.0 (minimal look, themes, new details page)

**New**
- Themes (Settings, Appearance): Aurora (blue into pink, the new default), Ocean, Sunset, Emerald and
  Monochrome. The whole app recolours straight away.
- Details page redesigned: the artwork at full strength, with the title in large poster lettering and
  everything pinned along the bottom. One line of facts, a sleek play button that shows how far you
  got, and quieter buttons beside it. Series show minimal season tabs and episode cards below.
- Scroll down on a details page for "More like this" (from your catalogues, matching genres) and the
  full details: release, runtime, genres, rating, director, cast and description.
- New sidebar: slim icons floating over the page with a small gradient bar for where you are; it opens
  with labels over a soft fade.

**Changed**
- A more minimal look throughout: thinner outline buttons, quieter text, and a soft blue-to-pink glow
  instead of plain blue.
- Home and Movies/Series headers use the same poster lettering and one-line facts.
- Continue watching now always appears at the top of Home, even when it loads after the catalogues.
- Bundles the Anton font (SIL Open Font License, see licenses/).


## 0.9.1 (polish)

**Changed**
- Home: the artwork now fades smoothly into the page with no visible edge, and cards no longer grow
  and shrink as you move along a row.
- Movies and Series use the same design as Home: big header with artwork and details, smaller cards,
  smooth scrolling, and hold OK on a card to change that row's size or shape.
- New player controls: title, clock and end time at the top; round play/pause and 10-second skip
  buttons; Audio and Subtitles show what's playing now (e.g. "English, 5.1").
- English is now the preferred audio and subtitle language (unless you'd picked one), and English
  tracks are listed first.
- Starting a film or episode shows an animated Zapp logo over the title's artwork, from choosing play
  (and while your debrid service fetches it) until the picture appears.


## 0.9.0 (new Home)

**New**
- Home is now just your films and series: live TV rows are gone from it (Live TV and the guide are in the
  sidebar as before).
- A big header shows whatever you're resting on: artwork across the top of the screen, logo or title,
  year, rating, runtime, genres, description and cast. It changes after a short pause so fast scrolling
  stays smooth.
- Continue watching is always the first row, with landscape cards and how much is left.
- Smaller cards, and smoother scrolling: the row you're on glides to the top and the card you're on stays
  in the same place as you move along.
- Hold OK on any card to change its row: Small, Medium or Large cards, posters or landscape, move the row
  up or down, or hide it. In Continue watching you can also remove that title.
- Sources: "Ready to play only" (Settings, Playback) hides sources that would have to download first.
  Zapp asks Premiumize and TorBox which torrents they already have, and reads the ready markers add-ons
  put in source names. Ready sources get a "Ready" tag.
- "Best quality first" and "Highest quality" (Any, up to 1080p, up to 720p) for choosing sources.
- Autoplay is on: films and episodes start the best source straight away. "Let me choose" still shows
  the list, and you can turn it off in Settings.


## 0.8.1 (Nuvio import)

**New**
- Import add-ons from Nuvio (Settings, Accounts and sync). Zapp reads the add-on list from Nuvio TV's own
  "Manage from phone" page while it's open (on this TV, or another device by address) and installs them
  with their settings. Watch history, progress and library come across through Simkl.

## 0.8.0 (debrid services)

**New**
- Debrid services (Settings, Accounts and sync): Real-Debrid and AllDebrid sign in with a code or QR on your
  phone; Premiumize and TorBox take your API key on the phone page (new Debrid tab). Keys and tokens are
  stored encrypted.
- Torrent sources from your add-ons now play: Zapp sends the torrent to your debrid service, picks the
  right file (by name, position, or the biggest video) and plays the link it returns. With several
  services connected they're tried in order. The source list shows "Through Real-Debrid" (etc.).
- Clear messages when a service doesn't have something ready yet, needs premium, or has signed Zapp out.

**Note**
- Many add-ons can use your debrid account themselves when you configure them with it; their sources
  then play directly without this step.

## 0.7.0 (Simkl sync)

**New**
- Simkl sync (Settings, Accounts and sync). Sign in by scanning a QR code and approving on your phone;
  Zapp never sees your password. Uses Simkl's current sign-in (AUTH V2).
- Brings in your Simkl watched history (films and episodes), paused progress (Continue watching)
  and Plan to Watch / Watching lists (Library). Checks for changes when Zapp starts and every 30 minutes,
  and only downloads what changed.
- Sends what you watch in Zapp back to Simkl as it happens (start, pause, stop; 80 % or more counts as
  watched). Adding to your Library also adds to your Simkl Plan to Watch list.
- With Nuvio set to use Simkl for watch progress and library, the two apps stay in step.
- The Simkl app ID can be pasted on the phone page (new Simkl tab), or built in from a SIMKL_CLIENT_ID secret.
- Progress synced from Simkl resumes at the right point even before Zapp knows the film's length.

**Changed**
- Database version 7 (progress can be stored as a percentage). Upgrades keep all data.

**Limits**
- Removing something from your Library isn't sent to Simkl yet.
- Titles need an IMDb id (as Cinemeta and most add-ons use) to sync.

## 0.6.0 (Stage 4: Stremio add-ons)

**New**
- Stremio add-on support. Install add-ons from your phone (QR page, Add-on tab) or by typing a link;
  turn them on or off, reorder and remove them in Settings, Add-ons. One-tap suggestion: Cinemeta.
- Movies and Series in the sidebar: a hero with the focused title's artwork and a row per catalogue,
  loading more as you scroll.
- Detail pages with artwork, logo, year, rating, runtime, genres, description, cast and director;
  Play or Resume, Start over, Choose source, Add to library; seasons and episodes for series.
- Source picker: asks every add-on at once and shows quality, size, provider and tags (HDR, Dolby Vision,
  HEVC, Atmos, Remux). Optional autoplay of the first suitable source (Settings, Playback).
- Film and episode player: Left/Right seek (hold to go faster), controls, audio, subtitles, stream info,
  switch source mid-film keeping your place, progress saved as you watch, next-episode countdown.
- Library screen; Continue watching for films and series on Home (hold OK to remove a card).
- Settings, Home screen: show, hide and reorder Home rows, including add-on catalogues.
- Search includes films and series from add-ons.
- Returning from a title or the player puts you back on the card you opened.

**Changed**
- Database version 6 (adds add-ons, watch progress and library). Upgrades keep all data.
- Add-on install links are stored encrypted, as they can contain your add-on settings.

**Not yet**
- Torrent-only sources need a debrid service (next), and Nuvio plugins (later).
- Simkl sync with Nuvio (next).

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
