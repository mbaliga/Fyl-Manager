import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { OBJLoader } from "three/examples/jsm/loaders/OBJLoader.js";
import { STLLoader } from "three/examples/jsm/loaders/STLLoader.js";
import { PLYLoader } from "three/examples/jsm/loaders/PLYLoader.js";
import { el } from "../../lib/format";
import { ViewerError, type PluginModule, type ViewContext, type ViewHandle } from "../../core/types";

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const { info } = ctx;
    const buf = await (await ctx.file.blob()).arrayBuffer();
    const ext = info.detected === "model/gltf-binary" ? "glb" : info.ext;

    let object: THREE.Object3D;
    const mat = () => new THREE.MeshStandardMaterial({ color: 0x8ab4ff, metalness: 0.1, roughness: 0.6, side: THREE.DoubleSide });
    if (ext === "glb" || ext === "gltf") {
      const gltf = await new Promise<{ scene: THREE.Object3D }>((res, rej) => new GLTFLoader().parse(ext === "gltf" ? new TextDecoder().decode(buf) : buf, "", res as never, rej));
      object = gltf.scene;
    } else if (ext === "obj") {
      object = new OBJLoader().parse(new TextDecoder().decode(buf));
      object.traverse((o) => { if ((o as THREE.Mesh).isMesh) (o as THREE.Mesh).material = mat(); });
    } else {
      const geo = ext === "stl" ? new STLLoader().parse(buf) : new PLYLoader().parse(buf);
      geo.computeVertexNormals();
      const hasFaces = !!geo.index || geo.getAttribute("position").count % 3 === 0 && (ext === "stl" || !!geo.getAttribute("normal"));
      const colored = !!geo.getAttribute("color");
      object = hasFaces && (ext === "stl" || geo.index)
        ? new THREE.Mesh(geo, colored ? new THREE.MeshStandardMaterial({ vertexColors: true, side: THREE.DoubleSide }) : mat())
        : new THREE.Points(geo, new THREE.PointsMaterial({ size: 0.02, color: colored ? 0xffffff : 0x8ab4ff, vertexColors: colored }));
    }

    let tris = 0, verts = 0;
    object.traverse((o) => { const g = (o as THREE.Mesh).geometry as THREE.BufferGeometry | undefined; if (g) { verts += g.getAttribute("position")?.count ?? 0; tris += g.index ? g.index.count / 3 : (g.getAttribute("position")?.count ?? 0) / 3; } });

    const root = el("div", { class: "fv-3d" });
    const canvasHost = el("div", { class: "fv-3d-stage", "data-testid": "viewport" });
    const status = el("div", { class: "fv-statusbar", role: "status", "data-testid": "model-stats" }, el("span", { text: `${Math.round(verts).toLocaleString()} vertices` }), el("span", { text: `${Math.round(tris).toLocaleString()} triangles` }), el("span", { text: "drag to rotate · scroll to zoom" }));
    root.append(canvasHost, status);
    ctx.container.append(root);

    let renderer: THREE.WebGLRenderer;
    try { renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true }); }
    catch { root.remove(); throw new ViewerError("WebGL is not available on this device"); }
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    canvasHost.append(renderer.domElement);
    renderer.domElement.setAttribute("data-testid", "gl-canvas");

    const scene = new THREE.Scene();
    scene.add(object);
    scene.add(new THREE.HemisphereLight(0xffffff, 0x445566, 1.4));
    const dl = new THREE.DirectionalLight(0xffffff, 1.6); dl.position.set(3, 5, 4); scene.add(dl);
    const box = new THREE.Box3().setFromObject(object);
    const size = box.getSize(new THREE.Vector3()), center = box.getCenter(new THREE.Vector3());
    const radius = Math.max(size.x, size.y, size.z, 1e-6);
    const camera = new THREE.PerspectiveCamera(45, 1, radius / 100, radius * 100);
    const home = () => { camera.position.copy(center).add(new THREE.Vector3(radius * 1.2, radius * 0.9, radius * 1.6)); controls.target.copy(center); controls.update(); };
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = false;
    home();

    let raf = 0;
    const draw = () => { raf = 0; renderer.render(scene, camera); };
    const schedule = () => { if (!raf) raf = requestAnimationFrame(draw); };
    controls.addEventListener("change", schedule);
    const resize = () => { const w = canvasHost.clientWidth || 300, h = canvasHost.clientHeight || 300; renderer.setSize(w, h, false); renderer.domElement.style.width = "100%"; renderer.domElement.style.height = "100%"; camera.aspect = w / h; camera.updateProjectionMatrix(); schedule(); };
    const ro = new ResizeObserver(resize); ro.observe(canvasHost); resize();

    let wire = false;
    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      commands: () => [
        { id: "reset", label: "Reset view", run: () => { home(); schedule(); } },
        { id: "wire", label: wire ? "Solid" : "Wireframe", run: () => { wire = !wire; object.traverse((o) => { const m = (o as THREE.Mesh).material as THREE.Material & { wireframe?: boolean }; if (m && "wireframe" in m) m.wireframe = wire; }); schedule(); } },
      ],
      unmount() {
        cancelAnimationFrame(raf); ro.disconnect(); controls.dispose();
        object.traverse((o) => { const m = o as THREE.Mesh; m.geometry?.dispose?.(); const mt = m.material as THREE.Material | THREE.Material[] | undefined; (Array.isArray(mt) ? mt : mt ? [mt] : []).forEach((x) => x.dispose()); });
        renderer.dispose(); root.remove();
      },
    };
  },
};
export default mod;
