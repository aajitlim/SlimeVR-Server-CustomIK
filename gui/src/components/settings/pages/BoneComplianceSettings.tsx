import { useState } from 'react';
import { Button } from '@/components/commons/Button';
import { CheckboxInternal } from '@/components/commons/Checkbox';
import { Typography } from '@/components/commons/Typography';
import { SkeletonVisualizerWidget } from '@/components/widgets/SkeletonVisualizerWidget';
import {
  BONE_COMPLIANCE_SEGMENTS,
  BONE_COMPLIANCE_SEGMENT_LABEL,
  BoneComplianceSegmentAdjustment,
  BoneComplianceSegmentKey,
  makeDefaultTrackerRetargetConfig,
  TrackerRetargetConfig,
} from '@/hooks/tracker-retarget';

function withSegment(
  config: TrackerRetargetConfig,
  key: BoneComplianceSegmentKey,
  patch: Partial<BoneComplianceSegmentAdjustment>
): TrackerRetargetConfig {
  return {
    ...config,
    boneComplianceSegments: {
      ...config.boneComplianceSegments,
      [key]: {
        ...config.boneComplianceSegments[key],
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

export function BoneComplianceSettings({
  config,
  updateConfig,
}: {
  config: TrackerRetargetConfig;
  updateConfig: (config: TrackerRetargetConfig) => void;
}) {
  const [selectedSegment, setSelectedSegment] =
    useState<BoneComplianceSegmentKey>('chest_to_waist');

  const segment = config.boneComplianceSegments[selectedSegment];
  const defaults = makeDefaultTrackerRetargetConfig();
  const defaultSegment = defaults.boneComplianceSegments[selectedSegment];

  const resetAll = () => {
    updateConfig({
      ...config,
      boneComplianceEnabled: false,
      boneComplianceOverall: defaults.boneComplianceOverall,
      boneCompliancePreserveTorsoLength:
        defaults.boneCompliancePreserveTorsoLength,
      boneComplianceResponse: defaults.boneComplianceResponse,
      boneComplianceGroundClosureEnabled:
        defaults.boneComplianceGroundClosureEnabled,
      boneComplianceGroundClosureStrength:
        defaults.boneComplianceGroundClosureStrength,
      boneComplianceGroundClosureMaxCorrectionMeters:
        defaults.boneComplianceGroundClosureMaxCorrectionMeters,
      boneComplianceGroundClosureBilateralToleranceMeters:
        defaults.boneComplianceGroundClosureBilateralToleranceMeters,
      boneComplianceGroundClosureRequireBothFeet:
        defaults.boneComplianceGroundClosureRequireBothFeet,
      boneComplianceSegments: defaults.boneComplianceSegments,
    });
  };

  return (
    <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(380px,0.9fr)] gap-4 mt-4">
      <div className="flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <Typography variant="section-title">
            Physical bone compliance
          </Typography>
          <Typography color="secondary">
            Allows small bounded changes in effective torso segment length after
            rotation solving but before computed tracker positions are emitted.
            This is part of the physical body estimate, not virtual Spring Bone
            motion.
          </Typography>

          <CheckboxInternal
            name="bone-compliance-enabled"
            variant="toggle"
            outlined
            label="Enable compliant torso solve"
            checked={config.boneComplianceEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                boneComplianceEnabled: event.currentTarget.checked,
              })
            }
          />

          <RangeControl
            label="Overall compliance"
            value={config.boneComplianceOverall * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(percent) =>
              updateConfig({
                ...config,
                boneComplianceOverall: percent / 100,
              })
            }
          />
          <div className="flex justify-between gap-3">
            <Typography color="secondary">Rigid SlimeVR lengths</Typography>
            <Typography color="secondary">Full allowed compliance</Typography>
          </div>
          <Typography color="secondary">
            This percentage is a blend into the bounded compliant solution. It
            does not mean a bone can shrink by that percentage.
          </Typography>

          <CheckboxInternal
            name="bone-compliance-preserve-torso-length"
            variant="toggle"
            outlined
            label="Preserve total torso length"
            checked={config.boneCompliancePreserveTorsoLength}
            onChange={(event) =>
              updateConfig({
                ...config,
                boneCompliancePreserveTorsoLength:
                  event.currentTarget.checked,
              })
            }
          />
          <Typography color="secondary">
            When enabled, local compression is redistributed across the other
            compliant torso spans so their combined calibrated length remains
            approximately constant. Disable it to permit small net torso
            compression/extension inside the configured limits.
          </Typography>

          <RangeControl
            label="Compliance response"
            value={config.boneComplianceResponse * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(percent) =>
              updateConfig({
                ...config,
                boneComplianceResponse: percent / 100,
              })
            }
          />
          <div className="flex justify-between gap-3">
            <Typography color="secondary">Smoother</Typography>
            <Typography color="secondary">More reactive</Typography>
          </div>
        </div>

        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <Typography variant="section-title">
              Planted-foot Ground Closure
            </Typography>
            <Typography color="secondary">
              Uses the previous stable planted-foot heights as a closure
              constraint. Common vertical foot error is pushed back into the
              bounded torso compliance solve instead of lifting or dipping both
              feet when the torso bends.
            </Typography>
          </div>

          <CheckboxInternal
            name="bone-compliance-ground-closure-enabled"
            variant="toggle"
            outlined
            label="Enable planted-foot Ground Closure"
            checked={config.boneComplianceGroundClosureEnabled}
            disabled={!config.boneComplianceEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                boneComplianceGroundClosureEnabled:
                  event.currentTarget.checked,
              })
            }
          />

          {!config.boneComplianceEnabled && (
            <Typography color="secondary">
              Enable the compliant torso solve above before Ground Closure can
              modify torso strain.
            </Typography>
          )}

          <RangeControl
            label="Ground correction strength"
            value={config.boneComplianceGroundClosureStrength * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(percent) =>
              updateConfig({
                ...config,
                boneComplianceGroundClosureStrength: percent / 100,
              })
            }
          />
          <Typography color="secondary">
            Controls how strongly trusted common foot-height error contributes
            to the torso strain target. This is a non-oscillating closure servo,
            not a Spring Bone.
          </Typography>

          <RangeControl
            label="Maximum vertical correction"
            value={
              config.boneComplianceGroundClosureMaxCorrectionMeters * 100
            }
            min={0}
            max={10}
            step={0.25}
            suffix=" cm"
            onChange={(centimeters) =>
              updateConfig({
                ...config,
                boneComplianceGroundClosureMaxCorrectionMeters:
                  centimeters / 100,
              })
            }
          />

          <RangeControl
            label="Left / right disagreement tolerance"
            value={
              config.boneComplianceGroundClosureBilateralToleranceMeters * 100
            }
            min={0.25}
            max={10}
            step={0.25}
            suffix=" cm"
            onChange={(centimeters) =>
              updateConfig({
                ...config,
                boneComplianceGroundClosureBilateralToleranceMeters:
                  centimeters / 100,
              })
            }
          />
          <Typography color="secondary">
            If both planted feet disagree vertically beyond this tolerance, the
            torso receives no Ground Closure correction for that frame. That
            keeps one bad leg or foot from turning into a spine-length error.
          </Typography>

          <CheckboxInternal
            name="bone-compliance-ground-closure-both-feet"
            variant="toggle"
            outlined
            label="Require both feet planted"
            checked={config.boneComplianceGroundClosureRequireBothFeet}
            onChange={(event) =>
              updateConfig({
                ...config,
                boneComplianceGroundClosureRequireBothFeet:
                  event.currentTarget.checked,
              })
            }
          />
          <Typography color="secondary">
            Recommended for the first tests. If disabled, one strongly planted
            foot may drive Ground Closure at reduced confidence.
          </Typography>

          <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-2">
            <Typography bold>Allocation rule</Typography>
            <Typography color="secondary">
              Each torso span receives correction according to its current
              vertical length sensitivity. Horizontal segments contribute very
              little; more vertical segments receive more of the correction.
              The normal compression/extension limits and optional total-torso
              length preservation still apply.
            </Typography>
          </div>
        </div>

        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <Typography variant="section-title">Torso segment</Typography>
            <Typography color="secondary">
              Every span is solved from the same frame. One segment's length
              change is never fed into another segment's sensor estimate.
            </Typography>
          </div>

          <div className="grid sm:grid-cols-3 gap-2">
            {BONE_COMPLIANCE_SEGMENTS.map((key) => (
              <Button
                key={key}
                variant={key === selectedSegment ? 'tertiary' : 'secondary'}
                onClick={() => setSelectedSegment(key)}
              >
                {BONE_COMPLIANCE_SEGMENT_LABEL[key]}
                {config.boneComplianceSegments[key].enabled ? '  ✓' : ''}
              </Button>
            ))}
          </div>
        </div>

        <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-col">
              <Typography variant="section-title">
                {BONE_COMPLIANCE_SEGMENT_LABEL[selectedSegment]}
              </Typography>
              <Typography color="secondary">
                Bounded axial freedom around the calibrated rest length
              </Typography>
            </div>
            <Button
              variant="secondary"
              onClick={() =>
                updateConfig(
                  withSegment(config, selectedSegment, defaultSegment)
                )
              }
            >
              Reset segment
            </Button>
          </div>

          <CheckboxInternal
            name={`bone-compliance-${selectedSegment}-enabled`}
            variant="toggle"
            outlined
            label="Allow compliance on this span"
            checked={segment.enabled}
            onChange={(event) =>
              updateConfig(
                withSegment(config, selectedSegment, {
                  enabled: event.currentTarget.checked,
                })
              )
            }
          />

          <RangeControl
            label="Segment compliance"
            value={segment.compliance * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(percent) =>
              updateConfig(
                withSegment(config, selectedSegment, {
                  compliance: percent / 100,
                })
              )
            }
          />
          <Typography color="secondary">
            Local share of the global compliant solution. The global and local
            percentages multiply, so either can return this span toward rigid
            SlimeVR behavior.
          </Typography>

          <RangeControl
            label="Maximum compression"
            value={segment.compressionLimit * 100}
            min={0}
            max={8}
            step={0.25}
            suffix="%"
            onChange={(percent) =>
              updateConfig(
                withSegment(config, selectedSegment, {
                  compressionLimit: percent / 100,
                })
              )
            }
          />

          <RangeControl
            label="Maximum extension"
            value={segment.extensionLimit * 100}
            min={0}
            max={8}
            step={0.25}
            suffix="%"
            onChange={(percent) =>
              updateConfig(
                withSegment(config, selectedSegment, {
                  extensionLimit: percent / 100,
                })
              )
            }
          />

          <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
            <Typography bold>Allowed strain envelope</Typography>
            <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-2">
              <div className="h-2 rounded bg-background-80 overflow-hidden flex justify-end">
                <div
                  className="h-full bg-background-20"
                  style={{
                    width: `${Math.min(
                      100,
                      (segment.compressionLimit / 0.08) * 100
                    )}%`,
                  }}
                />
              </div>
              <Typography>REST</Typography>
              <div className="h-2 rounded bg-background-80 overflow-hidden">
                <div
                  className="h-full bg-background-20"
                  style={{
                    width: `${Math.min(
                      100,
                      (segment.extensionLimit / 0.08) * 100
                    )}%`,
                  }}
                />
              </div>
            </div>
            <div className="flex justify-between gap-3">
              <Typography color="secondary">
                -{(segment.compressionLimit * 100).toFixed(2)}%
              </Typography>
              <Typography color="secondary">
                +{(segment.extensionLimit * 100).toFixed(2)}%
              </Typography>
            </div>
          </div>

          <RangeControl
            label="Differential sensor influence"
            value={segment.sensorInfluence * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(percent) =>
              updateConfig(
                withSegment(config, selectedSegment, {
                  sensorInfluence: percent / 100,
                })
              )
            }
          />
          <Typography color="secondary">
            Uses neighboring IMU world-Y acceleration differences and their
            rate of change as local strain evidence. Common whole-body motion
            largely cancels. With missing adjacent IMUs, the segment falls back
            to rotational bend evidence only.
          </Typography>
        </div>

        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
          <Typography variant="section-title">Rigid regions</Typography>
          <Typography color="secondary">
            Long limb bones stay rigid in this first compliance version:
            femurs, tibias, upper arms, forearms, hands, and feet are not
            length-modified by this solver. This avoids turning tracking error
            into physically implausible leg or arm stretch.
          </Typography>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={resetAll}>
            Reset bone compliance
          </Button>
        </div>
      </div>

      <div className="lg:sticky lg:top-2 self-start flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
          <Typography variant="section-title">
            Physical compliant skeleton
          </Typography>
          <Typography color="secondary">
            This viewer shows the live anatomical skeleton. When compliance is
            active, torso length changes occur here before computed trackers,
            retargeting, and Spring Bones.
          </Typography>
        </div>

        <div className="relative rounded-lg overflow-hidden bg-background-60 min-h-[620px]">
          <SkeletonVisualizerWidget
            key="bone-compliance-visualizer"
            retargetConfig={config}
            selectedRetargetRole="waist"
            showSpineNodes
            showSourceTargets={false}
            showRetargetTargets={false}
            showDisplacementLines={false}
          />

          <div className="absolute bottom-3 left-3 right-3 bg-background-80/90 rounded-lg p-3 pointer-events-none flex flex-col gap-1">
            <Typography bold>
              Physical layer — before virtual retargeting and Spring Bones
            </Typography>
            <Typography color="secondary">
              Rotation remains authoritative. Only the calibrated axial lengths
              of the three torso spans can change, and only inside their hard
              strain limits.
            </Typography>
          </div>
        </div>
      </div>
    </div>
  );
}
