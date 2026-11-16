/** Draws simple PNG icons (no external tools). Output is committed in public/. */
import { PNG } from "pngjs";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

for (const size of [192, 512]) {
  const png = new PNG({ width: size, height: size });
  const cx = size * 0.49, cy = size * 0.58, r = size * 0.1, rw = size * 0.043;
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const i = (y * size + x) * 4;
    const inPage = x > size * 0.23 && x < size * 0.77 && y > size * 0.19 && y < size * 0.88;
    const d = Math.hypot(x - cx, y - cy);
    const ring = Math.abs(d - r) < rw / 2;
    const handle = Math.abs(x - cx - (y - cy)) < rw / 1.4 && x > cx + r * 0.7 && x < cx + r * 1.9;
    const [R, G, B] = ring || handle ? [0x1a, 0x5f, 0xd0] : inPage ? [255, 255, 255] : [0x1a, 0x5f, 0xd0];
    png.data[i] = R; png.data[i + 1] = G; png.data[i + 2] = B; png.data[i + 3] = 255;
  }
  writeFileSync(join(import.meta.dirname, "..", "public", `icon-${size}.png`), PNG.sync.write(png));
}
console.log("icons written");
