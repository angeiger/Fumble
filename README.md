# Fumble

### Foto Bumble — swipe through your own gallery to clear it out.

Right keeps a photo, left sends it to the Android system trash, **up moves it into a
*Fumble Favoriten* album** so a forgotten holiday shot ends up somewhere you will find it.
Photos come up in random order, optionally narrowed to a single album, and every photo
is shown exactly once, ever.

[![Download Fumble](https://img.shields.io/badge/Download-fumble.apk-5B4BFF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/angeiger/Fumble/releases/latest/download/fumble.apk)

**Android 11 or newer.** The download always serves the newest version.
Installation walkthrough in German: [INSTALL.md](INSTALL.md) ·
All versions: [Releases](https://github.com/angeiger/Fumble/releases)

### What it does

- **Three directions.** Right keeps, left bins, up favourites.
- **Pinch or double tap to zoom** before deciding. While zoomed the card will not swipe.
- **Nothing is deleted immediately.** Left swipes queue up; the app asks once, then
  Android holds them in the trash for 30 days.
- **Undo goes back as far as you like** — every swipe since the last time you applied.
- **One album at a time**, e.g. only Screenshots.
- **Four looks**, two light and two dark.
- **Nothing leaves the device.** The app has no network permission at all.


---

## Architecture

```
ui/            Compose. Stateless components + one ViewModel per screen.
  swipe/       SwipeViewModel, SwipeScreen, the card stack, gestures
  permission/  Photo-access state machine and the gate screen
  theme/       Palettes and the Material 3 bridge
domain/        Models and the PhotoRepository interface. No Android UI, no Room.
data/
  local/       Room: the decision history. Preferences: album, threshold, palette
  media/       MediaStoreDataSource: the only place that talks to the gallery
  repository/  PhotoRepositoryImpl: joins the two
di/            Hilt modules
```

- **minSdk 30** (Android 11). Not an arbitrary floor: `MediaStore.MediaColumns.IS_TRASHED`
  and `MediaStore.createTrashRequest` only exist from API 30.
- **targetSdk / compileSdk 35**, Kotlin 2.0.21, Jetpack Compose (Material 3).

Unidirectional flow: the screen renders one `SwipeUiState` and calls ViewModel methods.
The ViewModel exposes state as a `StateFlow` and one-shot work (launching the system
trash dialog, showing a snackbar) through a `Channel` of `SwipeEffect`.

`SwipeUiState` is assembled by `combine`-ing three sources: the in-memory deck, the
Room stats query, and the Room pending-trash query. Counters therefore come straight
out of SQLite and cannot drift from what was actually written.

### Dependencies

| What | Why |
| --- | --- |
| Jetpack Compose + Material 3 | UI, gestures, animation |
| Hilt | DI, `@HiltViewModel` |
| Room + KSP | The decision history |
| Coil | Image loading, with the disk cache **off** — the files are already local |
| kotlinx-coroutines | Everything asynchronous |

All versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## The two mechanisms that matter

### 1. Never show a photo twice

`photo_decision` stores one row per swiped photo, keyed by `MediaStore._ID`. The row is
written the instant a card leaves the screen, before any trashing is attempted.

`PhotoRepositoryImpl` deals from a queue of undecided ids, built once per session:
every image id on the device, minus everything in that table, **shuffled**. The
decision history is read once and kept in memory, so a refill costs no extra query.

Three details worth knowing:

- **Why a materialised queue instead of a cursor.** Cards are dealt in random order,
  and shuffling requires knowing the whole candidate set up front. `ORDER BY RANDOM()`
  would re-sample on every page — repeating photos and never signalling exhaustion.
  One id-only scan of the library is cheap (a `Long` per photo) and buys an exact
  remaining count for free.
- **Order is restored after the query.** `WHERE _ID IN (…)` comes back in SQLite's
  order, so `photosByIds` re-sorts the result against the requested ids. Otherwise the
  shuffle would be silently undone.
- **The database is excluded from backup and device transfer.** `MediaStore._ID` is
  assigned per device. Restoring this table onto a new phone would hide a random set of
  that phone's photos. See `backup_rules.xml`.

Photos added to the device mid-session appear after the next queue rebuild — a restart,
or a change to the photo-access grant.

### The album filter falls out of the same scan

`indexImages()` returns `(id, bucketId)` per photo plus a small map of album names, so
the one pass that builds the deal queue also answers "which albums are there, and how
many undecided photos are in each". Opening the picker costs no query at all.

Counts are of *undecided* photos, not of everything in the folder, so the list doubles
as a progress readout. Empty albums are filtered out — offering a folder that would
immediately say "all caught up" is only a way to waste a tap.

Album names would have been the expensive part: a `String` per row dwarfs everything
else at gallery scale, so names live in a map keyed by bucket and each entry is just
two `Long`s. Switching albums keeps the scan and only rebuilds the queue; the decision
history is untouched, so a photo already ruled on does not come back just because it is
being looked at through a different album.

The selection persists in `SharedPreferences`, deliberately not in Room — it is one
number, unrelated to the decision history, and must not be caught up in a destructive
migration of that table. A stored album that no longer exists is dropped rather than
kept, so deleting a folder cannot strand the user on a permanent "all caught up".

### 2. Trashing, and favouriting

A left swipe does *not* immediately touch MediaStore. It records the decision, drops the
card, and adds the photo to a queue shown as a pill under the header.

**Favouriting is queued the same way, for the same reason**, and shares one settle path
in the repository rather than two that would drift apart. What it *writes* changed in
4.1.0, and the reason is worth knowing:

Up to 4.0.0 a favourite set Android's `IS_FAVORITE` flag through
`createFavoriteRequest`. It worked — Android approved the request without even showing
a dialog — and it was useless: **Google Photos does not show that flag.** Favourited
photos appeared in no favourites view the user could find. Google Photos' own favourites
cannot be written from outside at all; no API exists, and the ones that come close need
network access this app deliberately does not have.

So a favourite now ends up in `Pictures/Fumble Favoriten/` — an album every gallery
shows, Google Photos under *Collections → On this device*. How it gets there depends on
where it lives, and 4.1.0 learned that the hard way:

- **Most photos are moved** by rewriting `RELATIVE_PATH`. The row keeps its MediaStore
  id, so the decision history stays valid, and nothing is duplicated. `IS_FAVORITE` is
  set alongside for the apps that honour it.
- **Photos in another app's media area are copied.** Android will not let a file leave
  `Android/media/<package>/` — every WhatsApp picture lives there — and it refuses
  *silently*: the update reports success, the target folder is even created, and the
  photo stays exactly where it was. 4.1.0 believed the report and announced 48 moves
  into an empty album. From 4.1.1 those photos are copied instead, with their original
  capture date carried over (as `DATE_TAKEN`, and stamped into the EXIF data when the
  file has none, since WhatsApp strips it). The original stays in the chat. Copying a
  file the app can read into its own new file needs no dialog.

What makes this trustworthy is in `PhotoRepositoryImpl.flushFavoritesLocked`:

- **Every favourite is located before and after.** A photo already in the album is
  done; one that has vanished is dropped from the queue; one in an app media area goes
  straight to copying. After a move the path is read back, and anything the system
  quietly left behind is copied rather than counted. Only what is verifiably in the
  album is marked done and announced — the rest stays queued.
- **Copies are recorded as decided** (a favourite row that is already applied), so the
  new file is never dealt as a card of its own.
- **Moving needs `createWriteRequest`, and that request only grants access.** Unlike
  a trash request, approval changes nothing by itself; `confirmApplied` performs the
  move afterwards, while the grant is fresh, then verifies it the same way. Photos that
  were settled before the dialog appeared are counted in the same message.
- **Database versions 3 and 4 re-queue every earlier favourite**, so photos favourited
  under 4.0.0 (flag only) or 4.1.0 (reported as moved, possibly not) run through the
  verified path. Ones that did arrive are recognised and cost nothing.

The folder rules (what counts as the album, what cannot be moved) live in
`FavoritesAlbum` as plain functions with their own tests.

The platform has no combined request, so a settle that needs consent for both raises
two dialogs — trash first, favourites only once that answer is in. Stacking them would
put a second system window over the first.

**The trigger reads the queue, never a swipe counter.** `SwipeViewModel` observes the
pending-trash query and hands the size to `TrashPromptPolicy`. This is the fix for a
bug that went unnoticed for two weeks of daily use: the threshold used to be a counter
in a ViewModel field, which died with the process while the queue in Room did not. A
queue built up across several background-and-return cycles never reached the threshold,
so hundreds of photos could pile up without a single prompt. Reading the durable value
also means a backlog inherited from an earlier session is picked up the moment the app
opens.

When the queue reaches the threshold (100), or the user taps the pill, `flush()`
runs two steps:

1. **Direct write.** `ContentResolver.update` with `IS_TRASHED = 1`. This is silent and
   free for media the app owns. After five refusals in a row it stops probing — the
   remainder is bound for the dialog anyway, and at batch sizes in the hundreds the
   wasted binder calls delay that dialog visibly.
2. **One consent dialog for the rest.** Almost every photo on a phone is owned by the
   camera app, so step 1 throws `SecurityException` for most of them.
   `MediaStore.createTrashRequest` bundles all of those into a single system prompt.
   On approval the platform does the trashing itself.

Batching is the whole point: without it the user would face a system dialog on every
single left swipe. Rows stay in the queue until the write actually lands
(`trash_applied`), so a declined dialog, a backgrounded app, or process death all just
mean "try again later" — nothing is lost and nothing is double-counted.

Declining the dialog raises the next threshold by 20 rather than resetting it, so a
refusal buys a little more swiping without letting the queue drift far past the limit.

**The queue is durable.** It lives in Room, so closing the app with 70 photos waiting
leaves 70 photos waiting — swiped left, not yet trashed, still on the device. Only two
things empty it: a confirmed flush, or the explicit "Start over".

Android purges trashed items automatically after 30 days. This app never deletes
anything permanently and never asks to.

### 3. Undo reaches back to the last settle

Every swipe, in either direction, is pushed onto a stack. Undo pops it, deletes the
history row, and puts the card back on top of the deck. The stack is emptied at exactly
one moment: when photos actually reach the system trash — whether that was the
automatic prompt or a tap on the pill. Past that point this app no longer decides their
fate, and offering undo would be a lie. **The pill is the undo horizon.**

The ids awaiting a consent dialog live in the ViewModel, not in composition state. The
dialog is a separate activity and ours can be recreated behind it; state remembered in
composition would come back empty, and the app would believe rows were still queued
after the platform had already trashed them.

### Permissions

| Release | Requested |
| --- | --- |
| Android 14+ | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` |
| Android 13 | `READ_MEDIA_IMAGES` |
| Android 11–12L | `READ_EXTERNAL_STORAGE` (capped with `maxSdkVersion="32"`) |

Partial access on Android 14+ is handled as a first-class state, not an error: the app
says it is only seeing the selected photos and offers to widen the selection. Without
that, "All caught up" would be a lie about photos it simply cannot see.

Access is re-read on every resume, so returning from system Settings or the photo
picker updates the UI immediately.

---

### 4. Three directions from one gesture

`SwipeGeometry` returns a `Lean` — how far the card is towards committing sideways and
how far upwards — and every overlay, the rotation and the cards behind all read from
that one pair of numbers.

**Up wins ties.** A thumb pushing up nearly always drifts sideways, while a sideways
swipe rarely climbs, so `isUpward` compares `up` against the *absolute* horizontal lean.
Without that, favouriting would be almost impossible to hit one-handed. Only the winning
direction shows its badge; two badges during a diagonal drag would leave the user
guessing which one the release picks.

### 5. Zoom shares its fingers with the swipe

Pinch and swipe compete for the same input, so they cannot be two gesture detectors
racing to consume events. `detectCardGestures` is one handler that decides, per
gesture, which it is:

- Two fingers down at any point → a transform. Zooming and panning the photo.
- One finger while already zoomed → pan. A drag across a magnified image means "show
  me the rest of it", not "throw this away".
- One finger at rest scale → swipe the card, with the same touch slop and release
  velocity it had before zoom existed.

Once two fingers have been seen the gesture stays a transform even after one lifts, so
a pinch that ends one finger at a time can never turn into an accidental trash swipe.
A press within the slop that ends quickly is a tap; two in a row toggle the zoom.

`PhotoZoomState` is per card and separate from the card's own position — a zoomed image
being dragged must not look like a card being thrown away, and the two transforms apply
at different levels of the layout. `flyOut` eases the zoom back to rest before the card
leaves; at rest scale that call returns immediately, so an ordinary swipe is untouched.

## Design

One ground, flat cards, and exactly three colours that carry meaning: an accent for the
app itself, green for keep, red for trash. No borders, no bottom navigation.

Four palettes ship — Daylight, Paper, Midnight, Carbon — chosen in settings rather than
followed from the system. Dark mode is one of the palettes, not a separate axis: these
are complete looks, and pairing a chosen accent with an inferred ground would only
produce combinations nobody picked. Keep and trash stay green-ish and red-ish in all of
them; they are not decoration but the two words the interface says.

Colours reach components through a `CompositionLocal` exposed as composable getters
(`FumbleInk`, `FumbleAccent`, …), so every call site kept working when themes arrived.
The catch is that they can only be read *in* composition — a `drawBehind` or
`graphicsLayer` lambda needs the value hoisted first.

Settings is deliberately two decisions long: how many left swipes pile up before the
app asks, and which palette. If it ever needs a third section, that is a sign a default
is wrong.

When photos actually reach the trash, `TrashCelebration` takes the screen for two
seconds — the only place in the app that exists purely to feel good. It is kept honest
by showing the two real numbers and naming the 30-day window rather than implying
anything was destroyed, and a tap skips it.

The card stack renders three cards. The top one is dragged directly; the ones behind
scale and rise into place in proportion to that drag, anchored at their bottom edge so
the peek is exactly 16dp regardless of card height. Drag progress is shared through a
`MutableFloatState` that is only ever read inside `graphicsLayer` and `drawBehind`
lambdas — dragging a card triggers zero recompositions.

A gesture commits if it crosses 30% of the card width, or on a flick faster than
900 px/s. A fast flick *back* towards centre always cancels, even from past the
threshold — that gesture is someone taking back a swipe, and it must never fire a trash
decision. Those rules live in `SwipeGeometry`, free of Compose, and are unit tested.

---

## Building

Requires JDK 17 and the Android SDK for API 35.

```bash
./gradlew :app:installDebug
```

The Gradle wrapper JAR is not checked in. Open the project in Android Studio (which
generates it), or run once:

```bash
gradle wrapper --gradle-version 8.9
```

Unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

---

## Where to look first

| File | Role |
| --- | --- |
| `data/media/MediaStoreDataSource.kt` | Every read and write against the gallery |
| `data/repository/PhotoRepositoryImpl.kt` | The shuffled queue, albums, de-duplication, trash and favourite flush |
| `data/media/FavoritesAlbum.kt` | Which folder is the album, which photos must be copied |
| `ui/swipe/SwipeViewModel.kt` | Deck, undo stack, effects |
| `ui/swipe/TrashPromptPolicy.kt` | When to ask the system to empty the queue |
| `ui/swipe/components/SwipeCardStack.kt` | The stack and its animation |
| `ui/swipe/components/CardGestures.kt` | Arbitrating pinch, pan, swipe and tap |
| `ui/swipe/SwipeGeometry.kt` | When a drag counts as a decision |
| `ui/theme/Palette.kt` | The four palettes |
| `data/local/FumbleDatabase.kt` | Schema version and migrations |
