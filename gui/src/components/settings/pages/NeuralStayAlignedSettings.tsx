import { Button } from '@/components/commons/Button';
import { CheckboxInternal } from '@/components/commons/Checkbox';
import { Typography } from '@/components/commons/Typography';
import {
  NeuralStayAlignedRuntimeStatus,
  TrackerRetargetConfig,
} from '@/hooks/tracker-retarget';

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
          {value.toFixed(step < 0.1 ? 2 : 0)}
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

export function NeuralStayAlignedSettings({
  config,
  status,
  updateConfig,
  refresh,
  clearLearning,
}: {
  config: TrackerRetargetConfig;
  status: NeuralStayAlignedRuntimeStatus;
  updateConfig: (config: TrackerRetargetConfig) => void;
  refresh: () => void;
  clearLearning: () => void;
}) {
  const historySeconds =
    config.neuralStayAlignedHistorySamples /
    Math.max(1, config.neuralStayAlignedSampleRateHz);

  return (
    <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(400px,0.95fr)] gap-4 mt-4">
      <div className="flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <Typography variant="section-title">
            Neural Stay Aligned
          </Typography>
          <Typography color="secondary">
            A tiny shared GRU learns signed yaw-drift rate from compact
            cross-skeleton motion features. Every physical sensor keeps its own
            recurrent state, history, and learned output bias.
          </Typography>

          <CheckboxInternal
            name="neural-stay-aligned-enabled"
            variant="toggle"
            outlined
            label="Enable neural sampling / inference"
            checked={config.neuralStayAlignedEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                neuralStayAlignedEnabled: event.currentTarget.checked,
              })
            }
          />

          <CheckboxInternal
            name="neural-stay-aligned-learn-resets"
            variant="toggle"
            outlined
            label="Learn from soft yaw resets"
            checked={config.neuralStayAlignedLearnFromYawResets}
            disabled={!config.neuralStayAlignedEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                neuralStayAlignedLearnFromYawResets:
                  event.currentTarget.checked,
              })
            }
          />

          <CheckboxInternal
            name="neural-stay-aligned-apply"
            variant="toggle"
            outlined
            label="Apply learned yaw corrections"
            checked={config.neuralStayAlignedApplyCorrections}
            disabled={!config.neuralStayAlignedEnabled}
            onChange={(event) =>
              updateConfig({
                ...config,
                neuralStayAlignedApplyCorrections:
                  event.currentTarget.checked,
              })
            }
          />

          <Typography color="secondary">
            Training and correction are separate on purpose. Leave correction
            application OFF while gathering the first reset examples; the model
            will still sample, infer, and train.
          </Typography>
        </div>

        <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-4">
          <Typography variant="section-title">
            Learned correction safety gate
          </Typography>

          <RangeControl
            label="Correction strength"
            value={config.neuralStayAlignedCorrectionStrength * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedCorrectionStrength: value / 100,
              })
            }
          />

          <RangeControl
            label="Hard max correction rate"
            value={config.neuralStayAlignedMaxCorrectionRateDegPerSec}
            min={0}
            max={2}
            step={0.05}
            suffix="°/s"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedMaxCorrectionRateDegPerSec: value,
              })
            }
          />

          <RangeControl
            label="Minimum confidence"
            value={config.neuralStayAlignedConfidenceThreshold * 100}
            min={0}
            max={95}
            step={5}
            suffix="%"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedConfidenceThreshold: value / 100,
              })
            }
          />

          <RangeControl
            label="Intentional-motion protection"
            value={config.neuralStayAlignedMotionProtection * 100}
            min={0}
            max={100}
            step={5}
            suffix="%"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedMotionProtection: value / 100,
              })
            }
          />

          <Typography color="secondary">
            The neural output is only a requested yaw-rate correction. A
            non-neural limiter clamps that rate, gates low-confidence devices,
            and suppresses correction as angular velocity rises.
          </Typography>
        </div>

        <div className="bg-background-70 rounded-lg p-3 flex flex-col gap-4">
          <Typography variant="section-title">Training window</Typography>

          <RangeControl
            label="Compact history samples"
            value={config.neuralStayAlignedHistorySamples}
            min={100}
            max={5000}
            step={100}
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedHistorySamples: Math.round(value),
              })
            }
          />

          <RangeControl
            label="Feature sample rate"
            value={config.neuralStayAlignedSampleRateHz}
            min={5}
            max={60}
            step={1}
            suffix=" Hz"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedSampleRateHz: value,
              })
            }
          />

          <Typography color="secondary">
            Current retained temporal coverage: approximately{' '}
            {historySeconds.toFixed(1)} seconds per physical sensor. Only the
            compact 32-value neural feature packets are retained, not the raw
            SlimeVR sensor stream.
          </Typography>

          <div className="flex flex-col gap-2">
            <div className="flex justify-between gap-3">
              <Typography bold>Learning rate</Typography>
              <Typography>
                {config.neuralStayAlignedLearningRate.toExponential(2)}
              </Typography>
            </div>
            <input
              className="w-full"
              type="range"
              min={-6}
              max={-2}
              step={0.1}
              value={Math.log10(config.neuralStayAlignedLearningRate)}
              onChange={(event) =>
                updateConfig({
                  ...config,
                  neuralStayAlignedLearningRate: Math.pow(
                    10,
                    Number(event.currentTarget.value)
                  ),
                })
              }
            />
            <div className="flex justify-between gap-3">
              <Typography color="secondary">1e-6</Typography>
              <Typography color="secondary">1e-2</Typography>
            </div>
          </div>

          <RangeControl
            label="Minimum reset interval"
            value={config.neuralStayAlignedMinimumResetIntervalSeconds}
            min={1}
            max={120}
            step={1}
            suffix=" s"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedMinimumResetIntervalSeconds: value,
              })
            }
          />

          <RangeControl
            label="Maximum reset supervision"
            value={config.neuralStayAlignedMaxResetSupervisionDeg}
            min={5}
            max={90}
            step={1}
            suffix="°"
            onChange={(value) =>
              updateConfig({
                ...config,
                neuralStayAlignedMaxResetSupervisionDeg: value,
              })
            }
          />
        </div>

        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-3">
          <Typography variant="section-title">
            Reset-supervision pathway
          </Typography>
          <div className="grid md:grid-cols-5 gap-2 text-center">
            {[
              'Compact feature stream',
              'Per-device GRU state',
              'Soft yaw reset',
              'Signed reset residual',
              'BPTT update',
            ].map((label, index) => (
              <div
                key={label}
                className="bg-background-70 rounded-lg p-3 flex flex-col gap-1"
              >
                <Typography bold>{index + 1}</Typography>
                <Typography color="secondary">{label}</Typography>
              </div>
            ))}
          </div>
          <Typography color="secondary">
            A soft yaw reset supervises the integrated predicted drift over the
            retained history. Full resets and mounting resets discard temporal
            history instead of being treated as drift labels.
          </Typography>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={refresh}>
            Refresh neural status
          </Button>
          <Button variant="secondary" onClick={clearLearning}>
            Clear neural learning
          </Button>
        </div>

        <Typography color="secondary">
          Learned GRU weights and per-device adapters are runtime-only in this
          first structure. Restarting the server clears them; persistent model
          serialization can be added after the online behavior is validated.
        </Typography>
      </div>

      <div className="lg:sticky lg:top-2 self-start flex flex-col gap-4">
        <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
          <Typography variant="section-title">Runtime learning status</Typography>
          <div className="grid grid-cols-3 gap-2">
            <div className="bg-background-70 rounded-lg p-3">
              <Typography bold>{status.deviceCount}</Typography>
              <Typography color="secondary">device states</Typography>
            </div>
            <div className="bg-background-70 rounded-lg p-3">
              <Typography bold>{Math.round(status.totalSamples)}</Typography>
              <Typography color="secondary">samples</Typography>
            </div>
            <div className="bg-background-70 rounded-lg p-3">
              <Typography bold>
                {Math.round(status.totalSupervisionEvents)}
              </Typography>
              <Typography color="secondary">reset labels</Typography>
            </div>
          </div>
          <Typography color="secondary">
            Status is returned by the server when this settings page refreshes.
          </Typography>
        </div>

        {status.devices.length === 0 ? (
          <div className="bg-background-60 rounded-lg p-4">
            <Typography bold>No neural device state yet</Typography>
            <Typography color="secondary">
              Enable neural sampling and move with assigned IMU trackers. Device
              controllers appear after their first compact sample.
            </Typography>
          </div>
        ) : (
          <div className="flex flex-col gap-3 max-h-[720px] overflow-y-auto pr-1">
            {status.devices.map((device) => (
              <div
                key={device.deviceKey}
                className="bg-background-60 rounded-lg p-3 flex flex-col gap-3"
              >
                <div className="flex flex-wrap justify-between gap-2">
                  <div className="flex flex-col">
                    <Typography bold>{device.bodyPosition}</Typography>
                    <Typography color="secondary">
                      {device.trackerName}
                    </Typography>
                  </div>
                  <Typography>
                    {Math.round(device.confidence * 100)}% confidence
                  </Typography>
                </div>

                <Typography color="secondary">
                  {device.deviceKey}
                </Typography>

                <div className="grid grid-cols-2 gap-2">
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>
                      {Math.round(device.samplesSeen)}
                    </Typography>
                    <Typography color="secondary">samples seen</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>{device.historySize}</Typography>
                    <Typography color="secondary">in history</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>{device.supervisionEvents}</Typography>
                    <Typography color="secondary">reset labels</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>{device.lastLoss.toFixed(4)}</Typography>
                    <Typography color="secondary">last loss</Typography>
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-2">
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>
                      {device.predictedRateDegPerSec >= 0 ? '+' : ''}
                      {device.predictedRateDegPerSec.toFixed(3)}°/s
                    </Typography>
                    <Typography color="secondary">predicted drift</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>
                      {device.appliedRateDegPerSec >= 0 ? '+' : ''}
                      {device.appliedRateDegPerSec.toFixed(3)}°/s
                    </Typography>
                    <Typography color="secondary">applied correction</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>
                      {device.lastResetCorrectionDeg >= 0 ? '+' : ''}
                      {device.lastResetCorrectionDeg.toFixed(2)}°
                    </Typography>
                    <Typography color="secondary">last reset residual</Typography>
                  </div>
                  <div className="bg-background-70 rounded-lg p-2">
                    <Typography bold>
                      {device.lastTrainingTargetDeg >= 0 ? '+' : ''}
                      {device.lastTrainingTargetDeg.toFixed(2)}°
                    </Typography>
                    <Typography color="secondary">
                      retained-window target
                    </Typography>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
