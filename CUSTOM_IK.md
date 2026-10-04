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


## Current custom version: articulated retargeting + Spring Bones

This version expands the original position-retargeting experiment into a broader
**Custom IK** workspace for tuning how SlimeVR's solved body is exported and
visually inspected.

The main additions in this version are:

- decoupled physical and virtual tracker positions,
- independent articulated torso interpolation,
- pelvis/floor-lift decoupling,
- a visual tracker placement editor,
- a new **Spring Bones** secondary-motion system,
- a dedicated spring-motion close-up renderer,
- safer WebGL renderer lifecycle handling when switching between Custom IK
  subtabs,
- persistent configuration over the existing SlimeVR websocket without changing
  the SolarXR binary protocol.

The Custom IK UI is split into two subtabs:

1. **Retargeting / Spine**
2. **Spring Bones**

These tabs intentionally solve different problems and now own separate rendering
lifecycles.

### Retargeting / Spine tab

This is the full-body adjustment workspace.

It contains:

- SteamVR output mode selection:
  - **Physical source positions**
  - **Virtualized positions**
- articulated spine controls,
- lower-body floor-lift controls,
- per-tracker virtual position adjustment,
- body-yaw or world-space position offsets,
- per-axis X/Y/Z controls,
- paired-limb copy and X-mirror helpers,
- full-body skeleton visualization,
- source tracker discs,
- virtual tracker discs,
- positional delta lines,
- upper-chest/chest/waist/hip spine-joint markers.

The full-body/disc viewer lives **only on this tab**. It is the reference view
for understanding where the selected tracker point sits on the actual solved
body.

### Spring Bones tab

This version adds a dedicated **Spring Bones** system for bounded secondary
motion.

A Spring Bone does not alter the anatomical skeleton. It also does not alter
tracker rotation.

Instead, it adds a small dynamic offset only to the exported tracker's **world Y
position** after the normal solve and after optional position retargeting.

The runtime order is:

```text
SlimeVR anatomical solve
        |
        v
computed tracker position
        |
        v
optional retarget position
        |
        v
Spring Bone Y offset
        |
        v
SteamVR position
```

Tracker rotation follows an independent path:

```text
SlimeVR computed quaternion
        |
        v
SteamVR quaternion
```

This means Spring Bones cannot change tracker pitch, yaw, or roll.

They also cannot directly change X or Z.

### Spring Bone controls

Each supported exported tracker role can have its own spring configuration.

The current supported roles are:

- Hip / Waist
- Chest
- Left knee
- Right knee
- Left foot
- Right foot
- Left elbow
- Right elbow
- Left hand
- Right hand

Each role has:

- **Enable spring on this tracker**
- **Spring distance**
- **Strength**
- **Pull**

There is also a global:

- **Enable spring bones on SteamVR output**

toggle.

All spring behavior defaults to disabled.

#### Spring distance

Spring distance is the hard maximum vertical displacement above or below the
solved tracker point.

For example, a distance of `0.03 m` gives the point a maximum freedom of:

```text
+3 cm
  |
  |
rest position
  |
  |
-3 cm
```

The runtime spring cannot move outside this interval.

#### Strength

Strength controls the return stiffness of the oscillator.

Higher values:

- return toward the solved point faster,
- produce a tighter, faster oscillation,
- make the tracker feel more firmly attached.

Lower values:

- return more slowly,
- create a softer secondary motion,
- allow a longer visible bounce.

#### Pull

Pull controls how strongly changes in the solved point's vertical velocity inject
motion into the spring.

Higher values cause starts, stops, crouches, rises, bends, impacts, and other
vertical acceleration changes to produce a stronger spring reaction.

At `0`, body motion cannot kick the spring.

### IMU accelerometer-driven Spring Bones

Spring Bones can now optionally use the physical tracker's IMU acceleration as
their fast motion driver.

The Spring Bones tab exposes:

- **Use physical IMU accelerometer when available**

When this toggle is disabled, the spring uses the original solved-position
driver:

```text
solved Y position
    |
    v
derived vertical velocity
    |
    v
change in velocity
    |
    v
spring impulse
```

When the toggle is enabled, each exported role tries to find the nearest usable
physical IMU assigned to that body point.

Examples:

- Chest prefers chest, then upper-chest, waist, and hip IMUs.
- Hip / Waist prefers hip, then waist, chest, and upper-chest IMUs.
- Knee points prefer the corresponding lower-leg / upper-leg IMUs.
- Foot points prefer the corresponding foot / lower-leg IMUs.
- Elbow and hand points use the nearest corresponding arm/hand IMUs.

If no usable acceleration sample is available for a role, that role
automatically falls back to the solved-position driver. Enabling accelerometer
mode therefore cannot make a Spring Bone stop working simply because one body
point lacks acceleration data.

The physical tracker acceleration is obtained through SlimeVR's existing
`Tracker.getAcceleration()` path. This transforms the raw IMU acceleration
into SlimeVR/world reference space.

The existing SlimeVR code notes that this reference-space acceleration can
retain an unknown heading/yaw offset. Spring Bones only consume the world
**Y-axis** component, so heading/yaw ambiguity does not alter the vertical
component used by this feature.

#### Gravity and sensor-bias removal

Raw IMU acceleration is not treated as gravity-free linear acceleration.

The Spring Bone processor maintains a slowly adapting Y-axis baseline and
subtracts it from the current acceleration sample:

```text
world/reference accel Y
        |
        +------ slow baseline ------> gravity + stationary bias
        |
        v
current Y - baseline
        |
        v
dynamic vertical acceleration
```

The baseline initializes from the first valid acceleration sample, preventing an
immediate kick when accelerometer mode is enabled.

The dynamic signal then passes through:

- a small stationary-noise deadzone,
- a bounded +/- acceleration clamp,
- a fast low-pass filter.

The processor also normalizes the dynamic signal against the observed static
gravity scale. This makes the spring driver more tolerant of tracker transports
that represent acceleration with slightly different numeric scaling.

#### Hybrid driver

Accelerometer mode does not replace the solved position.

The solved tracker position remains the authoritative spring center/rest point.

The IMU contributes only transient excitation:

```text
physical IMU acceleration
        |
        v
gravity/bias removal
        |
        v
filtered dynamic Y acceleration
        |
        v
velocity impulse
        |
        +--------------------+
                             |
solved tracker position -----+----> bounded Spring Bone ----> SteamVR Y
```

A small fraction of the solved-position derivative remains active as a
stabilizing cross-check while the IMU supplies the high-frequency motion.

The system never integrates accelerometer data into absolute tracker position.
This avoids the drift that would occur with double integration.

In practical terms:

- the solved SlimeVR point controls **where the spring belongs**,
- the accelerometer controls **how sharply motion excites it**,
- spring distance still controls the hard positional limit,
- strength still controls return stiffness,
- pull now controls how strongly measured acceleration excites the oscillator.

The close-up renderer displays the selected driver mode as either:

- **IMU preferred + fallback**, or
- **Position derived**.

### Acceleration derivative motion driver

Spring Bones can optionally shape IMU excitation using a filtered acceleration
derivative hierarchy:

```text
filtered acceleration A
        |
        v
first derivative
        |
        v
filtered jerk J
        |
        v
second derivative
        |
        v
filtered snap S
```

This is enabled per Spring Bone with:

- **Use acceleration derivative motion driver**

The derivative driver is only active when the global physical-IMU acceleration
mode is enabled and a usable local acceleration source exists. If acceleration
is unavailable, that tracker still falls back to its own independent
position-derived spring driver.

The default derivative influence mix is:

```text
Acceleration  0.20
Jerk          0.70
Snap          0.10
```

The three values are treated as relative weights and normalized before the
combined drive is applied. Increasing all three values equally therefore does
not accidentally multiply the spring force.

#### Why derivatives are used

Raw acceleration can remain elevated for several frames and continuously pump a
spring. The first derivative of acceleration, jerk, emphasizes the beginning,
ending, and reversal of a force instead:

```text
acceleration:
      +------------+
------+            +------

jerk:
      ^            v
------+------------+------
```

The second derivative, snap, adds information about how sharply the jerk itself
changes.

This lets the spring respond strongly to motion transitions without treating a
sustained acceleration plateau as an equally sustained secondary-motion force.

#### Filtered derivative hierarchy

Differentiation amplifies high-frequency IMU noise, so jerk and snap are never
calculated from unfiltered raw acceleration.

The runtime path is:

```text
physical IMU world-Y acceleration
        |
        v
gravity / stationary-bias removal
        |
        v
deadzone + bounded dynamic acceleration
        |
        v
filtered A
        |
        +---- derivative ----> filtered J
                                |
                                +---- derivative ----> filtered S
```

Each derivative stage has its own low-pass filter.

The **Motion response** control changes these filter rates:

- lower values favor smoother, slower derivative estimates,
- higher values preserve shorter and sharper motion transients.

#### Unit normalization

Acceleration, jerk, and snap have different physical units and cannot be added
directly without one derivative order dominating numerically.

The driver therefore converts jerk and snap back into acceleration-like
normalized terms using a characteristic response time `tau`:

```text
A_term = A
J_term = J * tau
S_term = S * tau^2
```

The response control changes `tau` together with the derivative filter
bandwidth.

The weighted normalized drive is conceptually:

```text
drive =
    (wa * A_term
   + wj * J_term
   + ws * S_term)
    / (wa + wj + ws)
```

The combined result then passes through a smooth `tanh` saturation instead of
a sharp hard clip before being converted into spring velocity.

This preserves small-signal precision while preventing a noisy derivative spike
from producing an extreme spring impulse.

#### Independent derivative histories

Every Spring Bone role owns its own acceleration, jerk, and snap history.

For example:

```text
CHEST:
  A_chest
  J_chest
  S_chest

WAIST:
  A_waist
  J_waist
  S_waist
```

Derivative state is never propagated down the skeleton and is never inherited
from another Spring Bone.

The already-strict physical IMU affinity rules remain in effect, so a chest
spring does not borrow hip/waist acceleration merely to stay in accelerometer
mode.

This is specifically intended to prevent multiple torso springs from sharing
one excitation source, drifting into different phases, and creating apparent
destructive harmonics in downstream avatar IK.

#### Derivative preview

The Spring Bones close-up now contains a **Derivative preview** panel.

It renders normalized bars for:

- Acceleration
- Jerk
- Snap
- Combined drive

The close-up generates a deterministic synthetic acceleration pulse and runs it
through the same filtering, derivative normalization, influence weighting, and
soft-clamping structure used by the runtime Spring Bone driver.

This remains a parameter preview rather than live tracker telemetry, but it
makes the effect of the derivative mix and Smooth-to-Reactive response control
visible without requiring repeated physical motion.

### Spring motion model

The Spring Bone system is impulse-driven rather than being a simple positional
smoothing filter.

The solved tracker position itself is never delayed or smoothed.

Instead, the processor observes changes in vertical velocity and converts part
of that change into spring velocity.

This is important because it means:

- slow steady motion remains close to the true SlimeVR position,
- sudden starts/stops create visible secondary motion,
- the spring can oscillate without making all movement feel delayed,
- the original anatomical tracking remains authoritative.

The oscillator uses bounded semi-implicit integration and a damping term.

A long frame gap or stalled bridge resets the spring state instead of allowing
stale velocity to accumulate. This prevents a paused or reconnecting bridge from
creating an extreme launch impulse when updates resume.

### Spring presets

The UI includes three convenience presets:

- **Subtle**
  - 1.5 cm distance
  - strength 18
  - pull 0.35
- **Soft**
  - 3.0 cm distance
  - strength 12
  - pull 0.65
- **Loose**
  - 6.0 cm distance
  - strength 7
  - pull 1.10

These are starting points only. Every value remains independently adjustable.

### Dedicated Spring Bones visualizer

The Spring Bones tab now uses a dedicated close-up renderer instead of reusing
the full-body skeleton viewer.

The close-up is an orthographic local-space view centered on the currently
selected spring point.

It displays:

- the tracker-disc radius,
- the solved/rest disc as a cyan wireframe disc,
- the moving spring disc in magenta,
- the vertical Y travel rail,
- the rest/center line,
- upper and lower hard-limit rings,
- the configured +/- travel distance,
- the current strength and pull values.

The close-up periodically injects a deterministic preview impulse using the
current spring settings.

This animation is a **parameter preview**, not live tracker telemetry.

Its purpose is to make the mechanical meaning of distance, strength, and pull
visually understandable without requiring the user to physically move every time
a slider is adjusted.

### Visualizer ownership and tab behavior

The Custom IK visualizers are intentionally separated:

```text
Retargeting / Spine
    |
    +-- full body skeleton
    +-- source discs
    +-- virtual discs
    +-- delta lines
    +-- spine joints

Spring Bones
    |
    +-- spring close-up only
```

The full-body viewer is not duplicated on the Spring Bones tab.

This avoids making one WebGL scene serve two unrelated tuning tasks.

### Renderer lifecycle fix

This version also changes how the Custom IK visualizers are mounted.

Previously the Retargeting / Spine viewer remained mounted and was only hidden
with CSS when switching to Spring Bones.

That could leave Three.js rendering into a hidden or zero-sized canvas and could
cause stale, blank, or incorrectly sized views when returning to the first tab.

The tabs now use true conditional mounting.

Switching away from a tab destroys its renderer and switching back creates a
fresh renderer.

The skeleton visualizer cleanup now explicitly:

- disconnects its ResizeObserver,
- removes mouse enter/leave listeners,
- destroys the exact Three.js context created by that mount,
- disposes geometries and materials,
- releases the WebGL renderer,
- clears the active context reference.

The Spring Bone close-up uses the opposite rule for slider changes: it keeps one
WebGL renderer alive while distance, strength, and pull are being adjusted.

Slider updates therefore change only spring parameters and geometry rather than
destroying and recreating the entire renderer on every input event.

Changing to a different spring role intentionally remounts the close-up because
that represents a different spring point.

### Configuration and persistence

The new Spring Bone settings are stored alongside the existing SteamVR bridge
configuration.

Per-role configuration stores:

```text
enabled
distance
strength
pull
```

The global bridge configuration also stores:

```text
springBonesEnabled
```

The existing Custom IK JSON control path over the SlimeVR websocket carries
these values between the GUI and server.

The normal SolarXR FlatBuffer protocol remains unchanged.

This keeps the fork easier to rebase and avoids requiring a protocol schema
change for settings that are specific to this custom server.

### Separation guarantees in this version

The intended separation is now:

```text
ANATOMICAL SOLVE
    |
    +-- skeleton rotations
    +-- computed tracker rotations
    +-- computed tracker positions
                |
                v
        optional position retarget
                |
                v
        optional Spring Bone Y motion
                |
                v
          SteamVR XYZ output
```

while rotation remains:

```text
computed tracker quaternion
        |
        v
SteamVR quaternion
```

Therefore:

- virtual position offsets do not deform the physical skeleton,
- Spring Bones do not deform the physical skeleton,
- Spring Bones do not affect tracker rotation,
- tracker retargeting does not affect tracker rotation,
- calibration data remains separate from avatar-specific output placement,
- Spring Bones can be enabled or disabled without altering the underlying body
  solve.


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


### Spring Bones visualizer layout

The full-body disc/skeleton visualizer now belongs exclusively to the
**Retargeting / Spine** tab.

The **Spring Bones** tab contains only the dedicated spring close-up renderer.

This separation is intentional:

- Retargeting / Spine answers **where is this tracker point on the body?**
- Spring Bones answers **how can this selected point move along its spring
  freedom?**

The Spring Bone close-up is a local-space orthographic renderer centered on the
selected tracker disc. It renders the same 5.8 cm preview disc radius used by
the tracker overlay, the Y-axis travel rail, the rest position, upper/lower
hard-limit rings, and a moving preview disc.

The close-up injects a deterministic periodic vertical-motion impulse using the
current spring distance/strength/pull values so the spring response can be seen
without requiring the user to physically move. It is explicitly a parameter
preview, not live spring telemetry.

The two tabs also have independent WebGL lifecycles. The Retargeting / Spine
renderer is fully unmounted when Spring Bones is selected, and rebuilt when the
user returns. The spring close-up remains stable while its sliders are adjusted
and only remounts when the selected tracker role changes.
