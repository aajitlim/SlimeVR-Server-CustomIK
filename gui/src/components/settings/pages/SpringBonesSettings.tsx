import { Button } from '@/components/commons/Button';
import { CheckboxInternal } from '@/components/commons/Checkbox';
import { Typography } from '@/components/commons/Typography';
import { SpringBoneCloseupWidget } from '@/components/widgets/SpringBoneCloseupWidget';
import {
  RETARGET_ROLES,
  RETARGET_ROLE_LABEL,
  TrackerRetargetConfig,
  TrackerRetargetRole,
  TrackerSpringBoneAdjustment,
} from '@/hooks/tracker-retarget';

const OPPOSITE_ROLE: Partial<
  Record<TrackerRetargetRole, TrackerRetargetRole>
> = {
  left_knee: 'right_knee',
  right_knee: 'left_knee',
  left_foot: 'right_foot',
  right_foot: 'left_foot',
  left_elbow: 'right_elbow',
  right_elbow: 'left_elbow',
  left_hand: 'right_hand',
  right_hand: 'left_hand',
};

function withSpring(
  config: TrackerRetargetConfig,
  role: TrackerRetargetRole,
  patch: Partial<TrackerSpringBoneAdjustment>
): TrackerRetargetConfig {
  return {
    ...config,
    springBones: {
      ...config.springBones,
      [role]: {
        ...config.springBones[role],
        ...patch,
      },
    },
  };
}

function RangeControl({
  label,
  value,
  min,
  max,
  step,
  suffix,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  suffix?: string;
  onChange: (value: number) => void;
}) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-between gap-3">
        <Typography bold>{label}</Typography>
        <Typography>
          {value.toFixed(step < 0.1 ? 2 : 1)}
          {suffix ?? ''}
        </Typography>
      </div>
      <input
        className="w-full"
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.currentTarget.value))}
      />
    </div>
  );
}

export function SpringBonesSettings({
  config,
  selectedRole,
  setSelectedRole,
  updateConfig,
}: {
  config: TrackerRetargetConfig;
  selectedRole: TrackerRetargetRole;
  setSelectedRole: (role: TrackerRetargetRole) => void;
  updateConfig: (config: TrackerRetargetConfig) => void;
}) {
  const spring = config.springBones[selectedRole];
  const oppositeRole = OPPOSITE_ROLE[selectedRole];

  const applyPreset = (
    distance: number,
    strength: number,
    pull: number
  ) => {
    updateConfig(
      withSpring(config, selectedRole, {
        enabled: true,
        distance,
        strength,
        pull,
      })
    );
  };

  const copyToOpposite = () => {
    if (!oppositeRole) return;
    updateConfig({
      ...config,
      springBones: {
        ...config.springBones,
        [oppositeRole]: { ...spring },
      },
    });
  };

  const resetAll = () => {
    updateConfig({
      ...config,
      springBonesEnabled: false,
      springBonesUseAcceleration: false,
      springBones: Object.fromEntries(
        RETARGET_ROLES.map((role) => [
          role,
          {
            enabled: false,
            distance: 0.03,
            strength: 12,
            pull: 0.65,
          },
        ])
      ) as TrackerRetargetConfig['springBones'],
    });
  };

  return (
    <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(380px,0.9fr)] gap-4 mt-4">
      <div className="flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <Typography variant="section-title">Spring Bones</Typography>
          <Typography color="secondary">
            Adds bounded secondary motion only to the exported tracker's world Y
            position. X/Z and all tracker rotations remain exactly as SlimeVR
            solved them.
          </Typography>

          <CheckboxInternal
            name="spring-bones-enabled"
            variant="toggle"
            outlined
            label="Enable spring bones on SteamVR output"
            checked={config.springBonesEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                springBonesEnabled: event.currentTarget.checked,
              })
            }
          />

          <CheckboxInternal
            name="spring-bones-use-acceleration"
            variant="toggle"
            outlined
            label="Use physical IMU accelerometer when available"
            checked={config.springBonesUseAcceleration}
            onChange={(event) =>
              updateConfig({
                ...config,
                springBonesUseAcceleration: event.currentTarget.checked,
              })
            }
          />

          <Typography color="secondary">
            Accelerometer mode uses gravity/bias-removed world-Y IMU motion as
            the fast spring impulse while keeping the solved position as the
            rest anchor. Sensor affinity stays local to each body point: a
            chest spring will not borrow hip/waist acceleration, and a missing
            local IMU falls back to that spring's own position-derived motion.
          </Typography>
        </div>

        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <Typography variant="section-title">Spring point</Typography>
            <Typography color="secondary">
              Choose which exported body point gets independent secondary
              vertical motion.
            </Typography>
          </div>

          <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-2">
            {RETARGET_ROLES.map((role) => (
              <Button
                key={role}
                variant={role === selectedRole ? 'tertiary' : 'secondary'}
                onClick={() => setSelectedRole(role)}
              >
                {RETARGET_ROLE_LABEL[role]}
                {config.springBones[role].enabled ? '  ✓' : ''}
              </Button>
            ))}
          </div>
        </div>

        <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-col">
              <Typography variant="section-title">
                {RETARGET_ROLE_LABEL[selectedRole]}
              </Typography>
              <Typography color="secondary">
                Y-only bounded spring around this tracker's solved position
              </Typography>
            </div>
            <Button
              variant="secondary"
              onClick={() =>
                updateConfig(
                  withSpring(config, selectedRole, {
                    enabled: false,
                    distance: 0.03,
                    strength: 12,
                    pull: 0.65,
                  })
                )
              }
            >
              Reset point
            </Button>
          </div>

          <CheckboxInternal
            name={`spring-${selectedRole}-enabled`}
            variant="toggle"
            outlined
            label="Enable spring on this tracker"
            checked={spring.enabled}
            onChange={(event) =>
              updateConfig(
                withSpring(config, selectedRole, {
                  enabled: event.currentTarget.checked,
                })
              )
            }
          />

          <div className="grid sm:grid-cols-3 gap-2">
            <Button variant="secondary" onClick={() => applyPreset(0.015, 18, 0.35)}>
              Subtle
            </Button>
            <Button variant="secondary" onClick={() => applyPreset(0.03, 12, 0.65)}>
              Soft
            </Button>
            <Button variant="secondary" onClick={() => applyPreset(0.06, 7, 1.1)}>
              Loose
            </Button>
          </div>

          <RangeControl
            label="Spring distance"
            value={spring.distance * 100}
            min={0}
            max={15}
            step={0.5}
            suffix=" cm"
            onChange={(centimeters) =>
              updateConfig(
                withSpring(config, selectedRole, {
                  distance: centimeters / 100,
                })
              )
            }
          />
          <Typography color="secondary">
            Maximum Y displacement above or below the solved point. This is a
            hard clamp, not just a suggestion to the spring.
          </Typography>

          <RangeControl
            label="Strength"
            value={spring.strength}
            min={1}
            max={30}
            step={0.5}
            onChange={(strength) =>
              updateConfig(withSpring(config, selectedRole, { strength }))
            }
          />
          <Typography color="secondary">
            Return stiffness. Higher values snap back faster and oscillate more
            tightly; lower values make a slower, softer bounce.
          </Typography>

          <RangeControl
            label="Pull"
            value={spring.pull}
            min={0}
            max={2}
            step={0.05}
            onChange={(pull) =>
              updateConfig(withSpring(config, selectedRole, { pull }))
            }
          />
          <Typography color="secondary">
            Motion coupling. Higher pull converts more of a sudden body motion
            into spring velocity. At zero, movement cannot kick the spring.
          </Typography>

          {oppositeRole && (
            <Button variant="secondary" onClick={copyToOpposite}>
              Copy spring to {RETARGET_ROLE_LABEL[oppositeRole]}
            </Button>
          )}
        </div>

        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={resetAll}>
            Reset all spring bones
          </Button>
        </div>
      </div>

      <div className="lg:sticky lg:top-2 self-start flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
          <Typography variant="section-title">Spring motion close-up</Typography>
          <Typography color="secondary">
            The full body/disc placement visualizer lives on Retargeting /
            Spine. This renderer is intentionally isolated to the selected
            spring point so its radius, Y travel, and oscillator response are
            easy to inspect.
          </Typography>
        </div>

        <SpringBoneCloseupWidget
          key={selectedRole}
          role={selectedRole}
          spring={spring}
          useAcceleration={config.springBonesUseAcceleration}
        />
      </div>
    </div>
  );
}
