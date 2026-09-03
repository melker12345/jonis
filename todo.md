# Todo

## 1. Redo the challenge text

Every CHALLENGE node's copy needs another pass — it was written fast and reads it.
The text lives in three `Quest` fields plus one shared line:

- `tag` — the task itself, the big bold line
- `flavor` — the italic aside under it
- `proofHint` — the `[ ... ]` line spelling out what counts as proof
- the shared SMS line in `ChallengeScreen` ("Skicka bildbeviset via SMS till världens
  bästa lillebror…") — same for all 20 challenge nodes, so it's worth getting right

Nodes 11–21 (the original IRL set). Both tails now use Melker's own copy and are done.

## 2. Bird sound bites for Flappy Jonis

Lugn node 22 (Fågelskådning) now comes BEFORE Flappy Jonis at node 27, so the voice notes
he sends of himself imitating five birds arrive in time to become the flap sounds in the
game. The audio isn't wired up yet — the recordings have to land first. When they do:
trim them, drop them in `res/raw/`, and play one per flap.

## 3. More photos for Gissa åldern

`guessPhotos` in `MainActivity.kt` has 12 entries; the plan is 30. Drop a file in
`guess_age/` NAMED AFTER THE ANSWER (`17.jpg` = the person is 17), crop it into
`res/drawable/ga_ageNN.jpg`, add a line to the list, and the game grows a round on its
own. The pass mark scales with the pool (3/5, rounded up — 8-of-12 today). The node sits
on both tails (äventyr 29, lugn 24). One photo has two people in it and so asks for the
YEAR instead: `ga_year2012.jpg`, `askYear = true`.

## Before release

- [x] `DEV_UNLOCK_ALL` back to `false`
- [x] `APP_VERSION` 27, `versionCode` 27, `versionName` 3.6
- [x] fresh installs start with nodes 1–21 cleared (`prefs.getInt("unlocked", 22)`), and
      the fork holds the road at node 20 until a tail is picked — without that clamp the
      nine `lockedTail` "???" slots get skipped and the hub reads 29/30 klara
- [x] the fork at node 20 is one-way — no switching roads afterwards
- [ ] release: `git tag v27 && git push origin v27`. CI checks that the tag, `APP_VERSION`
      and `versionCode` all agree, publishes the APK, and only THEN bumps `version.json`
      to 27 itself. Never bump `version.json` by hand — an early bump points every old
      install at an APK that still reports the old version, which is an update loop.
