/** Copies third-party runtime assets (WASM, workers, font data) into public/ so they ship in dist/ and are precached. */
import { cpSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";

const root = join(import.meta.dirname, "..");
const nm = (p: string) => join(root, "node_modules", p);
const pub = (p: string) => join(root, "public", p);

rmSync(pub("libarchive"), { recursive: true, force: true });
rmSync(pub("pdfjs"), { recursive: true, force: true });
mkdirSync(pub("libarchive"), { recursive: true });
mkdirSync(pub("pdfjs"), { recursive: true });
cpSync(nm("libarchive.js/dist/worker-bundle.js"), pub("libarchive/worker-bundle.js"));
cpSync(nm("libarchive.js/dist/libarchive.wasm"), pub("libarchive/libarchive.wasm"));
cpSync(nm("pdfjs-dist/cmaps"), pub("pdfjs/cmaps"), { recursive: true });
cpSync(nm("pdfjs-dist/standard_fonts"), pub("pdfjs/standard_fonts"), { recursive: true });
console.log("assets copied to public/");
