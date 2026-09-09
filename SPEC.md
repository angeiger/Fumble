# Fumble — product specification

*Version 4.0.0 · written as a hand-off document so the app can be rebuilt on another
platform without reading the Android source.*

This describes **what Fumble is and why it behaves the way it does**, not how the Kotlin
happens to be arranged. Several rules here look arbitrary and are not — they were
learned from real use over four releases, and a re-implementation that ignores them will
reproduce bugs that took weeks to notice. Those are marked **⚠ Learned the hard way**.

---

## 1. What it is

A photo-cleanup app. The user is shown one photo at a time from their own gallery as a
card, and swipes to decide:

| Gesture | Meaning | Effect |
| --- | --- | --- |
| **Right** | Keep | Nothing happens to the photo. It is only recorded as decided. |
| **Left** | Trash | Queued for deletion into the OS trash. |
| **Up** | Favourite | Kept **and** marked as a favourite in the OS photo library. |

The point is to make clearing out a gallery fast and low-stakes. Nothing is ever deleted
permanently by the app: everything goes to the operating system's own trash, which
restores for 30 days.

**Non-negotiable properties:**

- **No photo is ever shown twice**, across sessions, restarts and reinstalls of the same
  app identity.
- **Nothing leaves the device.** There is no network permission at all, and no analytics.
- **Nothing is destroyed.** The app only ever sets a trash flag; the OS does the rest.

---

## 2. The core loop

1. Photos are dealt from a **shuffled queue** of everything the user has not yet ruled
   on, optionally narrowed to one album.
2. Each swipe writes a decision row locally, immediately, and removes the card.
3. Left swipes and up swipes accumulate in **queues**. They are not applied one at a time.
4. When the trash queue reaches a threshold (default 100, user-configurable), or the user
   taps the pending pill, the queues are **applied** — which is when the OS asks for
   confirmation.
5. Applying the trash queue triggers a short celebration showing how much space was freed.

### Why queues instead of acting immediately

On both Android and iOS, an app may freely modify only media it created itself. Every
photo taken by the camera app belongs to the camera app, so **every deletion needs the
user's confirmation through a system dialog**. Doing that per swipe would mean a modal
dialog after every single left swipe — the app would be unusable.

Batching turns one dialog per swipe into one dialog per ~100 swipes. Everything else in
the design follows from that decision.

⚠ **Learned the hard way:** favouriting has the *same* permission rule. It must be
queued too, or the second gesture reintroduces exactly the problem batching solved.

---

## 3. Screens

### 3.1 Permission gate

Shown until the app can read at least part of the photo library.

- Centred: a drawn glyph of three fanned cards, then the wordmark **F**umble (first
  letter in the accent colour), then the subtitle *Foto Bumble*, then a headline
  ("Your gallery, one card at a time"), a one-line reassurance that nothing leaves the
  device, and a single pill button.
- This is the first screen a new user sees, so it is where the name is explained.
- If the user has permanently denied access, the body text and button change to point at
  system settings instead of re-asking.
- Access state is re-read on every resume, so returning from Settings updates the screen
  immediately.

### 3.2 Main screen

Top to bottom:

**Header row** — the wordmark on the left; on the right a muted readout
`328 left · 1,2 GB freed` and a small settings gear. Either half of the readout is
omitted when it would be zero: while the first batch loads there is nothing truthful to
say, and "0 left" beside an empty state is noise.

**Scope chip** — a small, muted, tappable chip below the wordmark showing what is being
swiped through: *All photos* or an album name, with a chevron. Always visible. Once the
deck can be narrowed, "all caught up" means nothing unless the user can see what it was
caught up *with*.

**Pending pill** — appears only when something is queued. Shows
`12 to delete · 84 MB`, or `12 to delete · 3 to favourite`, or `3 to favourite`.
Tapping it applies the queues immediately. It is also the **undo horizon** (see §5.3).

**Card stack** — fills the remaining space. Three cards are kept alive; only the top one
is interactive. See §4 and §6.

**Action bar** — four equal circular buttons, left to right:
**Trash · Undo · Favourite · Keep**.

The order mirrors the gestures: trash on the left where a left swipe goes, keep on the
right where a right swipe goes. Undo sits next to the destructive button it most often
has to take back. All four are the same size; a hierarchy of sizes made the row look
unbalanced.

Each button uses a soft tinted container with a saturated icon. Undo is the only one
carrying no meaning, so it uses the palette's **neutral** pair rather than one of the
three semantic tints.

⚠ **Learned the hard way:** undo originally reused the generic "surface" and "muted
text" colours. Those exist to sit *quietly* against the background, and on three of the
four palettes the button was nearly invisible. Neutral is not the same as quiet. Every
palette needs its own tuned grey, because how far a neutral must travel from the
background to register is completely different on a near-white than on a true black.

### 3.3 Album sheet

A bottom sheet listing *All photos* plus every album that still contains undecided
photos, largest first, each with a count and a tick on the current selection.

- **Counts are of undecided photos, not of everything in the folder.** The list doubles
  as a progress readout and shrinks as albums are worked through.
- Albums with nothing left are omitted entirely — offering a folder that would
  immediately say "all caught up" only wastes a tap.
- Switching albums does **not** touch the decision history. A photo already ruled on
  while viewing everything stays ruled on inside its album.
- The selection persists across restarts.
- A stored album that no longer exists is silently dropped back to *All photos*, so
  deleting a folder cannot strand the user on a permanent empty state.

### 3.4 Settings sheet

Deliberately two decisions long. If it ever needs a third section, that is a sign a
default is wrong.

1. **"Ask me after"** — how many left swipes accumulate before the app offers to apply
   them. Preset chips: **10 · 25 · 50 · 100 · 200**. Free numeric entry was rejected;
   it only invites a value nobody wants to live with.
2. **"Look"** — the four palettes, each as a row with a miniature swatch (ground, card,
   accent), the name, and a dot on the active one.

Footer: `Fumble 4.0.0 · Foto Bumble`.

### 3.5 Celebration

Plays when photos actually reach the trash. This is the only screen that exists purely
to feel good; everything else earns its pixels by informing a decision.

Roughly 2.4 seconds, tap anywhere to skip. Three overlapping passes:

1. **Two thick ribbons draw themselves** along curves — one sweeping in from beyond the
   top-right corner down across to the left, one arcing off the bottom edge. They are
   revealed progressively along their length (path trimming), not faded in. A stroke
   that *grows* reads as something happening; the same shape faded in reads as
   decoration appearing. This is the single most important detail of the whole animation.
2. **About 22 outlined card glyphs** pop in one after another over the first third of a
   second, each with its own birth time so they never appear in unison, then fall away
   under gravity with rotation and fade.
3. **The reclaimed size counts up** in a large light numeral, front-loaded so it lands
   while the ribbons are still moving, with a spring pop on entry.

Below it: the word *freed*, and `12 photos · recoverable for 30 days`.

**No congratulatory slogan.** The user cleared out some photos, which is a fact, not an
achievement to be told about. The two real numbers and the 30-day note carry it.

Colours come from the active palette — accent for the first ribbon, the keep colour for
the second, ink for the glyphs, the canvas for the ground — so the celebration belongs
to whichever look the user chose.

### 3.6 Empty state

The card glyph, "All caught up", and a line that names the album when the deck was
narrowed to one. Finishing a folder must not look identical to finishing the whole
device.

- Inside an album: a button back to *All photos*.
- At the top level: a "Start over" button that clears the review history after a
  confirmation dialog. Photos are not affected — only the record of having seen them.

---

## 4. Gestures

One gesture handler arbitrates everything, because pinch and swipe compete for the same
fingers and cannot be independent detectors racing to consume events.

Per gesture, it decides:

- **Two fingers down at any point → transform.** Zoom and pan the photo.
  Once two fingers have been seen the gesture stays a transform even after one lifts, so
  a pinch released one finger at a time can never turn into an accidental trash swipe.
- **One finger while already zoomed → pan the photo.** A drag across a magnified image
  means "show me the rest of it", not "throw this away".
- **One finger at rest scale → swipe the card.**
- **A press within the touch slop that ends quickly → a tap.** Two in quick succession
  toggle the zoom.

### Commit rules

Let `dismissDistance = 0.30 × card width` and `upDistance = 0.18 × card height`.
Upward needs proportionally less travel because a thumb pushing up has far less room
than one sweeping across.

Define the **lean**: `horizontal = x / dismissDistance`, `up = −y / upDistance`, both
clamped to ±1.

- **Direction:** up wins if `up > |horizontal|`, otherwise the sign of `x` decides.
  ⚠ **Up must win ties.** A thumb pushing up nearly always drifts sideways, while a
  sideways swipe rarely climbs. Without this, favouriting is almost impossible to hit
  one-handed.
- **Commit:** a flick faster than **900 px/s** commits on its own; otherwise the lean
  must reach 1.
- ⚠ **A fast release pushing *back* towards centre must never commit.** That gesture is
  someone changing their mind, and committing it would trash a photo the user was
  rescuing. Check that position and velocity agree in sign.
- Only the winning direction shows its overlay. Two badges during a diagonal drag would
  leave the user guessing which one the release will pick.

### Zoom

- Pinch to zoom, capped at 6×. Double tap toggles between 1× and 2.6×, centred on the
  tapped point.
- The pinch centroid stays pinned to the same part of the photo, which is what makes it
  feel like moving paper rather than moving a camera.
- Panning is clamped so the image cannot be dragged off its own card.
- Zoom state is **per card** and resets when the card is replaced.
- Before a card flies out, the zoom eases back to rest — a card leaving mid-zoom reads as
  a glitch. At rest scale this costs nothing, so ordinary swipes are unaffected.

---

## 5. The rules that matter

### 5.1 Never show a photo twice

Every swipe writes a row keyed by the photo's stable library identifier, **the instant
the card leaves the screen**, before any deletion is attempted. The deal queue excludes
every id in that table.

⚠ **The decision history must never be included in device-to-device backup or
transfer.** Photo library identifiers are assigned per device. Restoring the table onto a
new phone would silently hide a random set of that phone's photos.

⚠ **Do not rename the database file or the preferences file when rebranding.** Renaming
either means a new, empty store, and every photo the user already kept comes back.

### 5.2 Random order

Cards are dealt in random order, which rules out streaming from a database cursor:
a random ordering re-samples on every page, repeating photos and never signalling
exhaustion.

Instead: scan the library once per session for identifiers only, subtract everything
already decided, **shuffle the result**, and deal from that list. An identifier per photo
is cheap even for very large libraries, and it makes the "N left" counter exact rather
than estimated.

The same scan should also yield the album list, so opening the album picker costs no
extra query.

### 5.3 Undo reaches back to the last apply

Every swipe, in either direction, is pushed onto a stack. Undo pops it, deletes the
history row, and puts the card back on top of the deck. Arbitrarily deep.

The stack is emptied at exactly one moment: **when photos actually reach the trash.**
Past that point the app no longer decides their fate, and offering undo would be a lie.

**The pending pill is therefore the undo horizon**, and that is the mental model to
preserve: anything still on the pill can be taken back.

The stack is session-scoped; a restart starts fresh. The *queue* however persists — see
§5.5.

### 5.4 When to ask ⚠ Learned the hard way

**The prompt must be triggered by the size of the persisted queue, never by a counter of
swipes in memory.**

The original implementation counted left swipes in a field on the screen's state holder.
That field died with the process; the queue in the database did not. Every time the OS
reclaimed the backgrounded app, the counter reset while the queue stayed. A queue built
up across several sessions never reached the threshold, and **hundreds of photos piled
up over two weeks without a single prompt.**

Reading the durable value also means a backlog inherited from an earlier session is
noticed the moment the app opens.

Related rules:

- Changing the threshold in settings applies **at once**. Lowering it below what is
  already queued must prompt straight away, not wait for the next swipe.
- **Declining the dialog raises the next threshold by 20** rather than resetting it. A
  refusal buys a little more swiping without letting the queue drift far past the limit.
  Resetting the count would mean another full batch before being asked again.
- Repeated failures back off the same way, so a broken storage volume does not retry on
  every swipe.
- Guard against two dialogs being raised at once — the automatic trigger and the pill can
  fire together.
- Cap a single dialog at ~200 items. A queue that grew unusually large must not hand the
  system a confirmation sheet with thousands of thumbnails; the remainder stays queued
  for the next round.

### 5.5 The queue is durable

Closing the app with 70 photos waiting leaves 70 photos waiting — swiped left, not yet
trashed, still on the device. Only two things empty it: a confirmed apply, or the
explicit "Start over".

Rows stay queued until the write actually lands. A declined dialog, a backgrounded app,
or process death all just mean "try again later" — nothing is lost, nothing is
double-counted.

⚠ **The identifiers awaiting a confirmation dialog must be held outside the view layer.**
The dialog is a separate system screen and the app's own screen can be recreated behind
it. State held in the view would come back empty, and the app would believe rows were
still queued after the system had already deleted them.

### 5.6 Two dialogs, in sequence

If both queues have content, applying raises the trash confirmation first and the
favourite confirmation only once that answer is in. Neither platform offers a combined
request, and stacking two system sheets is worse than showing them in order.

---

## 6. The card stack

- **Three cards** are alive at once. The hindmost exists mainly to have its image decoded
  before it is needed.
- Cards behind are scaled down ~4.5% per depth and offset **16 pt downwards**, anchored
  at their **bottom edge** so the visible sliver is exactly 16 pt regardless of card
  height.
- As the top card is dragged away, the cards behind **rise into place in proportion**,
  interpolating to exactly the position the next card will occupy. The transition through
  promotion must be continuous — no pop.
- The top card rotates up to **11°**, pivoting *below* the card so it swings rather than
  spins.
- Exit animation: ~280 ms, accelerating outward, to 1.15× the screen dimension.
- ⚠ **The stack must drop a dismissed card locally rather than waiting for the state
  round trip.** Waiting is a frame or two too slow, and the second card visibly dips at
  its old depth for one frame before being promoted.
- ⚠ **Drag progress must be read without triggering layout recomposition** — dragging a
  card should cost zero re-renders of the surrounding UI.

### Card appearance

- Rounded rectangle, **32 pt corner radius**, filled with the surface colour.
- Very soft shadow on light palettes; **none at all on dark ones**, where a shadow under
  a dark card on a dark ground is invisible and the surface lift does the separating.
- The photo is **fitted, never cropped**. The app asks the user to make a keep-or-bin
  call, and cropping would hide exactly the edges they might be judging it on.
- A small frosted pill in the bottom-left shows the file size.
- If the image fails to load, a neutral "no longer available" message replaces it; the
  card is still swipeable.

### Swipe overlays

A colour wash over the whole card at up to 22% opacity in the direction's colour, plus a
small pill badge that scales and fades in:

| Direction | Badge | Position | Colour |
| --- | --- | --- | --- |
| Right | `KEEP` | top-left | keep |
| Left | `TRASH` | top-right | trash |
| Up | `FAVOURITE` | bottom-centre | accent |

---

## 7. Design system

Minimal and photo-first. No borders, no bottom navigation, no menus beyond the two
sheets. The largest type on the main screen is the wordmark at 22 pt.

### 7.1 Palettes

Four complete looks, chosen in settings — **not** followed from the system. Dark mode is
one of the palettes, not a separate axis: pairing a chosen accent with an inferred ground
only produces combinations nobody picked.

**Keep stays green-ish and trash stays red-ish in every palette.** They are not
decoration; they are the two words the interface says.

| Role | Daylight | Paper | Midnight | Carbon |
| --- | --- | --- | --- | --- |
| canvas | `#F6F7FB` | `#F7F3EC` | `#12141C` | `#000000` |
| surface | `#FFFFFF` | `#FFFCF7` | `#1C1F2A` | `#121212` |
| ink | `#12141C` | `#221C15` | `#F2F3F7` | `#F5F5F5` |
| inkMuted | `#7A7F8E` | `#8A7E6E` | `#9AA0B0` | `#9E9E9E` |
| inkFaint | `#B6BAC6` | `#C4B8A6` | `#5C6273` | `#5A5A5A` |
| hairline | `#E8EAF1` | `#EBE3D6` | `#2A2E3C` | `#222222` |
| accent | `#5B4BFF` | `#C2643C` | `#8B7BFF` | `#3DDC97` |
| accentSoft | `#EDEBFF` | `#F8E7DD` | `#262640` | `#10281F` |
| keep | `#00C48C` | `#3E9E6B` | `#2BD9A4` | `#3DDC97` |
| keepSoft | `#E1F8F1` | `#E4F2E9` | `#16342C` | `#10281F` |
| trash | `#FF4D6A` | `#D9534F` | `#FF6B83` | `#FF5C7A` |
| trashSoft | `#FFE8EC` | `#FBE6E5` | `#3A2029` | `#2E1119` |
| neutral | `#3C414F` | `#6E6152` | `#C7CDDC` | `#D2D2D2` |
| neutralSoft | `#DFE2EB` | `#EBE0CE` | `#333949` | `#2C2C2C` |
| card shadow | `#161A2E` @ 12% | `#3A2E1E` @ 12% | none | none |

System bar / status bar icons follow the palette's own darkness, not the system setting.

### 7.2 Typography

System sans throughout, light weights, open tracking. Nothing should compete with the
photograph.

| Role | Size | Weight | Tracking |
| --- | --- | --- | --- |
| Wordmark | 22 | Light | +3 |
| Celebration numeral | 60 | Light | −2 |
| Display (empty state) | 28 | Light | −0.4 |
| Title | 20 / 16 | Regular / Medium | — |
| Body | 15 / 13 | Regular | — |
| Label (buttons) | 14 | Medium | +0.3 |
| Chrome caps (badges, chips, wordmark of sections) | 11 | Semibold | +1.6 |

### 7.3 Shape and motion

- Corner radii: 10 / 14 / 20 / 28 / 36. Cards use 32. Buttons and pills are fully round.
- Action buttons: **60 pt**, 12 pt apart, 20 pt side margins — four equal circles plus
  gaps must fit a 320 pt wide screen.
- Buttons scale to 0.90 on press with a bouncy spring.
- Card return-to-centre: a spring, damping ≈ 0.62, medium-low stiffness.
- Pills and banners animate in with fade + vertical expand.

---

## 8. Data model

One table, one row per decided photo:

| Field | Notes |
| --- | --- |
| `mediaId` | Primary key. The OS photo library's stable local identifier. |
| `contentUri` | How to address the asset later. |
| `decision` | `KEEP` \| `TRASH` \| `FAVORITE`, stored by name. |
| `sizeBytes` | For the freed-space figures. |
| `decidedAt` | Ordering within the queues. |
| `trashApplied` | Whether the trash write has landed. |
| `favoriteApplied` | Whether the favourite write has landed. |

Two separate applied-flags rather than one shared "settled" flag: they are different
writes needing separate confirmations, and collapsing them makes it impossible to tell
which half of a batch succeeded.

Indexed on `(decision, trashApplied)`.

**Any schema change after first release needs a real migration.** People are using this;
a destructive fallback shows them every photo they had already kept.

Separately, three small persisted settings: selected album, trash threshold, palette id.
Keep these out of the main database so they cannot be caught in a destructive migration
of it.

---

## 9. Porting notes for iOS

The concept maps well; none of the code does.

| Android | iOS equivalent |
| --- | --- |
| `MediaStore` query | `PHAsset.fetchAssets` via PhotoKit |
| `MediaStore._ID` | `PHAsset.localIdentifier` |
| `IS_TRASHED = 1` | `PHAssetChangeRequest.deleteAssets` → *Recently Deleted*, also 30 days |
| `IS_FAVORITE = 1` | `PHAssetChangeRequest.isFavorite = true` |
| `createTrashRequest` consent dialog | The system confirmation shown for `performChanges` on assets the app does not own |
| Album `BUCKET_ID` | `PHAssetCollection` |
| Partial photo access (Android 14+) | Limited Photo Library access |
| Jetpack Compose | SwiftUI |
| Room | SwiftData or Core Data |
| Coil | `PHImageManager` / `PHCachingImageManager` |

**Watch for:** iOS confirms a *batch* of changes in one prompt, which is the same shape
as the Android design — so the queue-and-apply model transfers directly rather than
needing rework. Use `PHCachingImageManager` to warm the next cards, mirroring the
three-card stack.

**Distribution reality:** an iOS build requires macOS (or a cloud build service such as
Expo EAS or Codemagic), and putting the app on anyone else's device requires the Apple
Developer Program at €99/year. TestFlight is then the right channel — up to 10,000
testers, normal installation, automatic updates. There is no free equivalent of
sideloading an APK.

---

## 10. Mistakes worth not repeating

Collected from four releases of real use.

1. **Trigger from durable state, not from memory.** The threshold counter in memory
   silently stopped working the moment the OS reclaimed the app. Two weeks before anyone
   noticed. (§5.4)
2. **Neutral is not the same as quiet.** Reusing "background" colours for the undo button
   made it invisible on three of four palettes. (§3.2)
3. **Do not rename storage when renaming the app.** The application identity, the
   database file and the preferences file are identity and storage, not branding.
   Changing them installs a second, empty app beside the first.
4. **Do not let a fast reverse flick commit.** It is someone taking back a swipe.
5. **Promote the next card locally**, not after a round trip through app state.
6. **Cap the confirmation batch.** An unusually large queue must not produce a system
   sheet with thousands of items.
7. **Hold consent state outside the view layer**, or an app restart behind the system
   dialog loses track of what was approved.
8. **Fit photos, do not crop them.** The user is judging the whole image.
