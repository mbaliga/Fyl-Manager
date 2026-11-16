import type { FormatPlugin } from "../../core/types";

const EXTS = new Set(["glb", "gltf", "obj", "stl", "ply"]);

export const model3dPlugin: FormatPlugin = {
  id: "model3d",
  name: "3D model",
  maxLevel: 3,
  sizeLimits: { view: 256 * 1024 * 1024 },
  formats: ["glb", "gltf", "obj", "stl", "ply"],
  match(info) {
    if (info.detected === "model/gltf-binary" || EXTS.has(info.ext)) return { confidence: 0.92, level: 3, label: "3D viewer (orbit)" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
