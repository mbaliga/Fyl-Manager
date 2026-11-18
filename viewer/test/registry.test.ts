import { describe, expect, it } from "vitest";
import { Registry, effectiveLevel } from "../src/core/registry";
import { createRegistry } from "../src/plugins";
import { detect } from "../src/core/detect";
import { fixtureSource } from "./util";
import type { FormatPlugin } from "../src/core/types";

const stub = (id: string, confidence: number, level: 0 | 1 | 2 | 3 | 4 | 5, extra: Partial<FormatPlugin> = {}): FormatPlugin => ({
  id, name: id, maxLevel: level, match: () => ({ confidence, level, label: id }), load: async () => { throw new Error("n/a"); }, ...extra,
});
const info = { name: "x", ext: "", size: 10, isText: false };

describe("registry", () => {
  it("orders by confidence then level", () => {
    const r = new Registry().register(stub("a", 0.5, 3)).register(stub("b", 0.9, 1)).register(stub("c", 0.5, 4));
    expect(r.candidates(info, new Uint8Array()).map((c) => c.plugin.id)).toEqual(["b", "c", "a"]);
  });
  it("rejects duplicate ids", () => {
    expect(() => new Registry().register(stub("a", 1, 1)).register(stub("a", 1, 1))).toThrow();
  });
  it("survives a throwing match()", () => {
    const bad: FormatPlugin = { ...stub("bad", 1, 1), match: () => { throw new Error("boom"); } };
    const r = new Registry().register(bad).register(stub("ok", 0.2, 1));
    expect(r.candidates(info, new Uint8Array()).map((c) => c.plugin.id)).toEqual(["ok"]);
  });
  it("openAs always offers text and hex", () => {
    const r = createRegistry();
    const list = r.openAs(info, new Uint8Array());
    expect(list.map((c) => c.plugin.id)).toEqual(expect.arrayContaining(["text", "hex"]));
    // text on a binary file is view-only (level 3)
    expect(list.find((c) => c.plugin.id === "text")!.match.level).toBe(3);
  });
  it("picks text for text and hex for unknown binary", async () => {
    const r = createRegistry();
    const t = await detect(fixtureSource("hello.txt"));
    expect(r.best(t.info, t.head)?.plugin.id).toBe("text");
    const b = await detect(fixtureSource("unknown.bin"));
    expect(r.best(b.info, b.head)?.plugin.id).toBe("hex");
  });
});

describe("effectiveLevel", () => {
  const p = stub("p", 1, 5, { sizeLimits: { view: 1000, edit: 100 } });
  const m = { confidence: 1, level: 5 as const, label: "p" };
  it("edit within limits", () => expect(effectiveLevel(p, m, 50, true, true)).toMatchObject({ level: 5, mode: "edit" }));
  it("view only above edit limit", () => expect(effectiveLevel(p, m, 500, true, true)).toMatchObject({ level: 3, mode: "view" }));
  it("hex fallback above view limit", () => expect(effectiveLevel(p, m, 5000, true, true)).toMatchObject({ level: 1, tooBigToView: true }));
  it("no write permission → level 3", () => expect(effectiveLevel(p, m, 50, false, true)).toMatchObject({ level: 3, mode: "view" }));
});
