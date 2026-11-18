import { test, expect, type Page, type FrameLocator } from "@playwright/test";
import { readFileSync } from "node:fs";
import { fx, openFile, watchErrors } from "./helpers";

declare global {
  interface Window { mockHost: {
    openFile(name: string, bytes: number[] | Uint8Array, o?: { mime?: string; canWrite?: boolean; latency?: number }): void;
    setTheme(t: "light" | "dark"): void; sendClose(): void; failNextSave(m: string): void;
    saves: { kind: string; name?: string; bytes: Uint8Array }[]; events: { type: string; [k: string]: unknown }[]; readLog: { offset: number; length: number }[]; ready: boolean;
  } }
}

test.describe("PWA", () => {
  test("manifest is valid and the service worker precaches; app works fully offline", async ({ page, context }) => {
    const errs = watchErrors(page);
    const external: string[] = [];
    page.on("request", (r) => { const u = new URL(r.url()); if (u.origin !== "http://localhost:4173" && !u.protocol.startsWith("data") && !u.protocol.startsWith("blob")) external.push(r.url()); });
    await page.goto("/");
    const manifest = await (await page.request.get("/manifest.webmanifest")).json();
    expect(manifest.share_target.action).toBe("./share-target");
    expect(manifest.file_handlers.length).toBeGreaterThan(0);
    await page.evaluate(async () => { await navigator.serviceWorker.ready; });
    await page.waitForFunction(() => !!navigator.serviceWorker.controller);
    await context.setOffline(true);
    await page.reload();
    await expect(page.getByTestId("dropzone")).toBeVisible();
    // exercise heavy lazy chunks, workers and wasm while offline
    await openFile(page, fx("sample.pdf"));
    await expect(page.locator(".textLayer").first()).toContainText("Fylz test page 1");
    await openFile(page, fx("book.xlsx"));
    await expect(page.locator(".fv-gridlist")).toContainText("row 1");
    await openFile(page, fx("sample.7z"));
    await expect(page.locator(".fv-arclist")).toContainText("dir/b.txt");
    await openFile(page, fx("hello.txt"));
    await expect(page.locator(".cm-content")).toContainText("Hello");
    await page.getByTestId("info-toggle").click();
    await expect(page.locator('[data-k="sha256"]')).toContainText(/[0-9a-f]{64}/);
    await openFile(page, fx("doc.docx"));
    await expect(page.frameLocator("iframe.fv-sandbox").locator("h1")).toContainText("Fixture Heading");
    expect(external).toEqual([]);
    expect(errs.filter((e) => !/Failed to load resource/.test(e))).toEqual([]);
  });
});

const viewer = (page: Page): FrameLocator => page.frameLocator("#viewer");
async function host(page: Page) {
  await page.goto("/dev/fylz-host.html");
  await page.waitForFunction(() => window.mockHost?.ready === true, undefined, { timeout: 15000 });
}
const open = (page: Page, name: string, bytes: Buffer | Uint8Array, o: { canWrite?: boolean; latency?: number } = {}) =>
  page.evaluate(([n, b, opts]) => window.mockHost.openFile(n as string, b as number[], opts as never), [name, [...bytes], o] as const);

test.describe("Fylz host bridge (mock host)", () => {
  test("ranges are read through the bridge; text opens", async ({ page }) => {
    const errs = watchErrors(page);
    await host(page);
    await open(page, "hello.txt", readFileSync(fx("hello.txt")));
    await expect(viewer(page).locator(".cm-content")).toContainText("Hello, Fylz Viewer.");
    const reads = await page.evaluate(() => window.mockHost.readLog.length);
    expect(reads).toBeGreaterThan(0);
    expect(errs.filter((e) => !e.includes("[fylz-viewer]"))).toEqual([]);
  });

  test("big file with slow reads: hex only reads what it shows", async ({ page }) => {
    await host(page);
    const big = new Uint8Array(3 * 1024 * 1024);
    big.set(new TextEncoder().encode("TAIL-MARKER"), big.length - 11);
    await open(page, "big.bin", big, { latency: 120 });
    await expect(viewer(page).locator(".fv-hexlist")).toContainText("00000000", { timeout: 15000 });
    await viewer(page).locator(".fv-hexlist").evaluate((e) => { e.scrollTop = e.scrollHeight; });
    await expect(viewer(page).locator(".fv-hexlist")).toContainText("4D 41 52 4B", { timeout: 15000 });
    const total = await page.evaluate(() => window.mockHost.readLog.reduce((a, r) => a + r.length, 0));
    expect(total).toBeLessThan(1024 * 1024); // far less than the 3 MB file
  });

  test("edit → save is chunked to the host and acknowledged; dirty is reported", async ({ page }) => {
    await host(page);
    await open(page, "note.txt", new TextEncoder().encode("one\ntwo\n"));
    const v = viewer(page);
    await v.getByTestId("edit-toggle").click();
    await v.locator(".cm-content").click();
    await page.keyboard.press("Control+End");
    await page.keyboard.type("three");
    await expect(v.getByTestId("dirty")).toBeVisible();
    await v.getByTestId("save").click();
    await expect(v.getByTestId("dirty")).toHaveCount(0);
    const saves = await page.evaluate(() => window.mockHost.saves.map((s) => ({ kind: s.kind, text: new TextDecoder().decode(s.bytes) })));
    expect(saves).toEqual([{ kind: "save", text: "one\ntwo\nthree" }]);
    const types = await page.evaluate(() => window.mockHost.events.map((e) => e.type));
    expect(types).toContain("dirty");
  });

  test("a failed host save keeps the file dirty and shows the error", async ({ page }) => {
    await host(page);
    await open(page, "note.txt", new TextEncoder().encode("x"));
    const v = viewer(page);
    await v.getByTestId("edit-toggle").click();
    await v.locator(".cm-content").click();
    await page.keyboard.type("y");
    await page.evaluate(() => window.mockHost.failNextSave("disk full"));
    await v.getByTestId("save").click();
    await expect(v.getByTestId("error")).toContainText("disk full");
    await expect(v.getByTestId("dirty")).toBeVisible();
  });

  test("a large save (> 1 MiB) arrives intact", async ({ page }) => {
    await host(page);
    const csvRows = ["a,b"]; for (let i = 0; i < 120_000; i++) csvRows.push(`${i},value-${i}`);
    const bytes = new TextEncoder().encode(csvRows.join("\n") + "\n");
    expect(bytes.length).toBeGreaterThan(1 << 20);
    await open(page, "big.csv", bytes);
    const v = viewer(page);
    await v.getByTestId("edit-toggle").click();
    await v.locator('.fv-cell[data-r="1"][data-c="1"]').dblclick();
    await v.getByLabel("Edit cell").fill("EDITED");
    await v.getByLabel("Edit cell").press("Enter");
    await v.getByTestId("save").click();
    await expect(v.getByTestId("dirty")).toHaveCount(0, { timeout: 15000 });
    const out = await page.evaluate(() => { const s = window.mockHost.saves[0]!; return { len: s.bytes.length, head: new TextDecoder().decode(s.bytes.slice(0, 30)) }; });
    expect(out.head).toBe("a,b\n0,EDITED\n1,value-1\n2,value-".slice(0, 30));
    expect(out.len).toBeGreaterThan(1 << 20);
    const chunks = await page.evaluate(() => window.mockHost.events.filter((e) => e.type === "save").length);
    expect(chunks).toBeGreaterThanOrEqual(2);
  });

  test("canWrite=false hides editing", async ({ page }) => {
    await host(page);
    await open(page, "ro.txt", new TextEncoder().encode("read only"), { canWrite: false });
    await expect(viewer(page).locator(".cm-content")).toContainText("read only");
    await expect(viewer(page).getByTestId("edit-toggle")).toHaveCount(0);
  });

  test("close with unsaved changes prompts; keep answers closed(false), discard answers closed(true)", async ({ page }) => {
    await host(page);
    await open(page, "c.txt", new TextEncoder().encode("abc"));
    const v = viewer(page);
    await v.getByTestId("edit-toggle").click();
    await v.locator(".cm-content").click();
    await page.keyboard.type("z");
    await page.evaluate(() => window.mockHost.sendClose());
    await expect(v.getByRole("dialog")).toBeVisible();
    await v.getByTestId("keep").click();
    await expect.poll(() => page.evaluate(() => window.mockHost.events.filter((e) => e.type === "closed").map((e) => e.ok))).toEqual([false]);
    await page.evaluate(() => window.mockHost.sendClose());
    await v.getByTestId("discard").click();
    await expect.poll(() => page.evaluate(() => window.mockHost.events.filter((e) => e.type === "closed").map((e) => e.ok))).toEqual([false, true]);
    await expect(v.getByTestId("dropzone")).toBeVisible();
  });

  test("theme messages from the host switch the viewer theme", async ({ page }) => {
    await host(page);
    await open(page, "t.txt", new TextEncoder().encode("t"));
    await page.evaluate(() => window.mockHost.setTheme("dark"));
    await expect(viewer(page).locator("html")).toHaveAttribute("data-theme", "dark");
    await page.evaluate(() => window.mockHost.setTheme("light"));
    await expect(viewer(page).locator("html")).toHaveAttribute("data-theme", "light");
  });
});
