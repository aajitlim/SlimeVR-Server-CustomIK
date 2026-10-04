import { useEffect, useRef } from 'react';
import {
  BufferGeometry,
  CylinderGeometry,
  GridHelper,
  Line,
  LineBasicMaterial,
  Mesh,
  MeshBasicMaterial,
  OrthographicCamera,
  RingGeometry,
  Scene,
  Vector3,
  WebGLRenderer,
} from 'three';
import { Typography } from '@/components/commons/Typography';
import {
  RETARGET_ROLE_LABEL,
  TrackerRetargetRole,
  TrackerSpringBoneAdjustment,
} from '@/hooks/tracker-retarget';

const DISC_RADIUS_METERS = 0.058;
const DISC_HEIGHT_METERS = 0.012;
const PREVIEW_DAMPING_RATIO = 0.38;
const GRAVITY_MPS2 = 9.80665;

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function lerp(from: number, to: number, t: number) {
  return from + (to - from) * t;
}

function softClampDrive(value: number) {
  const limit = 3;
  return limit * Math.tanh(value / limit);
}

export function SpringBoneCloseupWidget({
  role,
  spring,
  useAcceleration,
}: {
  role: TrackerRetargetRole;
  spring: TrackerSpringBoneAdjustment;
  useAcceleration: boolean;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const springRef = useRef(spring);
  const useAccelerationRef = useRef(useAcceleration);

  const accelerationBarRef = useRef<HTMLDivElement>(null);
  const jerkBarRef = useRef<HTMLDivElement>(null);
  const snapBarRef = useRef<HTMLDivElement>(null);
  const driveBarRef = useRef<HTMLDivElement>(null);
  const accelerationTextRef = useRef<HTMLSpanElement>(null);
  const jerkTextRef = useRef<HTMLSpanElement>(null);
  const snapTextRef = useRef<HTMLSpanElement>(null);
  const driveTextRef = useRef<HTMLSpanElement>(null);

  springRef.current = spring;
  useAccelerationRef.current = useAcceleration;

  useEffect(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;

    const scene = new Scene();
    const renderer = new WebGLRenderer({
      canvas,
      alpha: true,
      antialias: true,
    });

    const camera = new OrthographicCamera(-0.15, 0.15, 0.15, -0.15, 0.01, 10);
    camera.position.set(0.32, 0.18, 0.42);
    camera.lookAt(0, 0, 0);

    const grid = new GridHelper(0.5, 20, 0x2c2c6b, 0x2c2c6b);
    grid.position.y = -0.11;
    scene.add(grid);

    const restDisc = new Mesh(
      new CylinderGeometry(
        DISC_RADIUS_METERS,
        DISC_RADIUS_METERS,
        DISC_HEIGHT_METERS,
        48
      ),
      new MeshBasicMaterial({
        color: 0x44e4ff,
        transparent: true,
        opacity: 0.22,
        wireframe: true,
        depthTest: false,
      })
    );
    restDisc.renderOrder = 5;
    scene.add(restDisc);

    const movingDisc = new Mesh(
      new CylinderGeometry(
        DISC_RADIUS_METERS,
        DISC_RADIUS_METERS,
        DISC_HEIGHT_METERS,
        48
      ),
      new MeshBasicMaterial({
        color: 0xff67d8,
        transparent: true,
        opacity: 0.88,
        depthTest: false,
      })
    );
    movingDisc.renderOrder = 8;
    scene.add(movingDisc);

    const travelRail = new Line(
      new BufferGeometry(),
      new LineBasicMaterial({
        color: 0xff67d8,
        transparent: true,
        opacity: 0.75,
        depthTest: false,
      })
    );
    travelRail.renderOrder = 6;
    scene.add(travelRail);

    const centerLine = new Line(
      new BufferGeometry().setFromPoints([
        new Vector3(-0.085, 0, 0),
        new Vector3(0.085, 0, 0),
      ]),
      new LineBasicMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 0.45,
        depthTest: false,
      })
    );
    centerLine.renderOrder = 4;
    scene.add(centerLine);

    const upperLimit = new Mesh(
      new RingGeometry(0.064, 0.068, 48),
      new MeshBasicMaterial({
        color: 0xff67d8,
        transparent: true,
        opacity: 0.72,
        depthTest: false,
      })
    );
    upperLimit.rotation.x = -Math.PI / 2;
    scene.add(upperLimit);

    const lowerLimit = upperLimit.clone();
    lowerLimit.material = (upperLimit.material as MeshBasicMaterial).clone();
    scene.add(lowerLimit);

    let springOffset = 0;
    let springVelocity = 0;
    let lastTime = performance.now();
    let pulseStart = lastTime;
    let lastGeometryDistance = -1;

    let filteredAccelerationG = 0;
    let previousAccelerationG = 0;
    let filteredJerkGPerSecond = 0;
    let previousJerkGPerSecond = 0;
    let filteredSnapGPerSecondSquared = 0;
    let hasDerivativeHistory = false;

    const updateGeometry = () => {
      const distance = Math.max(0, springRef.current.distance);
      travelRail.geometry.setFromPoints([
        new Vector3(0, -distance, 0),
        new Vector3(0, distance, 0),
      ]);
      upperLimit.position.y = distance;
      lowerLimit.position.y = -distance;

      const visibleExtent = Math.max(
        DISC_RADIUS_METERS * 1.8,
        distance + DISC_RADIUS_METERS * 1.2
      );
      const halfView = Math.max(0.12, visibleExtent * 1.35);
      const aspect = Math.max(1, container.clientWidth / container.clientHeight);

      camera.left = -halfView * aspect;
      camera.right = halfView * aspect;
      camera.top = halfView;
      camera.bottom = -halfView;
      camera.updateProjectionMatrix();
    };

    const setMeter = (
      bar: HTMLDivElement | null,
      text: HTMLSpanElement | null,
      value: number
    ) => {
      if (bar) {
        bar.style.width = `${Math.min(100, Math.abs(value) * 100)}%`;
        bar.style.opacity = value === 0 ? '0.2' : '1';
      }
      if (text) {
        text.textContent = `${value >= 0 ? '+' : ''}${value.toFixed(2)}`;
      }
    };

    updateGeometry();

    const resize = () => {
      const width = Math.max(1, container.clientWidth);
      const height = Math.max(1, container.clientHeight);
      renderer.setSize(width, height, false);
      updateGeometry();
    };

    const observer = new ResizeObserver(resize);
    observer.observe(container);
    resize();

    let frameId = 0;
    const animate = (now: number) => {
      frameId = requestAnimationFrame(animate);

      const dt = Math.min(0.05, Math.max(1 / 240, (now - lastTime) / 1000));
      lastTime = now;

      const currentSpring = springRef.current;
      const distance = Math.max(0, currentSpring.distance);
      const strength = Math.max(1, currentSpring.strength);
      const pull = Math.max(0, currentSpring.pull);
      const accelerationMode = useAccelerationRef.current;

      if (distance !== lastGeometryDistance) {
        updateGeometry();
        lastGeometryDistance = distance;
      }

      let pulseAge = (now - pulseStart) / 1000;
      let pulseRestarted = false;
      if (pulseAge > 2.3) {
        pulseStart = now;
        pulseAge = 0;
        pulseRestarted = true;
      }

      let previewAccelerationG = 0;
      if (pulseAge < 0.16) {
        previewAccelerationG =
          Math.sin((Math.PI * pulseAge) / 0.16) * 0.22;
      } else if (pulseAge < 0.3) {
        previewAccelerationG =
          -Math.sin((Math.PI * (pulseAge - 0.16)) / 0.14) * 0.1;
      }

      const response = clamp(currentSpring.derivativeResponse, 0, 1);
      const accelerationFilterHz = lerp(14, 36, response);
      const accelerationFollow = clamp(dt * accelerationFilterHz, 0, 1);
      filteredAccelerationG +=
        (previewAccelerationG - filteredAccelerationG) * accelerationFollow;

      let normalizedJerkG = 0;
      let normalizedSnapG = 0;
      let previewDriveG = 0;

      if (
        accelerationMode &&
        currentSpring.derivativeDriverEnabled
      ) {
        if (!hasDerivativeHistory) {
          previousAccelerationG = filteredAccelerationG;
          filteredJerkGPerSecond = 0;
          previousJerkGPerSecond = 0;
          filteredSnapGPerSecondSquared = 0;
          hasDerivativeHistory = true;
        } else {
          const rawJerk = clamp(
            (filteredAccelerationG - previousAccelerationG) / dt,
            -80,
            80
          );
          const jerkFilterHz = lerp(8, 28, response);
          filteredJerkGPerSecond +=
            (rawJerk - filteredJerkGPerSecond) *
            clamp(dt * jerkFilterHz, 0, 1);

          const rawSnap = clamp(
            (filteredJerkGPerSecond - previousJerkGPerSecond) / dt,
            -800,
            800
          );
          const snapFilterHz = lerp(6, 22, response);
          filteredSnapGPerSecondSquared +=
            (rawSnap - filteredSnapGPerSecondSquared) *
            clamp(dt * snapFilterHz, 0, 1);
        }

        const tau = lerp(0.18, 0.06, response);
        normalizedJerkG = filteredJerkGPerSecond * tau;
        normalizedSnapG =
          filteredSnapGPerSecondSquared * tau * tau;

        const accelerationWeight = clamp(
          currentSpring.accelerationWeight,
          0,
          2
        );
        const jerkWeight = clamp(currentSpring.jerkWeight, 0, 2);
        const snapWeight = clamp(currentSpring.snapWeight, 0, 2);
        const totalWeight =
          accelerationWeight + jerkWeight + snapWeight;

        const weightedDrive =
          totalWeight > 0
            ? (filteredAccelerationG * accelerationWeight +
                normalizedJerkG * jerkWeight +
                normalizedSnapG * snapWeight) /
              totalWeight
            : 0;

        previewDriveG = softClampDrive(weightedDrive);
        previousAccelerationG = filteredAccelerationG;
        previousJerkGPerSecond = filteredJerkGPerSecond;
      } else if (accelerationMode) {
        hasDerivativeHistory = false;
        filteredJerkGPerSecond = 0;
        previousJerkGPerSecond = 0;
        filteredSnapGPerSecondSquared = 0;
        previewDriveG = filteredAccelerationG;
      } else {
        hasDerivativeHistory = false;
        filteredAccelerationG = 0;
        filteredJerkGPerSecond = 0;
        previousJerkGPerSecond = 0;
        filteredSnapGPerSecondSquared = 0;
      }

      setMeter(
        accelerationBarRef.current,
        accelerationTextRef.current,
        filteredAccelerationG
      );
      setMeter(jerkBarRef.current, jerkTextRef.current, normalizedJerkG);
      setMeter(snapBarRef.current, snapTextRef.current, normalizedSnapG);
      setMeter(driveBarRef.current, driveTextRef.current, previewDriveG);

      if (!currentSpring.enabled || distance <= 0 || pull <= 0) {
        springOffset += (0 - springOffset) * Math.min(1, dt * 12);
        springVelocity *= Math.max(0, 1 - dt * 12);
      } else {
        if (accelerationMode) {
          springVelocity -= previewDriveG * GRAVITY_MPS2 * dt * pull;
        } else if (pulseRestarted) {
          springVelocity += Math.min(1.25, 0.22 + pull * 0.48);
        }

        const springAcceleration =
          -(strength * strength) * springOffset -
          2 *
            PREVIEW_DAMPING_RATIO *
            strength *
            springVelocity;

        springVelocity += springAcceleration * dt;
        springOffset += springVelocity * dt;

        if (Math.abs(springOffset) > distance) {
          springOffset = Math.max(-distance, Math.min(distance, springOffset));
          if (
            (springOffset > 0 && springVelocity > 0) ||
            (springOffset < 0 && springVelocity < 0)
          ) {
            springVelocity *= -0.16;
          }
        }
      }

      movingDisc.position.y = springOffset;
      renderer.render(scene, camera);
    };

    frameId = requestAnimationFrame(animate);

    return () => {
      cancelAnimationFrame(frameId);
      observer.disconnect();

      restDisc.geometry.dispose();
      (restDisc.material as MeshBasicMaterial).dispose();
      movingDisc.geometry.dispose();
      (movingDisc.material as MeshBasicMaterial).dispose();
      travelRail.geometry.dispose();
      (travelRail.material as LineBasicMaterial).dispose();
      centerLine.geometry.dispose();
      (centerLine.material as LineBasicMaterial).dispose();
      upperLimit.geometry.dispose();
      (upperLimit.material as MeshBasicMaterial).dispose();
      lowerLimit.geometry.dispose();
      (lowerLimit.material as MeshBasicMaterial).dispose();
      renderer.dispose();
    };
  }, []);

  const driverLabel = !useAcceleration
    ? 'Position derived'
    : spring.derivativeDriverEnabled
      ? 'IMU derivative A / J / S + fallback'
      : 'IMU acceleration + fallback';

  return (
    <div className="flex flex-col gap-2">
      <div className="relative rounded-lg overflow-hidden bg-background-60 h-[360px]">
        <div ref={containerRef} className="absolute inset-0">
          <canvas ref={canvasRef} className="w-full h-full" />
        </div>

        <div className="absolute top-3 left-3 bg-background-80/90 rounded-lg p-3 pointer-events-none">
          <Typography bold>
            {RETARGET_ROLE_LABEL[role]} spring close-up
          </Typography>
          <Typography color="secondary">
            Disc radius {(DISC_RADIUS_METERS * 100).toFixed(1)} cm
          </Typography>
          <Typography color="secondary">
            Driver: {driverLabel}
          </Typography>
        </div>

        <div className="absolute top-3 right-3 bg-background-80/90 rounded-lg p-3 pointer-events-none text-right">
          <Typography bold>
            ±{(spring.distance * 100).toFixed(1)} cm Y
          </Typography>
          <Typography color="secondary">
            Strength {spring.strength.toFixed(1)} · Pull {spring.pull.toFixed(2)}
          </Typography>
        </div>

        <div className="absolute bottom-3 left-3 right-3 bg-background-80/90 rounded-lg p-3 pointer-events-none">
          <Typography bold>
            Cyan wireframe = solved/rest disc · Magenta = moving spring disc
          </Typography>
          <Typography color="secondary">
            The rings are the hard travel limits. This close-up generates a
            deterministic motion pulse and runs it through the configured
            driver; it is not live tracker telemetry.
          </Typography>
        </div>
      </div>

      <div className="bg-background-60 rounded-lg p-3 flex flex-col gap-2">
        <Typography variant="section-title">Derivative preview</Typography>
        <Typography color="secondary">
          Normalized preview values from the synthetic motion pulse. These show
          which term is contributing before the weighted drive reaches the
          spring.
        </Typography>

        {[
          ['Acceleration', accelerationBarRef, accelerationTextRef, '#44e4ff'],
          ['Jerk', jerkBarRef, jerkTextRef, '#ff67d8'],
          ['Snap', snapBarRef, snapTextRef, '#ffd54a'],
          ['Combined drive', driveBarRef, driveTextRef, '#ffffff'],
        ].map(([label, barRef, textRef, color]) => (
          <div key={label as string} className="grid grid-cols-[100px_1fr_58px] items-center gap-2">
            <Typography>{label as string}</Typography>
            <div className="h-2 rounded overflow-hidden bg-background-80">
              <div
                ref={(barRef as React.RefObject<HTMLDivElement>).current ? undefined : barRef as React.RefObject<HTMLDivElement>}
                className="h-full rounded"
                style={{ width: '0%', backgroundColor: color as string }}
              />
            </div>
            <span
              ref={(textRef as React.RefObject<HTMLSpanElement>).current ? undefined : textRef as React.RefObject<HTMLSpanElement>}
              className="text-right text-sm"
            >
              +0.00
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
