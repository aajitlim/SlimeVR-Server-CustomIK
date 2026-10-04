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
