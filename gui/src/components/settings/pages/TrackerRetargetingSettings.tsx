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
import { SpringBonesSettings } from '@/components/settings/pages/SpringBonesSettings';
import {
  makeDefaultTrackerRetargetConfig,
  RETARGET_ROLES,
  RETARGET_ROLE_LABEL,
  TrackerRetargetAdjustment,
  TrackerRetargetConfig,
  TrackerRetargetRole,
  useTrackerRetargeting,
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
      <div className="flex flex-wrap items-center gap-2 bg-background-60 rounded-lg p-2">
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

function SyncStatus({
  loaded,
  syncState,
}: {
  loaded: boolean;
  syncState: 'loading' | 'saving' | 'synced';
}) {
  const text =
    !loaded || syncState === 'loading'
      ? 'Loading server settings...'
      : syncState === 'saving'
        ? 'Saving...'
        : 'Saved to server config';

  return <Typography color="secondary">{text}</Typography>;
}

export function TrackerRetargetingSettings() {
  const { config, loaded, syncState, updateConfig, refresh } =
    useTrackerRetargeting();
  const [selectedRole, setSelectedRole] =
    useState<TrackerRetargetRole>('waist');
  const [activeSubtab, setActiveSubtab] = useState<
    'retargeting' | 'spring_bones'
  >('retargeting');
  const [showSpineNodes, setShowSpineNodes] = useState(true);
  const [showSourceTargets, setShowSourceTargets] = useState(true);
  const [showRetargetTargets, setShowRetargetTargets] = useState(true);
  const [showDisplacementLines, setShowDisplacementLines] = useState(true);

  const selected = config.trackers[selectedRole];
  const oppositeRole = OPPOSITE_ROLE[selectedRole];

  const roleButtons = useMemo(
    () =>
      RETARGET_ROLES.map((role) => (
        <Button
          key={role}
          variant={role === selectedRole ? 'tertiary' : 'secondary'}
          onClick={() => setSelectedRole(role)}
        >
          {RETARGET_ROLE_LABEL[role]}
          {config.trackers[role].enabled ? '  ✓' : ''}
        </Button>
      )),
    [config.trackers, selectedRole]
  );

  const copyToOpposite = (mirrorX: boolean) => {
    if (!oppositeRole) return;

    updateConfig({
      ...config,
      trackers: {
        ...config.trackers,
        [oppositeRole]: {
          ...selected,
          x: mirrorX ? -selected.x : selected.x,
        },
      },
    });
  };

  const resetTrackerTargets = () => {
    updateConfig({
      ...config,
      trackers: makeDefaultTrackerRetargetConfig().trackers,
    });
  };

  const resetSolverControls = () => {
    updateConfig({
      ...config,
      hipFloorLiftWeight: 0,
      spineArticulationEnabled: true,
      spineCurvePower: 1,
    });
  };

  const selectedMagnitudeCm =
    Math.sqrt(selected.x ** 2 + selected.y ** 2 + selected.z ** 2) * 100;

  return (
    <SettingsPageLayout className="flex flex-col gap-2">
      <SettingsPagePaneLayout
        id="retargeting"
        icon={<WrenchIcon width={20} />}
        className="gap-4"
      >
        <div className="flex flex-col gap-2">
          <Typography variant="main-title">Custom IK</Typography>
          <Typography color="secondary">
            Tune articulated spine behavior, independent game-space tracker
            placement, and bounded secondary spring motion without changing the
            physical tracker rotations.
          </Typography>
          <div className="flex flex-wrap items-center gap-3">
            <SyncStatus loaded={loaded} syncState={syncState} />
            <Button variant="secondary" onClick={refresh}>
              Reload from server
            </Button>
          </div>

          <div className="grid sm:grid-cols-2 gap-2 mt-2">
            <Button
              variant={activeSubtab === 'retargeting' ? 'tertiary' : 'secondary'}
              onClick={() => setActiveSubtab('retargeting')}
            >
              Retargeting / Spine
            </Button>
            <Button
              variant={activeSubtab === 'spring_bones' ? 'tertiary' : 'secondary'}
              onClick={() => setActiveSubtab('spring_bones')}
            >
              Spring Bones
            </Button>
          </div>
        </div>

        {activeSubtab === 'retargeting' && (
          <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(380px,0.9fr)] gap-4 mt-4">
          <div className="flex flex-col gap-4">
            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
              <Typography variant="section-title">
                SteamVR output mode
              </Typography>
              <Typography color="secondary">
                Physical keeps the normal SlimeVR computed tracker positions.
                Virtualized keeps those rotations but substitutes the configured
                game-space positions.
              </Typography>
              <div className="grid sm:grid-cols-2 gap-2">
                <Button
                  variant={!config.enabled ? 'tertiary' : 'secondary'}
                  onClick={() =>
                    updateConfig({
                      ...config,
                      enabled: false,
                    })
                  }
                >
                  Physical source positions
                </Button>
                <Button
                  variant={config.enabled ? 'tertiary' : 'secondary'}
                  onClick={() =>
                    updateConfig({
                      ...config,
                      enabled: true,
                    })
                  }
                >
                  Virtualized positions
                </Button>
              </div>
              <Typography color="secondary">
                Current live output:{' '}
                {config.enabled ? 'VIRTUALIZED' : 'PHYSICAL'}
              </Typography>
            </div>

            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-col gap-1">
                  <Typography variant="section-title">
                    Articulated spine
                  </Typography>
                  <Typography color="secondary">
                    Direct torso trackers stay authoritative. Missing
                    upper-chest, chest, waist, or hip segments are distributed
                    between their neighboring anchors.
                  </Typography>
                </div>
                <Button
                  variant="secondary"
                  onClick={() =>
                    updateConfig({
                      ...config,
                      spineArticulationEnabled: true,
                      spineCurvePower: 1,
                    })
                  }
                >
                  Length-linear 1.00
                </Button>
              </div>

              <CheckboxInternal
                name="spine-articulation-enabled"
                variant="toggle"
                outlined
                label="Distribute bend across untracked spine segments"
                checked={config.spineArticulationEnabled}
                onChange={(event) =>
                  updateConfig({
                    ...config,
                    spineArticulationEnabled: event.currentTarget.checked,
                  })
                }
              />

              <div className="flex flex-col gap-2">
                <div className="flex justify-between gap-3">
                  <Typography bold>Spine bend distribution</Typography>
                  <Typography>
                    {config.spineCurvePower.toFixed(2)}
                  </Typography>
                </div>
                <input
                  className="w-full"
                  type="range"
                  min={0.25}
                  max={2.5}
                  step={0.05}
                  value={config.spineCurvePower}
                  disabled={!config.spineArticulationEnabled}
                  onChange={(event) =>
                    updateConfig({
                      ...config,
                      spineCurvePower: Number(event.currentTarget.value),
                    })
                  }
                />
                <div className="flex justify-between gap-3">
                  <Typography color="secondary">
                    Earlier / distributed
                  </Typography>
                  <Typography color="secondary">
                    Later / pelvis-local
                  </Typography>
                </div>
                <Typography color="secondary">
                  1.00 follows the configured torso segment lengths. Lower
                  values move bend upward sooner; higher values hold the upper
                  torso straighter and concentrate more bend near the lower
                  anchor.
                </Typography>
              </div>
            </div>

            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-col gap-1">
                  <Typography variant="section-title">
                    Lower-body ground behavior
                  </Typography>
                  <Typography color="secondary">
                    Controls how much floor clipping is allowed to lift the
                    pelvis after correcting feet and knees.
                  </Typography>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="secondary"
                    onClick={() =>
                      updateConfig({
                        ...config,
                        hipFloorLiftWeight: 0,
                      })
                    }
                  >
                    Decoupled 0%
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() =>
                      updateConfig({
                        ...config,
                        hipFloorLiftWeight: 0.2,
                      })
                    }
                  >
                    Upstream-like 20%
                  </Button>
                </div>
              </div>

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

            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
              <div className="flex flex-col gap-1">
                <Typography variant="section-title">
                  Virtual tracker target
                </Typography>
                <Typography color="secondary">
                  Select the exported tracker you want to align to the avatar.
                  A check mark means that role currently has a custom position
                  enabled.
                </Typography>
              </div>

              <div className="grid sm:grid-cols-2 xl:grid-cols-3 gap-2">
                {roleButtons}
              </div>
            </div>

            <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-col">
                  <Typography variant="section-title">
                    {RETARGET_ROLE_LABEL[selectedRole]}
                  </Typography>
                  <Typography color="secondary">
                    Configured displacement: {selectedMagnitudeCm.toFixed(1)} cm
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

              {oppositeRole && (
                <div className="flex flex-wrap gap-2 pt-1">
                  <Button
                    variant="secondary"
                    onClick={() => copyToOpposite(false)}
                  >
                    Copy to {RETARGET_ROLE_LABEL[oppositeRole]}
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() => copyToOpposite(true)}
                  >
                    Mirror X to {RETARGET_ROLE_LABEL[oppositeRole]}
                  </Button>
                </div>
              )}
            </div>

            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={resetTrackerTargets}>
                Reset all tracker targets
              </Button>
              <Button variant="secondary" onClick={resetSolverControls}>
                Reset solver controls
              </Button>
              <Button variant="secondary" onClick={refresh}>
                Reload saved values
              </Button>
            </div>
          </div>

          <div className="lg:sticky lg:top-2 self-start flex flex-col gap-2">
            <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
              <Typography variant="section-title">Visualizer layers</Typography>
              <div className="grid sm:grid-cols-2 gap-2">
                <CheckboxInternal
                  name="show-spine-nodes"
                  variant="toggle"
                  outlined
                  label="Spine joints"
                  checked={showSpineNodes}
                  onChange={(event) =>
                    setShowSpineNodes(event.currentTarget.checked)
                  }
                />
                <CheckboxInternal
                  name="show-source-targets"
                  variant="toggle"
                  outlined
                  label="Source discs"
                  checked={showSourceTargets}
                  onChange={(event) =>
                    setShowSourceTargets(event.currentTarget.checked)
                  }
                />
                <CheckboxInternal
                  name="show-retarget-targets"
                  variant="toggle"
                  outlined
                  label="Virtual discs"
                  checked={showRetargetTargets}
                  onChange={(event) =>
                    setShowRetargetTargets(event.currentTarget.checked)
                  }
                />
                <CheckboxInternal
                  name="show-displacement-lines"
                  variant="toggle"
                  outlined
                  label="Delta lines"
                  checked={showDisplacementLines}
                  disabled={!showSourceTargets || !showRetargetTargets}
                  onChange={(event) =>
                    setShowDisplacementLines(event.currentTarget.checked)
                  }
                />
              </div>
              <Typography color="secondary">
                Source discs are SlimeVR's computed anatomical tracker
                positions. Virtual discs preview the configured SteamVR
                positions even while live output remains in Physical mode.
              </Typography>
            </div>

            <div className="relative rounded-lg overflow-hidden bg-background-60 min-h-[620px]">
              <SkeletonVisualizerWidget
                key="retargeting-body-visualizer"
                retargetConfig={config}
                selectedRetargetRole={selectedRole}
                showSpineNodes={showSpineNodes}
                showSourceTargets={showSourceTargets}
                showRetargetTargets={showRetargetTargets}
                showDisplacementLines={showDisplacementLines}
                previewConfiguredOffsets
              />

              <div className="absolute top-3 left-3 bg-background-80/90 rounded-lg p-3 pointer-events-none">
                <Typography bold>
                  Selected: {RETARGET_ROLE_LABEL[selectedRole]}
                </Typography>
                <Typography color="secondary">
                  X {((selected.x ?? 0) * 100).toFixed(1)} cm · Y{' '}
                  {((selected.y ?? 0) * 100).toFixed(1)} cm · Z{' '}
                  {((selected.z ?? 0) * 100).toFixed(1)} cm
                </Typography>
              </div>

              <div className="absolute bottom-3 left-3 right-3 bg-background-80/90 rounded-lg p-3 pointer-events-none flex flex-col gap-1">
                <Typography bold>
                  White joints = spine anchors · Wireframe = SlimeVR source ·
                  Cyan = virtual target · Yellow = selected virtual target
                </Typography>
                <Typography color="secondary">
                  The colored skeleton is the articulated anatomical solve.
                  Retarget offsets never feed back into those bones or their
                  rotations.
                </Typography>
              </div>
            </div>
          </div>
        </div>
        )}

        {activeSubtab === 'spring_bones' && (
          <SpringBonesSettings
            config={config}
            selectedRole={selectedRole}
            setSelectedRole={setSelectedRole}
            updateConfig={updateConfig}
          />
        )}
      </SettingsPagePaneLayout>
    </SettingsPageLayout>
  );
}
