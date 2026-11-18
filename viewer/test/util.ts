import { readFileSync } from "node:fs";
import { join } from "node:path";
import { BlobSource } from "../src/core/source";

export const fixturePath = (n: string) => join(__dirname, "fixtures", n);
export const fixtureBytes = (n: string) => new Uint8Array(readFileSync(fixturePath(n)));
export function fixtureSource(n: string): BlobSource {
  return new BlobSource(new Blob([fixtureBytes(n) as BlobPart]), n);
}
