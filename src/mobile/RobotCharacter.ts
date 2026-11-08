import * as THREE from 'three';

type FrameInput = {
  activity?: string;
  emotion?: string;
  outputAnalyser?: AnalyserNode | null;
  inputAnalyser?: AnalyserNode | null;
};

/**
 * A self-contained, procedural 3D male android companion for the Android build.
 * It deliberately uses Three.js geometry rather than an external licensed model,
 * so the character is included in the app bundle and never depends on a missing
 * PMX/VRM file or downloaded artwork.
 */
export class RobotCharacter {
  readonly stage = { clearPan: () => this.setCameraMode('full') };
  private readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera: THREE.PerspectiveCamera;
  private readonly clock = new THREE.Clock();
  private readonly root = new THREE.Group();
  private readonly torso = new THREE.Group();
  private readonly head = new THREE.Group();
  private readonly shoulderLeft = new THREE.Group();
  private readonly shoulderRight = new THREE.Group();
  private readonly elbowLeft = new THREE.Group();
  private readonly elbowRight = new THREE.Group();
  private readonly hipLeft = new THREE.Group();
  private readonly hipRight = new THREE.Group();
  private readonly kneeLeft = new THREE.Group();
  private readonly kneeRight = new THREE.Group();
  private readonly mouth: THREE.Mesh;
  private readonly core: THREE.Mesh;
  private readonly eyeLeft: THREE.Mesh;
  private readonly eyeRight: THREE.Mesh;
  private readonly materials: THREE.MeshStandardMaterial[] = [];
  private running = false;
  private disposed = false;
  private frame = 0;
  private width = 1;
  private height = 1;
  private pointer = new THREE.Vector2();
  private pointerTarget = new THREE.Vector2();
  private activity = 'idle';
  private emotion = 'idle';
  private behaviour = '';
  private behaviourStarted = 0;
  private sitting = false;
  private fullBody = true;
  private baseCameraDistance = 6.7;

  constructor(private readonly canvas: HTMLCanvasElement, quality: 'low' | 'balanced' | 'high' = 'low') {
    this.renderer = new THREE.WebGLRenderer({
      canvas,
      alpha: true,
      antialias: quality !== 'low',
      powerPreference: 'high-performance',
      stencil: false,
      depth: true,
    });
    this.renderer.setClearColor(0x000000, 0);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.12;
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, quality === 'high' ? 1.5 : quality === 'balanced' ? 1 : 0.85));

    this.camera = new THREE.PerspectiveCamera(34, 1, 0.1, 100);
    this.camera.position.set(0, 1.92, this.baseCameraDistance);
    this.camera.lookAt(0, 1.78, 0);
    this.scene.add(this.camera);

    this.scene.add(new THREE.HemisphereLight(0xb8e9ff, 0x101827, 1.8));
    const key = new THREE.DirectionalLight(0xe5f4ff, 2.4);
    key.position.set(-3.5, 5.5, 5);
    this.scene.add(key);
    const rim = new THREE.PointLight(0x13cfff, 24, 9, 2);
    rim.position.set(2.5, 2.8, -2.2);
    this.scene.add(rim);
    const fill = new THREE.PointLight(0x4a7dff, 13, 8, 2);
    fill.position.set(-3, 1.4, 2.5);
    this.scene.add(fill);

    const metal = this.material(0x26394b, 0.78, 0.24);
    const lightMetal = this.material(0x9bb6c7, 0.82, 0.2);
    const dark = this.material(0x101a27, 0.38, 0.48);
    const face = this.material(0x081421, 0.62, 0.2);
    const trim = this.material(0x3d6b85, 0.8, 0.24);
    const cyan = this.material(0x35dfff, 0.55, 0.18, 0x16bfff, 1.65);
    const blue = this.material(0x397cff, 0.55, 0.2, 0x225dff, 1.1);
    const soft = this.material(0x6e8796, 0.72, 0.25);

    // Small grounded platform and a subtle ring beneath the android.
    const floorRing = new THREE.Mesh(new THREE.TorusGeometry(0.82, 0.012, 8, 64), cyan);
    floorRing.rotation.x = -Math.PI / 2;
    floorRing.position.y = 0.035;
    this.root.add(floorRing);
    const floorDisc = new THREE.Mesh(new THREE.CylinderGeometry(0.72, 0.78, 0.07, 48), dark);
    floorDisc.position.y = 0.015;
    this.root.add(floorDisc);

    // Legs: articulated metallic thigh, knee, shin and boot assemblies.
    this.root.add(this.hipLeft, this.hipRight);
    this.makeLeg(this.hipLeft, this.kneeLeft, -0.255, metal, lightMetal, dark, cyan);
    this.makeLeg(this.hipRight, this.kneeRight, 0.255, metal, lightMetal, dark, cyan);

    // Waist and armored torso.
    this.torso.position.set(0, 1.56, 0);
    this.root.add(this.torso);
    this.mesh(this.torso, new THREE.SphereGeometry(0.54, 28, 22), metal, [0, 0.44, 0], [0.94, 1.15, 0.58]);
    this.mesh(this.torso, new THREE.BoxGeometry(0.76, 0.76, 0.2), lightMetal, [0, 0.43, 0.29], [1, 1, 1]);
    this.mesh(this.torso, new THREE.BoxGeometry(0.64, 0.64, 0.18), dark, [0, 0.46, 0.405], [1, 1, 1]);
    this.mesh(this.torso, new THREE.BoxGeometry(0.08, 0.5, 0.025), trim, [0, 0.46, 0.505], [1, 1, 1]);
    this.core = new THREE.Mesh(new THREE.IcosahedronGeometry(0.145, 2), cyan);
    this.core.position.set(0, 0.49, 0.525);
    this.torso.add(this.core);
    const coreRing = new THREE.Mesh(new THREE.TorusGeometry(0.205, 0.022, 8, 40), lightMetal);
    coreRing.position.set(0, 0.49, 0.52);
    this.torso.add(coreRing);
    this.mesh(this.torso, new THREE.BoxGeometry(0.22, 0.07, 0.03), blue, [0, 0.07, 0.49], [1, 1, 1]);
    this.mesh(this.torso, new THREE.BoxGeometry(0.54, 0.055, 0.04), trim, [0, 0.79, 0.2], [1, 1, 1]);

    // Neck and the two layered shoulder housings.
    const neck = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.19, 0.25, 16), dark);
    neck.position.set(0, 2.48, 0);
    this.root.add(neck);
    this.root.add(this.shoulderLeft, this.shoulderRight);
    this.shoulderLeft.position.set(-0.57, 2.28, 0);
    this.shoulderRight.position.set(0.57, 2.28, 0);
    this.makeArm(this.shoulderLeft, this.elbowLeft, -1, metal, lightMetal, dark, cyan);
    this.makeArm(this.shoulderRight, this.elbowRight, 1, metal, lightMetal, dark, cyan);

    // A masculine helmet silhouette, dark glass faceplate, luminous eyes,
    // segmented crown fins and small side audio/sensor modules.
    this.head.position.set(0, 3.08, 0);
    this.root.add(this.head);
    this.mesh(this.head, new THREE.SphereGeometry(0.48, 32, 28), lightMetal, [0, 0.08, 0], [0.84, 1.0, 0.72]);
    this.mesh(this.head, new THREE.SphereGeometry(0.41, 32, 24), metal, [0, 0.105, 0.035], [0.88, 1.02, 0.78]);
    this.mesh(this.head, new THREE.SphereGeometry(0.345, 32, 24), face, [0, 0.04, 0.298], [0.98, 0.82, 0.28]);
    this.eyeLeft = this.mesh(this.head, new THREE.SphereGeometry(0.075, 20, 16), cyan, [-0.145, 0.075, 0.385], [1.28, 0.72, 0.36]);
    this.eyeRight = this.mesh(this.head, new THREE.SphereGeometry(0.075, 20, 16), cyan, [0.145, 0.075, 0.385], [1.28, 0.72, 0.36]);
    for (const x of [-0.145, 0.145]) {
      const eyeFrame = new THREE.Mesh(new THREE.TorusGeometry(0.095, 0.014, 8, 28), trim);
      eyeFrame.position.set(x, 0.075, 0.377);
      this.head.add(eyeFrame);
    }
    this.mouth = this.mesh(this.head, new THREE.BoxGeometry(0.16, 0.018, 0.018), blue, [0, -0.115, 0.386], [1, 1, 1]);
    this.mesh(this.head, new THREE.BoxGeometry(0.34, 0.035, 0.025), trim, [0, 0.255, 0.35], [1, 1, 1]);
    this.mesh(this.head, new THREE.SphereGeometry(0.12, 18, 14), dark, [-0.38, 0.03, 0], [0.35, 1.05, 0.9]);
    this.mesh(this.head, new THREE.SphereGeometry(0.12, 18, 14), dark, [0.38, 0.03, 0], [0.35, 1.05, 0.9]);
    for (const side of [-1, 1]) {
      const ear = new THREE.Mesh(new THREE.TorusGeometry(0.095, 0.025, 8, 24), cyan);
      ear.position.set(side * 0.405, 0.035, 0.02);
      ear.rotation.y = side * 0.28;
      this.head.add(ear);
      this.mesh(this.head, new THREE.BoxGeometry(0.12, 0.035, 0.11), trim, [side * 0.17, 0.45, -0.005], [1, 1, 1]);
      const fin = this.mesh(this.head, new THREE.BoxGeometry(0.11, 0.25, 0.12), metal, [side * 0.19, 0.55, -0.015], [1, 1, 1]);
      fin.rotation.z = -side * 0.28;
      const stripe = this.mesh(this.head, new THREE.BoxGeometry(0.028, 0.16, 0.02), cyan, [side * 0.19, 0.56, 0.055], [1, 1, 1]);
      stripe.rotation.z = -side * 0.28;
    }
    // A swept-back crest gives the android a young male silhouette without a human hair asset.
    for (let i = 0; i < 4; i++) {
      const crest = this.mesh(this.head, new THREE.BoxGeometry(0.16, 0.26 - i * 0.025, 0.28), metal, [-0.13 + i * 0.085, 0.56 + (i % 2) * 0.04, -0.12 - i * 0.045], [1, 1, 1]);
      crest.rotation.z = -0.3 + i * 0.06;
      crest.rotation.x = -0.35;
    }
    this.mesh(this.head, new THREE.BoxGeometry(0.23, 0.025, 0.05), cyan, [0, 0.49, 0.075], [1, 1, 1]);

    this.scene.add(this.root);
    this.root.position.y = 0;
    this.resize(canvas.clientWidth || 1, canvas.clientHeight || 1);
  }

  private material(color: number, metalness: number, roughness: number, emissive = 0x000000, emissiveIntensity = 0): THREE.MeshStandardMaterial {
    const mat = new THREE.MeshStandardMaterial({ color, metalness, roughness, emissive, emissiveIntensity });
    this.materials.push(mat);
    return mat;
  }

  private mesh(parent: THREE.Object3D, geometry: THREE.BufferGeometry, material: THREE.Material, position: [number, number, number], scale: [number, number, number] = [1, 1, 1]): THREE.Mesh {
    const item = new THREE.Mesh(geometry, material);
    item.position.set(...position);
    item.scale.set(...scale);
    item.castShadow = false;
    item.receiveShadow = false;
    parent.add(item);
    return item;
  }

  private makeArm(pivot: THREE.Group, elbow: THREE.Group, side: -1 | 1, metal: THREE.Material, silver: THREE.Material, dark: THREE.Material, glow: THREE.Material): void {
    this.mesh(pivot, new THREE.SphereGeometry(0.22, 20, 16), silver, [0, -0.03, 0], [1, 0.96, 0.86]);
    this.mesh(pivot, new THREE.BoxGeometry(0.24, 0.56, 0.24), metal, [0, -0.36, 0], [1, 1, 1]);
    this.mesh(pivot, new THREE.BoxGeometry(0.19, 0.34, 0.035), silver, [0, -0.34, 0.135], [1, 1, 1]);
    this.mesh(pivot, new THREE.BoxGeometry(0.055, 0.32, 0.02), glow, [side * -0.015, -0.34, 0.16], [1, 1, 1]);
    elbow.position.set(0, -0.67, 0);
    pivot.add(elbow);
    this.mesh(elbow, new THREE.SphereGeometry(0.15, 18, 14), dark, [0, 0, 0], [1, 1, 0.9]);
    this.mesh(elbow, new THREE.TorusGeometry(0.12, 0.025, 8, 20), glow, [0, 0, 0.025], [1, 1, 1]);
    this.mesh(elbow, new THREE.BoxGeometry(0.19, 0.51, 0.22), metal, [0, -0.3, 0], [1, 1, 1]);
    this.mesh(elbow, new THREE.BoxGeometry(0.15, 0.28, 0.035), silver, [0, -0.29, 0.12], [1, 1, 1]);
    this.mesh(elbow, new THREE.SphereGeometry(0.11, 16, 14), dark, [0, -0.59, 0], [1, 0.9, 0.9]);
    this.mesh(elbow, new THREE.BoxGeometry(0.18, 0.23, 0.13), silver, [0, -0.7, 0.015], [1, 1, 1]);
    for (let i = -1; i <= 1; i++) this.mesh(elbow, new THREE.BoxGeometry(0.045, 0.15, 0.065), dark, [i * 0.055, -0.85, 0.06], [1, 1, 1]);
  }

  private makeLeg(hip: THREE.Group, knee: THREE.Group, x: number, metal: THREE.Material, silver: THREE.Material, dark: THREE.Material, glow: THREE.Material): void {
    hip.position.set(x, 1.46, 0);
    this.mesh(hip, new THREE.SphereGeometry(0.16, 18, 14), dark, [0, 0, 0], [1, 1, 0.9]);
    this.mesh(hip, new THREE.BoxGeometry(0.24, 0.62, 0.27), metal, [0, -0.34, 0], [1, 1, 1]);
    this.mesh(hip, new THREE.BoxGeometry(0.17, 0.36, 0.035), silver, [0, -0.31, 0.15], [1, 1, 1]);
    knee.position.set(0, -0.67, 0);
    hip.add(knee);
    this.mesh(knee, new THREE.SphereGeometry(0.16, 18, 14), silver, [0, 0, 0], [1, 0.88, 0.95]);
    this.mesh(knee, new THREE.TorusGeometry(0.12, 0.022, 8, 20), glow, [0, 0, 0.04], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.22, 0.58, 0.24), metal, [0, -0.35, 0], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.16, 0.34, 0.045), dark, [0, -0.33, 0.13], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.07, 0.26, 0.025), glow, [0, -0.33, 0.16], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.31, 0.19, 0.49), silver, [0, -0.69, 0.13], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.25, 0.085, 0.33), dark, [0, -0.62, 0.21], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.13, 0.025, 0.12), glow, [0, -0.575, 0.29], [1, 1, 1]);
  }

  async load(): Promise<void> {
    if (this.disposed) throw new Error('The 3D character has been disposed.');
    this.start();
  }

  get diagnostics(): Record<string, unknown> {
    return {
      character: 'Vishal Jarvis',
      characterType: 'procedural 3D male android',
      loaded: !this.disposed,
      running: this.running,
      renderer: 'Three.js WebGL',
      drawCalls: this.renderer.info.render.calls,
      geometries: this.renderer.info.memory.geometries,
      textures: this.renderer.info.memory.textures,
      activity: this.activity,
      behaviour: this.behaviour || 'idle',
    };
  }

  resize(width: number, height: number): void {
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    this.renderer.setSize(this.width, this.height, false);
    this.camera.aspect = this.width / this.height;
    this.camera.updateProjectionMatrix();
  }

  setPointer(x: number, y: number): void { this.pointerTarget.set(THREE.MathUtils.clamp(x, -1, 1), THREE.MathUtils.clamp(y, -1, 1)); }
  setEyeTracking(_enabled: boolean): void { /* Eye LEDs and helmet track the pointer subtly in the render loop. */ }
  setFrameInput(input: FrameInput): void {
    if (input.activity) this.activity = input.activity;
    if (input.emotion) this.emotion = input.emotion;
  }

  setCameraFocus(_boneName: string | null): void { this.setCameraMode('portrait'); }
  frameFullBody(): void { this.setCameraMode('full'); }
  private setCameraMode(mode: 'full' | 'portrait'): void {
    this.fullBody = mode === 'full';
    this.baseCameraDistance = this.fullBody ? 6.7 : 4.95;
    this.camera.position.z = this.baseCameraDistance;
    this.camera.position.y = this.fullBody ? 1.92 : 2.65;
    this.camera.lookAt(0, this.fullBody ? 1.78 : 2.42, 0);
  }

  playBehaviour(name: string): boolean {
    if (name !== 'wave' && name !== 'dance') return false;
    this.behaviour = name;
    this.behaviourStarted = this.clock.elapsedTime;
    return true;
  }
  stopBehaviour(): void { this.behaviour = ''; }

  sit(_options: { seat?: string; style?: string } = {}): void {
    this.sitting = true;
    this.hipLeft.rotation.x = 0.72;
    this.hipRight.rotation.x = 0.72;
    this.kneeLeft.rotation.x = -1.35;
    this.kneeRight.rotation.x = -1.35;
    this.torso.position.y = 1.36;
  }
  stand(_duration = 0.9): void {
    this.sitting = false;
    this.hipLeft.rotation.x = 0;
    this.hipRight.rotation.x = 0;
    this.kneeLeft.rotation.x = 0;
    this.kneeRight.rotation.x = 0;
    this.torso.position.y = 1.56;
  }

  start(): void { if (this.disposed || this.running) return; this.running = true; this.clock.start(); this.animate(); }
  stop(): void { this.running = false; if (this.frame) cancelAnimationFrame(this.frame); this.frame = 0; }

  private animate = (): void => {
    if (!this.running || this.disposed) return;
    this.frame = requestAnimationFrame(this.animate);
    const delta = Math.min(0.05, this.clock.getDelta());
    const elapsed = this.clock.elapsedTime;
    this.pointer.lerp(this.pointerTarget, 1 - Math.exp(-delta * 5));

    const speaking = this.activity === 'talking' || this.activity === 'speaking';
    const thinking = this.activity === 'thinking';
    const listening = this.activity === 'listening';
    const wave = this.behaviour === 'wave';
    const dance = this.behaviour === 'dance';
    const activeBehaviourTime = elapsed - this.behaviourStarted;
    if (this.behaviour && activeBehaviourTime > (wave ? 3.2 : 5.5)) this.behaviour = '';

    const breathe = Math.sin(elapsed * 1.65) * 0.012;
    this.root.position.y = (this.sitting ? -0.01 : 0) + Math.sin(elapsed * 2.1) * 0.018;
    this.root.rotation.y = Math.sin(elapsed * 0.42) * 0.045 + this.pointer.x * 0.045;
    this.torso.rotation.z = Math.sin(elapsed * 0.8) * 0.018 + (dance ? Math.sin(elapsed * 5) * 0.12 : 0);
    this.torso.scale.y = 1 + breathe;
    this.head.rotation.y = this.pointer.x * 0.18;
    this.head.rotation.x = -this.pointer.y * 0.09 + Math.sin(elapsed * 0.7) * 0.018;
    this.head.rotation.z = Math.sin(elapsed * 0.55) * 0.012;

    const targetWave = wave ? 2.25 + Math.sin(activeBehaviourTime * 7) * 0.18 : dance ? 1.35 + Math.sin(elapsed * 4) * 0.28 : 0.18;
    this.shoulderRight.rotation.z += (targetWave - this.shoulderRight.rotation.z) * Math.min(1, delta * 7);
    const leftTarget = dance ? -1.1 - Math.sin(elapsed * 4) * 0.25 : -0.18;
    this.shoulderLeft.rotation.z += (leftTarget - this.shoulderLeft.rotation.z) * Math.min(1, delta * 6);
    this.elbowRight.rotation.x = wave ? Math.sin(activeBehaviourTime * 7) * 0.22 : dance ? Math.sin(elapsed * 4) * 0.25 : 0;
    this.shoulderLeft.rotation.x = dance ? Math.sin(elapsed * 4 + 1) * 0.18 : 0;
    this.hipLeft.rotation.z = dance ? Math.sin(elapsed * 4) * 0.06 : 0;
    this.hipRight.rotation.z = dance ? -Math.sin(elapsed * 4) * 0.06 : 0;

    const pulse = speaking ? 1.35 + Math.sin(elapsed * 12) * 0.26 : thinking ? 0.9 + Math.sin(elapsed * 3) * 0.18 : listening ? 1.12 : this.emotion === 'happy' ? 1.5 : 1;
    (this.core.material as THREE.MeshStandardMaterial).emissiveIntensity = pulse;
    this.core.scale.setScalar(1 + Math.sin(elapsed * (speaking ? 12 : 2.2)) * (speaking ? 0.09 : 0.025));
    this.mouth.scale.x = speaking ? 1.3 + Math.sin(elapsed * 13) * 0.42 : 1;
    (this.eyeLeft.material as THREE.MeshStandardMaterial).emissiveIntensity = this.emotion === 'happy' ? 2.1 : 1.65;
    (this.eyeRight.material as THREE.MeshStandardMaterial).emissiveIntensity = this.emotion === 'happy' ? 2.1 : 1.65;
    this.renderer.render(this.scene, this.camera);
  };

  dispose(): void {
    if (this.disposed) return;
    this.stop();
    this.disposed = true;
    this.scene.traverse(object => {
      if (object instanceof THREE.Mesh) object.geometry.dispose();
    });
    for (const material of this.materials) material.dispose();
    this.renderer.dispose();
  }
}
