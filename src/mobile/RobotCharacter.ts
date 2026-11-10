import * as THREE from 'three';

type FrameInput = {
  activity?: string;
  emotion?: string;
  outputAnalyser?: AnalyserNode | null;
  inputAnalyser?: AnalyserNode | null;
};

/**
 * A Stark-workshop style android, built from geometry so it ships inside the APK with no external
 * model, texture or licensed asset.
 *
 * What makes it read as a high-graphics film rig rather than a toy:
 *  - a fully procedural studio environment map (metal reflects something real, so it has depth)
 *  - physically-based metals with clearcoat and emissive panel seams
 *  - real bloom on the arc core, the eyes, the chest seams and the floor ring
 *  - layered armour: separate plates, bevels, vents, joint rings and gunmetal under-suit
 *  - crimson/ember palette with a single cold blue rim so the red stays saturated
 *
 * Quality tiers keep the phone honest: 'low' drops bloom and the env map, 'balanced' keeps bloom at
 * half resolution, 'high' renders everything at native density.
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
  private readonly coreGlow: THREE.Sprite;
  private readonly eyeLeft: THREE.Mesh;
  private readonly eyeRight: THREE.Mesh;
  private readonly ring: THREE.Mesh;
  private readonly floorDisc: THREE.Mesh;
  private readonly materials: THREE.Material[] = [];
  private readonly seams: THREE.MeshStandardMaterial[] = [];
  private readonly disposables: Array<{ dispose(): void }> = [];
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
  private readonly quality: 'low' | 'balanced' | 'high';
  private readonly composer: { render(): void; setSize(w: number, h: number): void; dispose(): void } | null;

  constructor(private readonly canvas: HTMLCanvasElement, quality: 'low' | 'balanced' | 'high' = 'low') {
    this.quality = quality;
    this.renderer = new THREE.WebGLRenderer({
      canvas,
      alpha: true,
      antialias: quality === 'high',
      powerPreference: 'high-performance',
      stencil: false,
      depth: true,
    });
    this.renderer.setClearColor(0x000000, 0);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = quality === 'low' ? 1.1 : 1.24;
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, quality === 'high' ? 1.75 : quality === 'balanced' ? 1.15 : 0.9));

    this.camera = new THREE.PerspectiveCamera(33, 1, 0.1, 100);
    this.camera.position.set(0, 1.92, this.baseCameraDistance);
    this.camera.lookAt(0, 1.78, 0);
    this.scene.add(this.camera);

    this.setupLighting();

    // ---------- materials ----------
    const gunmetal = this.metal(0x1a1d24, 0.95, 0.28);
    const plate = this.metal(0x6f7683, 1.0, 0.18);
    const plateWarm = this.metal(0x8a7d78, 1.0, 0.22);
    const crimson = this.metal(0x9c0a20, 1.0, 0.2);
    const dark = this.metal(0x0b0d12, 0.7, 0.42);
    const glass = this.metal(0x230310, 0.35, 0.06);
    const emberMat = this.emissive(0xff2a1a, 2.6);
    const arcMat = this.emissive(0xffd9b0, 3.4);
    const eyeMat = this.emissive(0xffe6c4, 2.9);
    const seamMat = this.emissive(0xff3a1e, 1.5);
    this.seams.push(emberMat, arcMat, eyeMat, seamMat);

    // ---------- stage ----------
    this.ring = new THREE.Mesh(new THREE.TorusGeometry(0.84, 0.011, 10, 96), seamMat);
    this.ring.rotation.x = -Math.PI / 2;
    this.ring.position.y = 0.035;
    this.root.add(this.ring);
    const inner = new THREE.Mesh(new THREE.TorusGeometry(0.62, 0.005, 8, 80), emberMat);
    inner.rotation.x = -Math.PI / 2;
    inner.position.y = 0.04;
    this.root.add(inner);
    this.floorDisc = new THREE.Mesh(new THREE.CylinderGeometry(0.7, 0.78, 0.06, 72), gunmetal);
    this.floorDisc.position.y = 0.014;
    this.root.add(this.floorDisc);

    // ---------- legs ----------
    this.root.add(this.hipLeft, this.hipRight);
    this.makeLeg(this.hipLeft, this.kneeLeft, -0.255, plate, gunmetal, dark, crimson, seamMat);
    this.makeLeg(this.hipRight, this.kneeRight, 0.255, plate, gunmetal, dark, crimson, seamMat);

    // ---------- torso ----------
    this.torso.position.set(0, 1.56, 0);
    this.root.add(this.torso);
    // under-suit, then armour shells layered over it
    this.mesh(this.torso, new THREE.SphereGeometry(0.52, 32, 24), gunmetal, [0, 0.44, 0], [0.95, 1.16, 0.6]);
    this.mesh(this.torso, new THREE.SphereGeometry(0.545, 32, 24, 0, Math.PI * 2, 0, Math.PI * 0.52), plate, [0, 0.5, 0.02], [0.95, 1.1, 0.62]);
    const pectoralL = this.mesh(this.torso, new THREE.BoxGeometry(0.36, 0.3, 0.1), plate, [-0.2, 0.6, 0.29], [1, 1, 1]);
    pectoralL.rotation.z = 0.16;
    const pectoralR = this.mesh(this.torso, new THREE.BoxGeometry(0.36, 0.3, 0.1), plate, [0.2, 0.6, 0.29], [1, 1, 1]);
    pectoralR.rotation.z = -0.16;
    // abdominal plates with a crimson seam between each
    for (let i = 0; i < 4; i++) {
      this.mesh(this.torso, new THREE.BoxGeometry(0.46 - i * 0.045, 0.1, 0.09), plateWarm, [0, 0.36 - i * 0.115, 0.3 + i * 0.006], [1, 1, 1]);
      this.mesh(this.torso, new THREE.BoxGeometry(0.4 - i * 0.04, 0.012, 0.02), seamMat, [0, 0.305 - i * 0.115, 0.345], [1, 1, 1]);
    }
    // arc reactor: dark housing, platinum ring, glowing core with a halo sprite
    this.mesh(this.torso, new THREE.CylinderGeometry(0.2, 0.2, 0.08, 40), dark, [0, 0.5, 0.36], [1, 1, 1]).rotation.x = Math.PI / 2;
    const reactorRing = new THREE.Mesh(new THREE.TorusGeometry(0.185, 0.024, 12, 48), plate);
    reactorRing.position.set(0, 0.5, 0.41);
    this.torso.add(reactorRing);
    for (let i = 0; i < 10; i++) {
      const spoke = this.mesh(this.torso, new THREE.BoxGeometry(0.034, 0.13, 0.016), gunmetal,
        [Math.sin(i / 10 * Math.PI * 2) * 0.12, 0.5 + Math.cos(i / 10 * Math.PI * 2) * 0.12, 0.425], [1, 1, 1]);
      spoke.rotation.z = -i / 10 * Math.PI * 2;
    }
    this.core = new THREE.Mesh(new THREE.IcosahedronGeometry(0.105, 3), arcMat);
    this.core.position.set(0, 0.5, 0.43);
    this.torso.add(this.core);
    this.coreGlow = this.glowSprite(0xff5a30, 1.5);
    this.coreGlow.position.set(0, 0.5, 0.45);
    this.torso.add(this.coreGlow);
    // collar and neck
    this.mesh(this.torso, new THREE.BoxGeometry(0.62, 0.07, 0.42), plateWarm, [0, 0.86, 0.02], [1, 1, 1]);
    const neck = new THREE.Mesh(new THREE.CylinderGeometry(0.14, 0.17, 0.24, 24), gunmetal);
    neck.position.set(0, 2.44, 0);
    this.root.add(neck);
    for (const y of [2.36, 2.52]) {
      const collarRing = new THREE.Mesh(new THREE.TorusGeometry(0.155, 0.012, 8, 32), seamMat);
      collarRing.position.set(0, y, 0);
      collarRing.rotation.x = Math.PI / 2;
      this.root.add(collarRing);
    }

    // ---------- arms ----------
    this.root.add(this.shoulderLeft, this.shoulderRight);
    this.shoulderLeft.position.set(-0.6, 2.26, 0);
    this.shoulderRight.position.set(0.6, 2.26, 0);
    this.makeArm(this.shoulderLeft, this.elbowLeft, -1, plate, gunmetal, dark, crimson, seamMat);
    this.makeArm(this.shoulderRight, this.elbowRight, 1, plate, gunmetal, dark, crimson, seamMat);

    // ---------- head ----------
    this.head.position.set(0, 3.06, 0);
    this.root.add(this.head);
    // helmet shell: layered plating, a tapered jaw and a wrap-around dark visor
    this.mesh(this.head, new THREE.SphereGeometry(0.44, 40, 32), plateWarm, [0, 0.08, 0], [0.86, 1.02, 0.8]);
    this.mesh(this.head, new THREE.SphereGeometry(0.43, 40, 32), gunmetal, [0, 0.08, -0.02], [0.88, 1.02, 0.8]);
    this.mesh(this.head, new THREE.BoxGeometry(0.5, 0.16, 0.3), plate, [0, -0.2, 0.14], [1, 1, 1]);   // jaw
    this.mesh(this.head, new THREE.BoxGeometry(0.42, 0.05, 0.24), crimson, [0, -0.3, 0.13], [1, 1, 1]); // chin stripe
    this.mesh(this.head, new THREE.SphereGeometry(0.355, 36, 26), glass, [0, 0.05, 0.27], [1.0, 0.88, 0.36]);
    // brow line and cheek vents
    const brow = this.mesh(this.head, new THREE.BoxGeometry(0.56, 0.05, 0.08), plate, [0, 0.25, 0.3], [1, 1, 1]);
    brow.rotation.x = -0.16;
    for (const side of [-1, 1]) {
      for (let i = 0; i < 3; i++) {
        this.mesh(this.head, new THREE.BoxGeometry(0.09, 0.012, 0.02), seamMat,
          [side * 0.2, 0.02 - i * 0.05, 0.335], [1, 1, 1]);
      }
    }
    this.eyeLeft = this.mesh(this.head, new THREE.SphereGeometry(0.068, 24, 18), eyeMat, [-0.135, 0.09, 0.4], [1.5, 0.5, 0.3]);
    this.eyeRight = this.mesh(this.head, new THREE.SphereGeometry(0.068, 24, 18), eyeMat, [0.135, 0.09, 0.4], [1.5, 0.5, 0.3]);
    this.mouth = this.mesh(this.head, new THREE.BoxGeometry(0.14, 0.014, 0.014), emberMat, [0, -0.12, 0.39], [1, 1, 1]);
    // side pods
    for (const side of [-1, 1]) {
      const pod = new THREE.Mesh(new THREE.CylinderGeometry(0.09, 0.1, 0.12, 24), gunmetal);
      pod.position.set(side * 0.4, 0.02, 0.0);
      pod.rotation.z = side * Math.PI / 2;
      this.head.add(pod);
      const podRing = new THREE.Mesh(new THREE.TorusGeometry(0.085, 0.014, 8, 28), emberMat);
      podRing.position.set(side * 0.462, 0.02, 0.0);
      podRing.rotation.y = side * Math.PI / 2;
      this.head.add(podRing);
      const fin = this.mesh(this.head, new THREE.BoxGeometry(0.075, 0.3, 0.16), plate, [side * 0.2, 0.46, -0.04], [1, 1, 1]);
      fin.rotation.z = -side * 0.2;
      fin.rotation.x = -0.2;
    }
    // a single swept crest instead of hair
    const crest = this.mesh(this.head, new THREE.BoxGeometry(0.3, 0.06, 0.42), crimson, [0, 0.42, -0.02], [1, 1, 1]);
    crest.rotation.x = -0.34;
    const crestEdge = this.mesh(this.head, new THREE.BoxGeometry(0.05, 0.02, 0.44), seamMat, [0, 0.45, -0.02], [1, 1, 1]);
    crestEdge.rotation.x = -0.34;

    this.scene.add(this.root);
    this.root.position.y = 0;
    this.composer = this.setupBloom();
    this.resize(canvas.clientWidth || 1, canvas.clientHeight || 1);
  }

  // ---------------------------------------------------------------- construction helpers

  private setupLighting(): void {
    this.scene.add(new THREE.HemisphereLight(0x9fb4d8, 0x12040a, 1.15));
    const key = new THREE.DirectionalLight(0xfff0e2, 3.1);
    key.position.set(-3.6, 5.4, 5.2);
    this.scene.add(key);
    // crimson rim, the signature Stark look
    const rim = new THREE.PointLight(0xff2442, 46, 10, 2);
    rim.position.set(2.6, 2.9, -2.4);
    this.scene.add(rim);
    const warm = new THREE.PointLight(0xff8a4a, 22, 8, 2);
    warm.position.set(-2.8, 1.6, 2.6);
    this.scene.add(warm);
    // one cold edge so the red does not turn orange
    const cold = new THREE.DirectionalLight(0x6fa8ff, 1.05);
    cold.position.set(3.4, 2.2, -3.6);
    this.scene.add(cold);
    if (this.quality !== 'low') {
      const env = this.studioEnvironment();
      this.scene.environment = env;
      this.scene.environmentIntensity = 0.85;
    }
  }

  /**
   * A tiny procedural "studio": a dark room with bright strips and two coloured panels, captured
   * into a cube render target. Metal needs something to reflect, and this costs one render at
   * startup instead of a downloaded HDR file.
   */
  private studioEnvironment(): THREE.Texture {
    const size = this.quality === 'high' ? 256 : 128;
    const target = new THREE.WebGLCubeRenderTarget(size, { generateMipmaps: true, minFilter: THREE.LinearMipmapLinearFilter });
    const cubeCamera = new THREE.CubeCamera(0.1, 20, target);
    const room = new THREE.Scene();
    room.background = new THREE.Color(0x070307);
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(20, 20), new THREE.MeshBasicMaterial({ color: 0x14060a }));
    floor.rotation.x = -Math.PI / 2;
    floor.position.y = -1.5;
    room.add(floor);
    const strip = (color: number, intensity: number, x: number, y: number, z: number, w: number, h: number, ry = 0) => {
      const panel = new THREE.Mesh(new THREE.PlaneGeometry(w, h),
        new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(intensity), side: THREE.DoubleSide }));
      panel.position.set(x, y, z);
      panel.rotation.y = ry;
      room.add(panel);
    };
    strip(0xffffff, 5.0, -6, 3, 2, 2.2, 7, Math.PI / 2);   // tall key softbox
    strip(0xff2442, 4.0, 7, 2, -2, 2.2, 6, -Math.PI / 2);   // crimson fill
    strip(0xff8a4a, 2.4, 0, 6, 0, 8, 3, 0);                 // overhead warm
    strip(0x5f8fff, 1.6, 0, 2, -8, 6, 4, 0);                // cold back edge
    cubeCamera.update(this.renderer, room);
    room.traverse(object => {
      if (object instanceof THREE.Mesh) {
        object.geometry.dispose();
        (object.material as THREE.Material).dispose();
      }
    });
    this.disposables.push(target);
    return target.texture;
  }

  /** Bloom on the emitters only, so the plates stay crisp and the phone stays cool. */
  private setupBloom(): { render(): void; setSize(w: number, h: number): void; dispose(): void } | null {
    if (this.quality === 'low') return null;
    // Loaded lazily on the first frame; until then the plain renderer draws the frame.
    let composer: any = null;
    let ready = false;
    void (async () => {
      try {
        const [{ EffectComposer }, { RenderPass }, { UnrealBloomPass }, { OutputPass }] = await Promise.all([
          import('three/addons/postprocessing/EffectComposer.js'),
          import('three/addons/postprocessing/RenderPass.js'),
          import('three/addons/postprocessing/UnrealBloomPass.js'),
          import('three/addons/postprocessing/OutputPass.js'),
        ]);
        const built = new EffectComposer(this.renderer);
        built.addPass(new RenderPass(this.scene, this.camera));
        const bloom = new UnrealBloomPass(new THREE.Vector2(this.width, this.height), this.quality === 'high' ? 0.82 : 0.6, 0.62, 0.72);
        built.addPass(bloom);
        built.addPass(new OutputPass());
        built.setSize(this.width, this.height);
        composer = built;
        ready = true;
      } catch {
        ready = false;   // a device without the addons still renders, just without the glow pass
      }
    })();
    return {
      render: () => {
        if (ready && composer) composer.render();
        else this.renderer.render(this.scene, this.camera);
      },
      setSize: (w: number, h: number) => { if (composer) composer.setSize(w, h); },
      dispose: () => { if (composer) composer.dispose(); },
    };
  }

  /**
   * Armour uses a physical material, not a standard one: clearcoat is what makes the plates look
   * lacquered and photographic under the crimson rim light.
   */
  private metal(color: number, metalness: number, roughness: number): THREE.MeshPhysicalMaterial {
    const mat = new THREE.MeshPhysicalMaterial({
      color, metalness, roughness,
      envMapIntensity: 1.15,
      clearcoat: 0.6,
      clearcoatRoughness: 0.2,
      sheen: 0.2,
      sheenColor: new THREE.Color(0xff5a3c),
    });
    this.materials.push(mat);
    return mat;
  }

  private emissive(color: number, intensity: number): THREE.MeshStandardMaterial {
    const mat = new THREE.MeshStandardMaterial({ color: 0x140306, emissive: color, emissiveIntensity: intensity, roughness: 0.35, metalness: 0 });
    this.materials.push(mat);
    return mat;
  }

  private glowSprite(color: number, scale: number): THREE.Sprite {
    const size = 128;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext('2d');
    if (context) {
      const gradient = context.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
      const hex = `#${color.toString(16).padStart(6, '0')}`;
      gradient.addColorStop(0, hex);
      gradient.addColorStop(0.35, `${hex}80`);
      gradient.addColorStop(1, `${hex}00`);
      context.fillStyle = gradient;
      context.fillRect(0, 0, size, size);
    }
    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    this.disposables.push(texture);
    const material = new THREE.SpriteMaterial({ map: texture, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true });
    this.materials.push(material);
    const sprite = new THREE.Sprite(material);
    sprite.scale.setScalar(scale);
    return sprite;
  }

  private mesh(parent: THREE.Object3D, geometry: THREE.BufferGeometry, material: THREE.Material,
               position: [number, number, number], scale: [number, number, number] = [1, 1, 1]): THREE.Mesh {
    const item = new THREE.Mesh(geometry, material);
    item.position.set(...position);
    item.scale.set(...scale);
    parent.add(item);
    return item;
  }

  private makeArm(pivot: THREE.Group, elbow: THREE.Group, side: -1 | 1,
                  plate: THREE.Material, gun: THREE.Material, dark: THREE.Material,
                  crimson: THREE.Material, seam: THREE.Material): void {
    // layered pauldron: three overlapping shells, the outer one crimson
    const pauldronTop = this.mesh(pivot, new THREE.SphereGeometry(0.24, 28, 20, 0, Math.PI * 2, 0, Math.PI * 0.62), plate, [0, 0.02, 0], [1.05, 0.98, 0.95]);
    pauldronTop.rotation.z = side * 0.12;
    const pauldronMid = this.mesh(pivot, new THREE.SphereGeometry(0.215, 26, 18, 0, Math.PI * 2, 0, Math.PI * 0.5), crimson, [side * 0.01, -0.1, 0], [1.08, 0.8, 0.98]);
    pauldronMid.rotation.z = side * 0.18;
    for (let i = 0; i < 3; i++) {
      this.mesh(pivot, new THREE.BoxGeometry(0.2 - i * 0.03, 0.01, 0.02), seam, [side * 0.02, 0.06 - i * 0.06, 0.2], [1, 1, 1]);
    }
    // upper arm, exposed piston and armour band
    this.mesh(pivot, new THREE.CylinderGeometry(0.09, 0.085, 0.44, 20), gun, [0, -0.36, 0], [1, 1, 1]);
    this.mesh(pivot, new THREE.BoxGeometry(0.22, 0.26, 0.2), plate, [0, -0.3, 0.005], [1, 1, 1]);
    this.mesh(pivot, new THREE.BoxGeometry(0.05, 0.2, 0.02), seam, [side * -0.06, -0.3, 0.115], [1, 1, 1]);
    const piston = this.mesh(pivot, new THREE.CylinderGeometry(0.022, 0.022, 0.34, 12), dark, [side * 0.1, -0.4, -0.06], [1, 1, 1]);
    piston.rotation.z = side * 0.06;
    // elbow
    elbow.position.set(0, -0.66, 0);
    pivot.add(elbow);
    this.mesh(elbow, new THREE.SphereGeometry(0.13, 22, 16), dark, [0, 0, 0], [1, 1, 0.92]);
    const elbowRing = new THREE.Mesh(new THREE.TorusGeometry(0.115, 0.02, 10, 28), seam);
    elbowRing.position.set(0, 0, 0.02);
    elbow.add(elbowRing);
    // forearm: tapered shell with a crimson vent and a wrist coupler
    this.mesh(elbow, new THREE.CylinderGeometry(0.105, 0.085, 0.44, 20), gun, [0, -0.27, 0], [1, 1, 1]);
    this.mesh(elbow, new THREE.BoxGeometry(0.18, 0.3, 0.185), plate, [0, -0.26, 0.005], [1, 1, 1]);
    this.mesh(elbow, new THREE.BoxGeometry(0.1, 0.18, 0.02), crimson, [0, -0.26, 0.098], [1, 1, 1]);
    for (let i = 0; i < 3; i++) this.mesh(elbow, new THREE.BoxGeometry(0.06, 0.012, 0.016), seam, [0, -0.19 - i * 0.05, 0.1], [1, 1, 1]);
    this.mesh(elbow, new THREE.TorusGeometry(0.075, 0.018, 8, 24), plate, [0, -0.5, 0], [1, 1, 1]).rotation.x = Math.PI / 2;
    // hand: palm plate plus four fingers and a thumb, small but shaped
    this.mesh(elbow, new THREE.BoxGeometry(0.13, 0.15, 0.075), plate, [0, -0.6, 0.01], [1, 1, 1]);
    for (let f = 0; f < 4; f++) {
      this.mesh(elbow, new THREE.BoxGeometry(0.026, 0.11, 0.03), gun, [-0.048 + f * 0.032, -0.71, 0.02], [1, 1, 1]);
      this.mesh(elbow, new THREE.BoxGeometry(0.024, 0.028, 0.028), dark, [-0.048 + f * 0.032, -0.775, 0.02], [1, 1, 1]);
    }
    const thumb = this.mesh(elbow, new THREE.BoxGeometry(0.028, 0.08, 0.03), gun, [side * 0.082, -0.63, 0.03], [1, 1, 1]);
    thumb.rotation.z = side * 0.5;
    // the Stark signature: a repulsor in the palm
    const repulsor = new THREE.Mesh(new THREE.CircleGeometry(0.032, 20), seam);
    repulsor.position.set(0, -0.62, 0.049);
    elbow.add(repulsor);
    this.mesh(elbow, new THREE.TorusGeometry(0.042, 0.008, 8, 24), plate, [0, -0.62, 0.047], [1, 1, 1]);
  }

  private makeLeg(hip: THREE.Group, knee: THREE.Group, x: number,
                  plate: THREE.Material, gun: THREE.Material, dark: THREE.Material,
                  crimson: THREE.Material, seam: THREE.Material): void {
    hip.position.set(x, 1.44, 0);
    this.mesh(hip, new THREE.SphereGeometry(0.155, 22, 16), dark, [0, 0, 0], [1, 1, 0.94]);
    this.mesh(hip, new THREE.CylinderGeometry(0.125, 0.11, 0.56, 22), gun, [0, -0.32, 0], [1, 1, 1]);
    this.mesh(hip, new THREE.BoxGeometry(0.24, 0.3, 0.245), plate, [0, -0.28, 0.005], [1, 1, 1]);
    this.mesh(hip, new THREE.BoxGeometry(0.09, 0.2, 0.02), crimson, [x < 0 ? 0.05 : -0.05, -0.28, 0.125], [1, 1, 1]);
    this.mesh(hip, new THREE.BoxGeometry(0.2, 0.01, 0.018), seam, [0, -0.14, 0.125], [1, 1, 1]);
    // knee
    knee.position.set(0, -0.66, 0);
    hip.add(knee);
    this.mesh(knee, new THREE.SphereGeometry(0.14, 22, 16), plate, [0, 0, 0], [1, 0.9, 0.98]);
    const kneeRing = new THREE.Mesh(new THREE.TorusGeometry(0.115, 0.018, 10, 28), seam);
    kneeRing.position.set(0, 0, 0.03);
    knee.add(kneeRing);
    this.mesh(knee, new THREE.BoxGeometry(0.17, 0.15, 0.1), plate, [0, 0.015, 0.115], [1, 1, 1]);   // kneecap
    // shin
    this.mesh(knee, new THREE.CylinderGeometry(0.1, 0.075, 0.6, 20), gun, [0, -0.34, 0], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.2, 0.42, 0.2), plate, [0, -0.34, 0.01], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.07, 0.3, 0.02), seam, [0, -0.34, 0.115], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.16, 0.03, 0.02), crimson, [0, -0.16, 0.113], [1, 1, 1]);
    // boot: sole, toe, heel thruster
    this.mesh(knee, new THREE.BoxGeometry(0.3, 0.17, 0.46), plate, [0, -0.68, 0.12], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.26, 0.06, 0.32), dark, [0, -0.63, 0.2], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.12, 0.02, 0.1), seam, [0, -0.595, 0.3], [1, 1, 1]);
    this.mesh(knee, new THREE.BoxGeometry(0.22, 0.04, 0.36), gun, [0, -0.765, 0.1], [1, 1, 1]);
    const heel = new THREE.Mesh(new THREE.CylinderGeometry(0.055, 0.07, 0.06, 16), seam);
    heel.position.set(0, -0.7, -0.11);
    heel.rotation.x = Math.PI / 2;
    knee.add(heel);
  }

  // ---------------------------------------------------------------- public contract

  get diagnostics(): Record<string, unknown> {
    return {
      character: 'Vishal Jarvis',
      characterType: 'procedural Stark-style android, crimson',
      loaded: !this.disposed,
      running: this.running,
      renderer: 'Three.js WebGL',
      quality: this.quality,
      bloom: this.composer != null,
      environment: this.scene.environment != null,
      drawCalls: this.renderer.info.render.calls,
      geometries: this.renderer.info.memory.geometries,
      textures: this.renderer.info.memory.textures,
      activity: this.activity,
      behaviour: this.behaviour || 'idle',
    };
  }

  async load(): Promise<void> {
    if (this.disposed) throw new Error('The 3D character has been disposed.');
    this.start();
  }

  resize(width: number, height: number): void {
    this.width = Math.max(1, width);
    this.height = Math.max(1, height);
    this.renderer.setSize(this.width, this.height, false);
    this.camera.aspect = this.width / this.height;
    this.camera.updateProjectionMatrix();
    if (this.composer) this.composer.setSize(this.width, this.height);
  }

  setPointer(x: number, y: number): void { this.pointerTarget.set(THREE.MathUtils.clamp(x, -1, 1), THREE.MathUtils.clamp(y, -1, 1)); }
  setEyeTracking(_enabled: boolean): void { /* the visor tracks the pointer in the render loop */ }

  setFrameInput(input: FrameInput): void {
    if (input.activity) this.activity = input.activity;
    if (input.emotion) this.emotion = input.emotion;
  }

  setCameraFocus(_boneName: string | null): void { this.setCameraMode('portrait'); }
  frameFullBody(): void { this.setCameraMode('full'); }

  private setCameraMode(mode: 'full' | 'portrait'): void {
    this.fullBody = mode === 'full';
    this.baseCameraDistance = this.fullBody ? 6.7 : 4.6;
    this.camera.position.z = this.baseCameraDistance;
    this.camera.position.y = this.fullBody ? 1.92 : 2.72;
    this.camera.lookAt(0, this.fullBody ? 1.78 : 2.62, 0);
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

    const breathe = Math.sin(elapsed * 1.65) * 0.011;
    this.root.position.y = (this.sitting ? -0.01 : 0) + Math.sin(elapsed * 2.1) * 0.016;
    // a slow, deliberate idle: the head leads, the body follows, like a standing figure
    this.root.rotation.y = Math.sin(elapsed * 0.36) * 0.05 + this.pointer.x * 0.055;
    this.torso.rotation.z = Math.sin(elapsed * 0.7) * 0.015 + (dance ? Math.sin(elapsed * 5) * 0.12 : 0);
    this.torso.scale.y = 1 + breathe;
    this.head.rotation.y = this.pointer.x * 0.2 + Math.sin(elapsed * 0.31) * 0.04;
    this.head.rotation.x = -this.pointer.y * 0.08 + Math.sin(elapsed * 0.62) * 0.02;
    this.head.rotation.z = Math.sin(elapsed * 0.48) * 0.014;

    const targetWave = wave ? 2.25 + Math.sin(activeBehaviourTime * 7) * 0.18 : dance ? 1.35 + Math.sin(elapsed * 4) * 0.28 : 0.18;
    this.shoulderRight.rotation.z += (targetWave - this.shoulderRight.rotation.z) * Math.min(1, delta * 7);
    const leftTarget = dance ? -1.1 - Math.sin(elapsed * 4) * 0.25 : -0.18;
    this.shoulderLeft.rotation.z += (leftTarget - this.shoulderLeft.rotation.z) * Math.min(1, delta * 6);
    this.elbowRight.rotation.x = wave ? Math.sin(activeBehaviourTime * 7) * 0.22 : dance ? Math.sin(elapsed * 4) * 0.25 : 0;
    this.shoulderLeft.rotation.x = dance ? Math.sin(elapsed * 4 + 1) * 0.18 : 0;
    this.hipLeft.rotation.z = dance ? Math.sin(elapsed * 4) * 0.06 : 0;
    this.hipRight.rotation.z = dance ? -Math.sin(elapsed * 4) * 0.06 : 0;

    // the arc reactor is the life sign: bright and fast while speaking, slow while thinking
    const pulse = speaking ? 1.6 + Math.sin(elapsed * 12) * 0.3
      : thinking ? 0.95 + Math.sin(elapsed * 2.6) * 0.22
      : listening ? 1.3
      : this.emotion === 'happy' ? 1.9 : 1.05;
    (this.core.material as THREE.MeshStandardMaterial).emissiveIntensity = 3.4 * pulse * 0.55;
    this.core.scale.setScalar(1 + Math.sin(elapsed * (speaking ? 12 : 2.2)) * (speaking ? 0.09 : 0.025));
    (this.coreGlow.material as THREE.SpriteMaterial).opacity = 0.5 + pulse * 0.18;
    this.coreGlow.scale.setScalar(1.25 + pulse * 0.28);
    this.mouth.scale.x = speaking ? 1.35 + Math.sin(elapsed * 13) * 0.45 : 1;
    const eye = this.emotion === 'happy' ? 3.4 : 2.9;
    (this.eyeLeft.material as THREE.MeshStandardMaterial).emissiveIntensity = eye;
    (this.eyeRight.material as THREE.MeshStandardMaterial).emissiveIntensity = eye;

    // the floor ring breathes with the reactor and turns slowly under the figure
    this.ring.rotation.z = elapsed * 0.18;
    (this.ring.material as THREE.MeshStandardMaterial).emissiveIntensity = 0.9 + pulse * 0.35;

    if (this.composer) this.composer.render();
    else this.renderer.render(this.scene, this.camera);
  };

  dispose(): void {
    if (this.disposed) return;
    this.stop();
    this.disposed = true;
    this.scene.traverse(object => {
      if (object instanceof THREE.Mesh) object.geometry.dispose();
    });
    for (const item of this.disposables) item.dispose();
    for (const material of this.materials) material.dispose();
    if (this.composer) this.composer.dispose();
    this.renderer.dispose();
  }
}
