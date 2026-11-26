# Changelog

## 0.46.0

**New**
- A new Zapp logo: a soft play button with a lightning spark through it, and new "zapp" lettering.
  It's on the TV's apps row (a new, sharper banner and icon), the opening, the sidebar, loading
  screens, What's new, About and the phone setup page.
- A new opening: the spark spins into place, the bolt strikes through it, the name slides out and
  a gleam passes over the logo.

**Improved**
- The opening and the loading spark are smooth: their shapes are made once instead of every frame,
  and Home starts loading just after the logo has settled, so they no longer stutter.

## 0.45.0

**New**
- Glossy cards: a soft, still gloss over the top of every poster and card, like light on glass,
  a touch brighter on the one you're on. Settings, Appearance, Card style (with Corners and Names
  under posters, which moved there too).

**Improved**
- Rows further down Home, Films and Series are got ready in the background (their cards and the
  first pictures), so they're already there when you scroll to them, with no loading cards.

## 0.44.0

**Improved**
- Smoother scrolling: while you're moving around, the slow zoom on artwork and the glow's pulse
  hold still (they carry on when you rest); holding a direction glides quicker and evenly, and
  the header waits until you let go; cards appear without a fade.
- The first card in a row no longer has its focus glow cut off on the left.
- Settings, Updates and about: "Show frame rate" puts a small counter at the top of the screen
  (frames per second, and slow frames), to see how smoothly things run.

**Fixed**
- No trailer plays behind What's new (or Rate films).
- Collection pages: the posters no longer jump in size as you move between films.

## 0.43.1

**New**
- Night mode (replaces Volume boost in the player's options): loud moments are held down and
  quiet ones brought up, so speech stays clear at low volume. It works on the box's own sound,
  not when Dolby or DTS is passed straight to an amplifier.

**Improved**
- Continue watching: a thinner, sleeker progress line and smaller "time left" text.
- Player: the title or logo sits closer to the seek bar.

## 0.43.0

**Improved**
- Frame rate matching, rebuilt: Zapp now finds the film's real frame rate before it plays (from
  the start of the file, telling 23.976 from 24 by the timing of its frames), usually while you're
  still on the details page and the source is got ready. When you press Play it switches the TV,
  waits for it to settle ("Matching your TV to the film…"), and only then starts: no stutter at
  the start and no judder from the wrong refresh rate. If the rate can only be found once playing,
  the film is held while the TV switches.
- Match frame rate has three choices: Off, At the start, or At the start and back when you stop.
  If your box's own "Match content frame rate" setting would block it, Playback settings says where
  to change it.
- A small note shows what the TV switched to, and Stream info shows the film's frame rate (and
  whether it was measured) and what the TV is showing.

## 0.42.2

**Improved**
- Collections page: the shelf you're on sits neatly at the top; the edge of the shelf above it
  no longer peeks in.

## 0.42.1

**Improved**
- Collection pages just play the theme tune, with no tune name or "Change tune" line, and the
  posters are back to full size (a little bigger, in fact).

**Fixed**
- Collections page: Left into the sidebar and Right back out work properly, and you come back to
  the collection you were on.

## 0.42.0

**New**
- Collection theme tunes now come from the official 30-second previews on Apple Music and Deezer
  (looping softly), with the full YouTube tune only as a fallback. Under the collection's name a
  small line shows what's playing (or why nothing is), and "Change tune" (Up from the posters)
  lets you hear the other matches and keep the one you like, or go back to Zapp's pick.
- Collections: hold OK on any collection to pin it to the front of Your collections, remove it
  from there, move its shelf up or down, hide the shelf, or add the shelf to Home.
- Settings, Home screen, Collections layout: every shelf, to show or hide and put in order.
- Home layout is redesigned: "On Home" in order, then "Not on Home" grouped by where rows come
  from, one slim line each. OK on a row: Move (Up/Down, OK to drop), Rename, Size, Shape,
  Top 10, Remove from Home; on a row not on Home: add it to the top or bottom. Reset Home to the
  default at the end.
- Rename rows from Home too: hold OK on a card, Row settings, Rename.

## 0.41.3

**Fixed**
- Theme tunes (and some trailers): when YouTube answers the iPhone-style request, Zapp now plays
  its HLS stream, which downloads without the extra token its separate files need. Theme tunes play
  sound only, without fetching any picture.
- Collections page: the big title and the shelf names are no longer cut off at the top.
- Test trailers also checks that the files actually download, and lists what each way of asking
  YouTube said.

## 0.41.2

**Fixed**
- The tops of Library and Collections cards are no longer cut off.
- Smoother, quicker scrolling: focused cards no longer jump up and grow (they keep a soft
  shadow), and rows and pages scroll a little faster.
- Home: the title and text only step back once the trailer is actually playing, not as soon as
  it's asked for.

## 0.41.1

**Fixed**
- Down between rows on Home, Films, Series and Collections works again (the new card lift was
  confusing the remote's direction search).
- Collection pages: the poster you're on is no longer cut off at the top.

## 0.41.0

**New**
- Seek previews: while you seek a film or episode, a picture from that moment appears above the
  playhead, with the time and the part you're in (Intro, Recap, Credits).
- Paused, press Down: the parts of it to jump to, the sound and subtitles in use, and who's in it.
- "Skip intro" is now a slim pill that slides up, with a ring that empties as the intro runs out.
- Live TV: press Right while watching for now and next on the channels around this one; Up and
  Down move, OK switches, Left closes.
- Appearance, Look: True black (for OLED), Title lettering (Cinematic, Clean modern, Bold
  condensed), Card style (Rounded, Square, Rounded with border), Names under posters, and Glow
  strength (Subtle, Normal, Vivid).

**Improved**
- Home: a few seconds into a trailer the text steps back and the picture grows; move and it all
  comes back.
- Title logos almost everywhere (Cinemeta's logos fill in where the add-on and TMDB have none).
- Cards lift slightly with a soft shadow when focused.
- Details: seasons as tiles with a still and how much you've watched; episode stills slowly pan
  when you rest on them; 4K, Dolby Vision or HDR and Atmos badges from the sources found; round
  cast photos with who they play.
- TV guide: the "now" line glows in the theme's colours, programmes on now are tinted, and sport
  is tinted green.
- Sidebar: your profile sits at the top, and Library gets a dot when there are new episodes.
- Pages change with a soft slide and fade; details and Collections show their shape while loading.

## 0.40.1

**Fixed**
- Back now closes the full-screen trailer.
- Trailers start sooner: the lookup asks YouTube several ways at once with shorter waits, the
  trailer is fetched as soon as a details page opens (so Trailer is ready when pressed), and play
  begins after half a second of video instead of a few seconds.
- Collection theme tunes are fetched while the collection's card is focused, so they start as the
  page opens.

**New**
- Settings, Updates and about: "Test trailers" checks that trailers and theme tunes can play in
  Zapp's own player, and says what YouTube answered if not.

## 0.40.0

**New**
- Trailers now play in Zapp's own player instead of a YouTube page: sharper (up to 4K with
  Trailer quality on Best), sound in step with the picture, no YouTube buttons, and they fade in
  only once the first frame is ready. Black bars inside a trailer are zoomed away. Trailers are
  looked up the moment a title is focused, so they're ready when the wait is over. If YouTube
  won't play along, the old web player takes over.
- Details pages: the trailer starts while you rest on Play and stops when you press anything;
  rest on Play again to bring it back. While it plays, Up turns its sound on or off.
- The Trailer button opens a full-screen trailer with controls: OK pauses, Left and Right go back
  and forward 10 seconds.
- Collection theme tunes play through Zapp's player too, so they're more reliable.
- Collections has its own place in the sidebar, with a large centred header for the collection
  you're on. It shows once your TMDB key is added (Settings, Home screen to switch it off).
- Live TV has tabs: Channels, TV guide and Sports (Guide and Sports have left the sidebar).
  It always opens on Channels.
- Settings, Home screen: "Rate films and series", to rate more whenever you like.

**Improved**
- Rows: focus moves along the cards on screen until it's about halfway across, then the row
  slides, so you always see what's coming. At the ends, focus goes all the way to the first or
  last card.
- Library always opens on Films; tabs are Films, Series, All, Loved.
- For you starts after 5 ratings; Rate films shows how many you've done and a clear Done.
- Home shows only the IMDb rating; details pages show every score with the sites' logos.
- Fixed: focus could freeze in Rate films after rating something.

## 0.39.3

**Fixed**
- Rate films: focus could freeze after rating something (Home underneath took it). Home now never
  takes focus while Rate films or What's new is open, and Rate films puts focus back on the card
  you were on if anything takes it.

**Improved**
- "For you" waits until you've rated three things; until then Home shows "Rate a few more" with how
  many you've done.

## 0.39.2

**Improved**
- Home, Films and Series: the picture (and trailer) at the top sits higher and whole, so less of
  it is hidden behind the rows.

## 0.39.1

**Improved**
- Details pages: Like, Love and Dislike moved to the second page, beside Play and Mark as watched
  (for series, beside Credits).
- Black is now one of the custom theme colours.

## 0.39.0

**New**
- What's new: after an update, Zapp shows what changed (once). Open it again any time from
  Settings, Updates and about.
- New settings get a small dot until you've moved over them, and so do their section and group,
  so nothing new gets missed.
- The phone page shows what's connected on each account tab (a green dot on connected ones).
- Rate what you've watched: Rate films now shows the films and series you've finished (and series
  you've seen a few episodes of), instead of popular films.

**Improved**
- Live TV controls: play, pause, back and forward sit in the middle, always in the same place;
  channels on the left, audio, subtitles, favourite and info on the right. Paused, the controls
  stay up.
- Down from the Search and Settings buttons goes back to the page (Continue watching on Home).
- Back while you're along a row goes to the start of that row first.
- Details pages: IMDb sits in the year, runtime and genre line as text; every score (IMDb, Rotten
  Tomatoes, audience, Metacritic, Letterboxd) is on the next page with small badges.
- Rating buttons are just white icons, no circles.
- Collection pages: the title and story are centred, and the theme tune now plays.

## 0.38.0

**New**
- Collections now cover every major franchise on TMDB, not just ones you've started, on shelves:
  Your collections, Finish what you started, Superheroes, Action and adventure, Sci-fi, Fantasy,
  Horror, Animation and family, Comedy, Crime and thriller.
- Studio collections (Pixar, Studio Ghibli, A24, Marvel Studios, Lucasfilm, DreamWorks Animation,
  Blumhouse), decades ('70s to 2010s) and themes ('80s Action, Modern Horror, Space Adventures,
  Westerns and more), all in the same showcase in release order. Only franchises play a theme tune.
- Any collection shelf can be added to Home (Home layout, "Collections: …").
- The catalogue is found once and refreshed weekly.

**Improved**
- Film and series titles use a more cinematic typeface (Cinzel).
- The collection showcase sizes its posters to the screen so they no longer cover the story.
- Library cards without a poster from the add-on now fall back to another poster source.

## 0.37.0

**New**
- Ratings: Dislike, Like or Love any title, from the long-press menu (Rate), the details page
  (next to the library button), or the end screen ("How was it?"). Press again to clear.
  Kept per profile, and sent to Simkl when it's connected (Love 10, Like 8, Dislike 3).
  Loved titles get a tiny heart on their cards.
- Your taste: Zapp learns the genres, actors and directors you love (and don't) from your ratings
  (with your TMDB key), and uses it for:
  - New Home rows: "For you", "Because you loved …" and "Hidden gems for you" (Home layout to move
    or hide them).
  - A "% match" on details pages.
  - End-screen suggestions skip what you disliked.
- Things you dislike move to the back of catalogue rows.
- Library: a "Loved" tab, and "Rate films": popular films to rate quickly (OK cycles Like, Love,
  Dislike, clear). Home shows a "Rate a few films" card until you've rated some.

## 0.36.0

**New**
- When a film's credits start (or the latest episode of a series), it shrinks to a small window
  bottom right, still with sound, and something to watch next fills the screen:
  - The next film in its collection first (if you haven't seen it), then titles people also
    watched, skipping anything you've watched. Left and Right move between them.
  - Its colours and backdrop, then its trailer (muted) after a moment; why it's suggested, logo,
    scores and story, with Play and Details. Its sources are fetched while you look.
  - OK on the small window (or Back) goes back to full screen. If there's a scene after the
    credits, it goes full screen just before it. When the credits end, you're back on the details page.
  - Settings, Playback: "Suggest something when a film ends" (on). Needs your TMDB key.
- Blurred colour backgrounds: details pages, collections and the end screen sit on a soft wash of
  the title's own colours.
- Episode ratings: each episode card shows its rating (TMDB) as a tiny number in the corner.

## 0.35.0

**New**
- Sports (in the sidebar, under TV guide): follow any teams, in any sport (Your teams, then search).
  See their matches live now, coming up and recent results, with badges and scores, from
  TheSportsDB. OK on a match finds it in your TV guide and plays the channel it's on (or tells you
  when and where it starts; if several channels show it, you choose).
- Home: a "Your teams" row with matches live now or in the next 24 hours (Home layout to move or
  hide it).
- Works with TheSportsDB's free key; your own key (Patreon) can be added on the phone page's
  Sports tab for fuller data. Not shown on Kids profiles.

## 0.34.0

**New**
- Smarter search:
  - "Best matches" first: the top results from all your add-ons in one row, ranked by how well
    the title matches, then what's in your library, what you're watching or have watched, and rating.
  - People (with TMDB): actors and directors matching your search; pick one to see their films
    and series.
- Details page, cast: OK on someone's photo searches for them.

## 0.33.0

**New**
- Pause and rewind live TV, up to 5 minutes (Settings, Live TV; on by default). Zapp keeps a rolling
  copy of the channel you're watching on the TV, so you can pause, go back 10 seconds (hold for 30),
  move forward again, or jump back to live. The controls show a bar of the last 5 minutes, and the
  channel shows how far behind live you are. The remote's rewind and fast-forward keys work too.
  The copy is deleted when you change channel or leave the player. Channels sent as HLS playlists,
  or that won't play this way, play directly as before (without rewind).

## 0.32.0

**New**
- Ratings at a glance (with your own free MDBList key, added on the phone page's new MDBList tab):
  IMDb, Rotten Tomatoes critics and audience, Metacritic and Letterboxd on details pages, and
  Rotten Tomatoes beside the year on Home. Green or red where the score means fresh or not.
- Franchise collections (with your TMDB key): Library, Collections, for any franchise you've saved
  or watched a film of. Each opens as a showcase:
  - The page takes on the colours of the artwork, with the films' backdrops drifting behind.
  - The films stand in release order as a cover-flow of large posters: the one you're on lifted
    forward with a reflection, the rest turned away. Ticks on the ones you've seen, "Next to watch"
    marked, and a timeline of dots underneath.
  - Its theme tune plays softly (from ThemerrDB, when it has one), fading in, and stops when you
    open a film. Settings, Appearance: Collection theme music and its volume.
  - "Show/Hide collections" in Library; a "Your collections" row can be switched on in Home layout.

## 0.31.0

**New**
- Subtitles for films and series have their own settings, separate from live TV (Settings,
  Subtitles: on/off for each). In the player, Subtitles, "Timing and look":
  - Sync: earlier or later in 0.1 s steps, kept for that title. Add-on subtitles move either way;
    subtitles inside the video can be moved later.
  - Font (Default, Sans, Serif, Condensed, Typewriter), Bold, Style (drop shadow, outline,
    outline and shadow, dark box, plain), Size and Position, with a live preview.
- Sources that learn: Zapp notes how each add-on and release group plays on this box (how fast
  it starts, failures, switching away soon after) and lists reliable ones higher within the same
  quality; very unreliable ones sink. "Reliable" and "Often fails" show in the sources list.
  Kept only on this TV; Settings, Playback, "Sources that learn" to start over.

## 0.30.0

**New**
- Faster Play: a details page starts fetching the sources for what Play would start (the film, or
  your next episode) while you read it. After a few seconds there, with autoplay on, it also gets
  the debrid link ready for the source autoplay would choose, so Play goes almost straight to the
  picture. Links are thrown away after 10 minutes and fetched fresh.
  Only cached sources are prepared (no download slot used); if none is cached, Play works as before.
  Settings, Playback: "Get ready while I browse" (on by default). Prepared titles appear in your
  debrid service's list, as they would after pressing Play.

## 0.29.0

**New**
- Home starts instantly: catalogue rows are kept on the TV and shown straight away, then quietly
  refreshed in the background for next time.
- Live TV player:
  - The quality badge (UHD, FHD, HD, SD, from what's actually playing) sits by the channel name,
    with a quiet line of what's playing (HDR, audio, frame rate) under the programme.
  - Controls are small icons like the film player: play/pause, channels, last channel,
    "Watch from the start" (when the channel has catch-up), audio, subtitles, favourite, info.
  - The Zapp loader replaces the spinner while a channel buffers.

## 0.28.0

**New**
- Title logos on the details page (and its slim header) instead of lettering, when there is one.
- Player: the logo (or title) sits bottom left above the seek bar, with the episode and a quiet
  line of what's actually playing: resolution (4K, 1080p…), HDR10 / Dolby Vision / HLG, the audio
  (Dolby Atmos 5.1, DTS…) and frame rate. The clock and "Ends at" stay top right.
- Paused view: pause and leave it, and the picture dims while the logo, episode, story and what's
  playing appear above the seek bar. Any button brings the controls back.
- Up next card with the next episode's picture always appears at the credits. "Play the next
  episode automatically" (Settings, Playback) decides whether it counts down or waits for you.

**Fixed**
- The seek bar and progress lines were hard to see in Monochrome; they're near-white there now.

## 0.27.1

**Fixed**
- Back out of a film to its details page and the remote stopped working there: the page behind
  was taking focus. Details pages now keep it, and if focus is ever lost Zapp gives it back.
- Sidebar: it opens only when you go to it (Left from the page, or Back). If focus lands on it by
  accident (something closing under the remote), it goes straight back to the page and stays shut.
  Right always closes it.
- Switching the TV's resolution (resolution matching) no longer restarts Zapp.

**Changed**
- Player options are small icons now (audio, subtitles, sources, picture, boost, info, next
  episode); the one you're on is named, with its setting, just above the row.
- The starting screen uses the title's large background picture, dimmed.

## 0.27.0

**New**
- Frame rate matching (on by default) and resolution matching (off by default): Settings, Playback,
  Display matching. The TV switches to the film's frame rate (and, if you want, its resolution)
  while it plays, and back afterwards.
- Library: All, Films and Series tabs, and sorting by Date added, A–Z, Year or Recently watched
  (both remembered). Missing posters and "tt…" names are filled in from your add-ons or TMDB.

**Changed**
- Sleeker player controls: no panels or circles, just icons and words with a soft shadow over a
  gentle fade; a theme-coloured underline shows focus; the title in poster lettering at the top.
- New seek bar: thin theme gradient that thickens when selected, with a time bubble that follows
  it and time left on the right. Controls hide after 5 seconds.

## 0.26.2

**Changed**
- Up from the top row (Continue watching) goes to the Search and Settings buttons beside the clock.
- Those buttons are just the icons now, with a soft drop shadow (no circles).

## 0.26.1

**Fixed**
- Back out of a title (or the sources list) opened the sidebar: focus now goes straight back to the
  card you were on.
- The opening animation now lasts until Home has actually loaded (about 2.5 seconds at least).

## 0.26.0

**Fixed**
- Live TV and the TV guide were blank: their background picture was taking up the whole page and
  pushing everything off screen. It now sits behind the page.
- The sidebar closes whenever focus leaves it (including from the profile button), and Right always
  leaves it, back to where you were, even if the page is still loading.
- Focus glow: one smooth, blurred halo (no more lines), and it's no longer cut off above and below.

**New**
- Search and Settings buttons beside the clock (press Up from the top of a page). In the sidebar,
  Search sits under Home and Settings stays at the bottom.
- Nuvio: "Nuvio is the source for Continue watching" (Settings, Accounts and sync, Nuvio) makes the
  row show exactly what Nuvio's does, in the same order.

**Changed**
- Progress bars use your theme's colours everywhere, including the player's seek bar.
- Continue watching and New episodes are always wide cards; New episodes shows "New · S2 E3"
  bottom right, like the time left on Continue watching.
- Zapp opens on the first Continue watching card (after the opening animation or choosing a profile).

## 0.25.0

**New**
- Hold OK on any title (Home, Movies, Series, Library, Search, More like this) for a menu: add to or
  remove from your library, mark as watched or unwatched, and Row settings, which open in place.
  On Continue watching it also offers "Remove from Continue watching", and "Mark as watched" marks
  that episode so the next one becomes Up next.
- Details page, episodes: hold OK on an episode to mark it watched or unwatched, mark everything
  before it as watched, mark the whole season, or play it from the start.
- Films get a second details page for the story: the full synopsis, key facts and Mark as watched.
- A tiny white tick (in your theme's colours) on films and series you've watched.
- New loading animation: the Zapp mark draws itself, then rests with a sweep of light and a glow in
  the poster's colours, with a fun line underneath that changes every few seconds.
- An opening animation with a short chime while Home loads (Settings, Appearance: Startup sound).
- With a TMDB key: official trailers (often 4K), title logos and clean backdrops where add-ons
  have none (this also fills Continue watching cards that had no picture).
- Trailer quality setting: Best (up to 4K) or Auto for slower boxes.

**Changed**
- All wide cards (landscape rows and Continue watching) share one shape and size; Medium is the
  size Continue watching used to have on Small.
- Watched marks sync to Nuvio.

**Fixed**
- YouTube's play/pause and skip buttons no longer appear over trailers.
- Trailer sound now starts only when the picture appears, and plays in step with it.

## 0.24.0

**New**
- Nuvio sync, both ways (Settings, Accounts and sync, Nuvio; sign in on the phone page's Nuvio tab).
  Uses Nuvio's own server API: the server is found through its public discovery document, so
  self-hosted Nuvio servers work too.
  - Each Zapp profile links to one Nuvio profile.
  - First connection merges both sides (newest wins, nothing is deleted), then only changes move:
    Nuvio's change feeds come in (including removals), and what you watch, add to your library or
    remove from Continue watching goes back.
  - Pulls when Zapp opens, on profile switch, when you come back to Zapp and every 30 minutes;
    sends while you watch (about once a minute) and straight away when you pause or stop.
  - Changes made offline wait in a queue and are sent later.
  - Films and episodes with IMDb ids only; live TV and replays are never sent.
  - Only the Nuvio session is kept (encrypted, per profile); the password is used once.

## 0.23.0

**Fixed**
- Player controls (and every key) didn't work when a film was started from a details page: the
  details page kept hold of the remote. It now lets the player take over.

**New**
- TMDB (optional, with your own free key; Settings, Accounts and sync, or the phone page's TMDB tab):
  cast photos, the age rating, network and creators, writers and music, and a tagline on the title
  page; reviews on the cast page; and a fourth page with "More with" the lead actor and "People also
  watched" (the one you're on opens wide), above More like this.


## 0.22.1

**Changed**
- Continue watching cards: wider, with the show's own artwork and logo bottom left, the episode and
  time left bottom right ("S1 E5 · 46m left") and a thin progress line along the bottom edge.
- Titles that came through sync with only their id ("tt…") now show their real name and logo.
- New episodes: a small, quiet "New · S2 E3" line on the card instead of a coloured badge.


## 0.22.0 (new details page, same source)

**New**
- Details page in three pages you move down through:
  1. The title: artwork, poster lettering, a one-line tagline, facts, and the actions along the
     bottom (Play, Trailer with sound, library, start over, sources), "Ends at" and some of the cast.
  2. Episodes (series): the episode you're on fills the page with its picture and story; season tabs,
     a thread joining the season's episodes (watched ones filled), and cards that grow when chosen.
  3. Cast with photos (when the add-on has them) beside the credits, then More like this, under a slim
     header that keeps the title in view.
- Same source when resuming (Settings, Playback, on by default): autoplay tries the source you used
  last time first; for the next episode, the same release (binge group), then the same add-on at the
  same quality.


## 0.21.0 (Live TV redesign)

**Changed**
- Live TV: the programme on the channel you're resting on fills the background softly (and tints the
  glow). The preview is larger, with the channel's logo, quality and favourite on one line, the
  programme now on with time left and its description, and the next three programmes under "Later".
- TV guide: the selected programme's picture sits softly behind the top of the page, with a larger
  picture and title in the information panel.


## 0.20.0 (skip intro)

**New**
- Skip intros and recaps, using IntroDB's community timings (introdb.app) for titles with IMDb ids.
  A "Skip intro" / "Skip recap" button appears already selected, so one press of OK skips; or choose
  "Skip by itself" in Settings, Playback.
- When the end credits start (and there's no scene after them), the next-episode countdown begins
  early, with "Keep watching" to stay for the credits.
- Intro, recap and credits are marked on the seek bar.


## 0.19.0 (new search)

**New**
- Search has its own on-screen keyboard (letters, numbers, space, delete, clear), so the TV's
  keyboard never pops up. A real keyboard, or a remote with letters, types straight in too.
- Suggestions as you type: matching titles and channels, and your recent searches; pick one to search
  for it.
- Recent searches (per profile) when the box is empty.
- Voice search with the microphone key, on TVs that have speech recognition.
- Results in the new style: films and series as posters with titles, then channels, then the guide.


## 0.18.0 (player)

**New**
- Picture: Fit, Fill (covers the screen, cropping the edges) or Stretch, from the player controls.
- Boost: louder, clearer sound for quiet films, from the player controls.
- If a source stops working (and autoplay is on), Zapp switches to the next one by itself and carries
  on from where you were.


## 0.17.1

**New**
- Live TV quality badges: UHD, FHD, HD and SD from channel names, and the real picture size once the
  preview is playing.

**Fixed**
- Trailers fill their space with no gap, are zoomed enough to hide YouTube's title bar and logo, and
  only show while actually playing (never paused, loading or start-up overlays).
- Continue watching titles without pictures (often ones synced from Simkl or Stremio) now get their
  artwork from your add-ons.


## 0.17.0 (smarter Home)

**New**
- Up next: finish an episode and the next one appears at the front of Continue watching, marked
  "Up next"; choosing it starts that episode.
- "Because you watched …": titles from your catalogues that share genres with what you watched last.
- New episodes: series in your library with an episode out in the last 10 days that you haven't seen,
  with a NEW badge.
- Your library as a row on Home.
- All of these can be resized, moved or hidden like any row (hold OK, or Settings, Home screen).


## 0.16.0 (catch-up)

**New**
- Catch-up: on channels where your provider keeps past programmes, earlier shows in the TV guide are
  marked "↺ Replay" and play from the start in the film player (pause, rewind, skip). Works with Xtream
  providers that offer it and M3U playlists with catch-up information.
- Live TV categories match the new look: the open category has a small bar in your theme's colours.


## 0.15.0 (two-way sync, accounts per profile)

**New**
- Stremio now syncs both ways: when you pause or stop, where you got to goes to Stremio's Continue
  watching (and finished titles are marked watched); adding to or removing from your library here
  does the same in Stremio.
- Each profile has its own Simkl and Stremio accounts. Your existing sign-ins stay with your first
  profile; sign in on another profile to sync that one.


## 0.14.0 (subtitles from add-ons, steadier navigation)

**New**
- Subtitles from your subtitle add-ons (OpenSubtitles and others): they're fetched while the sources
  load and appear in the player's Subtitles menu, labelled with language and add-on. Your subtitle
  language and English are offered, English first unless you chose another.

**Fixed**
- Trailers never play under a film or the live player, stop when a details page covers Home, and
  stop when Zapp goes to the background.
- Back on Home, Movies and Series first returns to the top row, then to the sidebar.
- Back in Settings first returns to the section list.


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
