import { useEffect, useState } from 'react';
import { BodyPart } from 'solarxr-protocol';
import { useWebsocketAPI } from '@/hooks/websocket-api';

export const RETARGET_ROLES = [
  'waist',
  'chest',
  'left_knee',
  'right_knee',
  'left_foot',
  'right_foot',
  'left_elbow',
  'right_elbow',
  'left_hand',
  'right_hand',
] as const;

export type TrackerRetargetRole = (typeof RETARGET_ROLES)[number];
export type TrackerRetargetSpace = 'body_yaw' | 'world';

export type TrackerRetargetAdjustment = {
  enabled: boolean;
  x: number;
  y: number;
  z: number;
  space: TrackerRetargetSpace;
};

export type TrackerSpringBoneAdjustment = {
  enabled: boolean;
  distance: number;
  strength: number;
  pull: number;
  derivativeDriverEnabled: boolean;
  accelerationWeight: number;
  jerkWeight: number;
  snapWeight: number;
  derivativeResponse: number;
};

export const BONE_COMPLIANCE_SEGMENTS = [
  'upper_chest_to_chest',
  'chest_to_waist',
  'waist_to_hip',
] as const;

export type BoneComplianceSegmentKey =
  (typeof BONE_COMPLIANCE_SEGMENTS)[number];

export type BoneComplianceSegmentAdjustment = {
  enabled: boolean;
  compliance: number;
  compressionLimit: number;
  extensionLimit: number;
  sensorInfluence: number;
};

export const BONE_COMPLIANCE_SEGMENT_LABEL: Record<
  BoneComplianceSegmentKey,
  string
> = {
  upper_chest_to_chest: 'Upper chest → Chest',
  chest_to_waist: 'Chest → Waist',
  waist_to_hip: 'Waist → Hip',
};

export type TrackerRetargetConfig = {
  enabled: boolean;
  hipFloorLiftWeight: number;
  spineArticulationEnabled: boolean;
  spineCurvePower: number;
  springBonesEnabled: boolean;
  springBonesUseAcceleration: boolean;
  boneComplianceEnabled: boolean;
  boneComplianceOverall: number;
  boneCompliancePreserveTorsoLength: boolean;
  boneComplianceResponse: number;
  trackers: Record<TrackerRetargetRole, TrackerRetargetAdjustment>;
  springBones: Record<TrackerRetargetRole, TrackerSpringBoneAdjustment>;
  boneComplianceSegments: Record<
    BoneComplianceSegmentKey,
    BoneComplianceSegmentAdjustment
  >;
};

export const RETARGET_ROLE_BODY_PART: Record<TrackerRetargetRole, BodyPart> = {
  waist: BodyPart.HIP,
  chest: BodyPart.UPPER_CHEST,
  left_knee: BodyPart.LEFT_UPPER_LEG,
  right_knee: BodyPart.RIGHT_UPPER_LEG,
  left_foot: BodyPart.LEFT_FOOT,
  right_foot: BodyPart.RIGHT_FOOT,
  left_elbow: BodyPart.LEFT_UPPER_ARM,
  right_elbow: BodyPart.RIGHT_UPPER_ARM,
  left_hand: BodyPart.LEFT_HAND,
  right_hand: BodyPart.RIGHT_HAND,
};

export const RETARGET_ROLE_LABEL: Record<TrackerRetargetRole, string> = {
  waist: 'Hip / Waist',
  chest: 'Chest',
  left_knee: 'Left knee',
  right_knee: 'Right knee',
  left_foot: 'Left foot',
  right_foot: 'Right foot',
  left_elbow: 'Left elbow',
  right_elbow: 'Right elbow',
  left_hand: 'Left hand',
  right_hand: 'Right hand',
};

const defaultAdjustment = (): TrackerRetargetAdjustment => ({
  enabled: false,
  x: 0,
  y: 0,
  z: 0,
  space: 'body_yaw',
});

const defaultSpringBone = (): TrackerSpringBoneAdjustment => ({
  enabled: false,
  distance: 0.03,
  strength: 12,
  pull: 0.65,
  derivativeDriverEnabled: false,
  accelerationWeight: 0.2,
  jerkWeight: 0.7,
  snapWeight: 0.1,
  derivativeResponse: 0.55,
});

const defaultBoneComplianceSegment = (
  key: BoneComplianceSegmentKey
): BoneComplianceSegmentAdjustment => {
  switch (key) {
    case 'upper_chest_to_chest':
      return {
        enabled: true,
        compliance: 0.45,
        compressionLimit: 0.025,
        extensionLimit: 0.015,
        sensorInfluence: 0.35,
      };
    case 'chest_to_waist':
      return {
        enabled: true,
        compliance: 0.65,
        compressionLimit: 0.04,
        extensionLimit: 0.025,
        sensorInfluence: 0.55,
      };
    case 'waist_to_hip':
      return {
        enabled: true,
        compliance: 0.55,
        compressionLimit: 0.035,
        extensionLimit: 0.02,
        sensorInfluence: 0.5,
      };
    default:
      return {
        enabled: false,
        compliance: 0,
        compressionLimit: 0,
        extensionLimit: 0,
        sensorInfluence: 0,
      };
  }
};

export const makeDefaultTrackerRetargetConfig = (): TrackerRetargetConfig => ({
  enabled: false,
  hipFloorLiftWeight: 0,
  spineArticulationEnabled: true,
  spineCurvePower: 1,
  springBonesEnabled: false,
  springBonesUseAcceleration: false,
  boneComplianceEnabled: false,
  boneComplianceOverall: 0.5,
  boneCompliancePreserveTorsoLength: true,
  boneComplianceResponse: 0.5,
  trackers: Object.fromEntries(
    RETARGET_ROLES.map((role) => [role, defaultAdjustment()])
  ) as Record<TrackerRetargetRole, TrackerRetargetAdjustment>,
  springBones: Object.fromEntries(
    RETARGET_ROLES.map((role) => [role, defaultSpringBone()])
  ) as Record<TrackerRetargetRole, TrackerSpringBoneAdjustment>,
  boneComplianceSegments: Object.fromEntries(
    BONE_COMPLIANCE_SEGMENTS.map((key) => [
      key,
      defaultBoneComplianceSegment(key),
    ])
  ) as Record<BoneComplianceSegmentKey, BoneComplianceSegmentAdjustment>,
});

type TrackerRetargetMessage = Omit<
  Partial<TrackerRetargetConfig>,
  'trackers' | 'springBones' | 'boneComplianceSegments'
> & {
  type?: string;
  trackers?: Partial<
    Record<TrackerRetargetRole, Partial<TrackerRetargetAdjustment>>
  >;
  springBones?: Partial<
    Record<TrackerRetargetRole, Partial<TrackerSpringBoneAdjustment>>
  >;
  boneComplianceSegments?: Partial<
    Record<
      BoneComplianceSegmentKey,
      Partial<BoneComplianceSegmentAdjustment>
    >
  >;
};

export type TrackerRetargetSyncState = 'loading' | 'saving' | 'synced';

function finiteNumber(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export function normalizeTrackerRetargetConfig(
  message: TrackerRetargetMessage
): TrackerRetargetConfig {
  const defaults = makeDefaultTrackerRetargetConfig();

  const trackers = Object.fromEntries(
    RETARGET_ROLES.map((role) => {
      const current = message.trackers?.[role];
      const fallback = defaults.trackers[role];

      return [
        role,
        {
          enabled:
            typeof current?.enabled === 'boolean'
              ? current.enabled
              : fallback.enabled,
          x: finiteNumber(current?.x, fallback.x),
          y: finiteNumber(current?.y, fallback.y),
          z: finiteNumber(current?.z, fallback.z),
          space: current?.space === 'world' ? 'world' : 'body_yaw',
        },
      ];
    })
  ) as Record<TrackerRetargetRole, TrackerRetargetAdjustment>;

  const springBones = Object.fromEntries(
    RETARGET_ROLES.map((role) => {
      const current = message.springBones?.[role];
      const fallback = defaults.springBones[role];

      return [
        role,
        {
          enabled:
            typeof current?.enabled === 'boolean'
              ? current.enabled
              : fallback.enabled,
          distance: Math.min(
            0.25,
            Math.max(0, finiteNumber(current?.distance, fallback.distance))
          ),
          strength: Math.min(
            30,
            Math.max(1, finiteNumber(current?.strength, fallback.strength))
          ),
          pull: Math.min(
            2,
            Math.max(0, finiteNumber(current?.pull, fallback.pull))
          ),
          derivativeDriverEnabled:
            typeof current?.derivativeDriverEnabled === 'boolean'
              ? current.derivativeDriverEnabled
              : fallback.derivativeDriverEnabled,
          accelerationWeight: Math.min(
            2,
            Math.max(
              0,
              finiteNumber(
                current?.accelerationWeight,
                fallback.accelerationWeight
              )
            )
          ),
          jerkWeight: Math.min(
            2,
            Math.max(0, finiteNumber(current?.jerkWeight, fallback.jerkWeight))
          ),
          snapWeight: Math.min(
            2,
            Math.max(0, finiteNumber(current?.snapWeight, fallback.snapWeight))
          ),
          derivativeResponse: Math.min(
            1,
            Math.max(
              0,
              finiteNumber(
                current?.derivativeResponse,
                fallback.derivativeResponse
              )
            )
          ),
        },
      ];
    })
  ) as Record<TrackerRetargetRole, TrackerSpringBoneAdjustment>;

  const boneComplianceSegments = Object.fromEntries(
    BONE_COMPLIANCE_SEGMENTS.map((key) => {
      const current = message.boneComplianceSegments?.[key];
      const fallback = defaults.boneComplianceSegments[key];

      return [
        key,
        {
          enabled:
            typeof current?.enabled === 'boolean'
              ? current.enabled
              : fallback.enabled,
          compliance: Math.min(
            1,
            Math.max(
              0,
              finiteNumber(current?.compliance, fallback.compliance)
            )
          ),
          compressionLimit: Math.min(
            0.12,
            Math.max(
              0,
              finiteNumber(
                current?.compressionLimit,
                fallback.compressionLimit
              )
            )
          ),
          extensionLimit: Math.min(
            0.12,
            Math.max(
              0,
              finiteNumber(
                current?.extensionLimit,
                fallback.extensionLimit
              )
            )
          ),
          sensorInfluence: Math.min(
            1,
            Math.max(
              0,
              finiteNumber(
                current?.sensorInfluence,
                fallback.sensorInfluence
              )
            )
          ),
        },
      ];
    })
  ) as Record<BoneComplianceSegmentKey, BoneComplianceSegmentAdjustment>;

  return {
    enabled:
      typeof message.enabled === 'boolean' ? message.enabled : defaults.enabled,
    hipFloorLiftWeight: Math.min(
      1,
      Math.max(
        0,
        finiteNumber(
          message.hipFloorLiftWeight,
          defaults.hipFloorLiftWeight
        )
      )
    ),
    spineArticulationEnabled:
      typeof message.spineArticulationEnabled === 'boolean'
        ? message.spineArticulationEnabled
        : defaults.spineArticulationEnabled,
    spineCurvePower: Math.min(
      4,
      Math.max(
        0.25,
        finiteNumber(message.spineCurvePower, defaults.spineCurvePower)
      )
    ),
    springBonesEnabled:
      typeof message.springBonesEnabled === 'boolean'
        ? message.springBonesEnabled
        : defaults.springBonesEnabled,
    springBonesUseAcceleration:
      typeof message.springBonesUseAcceleration === 'boolean'
        ? message.springBonesUseAcceleration
        : defaults.springBonesUseAcceleration,
    boneComplianceEnabled:
      typeof message.boneComplianceEnabled === 'boolean'
        ? message.boneComplianceEnabled
        : defaults.boneComplianceEnabled,
    boneComplianceOverall: Math.min(
      1,
      Math.max(
        0,
        finiteNumber(
          message.boneComplianceOverall,
          defaults.boneComplianceOverall
        )
      )
    ),
    boneCompliancePreserveTorsoLength:
      typeof message.boneCompliancePreserveTorsoLength === 'boolean'
        ? message.boneCompliancePreserveTorsoLength
        : defaults.boneCompliancePreserveTorsoLength,
    boneComplianceResponse: Math.min(
      1,
      Math.max(
        0,
        finiteNumber(
          message.boneComplianceResponse,
          defaults.boneComplianceResponse
        )
      )
    ),
    trackers,
    springBones,
    boneComplianceSegments,
  };
}

export function useTrackerRetargeting() {
  const { isConnected, useTextPacket, sendTextPacket } = useWebsocketAPI();
  const [config, setConfig] = useState<TrackerRetargetConfig>(
    makeDefaultTrackerRetargetConfig
  );
  const [loaded, setLoaded] = useState(false);
  const [syncState, setSyncState] =
    useState<TrackerRetargetSyncState>('loading');

  useTextPacket<TrackerRetargetMessage>('retarget_config', (message) => {
    setConfig(normalizeTrackerRetargetConfig(message));
    setLoaded(true);
    setSyncState('synced');
  });

  useEffect(() => {
    if (!isConnected) return;
    sendTextPacket({ type: 'retarget_get' });
  }, [isConnected]);

  const updateConfig = (nextConfig: TrackerRetargetConfig) => {
    const normalized = normalizeTrackerRetargetConfig(nextConfig);
    setConfig(normalized);
    setSyncState('saving');
    sendTextPacket({
      type: 'retarget_set',
      enabled: normalized.enabled,
      hipFloorLiftWeight: normalized.hipFloorLiftWeight,
      spineArticulationEnabled: normalized.spineArticulationEnabled,
      spineCurvePower: normalized.spineCurvePower,
      springBonesEnabled: normalized.springBonesEnabled,
      springBonesUseAcceleration: normalized.springBonesUseAcceleration,
      boneComplianceEnabled: normalized.boneComplianceEnabled,
      boneComplianceOverall: normalized.boneComplianceOverall,
      boneCompliancePreserveTorsoLength:
        normalized.boneCompliancePreserveTorsoLength,
      boneComplianceResponse: normalized.boneComplianceResponse,
      trackers: normalized.trackers,
      springBones: normalized.springBones,
      boneComplianceSegments: normalized.boneComplianceSegments,
    });
  };

  return {
    config,
    loaded,
    syncState,
    updateConfig,
    refresh: () => {
      setSyncState('loading');
      sendTextPacket({ type: 'retarget_get' });
    },
  };
}
