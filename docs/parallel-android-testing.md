# Parallel Android UI tests: pool of lightweight instances

*Claude Code authored.* Measured 2026-11-14 on the dev host (Intel i5-6300U, 2 cores / 4 threads, 15 GB RAM, 4 GB swap, Linux 7.0).

## Question

Can the repo's device tests run on several lightweight Android instances, with a shorter total wall time and no more RAM than the single shared emulator (`emulator-5580`, 2.5 GB guest, about 2.8 GB host RSS)?

## Answer in short

Yes, with redroid (Android 14 in a Docker container that shares the host kernel). A pool of 2 containers runs the same work in 538–548 s, against 890–936 s on the shared emulator (−40%). Its peak memory is 2.8 GiB for both containers together, which is the same as one emulator. A pool of 3 is faster (463 s) but flaky, because the host has only 4 threads. A pool of 2 emulators saves less (−24%) and uses 5.8 GB. `docker checkpoint` does not work on this host.

## Setups

| Setup | How |
|---|---|
| Baseline | One `emulator-5580` from `scripts/emulator.sh`, restored from its quick-boot snapshot. |
| Emulator pool | 2 instances of the same AVD (`-read-only`, ports 5582/5584, `-memory 1536 -cores 2 -no-snapshot-save`), each restoring the shared snapshot. |
| Redroid | `redroid/redroid:14.0.0-latest`, `--privileged`, `androidboot.redroid_gpu_mode=guest`, 1080x2400 at 420 dpi, adb over TCP on 127.0.0.1:5601.. Needs `binder_linux` and binderfs (Alex ran the setup). |
| Not a candidate | Several installs on one device: one screen and one focus. |

All runs use the same work: `scripts/ui-tests-pool.sh BookUiTest ImportQueueUiTest FindBooksUiTest`. That runs every `@Smoke` class (annotation filter, 26 classes) plus the three named classes whole: 29 queue entries. ForceStopTest uses the same kill-between-phases logic as `scripts/ui-tests.sh`. Baseline also uses this runner with one device, so the work and the code path match. Raw runner output, environment lines and 4-second samples of every run are in [docs/parallel-android-testing-raw/](parallel-android-testing-raw/).

## Results

Wall time is the runner's `RUN` (install finished to last class done). Memory is the peak of the summed process RSS for emulators (`ps`) or of `docker stats` for containers (it includes page cache charged to the container). CPU is the mean of `100 − vmstat idle`.

| Setup | Run | RUN (s) | Peak instance memory | Mean CPU busy | Failed entries |
|---|---|---|---|---|---|
| Baseline, 1 emulator | 1 | 890 | 2817 MB | 52% | 3 |
| Baseline, 1 emulator | 2 | 936 | 2904 MB | 53% | 2 |
| Emulator pool of 2 | 1 | 693 | 5754 MB | 88% | 2 |
| Emulator pool of 2 | 2 | 679 | 5853 MB | 88% | 2 |
| Redroid, 1 container | 1 | 630 | 1716 MiB | 72% | 3 |
| Redroid pool of 2 | 1 | 548 | 3479 MiB* | 93% | 2 |
| Redroid pool of 2 | 2 | 538 | 2775 MiB | 95% | 2 |
| Redroid pool of 3 | 1 | 463 | 3785 MiB | 98% | 7 |

\* Includes an idle third container that was still running.

Verbatim final lines of the runner (`EXIT=… devices=… RUN=…`):

```
EXIT=1 devices=1 classes=29 failed=3 BUILD=2s INSTALL=1s RUN=890s WALL=893s      (baseline 1)
EXIT=1 devices=1 classes=29 failed=2 BUILD=19s INSTALL=2s RUN=936s WALL=957s     (baseline 2)
EXIT=1 devices=2 classes=29 failed=2 BUILD=16s INSTALL=2s RUN=693s WALL=711s     (emulator pool 2, run 1)
EXIT=1 devices=2 classes=29 failed=2 BUILD=14s INSTALL=3s RUN=679s WALL=696s     (emulator pool 2, run 2)
EXIT=1 devices=1 classes=29 failed=3 BUILD=17s INSTALL=2s RUN=630s WALL=649s     (redroid 1)
EXIT=1 devices=2 classes=29 failed=2 BUILD=14s INSTALL=2s RUN=548s WALL=564s     (redroid pool 2, run 1)
EXIT=1 devices=2 classes=29 failed=2 BUILD=1s INSTALL=1s RUN=538s WALL=540s      (redroid pool 2, run 2)
EXIT=1 devices=3 classes=29 failed=7 BUILD=2s INSTALL=2s RUN=463s WALL=467s      (redroid pool 3)
```

Boot and restore:

| Instance | Time | Source |
|---|---|---|
| Emulator restore from snapshot | 8–15 s each (`Successfully loaded snapshot 'default_boot' using 11262 ms`) | `emulator.sh ensure` took 12 s |
| Emulator cold boot | 133 s | hub's earlier measurement, not repeated |
| Redroid container, first | 22 s | `redroid boot 22s` |
| Redroid, second beside a running one | 21 s | |
| `scripts/redroid-pool.sh start 2` from nothing | 27 s | |

Per-class times that set the critical path (seconds):

| Class | Baseline | Emulator pool of 2 | Redroid 1 | Redroid pool of 2 |
|---|---|---|---|---|
| ImportQueueUiTest (smoke / whole) | 170 / 160 and 174 / 171 | 279 / 257 | – | – |
| BookUiTest whole | 126–129 | 169–182 | 78 | 137–139 |

Two long classes (ImportQueueUiTest ×2 entries, BookUiTest ×2) make up most of the critical path, so a pool beyond 2 to 3 workers cannot help much, and the list order (longest first) matters.

### Memory

- **`-memory 1536` does not apply on snapshot restore.** Guest `MemTotal` is `2534552 kB` in both pool emulators, as in the baseline. The snapshot fixes the guest RAM size. The hub saw the same with `hw.ramSize`. A cold boot (`-no-snapshot`) might apply it; I did not measure it.
- Each emulator costs about 2.7–2.9 GB RSS. A redroid container costs 0.7–1.7 GiB (more after tests, as page cache grows). Redroid shares the host kernel and page cache.
- Host swap was already 3.5–3.7 GB of 4 GB used before my first run, from other processes. Swap was full in every run, so host `free` figures do not separate the setups; I report instance memory only.

### CPU

The host has 4 threads. The baseline leaves about half of them idle, which is why 2 instances help. At 2 instances the CPU is 88–95% busy, and at 3 it is 98%: the gain from 2 to 3 is 15% of wall time, and failures go from 2 to 7.

## Failures and what they mean

| Failure | Where | Meaning |
|---|---|---|
| `BookUiTest.backupSharesAZipOfPdfAudioTranscriptAndNotes`, `RootViewWithoutFocusException` | Emulator, 4 of 4 runs | Pre-existing on the emulator, passes on redroid. A focus or share-sheet problem on this AVD. Not caused by pooling. |
| `BookUiTest.searchWithNoMatchEmptyQueryRotationAndBack`, same exception | Emulator runs (seen in baseline 1 and pool 2 run 1) and redroid runs 1, 2a and 3 (the redroid 2b log does not name the test) | Fails after rotation on both platforms. Not caused by pooling. |
| `BookUiTest.theNotesPanelHasNoAddButton`, `Failed to inject touch input` | Emulator pool 2, run 1 only | New under load. Flaky when the CPU is 88% busy. |
| `ControlsCentredTest`, `IllegalStateException` | Baseline 1 and redroid pool of 3 only | Flaky: passed in baseline 2 and elsewhere. |
| `ThemeModeTest.themeChipsFollowTheActiveScheme`: `DARK chip fill expected:<2047859> but was:<15330545>` | Every redroid run, never on the emulator | Redroid differs from the AOSP ATD image in night-mode or theme setup. Needs a fix or a device setting before redroid can replace the emulator. |
| `MediaNotificationTest` | Redroid 1, one run; passes alone (`OK (1 test)`) | Flaky in a full run. |
| `ChaptersWithoutAudioTest`, `ImportProgressTest`, `ImportQueueUiTest` ×2 (no test count), `ControlsCentredTest`, `ThemeModeTest`, `BookUiTest` | Redroid pool of 3 only | Instrumentation failed under 98% CPU and full swap. A pool of 3 is too much for this host. |

A test that passes on one setup and fails on another is a finding: the backup test (emulator only), the theme test (redroid only), and the touch-injection test (loaded emulator pool only). The runner reports `rc=1` for the whole set because of the two deterministic failures, so a clean `EXIT=0` was not reached in any setup. I did not fix tests.

## Finding on the shared lock

The hub's lock stall was a leak, not a long test run. A command started under `flock run.lock bash -c "… emulator …"` left the emulator's processes holding the lock's open file description, while the process that took it was gone (`/proc/locks` owner dead). Four queued `ui-tests.sh` runs waited behind it from 17:01 until the hub killed the leaking emulator. `emulator.sh watch` also never idled out, because its non-blocking `flock` always failed. Starting an emulator or any daemon under `flock` leaks the lock; `flock --close` (`-o`) or `9>&-` avoids it, as `emulator.sh` already does for fds 8 and 9.

## Pool runner

- `scripts/ui-tests-pool.sh`: builds once, installs both APKs on each device in `POOL_SERIALS`, and has one worker per device claim the next class (`mkdir` claim) from a longest-first queue. It prints one line per class and one summary line. It does not take `run.lock`.
- `scripts/redroid-pool.sh start N | stop | serials`: starts N containers, waits for boot, prints the serials.
- `scripts/measure-set.sh` and `scripts/measure-sampler.sh`: the sampler and wrapper used for the table above.
- Tests are pool-safe as written: they use `Engines.*` fakes, do not read `ANDROID_SERIAL` themselves, and run in the app's own data per device. The runner passes `-s <serial>` to every adb call, and ForceStopTest's kill step uses the same serial.

## Recommendation

1. **Use a pool of 2 redroid containers.** Expected saving: about 6 minutes per full run (913 s → 543 s, −40%), at about the same memory as today (2.8 GiB for two containers against 2.8 GB for one emulator). Pool of 1 redroid already saves about 5 minutes and 1 GB, so memory-only cases can use it.
2. **Do not use an emulator pool.** It saves less (−24%) and doubles the memory to 5.8 GB, which swaps.
3. **Do not use 3 instances** on this host.
4. **Before switching:**
   - Fix `ThemeModeTest` on redroid (dark-mode setup) or run that class on the emulator.
   - Make `binder_linux` and the binderfs mount persistent across reboots (needs root; Alex).
   - Decide what to do about the two deterministic `BookUiTest` failures, which exist today.
5. **Change `scripts/ui-tests.sh`** (not done here; Alex has not agreed to replace it): replace the single global `run.lock` with one lock per device. A run claims the first free device from the pool (`flock --nonblock` over the devices), so two sessions run at once instead of queueing, and use `flock --close` wherever a daemon is started. Add the pool's `start`/idle-stop to `emulator.sh` or call `redroid-pool.sh`. Containers cost nothing while stopped (`docker rm`), so stop the pool after 30 idle minutes like the emulator.
6. **Habits for other sessions:** hold no lock while building (`ui-tests.sh` already builds before it locks); never start long-lived processes inside `flock … command`; give a per-class run (`ui-tests.sh Class`) priority over a full smoke run.

`docker checkpoint` (CRIU): one attempt, `docker checkpoint create rd1 cp1` →

```
docker checkpoint create is only supported on a Docker daemon with experimental features enabled
```

`criu` is also not installed (`which criu` is empty). Both need root, so I stopped. It is not needed anyway: a container boots in 21–27 s and a pool can stay warm.

## Robolectric (not built, estimate only)

Compose UI tests that need no real audio, share sheet or notifications could run on the JVM in a few seconds per class. The tests that need the device (ForceStopTest, MediaNotificationTest, AppIconTest, OCR) would stay on devices. No measurement was made; treat any saving as unknown until one class is ported.

## Not measured

- A clean pass: no setup reached `EXIT=0`, so wall times include the deterministic failures' timeouts.
- Cold boot of an emulator with a smaller `-memory`, and `-gpu off` or fewer cores per emulator.
- Whether the swap pressure from other processes (3.5 GB used before the runs) caused the extra flakes, and how the setups compare on a host with free swap.
- Redroid with hardware GPU modes, `docker --memory` limits, or an image with the test APKs preinstalled.
- Gradle build memory and time with the pool (`BUILD=1–19 s`, always warm).
- Redroid pool run twice at size 1 and 3, and the emulator pool at size 3.
- Load still present during the runs: no other session ran builds or tests after I asked them to hold (I did not verify each), my own Gradle daemon stayed up, and the desktop app, the host's `htop` and the compositor kept running. Load average before the first baseline was 3.60, from my own warm-up build.
