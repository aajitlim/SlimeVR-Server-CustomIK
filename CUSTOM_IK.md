# Custom IK: decoupled tracker position export

This branch adds a first-pass SteamVR output retarget layer that keeps SlimeVR's
anatomical rotation solve authoritative while allowing avatar/game tracker
positions to be moved independently.

## What is separated

- **Rotation:** unchanged SlimeVR computed tracker quaternion.
- **Position:** optional export-only offset applied when the SteamVR protobuf
  message is built.
- **IK / LegTweaks:** never receive the export offset.
- **Computed tracker object:** never mutated by the export offset.

This is specifically intended for avatar alignment cases where moving a SteamVR
tracker point to the avatar's expected hip/knee/foot point should not bend or
rotate SlimeVR's solved body to satisfy that game-space point.

## Configuration

The feature is off by default. In the SteamVR bridge section of the config, use:

```yaml
positionRetargetingEnabled: true
trackerPositionOffsets:
  waist:
    enabled: true
    x: 0.0
    y: 0.0
    z: 0.05
    space: body_yaw
  left_knee:
    enabled: true
    x: 0.0
    y: 0.0
    z: 0.02
    space: body_yaw
  right_knee:
    enabled: true
    x: 0.0
    y: 0.0
    z: 0.02
    space: body_yaw
```

Offsets are metres.

### Spaces

- `body_yaw`: offset rotates with the user's horizontal facing direction. This
  is the normal choice for forward/back/left/right avatar tracker placement.
- `world`: fixed world-space offset, mostly useful for debugging.

The sign of body-local Z should be tuned against the actual SteamVR/avatar
orientation. The important property is that changing this value moves only the
exported position; tracker rotation remains untouched.

## Next lower-body work

The existing `LegTweaks.correctClipping()` separately raises foot/knee positions
and also raises the hip by `avgOffset / 2 * WAIST_PUSH_WEIGHT`. That is a
physical-output correction path, not this export retarget layer. It will be
handled independently so ground anchoring can be improved without coupling it
to avatar tracker placement.

## Hip lift decoupling

Upstream leg floor clipping raises the hip whenever a foot has to be pushed
above the calibrated floor. That can make a forward bend look like the whole
body is being lifted.

This branch adds `legTweaks.hipFloorLiftWeight`:

- `0.0` (custom default): floor clipping never lifts the pelvis.
- `0.2`: reproduces the previous upstream behavior.
- `1.0`: full average foot displacement is allowed to influence the pelvis.

Feet and knees still receive the existing floor correction. This setting only
controls the secondary pelvis lift contribution.


## Visual retarget editor

The GUI now includes **Settings -> Tracker retargeting**.

It uses the same persisted SteamVR bridge configuration as the exporter. The
editor can:

- enable/disable the export-only retarget layer,
- select hip/waist, chest, knees, feet, elbows, or hands,
- edit X/Y/Z offsets in centimetres,
- choose `body_yaw` or `world` position space,
- tune the lower-body `hipFloorLiftWeight`, and
- preview the resulting output targets directly on the skeleton visualizer.

The skeleton remains the anatomical SlimeVR solve. Cyan discs are the virtual
SteamVR output positions. The selected disc is highlighted, and a line connects
the original computed tracker position to the retargeted output position. The
disc keeps the original computed tracker rotation, so positional retargeting is
visually separate from rotational tracking.

The editor communicates over a small JSON text channel on the existing
websocket. Existing SolarXR binary protocol messages are unchanged, which keeps
this fork easier to rebase onto upstream SlimeVR.


## Articulated spine

The stock extended-spine model fills missing torso trackers with a small set of
special-case blends. In the common chest + hip case, the waist defaults to a
fixed chest-to-hip interpolation while the chest segments remain locked to the
chest orientation. This concentrates a large amount of angular change near the
pelvis.

The custom branch adds an articulated missing-segment solver over the existing
four torso bones:

`upperChest -> chest -> waist -> hip`

Every directly tracked torso bone remains authoritative. Missing bones are
sampled between the neighboring available anchors according to the configured
physical segment lengths. An inferred pelvis from the existing extended pelvis
model can also act as the lower anchor when no physical hip tracker is present.

With the stock torso lengths, a missing waist lies about 60% of the way between
the chest and hip rotation samples instead of the previous fixed 30% blend.
This spreads forward bend through the lumbar chain instead of leaving most of
the change for the hip.

The GUI exposes:

- **Distribute bend across untracked spine segments**
- **Spine bend distribution**

A curve power of `1.0` is length-linear. Values below `1.0` spread the bend
upward sooner; values above `1.0` keep the upper torso stiffer and move more of
the bend toward the lower anchor.


## Adjustment workflow

The completed custom workspace lives under **Settings -> Custom IK / Retargeting**.

### 1. Physical source mode

Choose **Physical source positions** while calibrating the normal SlimeVR body.
This leaves the SteamVR bridge on its original computed tracker positions.

In the visualizer:

- white spine joints show upper chest, chest, waist, and hip bend points,
- wireframe discs show the original SlimeVR computed tracker positions,
- the colored skeleton shows the anatomical solve.

### 2. Configure virtual targets

Keep Physical mode live while enabling individual custom tracker positions.
The solid cyan discs preview the configured game-space positions without sending
them to SteamVR yet. The selected role is yellow.

Delta lines connect each SlimeVR source disc to its configured virtual target.

For paired limbs, **Copy to opposite** copies the complete adjustment while
**Mirror X to opposite** copies Y/Z/space/enabled and negates lateral X.

### 3. Switch live output

Choose **Virtualized positions** when the preview targets are aligned with the
avatar anchors. This changes only the positions sent by the SteamVR bridge.
Tracker rotations remain the original SlimeVR computed quaternions.

Switching back to Physical mode does not delete the virtual target settings, so
the two modes can be compared repeatedly.

### 4. Tune spine and floor behavior

The same page exposes:

- articulated spine enable/disable,
- spine bend distribution curve power,
- hip floor-lift contribution,
- quick 0% decoupled and 20% upstream-like floor-lift presets.

**Reset all tracker targets** now resets only positional retarget targets.
**Reset solver controls** resets only the spine/floor custom controls.

## Visualizer legend

- **Colored skeleton:** SlimeVR anatomical bone solution.
- **White joint spheres:** upper chest, chest, waist, and hip bend points.
- **Wireframe discs:** original SlimeVR computed tracker positions.
- **Cyan solid discs:** configured virtual SteamVR tracker positions.
- **Yellow solid disc:** selected virtual tracker role.
- **Connecting lines:** positional delta from source to virtual target.

The virtual preview can remain visible while live SteamVR output is in Physical
mode. This is intentional so avatar targets can be adjusted without disturbing
the calibration/reference output.


## Spring Bones

The Custom IK workspace now has a dedicated **Spring Bones** subtab.

Spring Bones adds bounded secondary motion after the normal anatomical solve and
after optional position retargeting, immediately before SteamVR serialization.

The processing order is:

`computed position -> optional retarget position -> Y spring -> SteamVR XYZ`

Rotation bypasses the spring completely:

`computed quaternion -> SteamVR quaternion`

Each supported exported tracker role can independently enable a spring with:

- **Spring distance** — hard maximum vertical displacement above/below the
  solved point.
- **Strength** — return stiffness. Higher values return faster with a tighter
  oscillation.
- **Pull** — coupling from changes in solved vertical velocity into spring
  velocity.

The spring is impulse-driven rather than a simple positional smoothing filter.
Slow steady body motion therefore stays close to the anatomical solve, while
starts/stops, crouches, bends, landings, and other vertical acceleration changes
can produce small secondary motion.

Only world Y is modified. X, Z, the anatomical skeleton, calibration state, and
tracker quaternions are unchanged.

The runtime spring is bounded and resets after a long frame gap so resuming a
paused/stalled bridge cannot create a large accumulated impulse.

The Spring Bones visualizer shows a magenta line around the selected tracker
anchor representing its configured +/- travel limit. Individual roles default
to disabled, and the global Spring Bones output toggle also defaults to off.


### Spring Bones dual viewers

The Spring Bones subtab intentionally uses two separate renderers:

1. **Disc / body view** — the normal articulated skeleton with solved source
   discs and virtual target discs. This answers where the selected spring point
   is attached to the body.
2. **Spring motion close-up** — a dedicated local-space orthographic renderer
   centered on the selected tracker disc. It renders the same 5.8 cm preview
   disc radius used by the tracker overlay, the Y-axis travel rail, the rest
   position, upper/lower hard-limit rings, and a moving preview disc.

The close-up injects a deterministic periodic vertical-motion impulse using the
current spring distance/strength/pull values so the spring response can be seen
without requiring the user to physically move. It is explicitly a parameter
preview, not live spring telemetry.
