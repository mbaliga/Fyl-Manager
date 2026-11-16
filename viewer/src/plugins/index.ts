import { Registry } from "../core/registry";
import { infoPlugin } from "./info";
import { hexPlugin } from "./hex";
import { textPlugin } from "./text";

/** All plugin descriptors. Each is tiny; the heavy `view` modules are lazy-loaded on demand. */
export const ALL_PLUGINS = [infoPlugin, textPlugin, hexPlugin];

export function createRegistry(): Registry {
  const r = new Registry();
  for (const p of ALL_PLUGINS) r.register(p);
  return r;
}
