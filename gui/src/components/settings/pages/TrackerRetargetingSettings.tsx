import { useMemo, useState } from 'react';
import { Button } from '@/components/commons/Button';
import { CheckboxInternal } from '@/components/commons/Checkbox';
import { DropdownInside } from '@/components/commons/Dropdown';
import { Typography } from '@/components/commons/Typography';
import { WrenchIcon } from '@/components/commons/icon/WrenchIcons';
import {
  SettingsPageLayout,
  SettingsPagePaneLayout,
} from '@/components/settings/SettingsPageLayout';
import { SkeletonVisualizerWidget } from '@/components/widgets/SkeletonVisualizerWidget';
import {
  makeDefaultTrackerRetargetConfig,
  RETARGET_ROLES,
  RETARGET_ROLE_LABEL,
  TrackerRetargetAdjustment,
  TrackerRetargetConfig,
  TrackerRetargetRole,
  useTrackerRetargeting,
} from '@/hooks/tracker-retarget';

function clampOffset(value: number) {
  return Math.min(2, Math.max(-2, value));
}

function OffsetAxis({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
}) {
  const centimeters = value * 100;

  const nudge = (deltaCm: number) =>
    onChange(clampOffset((centimeters + deltaCm) / 100));

  return (
    <div className="flex flex-col gap-1">
      <Typography bold>{label}</Typography>
      <div className="flex items-center gap-2 bg-background-60 rounded-lg p-2">
        <Button variant="tertiary" onClick={() => nudge(-2)}>
          -2 cm
        </Button>
        <Button variant="tertiary" onClick={() => nudge(-0.5)}>
          -5 mm
        </Button>
        <input
          className="bg-background-70 rounded-md px-2 py-2 text-center flex-grow min-w-24"
          type="number"
          min={-200}
          max={200}
          step={0.5}
          value={Number(centimeters.toFixed(1))}
          onChange={(event) => {
            const next = Number(event.target.value);
            if (!Number.isFinite(next)) return;
            onChange(clampOffset(next / 100));
          }}
        />
        <Typography color="secondary">cm</Typography>
        <Button variant="tertiary" onClick={() => nudge(0.5)}>
          +5 mm
        </Button>
        <Button variant="tertiary" onClick={() => nudge(2)}>
          +2 cm
        </Button>
      </div>
    </div>
  );
}

function copyWithAdjustment(
  config: TrackerRetargetConfig,
  role: TrackerRetargetRole,
  patch: Partial<TrackerRetargetAdjustment>
): TrackerRetargetConfig {
  return {
    ...config,
    trackers: {
      ...config.trackers,
      [role]: {
        ...config.trackers[role],
        ...patch,
      },
    },
  };
}

export function TrackerRetargetingSettings() {
  const { config, loaded, updateConfig, refresh } = useTrackerRetargeting();
  const [selectedRole, setSelectedRole] =
    useState<TrackerRetargetRole>('waist');

  const selected = config.trackers[selectedRole];

  const roleButtons = useMemo(
    () =>
      RETARGET_ROLES.map((role) => (
        <Button
          key={role}
          variant={role === selectedRole ? 'tertiary' : 'secondary'}
          onClick={() => setSelectedRole(role)}
        >
          {RETARGET_ROLE_LABEL[role]}
        </Button>
      )),
    [selectedRole]
  );

  return (
    <SettingsPageLayout className="flex flex-col gap-2">
      <SettingsPagePaneLayout
        id="retargeting"
        icon={<WrenchIcon width={20} />}
        className="gap-4"
      >
        <div className="flex flex-col gap-2">
          <Typography variant="main-title">Tracker retargeting</Typography>
          <Typography color="secondary">
            Keep SlimeVR's solved body and rotations untouched, then move only
            the virtual tracker positions exported to SteamVR. The discs in the
            preview show those game-space targets over the anatomical skeleton.
          </Typography>
          {!loaded && (
            <div className="flex items-center gap-2">
              <Typography color="secondary">
                Waiting for retarget settings from the server...
              </Typography>
              <Button variant="secondary" onClick={refresh}>
                Retry
              </Button>
            </div>
          )}
        </div>

        <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(360px,0.9fr)] gap-4 mt-4">
          <div className="flex flex-col gap-4">
            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
              <CheckboxInternal
                name="retargeting-enabled"
                variant="toggle"
                outlined
                label="Enable SteamVR position retargeting"
                checked={config.enabled}
                onChange={(event) =>
                  updateConfig({
                    ...config,
                    enabled: event.currentTarget.checked,
                  })
                }
              />
              <Typography color="secondary">
                Rotation still comes directly from SlimeVR's computed tracker.
                These offsets only change the position sent across the SteamVR
                bridge.
              </Typography>
            </div>

            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
              <Typography variant="section-title">
                Lower-body ground behavior
              </Typography>
              <Typography color="secondary">
                Floor clipping can push feet and knees upward without also
                lifting the pelvis. Zero fully decouples that pelvis lift.
              </Typography>
              <div className="flex gap-3 items-center">
                <input
                  className="flex-grow"
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={config.hipFloorLiftWeight}
                  onChange={(event) =>
                    updateConfig({
                      ...config,
                      hipFloorLiftWeight: Number(event.currentTarget.value),
                    })
                  }
                />
                <div className="w-20 text-right">
                  <Typography>
                    {Math.round(config.hipFloorLiftWeight * 100)}%
                  </Typography>
                </div>
              </div>
            </div>

            <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-2">
              {roleButtons}
            </div>

            <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-3">
              <div className="flex items-center justify-between gap-2">
                <div className="flex flex-col">
                  <Typography variant="section-title">
                    {RETARGET_ROLE_LABEL[selectedRole]}
                  </Typography>
                  <Typography color="secondary">
                    Virtual target placement
                  </Typography>
                </div>
                <Button
                  variant="secondary"
                  onClick={() =>
                    updateConfig(
                      copyWithAdjustment(
                        config,
                        selectedRole,
                        makeDefaultTrackerRetargetConfig().trackers[selectedRole]
                      )
                    )
                  }
                >
                  Reset target
                </Button>
              </div>

              <CheckboxInternal
                name={`retarget-${selectedRole}-enabled`}
                variant="toggle"
                outlined
                label="Use custom position for this tracker"
                checked={selected.enabled}
                onChange={(event) =>
                  updateConfig(
                    copyWithAdjustment(config, selectedRole, {
                      enabled: event.currentTarget.checked,
                    })
                  )
                }
              />

              <div className="flex flex-col gap-1">
                <Typography bold>Offset coordinate space</Typography>
                <DropdownInside
                  direction="down"
                  variant="secondary"
                  display="block"
                  placeholder=""
                  name={`retarget-${selectedRole}-space`}
                  value={selected.space}
                  items={[
                    {
                      value: 'body_yaw',
                      label: 'Body yaw — follows facing direction only',
                    },
                    {
                      value: 'world',
                      label: 'World — fixed global direction',
                    },
                  ]}
                  onChange={(value) =>
                    updateConfig(
                      copyWithAdjustment(config, selectedRole, {
                        space: value === 'world' ? 'world' : 'body_yaw',
                      })
                    )
                  }
                />
              </div>

              <OffsetAxis
                label="X offset — left / right"
                value={selected.x}
                onChange={(x) =>
                  updateConfig(
                    copyWithAdjustment(config, selectedRole, { x })
                  )
                }
              />
              <OffsetAxis
                label="Y offset — down / up"
                value={selected.y}
                onChange={(y) =>
                  updateConfig(
                    copyWithAdjustment(config, selectedRole, { y })
                  )
                }
              />
              <OffsetAxis
                label="Z offset — forward / back"
                value={selected.z}
                onChange={(z) =>
                  updateConfig(
                    copyWithAdjustment(config, selectedRole, { z })
                  )
                }
              />
            </div>

            <div className="flex gap-2">
              <Button
                variant="secondary"
                onClick={() =>
                  updateConfig({
                    ...makeDefaultTrackerRetargetConfig(),
                    hipFloorLiftWeight: config.hipFloorLiftWeight,
                  })
                }
              >
                Reset all tracker targets
              </Button>
              <Button variant="secondary" onClick={refresh}>
                Reload from server
              </Button>
            </div>
          </div>

          <div className="relative rounded-lg overflow-hidden bg-background-60 min-h-[560px] lg:sticky lg:top-2">
            <SkeletonVisualizerWidget
              retargetConfig={config}
              selectedRetargetRole={selectedRole}
              showRetargetTargets
            />
            <div className="absolute bottom-3 left-3 right-3 bg-background-80/90 rounded-lg p-3 pointer-events-none">
              <Typography bold>
                Cyan discs = virtual SteamVR tracker targets
              </Typography>
              <Typography color="secondary">
                The colored skeleton remains the untouched SlimeVR anatomical
                solve. Select a role and move X/Y/Z until the disc sits on the
                avatar anchor you want.
              </Typography>
            </div>
          </div>
        </div>
      </SettingsPagePaneLayout>
    </SettingsPageLayout>
  );
}
