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

## 2. Node 27 (Äventyr) and 28 (Lugn) need the real photos

`app/src/main/res/drawable/imitation.jpg` (äventyr) and `imitation_lugn.jpg` (lugn) are
generated placeholders that just say "BILD KOMMER". Overwrite them with the funny
pictures he has to recreate — same filenames, no code change needed. They are two
separate files on purpose, so replaying the other tail cannot spend the same joke twice.

## 3. More photos for Gissa åldern

`guessPhotos` in `MainActivity.kt` has 3 entries; the plan is 30. Drop files in
`guess_age/`, crop them into `res/drawable/gaYYYY.jpg`, add a line to the map, and the
game grows a round on its own. The pass mark scales with the pool (3/5, rounded up).
The node sits on both tails now (äventyr 29, lugn 24).

## Before release

- [x] `DEV_UNLOCK_ALL` back to `false`
- [x] `APP_VERSION` 26, `versionCode` 26, `versionName` 3.5
- [x] fresh installs start with nodes 1–17 cleared (`prefs.getInt("unlocked", 18)`)
- [x] the fork at node 20 is one-way — no switching roads afterwards
- [ ] replace the two `imitation*.jpg` placeholders (see above) — the only content blocker
- [ ] release: `git tag v26 && git push origin v26`. CI checks that the tag, `APP_VERSION`
      and `versionCode` all agree, publishes the APK, and only THEN bumps `version.json`
      to 26 itself. Never bump `version.json` by hand — an early bump points every old
      install at an APK that still reports the old version, which is an update loop.
