# Spandan — Offline Disaster-Response BLE Mesh

Android-only, Kotlin, offline-first mesh network. Trapped/stranded people broadcast
distress packets over BLE; nearby phones relay hop-by-hop to a perimeter responder
(gateway). No internet, no cloud, no Play Services dependency for core function.
Built for a hackathon: networking core first, UI is a placeholder.

## Non-negotiables

- Kotlin, native Android. minSdk 26, target latest stable SDK.
- No network calls anywhere in the core. Airplane mode + BT on must fully work.
- No Google Play Services dependency for core mesh function.
- Android 12+ runtime permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`) with `neverForLocation` where applicable; pre-12 falls back to
  `ACCESS_FINE_LOCATION` for scan. Both paths must be handled explicitly, gated on
  `Build.VERSION.SDK_INT`.
- Emulators can't advertise BLE. All protocol logic must be testable off-device via
  a pure-JVM module + in-memory fake transport. Real hardware required to validate
  the Android BLE plumbing itself.

## Module layout

- `:mesh-core` — pure Kotlin, **no Android imports**. Packet encode/decode, dedup
  cache, relay election, severity rules, ack propagation logic. This is the module
  that later drives a large-N simulator, so it must not leak any `android.*` or
  `Bluetooth*` type into its public API. Transport is an interface this module
  depends on; Android and the simulator each supply an implementation.
- `:app` — Android application. `BluetoothLeAdvertiser`/`BluetoothLeScanner` plumbing,
  foreground service, permissions, Compose UI. Implements `mesh-core`'s transport
  interface by pushing bytes in/out of BLE advertisements.
- `:mesh-core` test source set — JVM unit tests, table/property-based, no device
  or emulator needed. This is the primary test target during development.

## Why legacy advertising, not GATT connections

Distress beacons must be non-connectable, best-effort, and require no pairing or
connection setup — a GATT connection is 1:1 and too slow/fragile for many nodes
broadcasting into a crowded channel. Everything rides in the advertisement payload
itself (`BluetoothLeAdvertiser` with `AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY`,
non-connectable, non-scannable). Scan side uses a `ScanFilter` matching our
manufacturer ID so we never process unrelated BLE traffic in the room.

**Manufacturer-specific data, not 16-bit service data.** Reasoning: service data
under a real 16-bit GATT service UUID implies registration/semantics we don't need,
and manufacturer-specific data gives us the full byte budget under one AD structure
with a 2-byte company-ID prefix we fully control as a packet magic/version discriminator.

**Known compliance wrinkle, flagging explicitly:** manufacturer-specific data is
supposed to use a company ID assigned by the Bluetooth SIG. We do not have one and
are not registering one for a hackathon. We use `0xFFFF` (reserved by the spec for
testing, never assigned to a real company) as our company ID. This is fine for a
demo on our own hardware; it is not spec-compliant and could theoretically collide
with another unregistered device's test traffic in the same room. Not a blocker,
just don't ship this to production as-is.

## Advertisement byte budget

Legacy (non-extended) advertising: 31 bytes total per PDU.
- Flags AD structure: 3 bytes (length + type + flags byte) — required for
  discoverability mode, added by the stack automatically.
- Manufacturer-specific-data AD structure header: length byte + type byte + 2-byte
  company ID = 4 bytes.
- Remaining payload: **31 − 3 − 4 = 24 bytes.**

We do not use BLE 5 extended advertising (bigger payload) because not all target
devices/chipsets support it reliably and the brief requires this to work on
whatever real hardware is on hand — legacy advertising is the safe common
denominator.

## Packet format (`:mesh-core`, `SpandanPacket`)

Fits in **16 bytes**, well inside the 24-byte legacy budget (8 bytes of headroom
reserved for a future fragment-2 / extended-fields AD structure — e.g. richer ack
metadata — without touching the fragment-1 layout).

| Field | Bits | Notes |
|---|---|---|
| `msg_type` | 2 | 0=SOS, 1=RELAY_META (presence heartbeat — single-hop only, never flood-relayed), 2=ACK, 3=COMMAND_MESSAGE (reverse-channel, gateway-originated, flood-relayed like SOS) |
| `protocol_version` | 3 | forward-compat, up to 8 versions |
| `hazard_category` | 2 | trapped / stranded / medical / other |
| `severity` | 3 | 0 (info) – 7 (critical), victim-declared |
| `origin_id` | 16 | rotating pseudonymous ID, regenerated on each app-level "session" (see below) — never MAC/IMEI |
| `msg_id` | 16 | random per-origin nonce; uniqueness key for dedup is **(origin_id, msg_id)**, not msg_id alone |
| `gps_valid` | 1 | 0 = no fix, ignore lat/lon |
| `lat_q` | 24 | signed, quantized: `round(lat * 2^23 / 90)` (lat spans ±90°, uses full 24-bit range) → ~1.2 m resolution |
| `lon_q` | 24 | signed, quantized: `round(lon * 2^23 / 180)` (lon spans ±180°) → ~2.4 m resolution at the equator |
| `baro_valid` | 1 | 0 = no barometer on this device, ignore baro_delta |
| `baro_delta` | 8 | signed, units of 0.1 hPa vs. device's own boot-time baseline; ±12.7 hPa range, enough to see "fell/is buried" pressure shifts |
| `battery_bucket` | 3 | 8 buckets, ~12.5% each — battery is a role-election input, not a display exact-percent, so bucketing is correct not lossy-for-no-reason |
| `liveness_bucket` | 3 | time-since-last-motion, exponential buckets (e.g. <1min, <5min, <15min, <1h, <4h, <12h, <24h, >24h) — coarse is fine, this only gates relay-vs-beacon role |
| `origin_ts` | 8 | seconds-since-origination mod 256 (rolls every 256s / ~4.3min); used for freshness/jitter comparisons, not wall-clock. Full epoch millis doesn't fit and isn't needed — relative recency is all dedup/TTL logic needs |
| `hop_count` | 4 | 0–15; incremented per relay, compared against severity-weighted hop TTL |
| `phrase` | 4 | canned distress phrase (`CannedPhrase` enum, 16 slots incl. NONE) — see below |
| reserved | 2 | pad to 128 bits, future use |

Total: 2+3+2+3+16+16+1+24+24+1+8+3+3+8+4+4+2 = **128 bits = 16 bytes**. Byte-aligned,
leaves 8 bytes of the 24-byte budget unused in v1.

**Why canned phrases instead of audio:** live/recorded audio doesn't fit this
architecture — even heavily compressed speech (Opus at ~6kbps) needs
throughput orders of magnitude past what a ~24-byte-every-few-seconds legacy
advertisement can carry, and a connected GATT link fast enough for audio would
mean abandoning the broadcast/flood-relay model per link (1:1, no mesh).
Bluetooth LE Audio's broadcast mode (Auracast) is built for real audio
broadcast but is one-source-to-many, not multi-hop relayed, and needs
LE-Audio-capable hardware/OS on every node — doesn't fit a flood-relay mesh
either. `CannedPhrase` is the practical substitute: a fixed vocabulary (16
slots) costing 4 bits instead of a live stream — `NEED_WATER`,
`NEED_MEDICAL_EVAC`, `BLEEDING`, `CANT_MOVE`, `TRAPPED_LIMB`,
`STRUCTURE_UNSTABLE`, `FIRE_NEARBY`, `SMOKE_PRESENT`, `WATER_RISING`,
`LOW_OXYGEN`, `WITH_CHILD`, `WITH_ELDERLY`, `HEAR_RESCUERS`, `PLEASE_HURRY`,
`OTHER_HAZARD`, plus `NONE`.

**`msg_id` collision resistance:** brief calls for "collision-resistant enough for a
few thousand nodes." A bare 16-bit `msg_id` has a ~1-in-few-thousand birthday
collision risk per origin, which would be a real problem if `msg_id` alone were the
dedup key. Fix: dedup key is the **pair** `(origin_id, msg_id)` — 32 bits of entropy
— which is what actually needs to be collision-resistant across the whole mesh, and
32 bits comfortably clears "a few thousand nodes ever." `origin_id` itself only
needs to avoid collision among concurrently-active nodes in one mesh (dozens–low
hundreds), which 16 bits handles fine.

**`origin_id` rotation:** not a permanent identifier. Generated fresh (random 16
bits) once per mesh session — i.e. on "start mesh" tap — and held for the whole
session. Decided: simplest option, keeps dedup/ack continuity intact for an SOS's
whole lifecycle; anti-tracking is a secondary concern next to "the ack has to
actually find its way back" for a hackathon demo.

**Ack packets (`msg_type = ACK`)** reuse the same 16-byte envelope: `origin_id`
carries the *original SOS's* origin_id (i.e. "this ack is for you"), `msg_id`
echoes the SOS's msg_id, `hop_count` counts ack hops back, other fields are
reserved/zero. This means ack routing is just "does this node recognize
origin_id/msg_id as one it's seen and cares about" — no separate ack-routing table
needed beyond the existing seen-packet cache.

**Correctness note:** because an ACK deliberately reuses the SOS's `origin_id`
and `msg_id`, the dedup key **must** include `msg_type` — `dedupKey() =
(msgType << 32) | (originId << 16) | msgId`. Without `msgType` in the key, every
node that already cached the SOS would see the ACK's key as an existing duplicate
and drop it on arrival, and the ack could never propagate back through the mesh.

**`MeshNode.updateActivePhrase(msgId, phrase)`** — lets the UI attach a canned
phrase to an SOS after it's already firing, without starting a new SOS
(msgId/dedupKey unchanged). Added for the victim-facing UI layer, which needs
to fire instantly on category tap and let phrase selection happen
non-blockingly afterward. **Known limitation, inherent to flood dedup, not a
bug:** a neighbour that already relayed the pre-update packet will drop the
phrase-updated resend as a duplicate (dedup key deliberately excludes phrase —
it's not part of packet identity). So an attached phrase only reaches
neighbours encountered *after* the update, never nodes that already relayed
the original. Acceptable for a hackathon demo; a real fix would need a
separate small "amendment" packet type, not attempted here.

**Reverse-channel command messages (`msg_type = COMMAND_MESSAGE`)** — added for
the victim-facing UI's "Messages from command" screen. Rescue command
(gateway node) originates a `CommandMessage` (a fixed vocabulary — `STAY_PUT`,
`EVACUATE_NOW`, `HELP_EN_ROUTE`, etc., same reasoning as `CannedPhrase`: no
room for free text in 16 bytes). **Reuses the same 4-bit `phrase` wire slot as
`CannedPhrase`** rather than taking new bits — a packet's 4-bit payload means
`CannedPhrase` when `msg_type = SOS` and `CommandMessage` when
`msg_type = COMMAND_MESSAGE`, exactly the way `origin_id`/`msg_id` already
mean different things on an SOS vs. its ACK. `SpandanPacket.commandMessage` is
a computed view over the same bits `phrase` reads, so no encode/decode changes
were needed for this reuse.

Gateway-only origination (`MeshNode.originateCommandMessage` throws if
`!isGateway`) — a normal relay node has no business injecting instructions
into the mesh. Flood-relayed exactly like an SOS, not single-hop like the
presence heartbeat, since a command message needs to reach victims who may be
several hops from the gateway. Uses the finite-repeat path (same as an ACK):
enough redundancy to be picked up by the flood once, no ack-loop of its own —
command messages aren't themselves acknowledged.

## Dedup, cache, relay (Stage 3+)

- Seen-packet cache keyed on `(origin_id, msg_id)`, bounded size, in `:mesh-core`.
- Never re-emit a packet whose key is already in cache.
- On receive: if new, cache it, increment `hop_count`, schedule a rebroadcast after
  `base_interval ± jitter` (jitter is mandatory — synchronized relays collide).
- TTL check: drop (don't cache, don't relay) if `hop_count` already at/over the
  severity-weighted hop ceiling.
- Eviction when cache is full: lowest `severity` first, then oldest `origin_ts`
  among ties.
- All of this lives in `:mesh-core` against a `Transport` interface — Android's BLE
  advertiser/scanner and the JVM fake transport both implement the same interface,
  so relay/dedup/election code is identical in both.

## Severity-weighted config (Stage 4)

Single `SeverityConfig` data class (or similar) mapping `severity -> {rebroadcast
interval, cache TTL, hop TTL, eviction weight}`. One flag flips between "naive
flooding" (flat config, ignore severity) and "weighted" (real curve) for demo
purposes.

## Gateway designation (Stage 7)

Manual: a UI toggle ("I am gateway") on the responder's phone. No auto-detection.
That node, on receiving any SOS, emits an ACK packet (see packet format above)
which relays back through the mesh like any other packet, keyed to the SOS's
`(origin_id, msg_id)`. Origin node recognizes an ACK matching its own `origin_id`
and flips its own UI state from "broadcasting" to "acknowledged."

## Role election (Stage 6)

Inputs: battery bucket, observed neighbour count, motionless-duration. Roles:
`RELAY` / `BEACON` / `DEEP_BEACON`. Also: advertising interval widens as observed
neighbour count rises (density-adaptive duty cycling), independent of role.

## Build & test commands

Environment: JDK 17 pinned via `gradle.properties` (`org.gradle.java.home`) — the
system default JDK (26) is too new for this AGP/Kotlin toolchain, so don't rely on
`JAVA_HOME`/`archlinux-java` here. Android SDK (cmdline-tools, platform 34,
build-tools 34.0.0) lives under `~/Android/Sdk`, pointed to by `local.properties`
(gitignored, machine-specific — regenerate with `echo sdk.dir=$ANDROID_SDK_ROOT >
local.properties` on a new machine). Gradle 8.9 via the committed wrapper.

- `./gradlew :mesh-core:test` — pure-JVM protocol/unit tests, no device needed. Run
  this constantly during Stage 2–4 development. Verified green: 9 tests incl. a
  2000-sample randomized round-trip and all documented boundary values.
- `./gradlew :app:assembleDebug` — build the APK for sideloading onto test phones.
  Verified green.
- `./gradlew :app:installDebug` — install to a connected/adb-visible device.
- Fake-transport simulation harness lives under `:mesh-core` test sources (or a
  `:mesh-sim` module if it grows beyond test-scope) — N virtual nodes, configurable
  adjacency graph and packet loss, drives the exact same relay/dedup/election code
  the app uses.

## mesh-core protocol layer — implemented (Stages 3, 4, 6, 7)

- `Transport`/`Clock`/`Scheduler` — the three seams that let identical logic run
  against real BLE or the fake-transport simulator.
- `SeenPacketCache` — dedup by `dedupKey()`, lazy TTL expiry (severity-scaled),
  pressure eviction (lowest severity first, then oldest).
- `SeverityConfig` — `NAIVE_FLOODING` vs `WEIGHTED`, swappable at runtime on
  `MeshNode.severityConfig` (the one-flag demo toggle Stage 4 asked for).
- `NeighbourTracker` + `DutyCycle.advertiseIntervalMs()` — density-adaptive
  interval widening, pure function of observed neighbour count.
- `RoleElection` — RELAY/BEACON/DEEP_BEACON from battery bucket + motionless
  duration; a node calls `MeshNode.updateRole(inputs)` periodically to re-evaluate.
- `MeshNode` — the orchestrator: `originateSos()`, incoming-packet handling
  (dedup → TTL → role-gated relay-with-jitter), gateway ack origination
  (`isGateway = true`), and ack recognition flipping `isAcknowledged(msgId)`.
- Fake-transport harness lives at `mesh-core/src/test/kotlin/dev/spandan/mesh/sim/`
  (`VirtualClock`, `FakeScheduler`, `FakeMeshNetwork`) — deterministic discrete-event
  simulation, not real sleeping, so thousands of nodes run at for-loop speed.
- 25 tests green covering: A→B→C single relay, no-double-relay under multiple
  paths, hop-TTL death, severity-extended reach, BEACON role refusing to relay,
  cache eviction/expiry, neighbour-window aging, role thresholds, duty-cycle
  cap, and the full ack round trip (including the dedup-key collision this
  would have hit without the `msgType` fix above).

## Presence heartbeat + nearby-devices roster (2026-08-30)

**Real gap caught by the user after Stage 7 verification:** the original
design only had `MeshNode` transmit when there was an SOS/ACK to send —
tapping Start didn't broadcast anything on its own, so two idle nodes never
discovered each other. Fixed by giving every node a continuous lightweight
presence beacon (`msgType = RELAY_META`, previously unused), sent immediately
on start and re-sent on `DutyCycle.advertiseIntervalMs(neighbourCount())` —
this is also exactly the density-adaptive duty cycling Stage 6 asked for, not
a separate mechanism. Heartbeats are single-hop only: never cached for
dedup/TTL, never flood-relayed (would turn idle presence into a broadcast
storm) — `handleIncoming` special-cases `RELAY_META` to record the neighbour
and return immediately.

`NeighbourTracker` upgraded from a bare count to a full roster
(`NeighbourInfo`: originId, last-seen time, RSSI, last hazard/severity/msgType),
exposed as `MeshNode.nearbyDevices()` and shown live in the UI. `Transport`'s
`onReceive` signature grew an `rssi: Int?` parameter to carry this through
from real BLE scans (simulator passes `null`, no radio to measure).

Verified on hardware: two phones, both idle (no SOS sent), each appears in
the other's "nearby devices" row within ~10s of tapping Start — fully
automatic, no pairing or discovery action needed.

## :app integration — Stages 5, 6, 7 wired (2026-08-30)

- `BleTransport` — bridges `MeshNode` to real BLE. A legacy advertiser only
  carries one payload at a time, but `MeshNode.send()` fires once per event
  (own SOS repeats, foreign relays, acks) and these can overlap, so sends are
  queued and advertised as ~1.5s back-to-back bursts rather than clobbered.
- `MeshService` — foreground service (Stage 5): owns the real `MeshNode` +
  `BleTransport`, samples battery via `ACTION_BATTERY_CHANGED`, samples motion
  via `TYPE_SIGNIFICANT_MOTION` (device without that sensor gets
  `motionlessMillis = 0`, i.e. never falsely demoted to DEEP_BEACON —
  deliberate choice over faking a stillness reading), re-evaluates role every
  10s, persistent notification, `START_STICKY`.
- MainActivity binds to the service: Start/Stop, SOS button + hazard-category
  picker + severity stepper, Gateway toggle, Weighted-vs-naive toggle, live
  role/battery/neighbour/status display, scrolling event log.

**Real-bug caught during hardware testing:** the first cut of `MeshNode`
capped an originated SOS's rebroadcast at a fixed `repeatCount` (3) regardless
of ack state — looked like a runaway "loop" on-device but was actually
stopping *too early* against the brief's intent ("broadcasting" should persist
until acknowledged, not for an arbitrary count). Fixed: an SOS now re-sends
indefinitely on jittered severity-weighted intervals until
`isAcknowledged(msgId)` is true; only non-SOS originations (i.e. gateway ACKs)
use the small finite repeat count, since an ACK just needs enough redundancy
to get picked up by the flood once.

**Verified end-to-end on real hardware** (Nothing 3a `origin=0x4351` sending,
Pixel 10a `origin=0x9FBA` as gateway): SOS sent → Pixel received, relayed
(`hop=1`), generated + sent ACK → Nothing 3a received the ACK, flipped
"broadcasting" → "acknowledged", and stopped repeating immediately. Dedup
visibly dropping the flood's duplicate copies (`DROPPED reason=DUPLICATE`) on
both sides throughout.

**Screen-off durability spot check:** locked the Nothing 3a's screen for 20s
while the mesh service was running; `dumpsys activity services` confirmed
`isForeground=true`, `types=0x10` (`connectedDevice`), process still alive
throughout. Not a multi-hour doze/battery-drain test — that needs real time
this session doesn't have — but the immediate "does it get killed the moment
the screen turns off" failure mode is ruled out.

## Stage 1 — verified on real hardware (2026-08-30)

Three phones: Nothing Phone 3a (Android, `origin_id 0xA174`), Samsung S23 Ultra
(`0xE438`), Pixel 10a (`0xEDCC`). All three advertising + scanning
simultaneously in the same room. Confirmed: S23 received Nothing 3a's packet
(`origin=0xa174 severity=5 hop=0`) live; both S23 and Nothing 3a also logged
packets from origin `0xedcc` (Pixel), including after the Pixel's USB/adb
connection dropped — the app kept advertising/scanning fine with no cable
attached, confirming this isn't an artifact of the debug tether.

Real RSSI observed at close range (same room, few meters, screens on):
**-43 to -63 dBm.** Gives a concrete anchor for range planning later — no
optimism needed, no walls tested yet.

Samsung-specific hiccup hit during setup: `AdvertiseCallback.onStartFailure`
with code 4 (`ADVERTISE_FAILED_INTERNAL_ERROR`) on the S23 right after fresh
install + permission grant. Fixed by toggling Bluetooth off/on once; did not
recur. Worth a retry-with-backoff in the advertiser wrapper before Stage 5
(foreground service) if it turns out to be common across Samsung devices.

## Known hard limits (not hidden, not silently worked around)

- BLE-through-walls range will be bad — expect single-digit to low-tens-of-meters
  indoors through rubble/walls, not open-air BLE range numbers. Real hardware
  testing will tell us the actual number; don't plan the demo around optimistic
  range.
- 24-byte legacy advertising budget is genuinely tight. Packet is designed to fit
  in 16 bytes precisely so we're not fighting the budget; if a future field doesn't
  fit, that will be raised explicitly rather than silently truncating something.
- Devices without a barometer report `baro_valid = 0` and the field is ignored,
  never a fake zero (zero is a valid delta reading).
- No GPS fix reports `gps_valid = 0`, same principle.
- MAC randomization is not fought — we never rely on MAC identity; `origin_id` is
  our own application-layer identifier for exactly this reason.
- iOS out of scope by design (no BLE peripheral-mode background advertising
  parity story on iOS worth building around for this).
