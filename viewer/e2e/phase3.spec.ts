import { test, expect } from "@playwright/test";
import { PNG } from "pngjs";
import { expectPlugin, fx, openFile, watchErrors } from "./helpers";

test.beforeEach(async ({ page }) => { await page.goto("/"); });

const TABLE: [string, string, number][] = [
  ["data.sqlite", "sqlite", 3], ["book.epub", "epub", 3], ["photo.heic", "heic", 3], ["tri.glb", "model3d", 3], ["cube.obj", "model3d", 3],
  ["pyramid.stl", "model3d", 3], ["tri.ply", "model3d", 3], ["shot.dng", "raw", 3], ["shapes.geojson", "geo", 3], ["track.gpx", "geo", 3],
  ["places.kml", "geo", 3], ["events.ics", "cal", 2], ["people.vcf", "cal", 2], ["analysis.ipynb", "ipynb", 3], ["message.eml", "mail", 2],
  ["inbox.mbox", "mail", 2], ["lib.jar", "package", 2], ["app.apk", "package", 2],
];
for (const [file, plugin, level] of TABLE) {
  test(`opens ${file} with ${plugin} at L${level} (no console errors)`, async ({ page }) => {
    const errs = watchErrors(page);
    await openFile(page, fx(file));
    await expectPlugin(page, plugin, level);
    await expect(page.getByTestId("notice")).toHaveCount(0);
    expect(errs).toEqual([]);
  });
}

test("sqlite: tables, schema, rows, read-only SQL console", async ({ page }) => {
  await openFile(page, fx("data.sqlite"));
  const tables = page.getByTestId("sql-tables");
  await expect(tables).toContainText("people");
  await expect(tables).toContainText("notes");
  await expect(tables).toContainText("adults");
  await tables.getByRole("button", { name: /people/ }).click();
  await expect(page.getByTestId("sql-schema")).toContainText("CREATE TABLE people");
  await expect(page.getByTestId("sql-grid")).toContainText("Person 1");
  await expect(page.getByTestId("sql-status")).toContainText("120");
  await tables.getByRole("button", { name: /notes/ }).click();
  await expect(page.getByTestId("sql-grid")).toContainText("first note");
  await page.getByLabel("SQL").fill("SELECT name, age FROM people WHERE id = 2");
  await page.locator('[data-act="run-sql"]').click();
  await expect(page.getByTestId("sql-grid")).toContainText("Person 2");
  await page.getByLabel("SQL").fill("DELETE FROM people");
  await page.locator('[data-act="run-sql"]').click();
  await expect(page.getByTestId("sql-status")).toContainText("read-only");
  await page.getByLabel("SQL").fill("SELECT COUNT(*) AS n FROM people; DROP TABLE people");
  await page.locator('[data-act="run-sql"]').click();
  await expect(page.getByTestId("sql-status")).toContainText("single read-only");
  await page.getByLabel("SQL").fill("INSERT INTO people (name) VALUES ('x')"); // not SELECT → refused
  await page.locator('[data-act="run-sql"]').click();
  await expect(page.getByTestId("sql-status")).toContainText("read-only");
});

test("epub: toc, chapters, inline image, sanitised", async ({ page }) => {
  await openFile(page, fx("book.epub"));
  await expect(page.getByTestId("toc")).toContainText("Chapter One");
  await expect(page.getByTestId("toc")).toContainText("Fixture Book");
  const frame = page.getByTestId("chapter");
  await expect(frame).toHaveAttribute("sandbox", "");
  const srcdoc = (await frame.getAttribute("srcdoc"))!;
  expect(srcdoc).toContain("It was a quiet start.");
  expect(srcdoc).toContain("data:image/png;base64");
  expect(srcdoc).not.toMatch(/<script/i);
  await page.locator('[data-act="next"]').click();
  await expect(page.frameLocator('[data-testid="chapter"]').locator("body")).toContainText("The story continues.");
  await page.getByTestId("toc").getByText("Chapter One").click();
  await expect(page.frameLocator('[data-testid="chapter"]').locator("body")).toContainText("quiet start");
});

test("heic decodes via libheif wasm", async ({ page }) => {
  await openFile(page, fx("photo.heic"));
  await expect(page.locator('[data-k="dims"]')).toHaveText("64 × 48");
  const px = await page.getByTestId("heic-canvas").evaluate((c: HTMLCanvasElement) => [...c.getContext("2d")!.getImageData(32, 24, 1, 1).data]);
  expect(px[3]).toBe(255);
  expect(px[0]! + px[1]! + px[2]!).toBeGreaterThan(50); // not black
});

for (const f of ["tri.glb", "cube.obj", "pyramid.stl", "tri.ply"]) {
  test(`3D: ${f} renders pixels in WebGL`, async ({ page }) => {
    await openFile(page, fx(f));
    await expect(page.getByTestId("model-stats")).toContainText("triangles");
    await page.waitForTimeout(400);
    const png = PNG.sync.read(await page.getByTestId("gl-canvas").screenshot());
    const colors = new Set<number>();
    for (let i = 0; i < png.data.length; i += 4) colors.add((png.data[i]! << 16) | (png.data[i + 1]! << 8) | png.data[i + 2]!);
    expect(colors.size).toBeGreaterThan(3);
  });
}

test("raw: embedded preview + EXIF, open preview as image", async ({ page }) => {
  await openFile(page, fx("shot.dng"));
  await expect(page.getByTestId("raw-note")).toContainText("embedded JPEG preview");
  await expect(page.locator("img.fv-img")).toBeVisible();
  expect(await page.locator("img.fv-img").evaluate((i: HTMLImageElement) => i.naturalWidth)).toBe(480);
  await expect(page.getByTestId("exif")).toContainText("Fylz Cameras");
  await page.getByTestId("more").click();
  await page.locator('[data-cmd="open-preview"]').click();
  await expectPlugin(page, "image");
  await expect(page.getByTestId("title")).toContainText("shot-preview.jpg");
});

test("geo: plots shapes as SVG with stats", async ({ page }) => {
  await openFile(page, fx("shapes.geojson"));
  const svg = page.getByTestId("geo-svg");
  await expect(svg.locator("circle")).toHaveCount(1);
  await expect(svg.locator("polyline")).toHaveCount(1);
  await expect(svg.locator("polygon")).toHaveCount(1);
  await expect(page.getByTestId("geo-info")).toContainText("3 shapes");
  await openFile(page, fx("track.gpx"));
  await expect(page.getByTestId("geo-info")).toContainText("Path length");
});

test("calendar and contacts are shown as cards", async ({ page }) => {
  await openFile(page, fx("events.ics"));
  const cards = page.getByTestId("cards");
  await expect(cards.locator("article")).toHaveCount(2);
  await expect(cards).toContainText("Team sync");
  await expect(cards).toContainText("2024-01-15 09:30 UTC");
  await expect(cards).toContainText("Ada Lovelace <ada@example.com>");
  await openFile(page, fx("people.vcf"));
  await expect(page.getByTestId("cards")).toContainText("Grace Hopper");
  await expect(page.getByTestId("cards")).toContainText("+1 555 0100");
});

test("ipynb: cells render read-only and outputs are sanitised", async ({ page }) => {
  await openFile(page, fx("analysis.ipynb"));
  await expect(page.getByTestId("nb-note")).toContainText("3 cells");
  const frame = page.locator("iframe.fv-sandbox");
  await expect(frame).toHaveAttribute("sandbox", "");
  const fl = page.frameLocator("iframe.fv-sandbox");
  await expect(fl.locator("h1")).toHaveText("Notebook title");
  await expect(fl.locator("body")).toContainText("bold html");
  await expect(fl.locator("body")).toContainText("ZeroDivisionError");
  expect(await frame.getAttribute("srcdoc")).not.toMatch(/<script/i);
});

test("mail: headers, sanitised html body, attachment opens recursively", async ({ page }) => {
  await openFile(page, fx("message.eml"));
  await expect(page.getByTestId("mail-headers")).toContainText("Ada Lovelace");
  await expect(page.getByTestId("mail-headers")).toContainText("Hello ✓ fixture");
  const body = page.getByTestId("mail-body");
  await expect(body).toHaveAttribute("sandbox", "");
  const srcdoc = (await body.getAttribute("srcdoc"))!;
  expect(srcdoc).toContain("HTML body ✓");
  expect(srcdoc).not.toMatch(/<script/i);
  expect(srcdoc).not.toContain("evil.example");
  await expect(page.getByTestId("attachments")).toContainText("note.txt");
  await page.locator('[data-act="open-att"]').click();
  await expect(page.getByTestId("title")).toContainText("note.txt");
  await expect(page.locator(".cm-content")).toContainText("attachment content");
  await page.getByTestId("back").click();
  await expectPlugin(page, "mail");
});

test("mbox lists messages", async ({ page }) => {
  await openFile(page, fx("inbox.mbox"));
  await expect(page.getByTestId("mail-list").locator("button")).toHaveCount(2);
  await page.getByTestId("mail-list").getByRole("button", { name: /Second/ }).click();
  await expect(page.getByTestId("mail-headers")).toContainText("Second");
  await expect(page.getByTestId("mail-body")).toContainText("body two");
});

test("jar and apk manifest summaries; browse contents opens the archive view", async ({ page }) => {
  await openFile(page, fx("lib.jar"));
  const s = page.getByTestId("pkg-summary");
  await expect(s).toContainText("com.example.Main");
  await expect(s).toContainText("1.2.3");
  await expect(s).toContainText("Classes");
  await openFile(page, fx("app.apk"));
  await expect(page.getByTestId("pkg-summary")).toContainText("com.example.fixture");
  await expect(page.getByTestId("pkg-summary")).toContainText("1.2.3 (code 7)");
  await expect(page.getByTestId("pkg-summary")).toContainText("24 / 34");
  await expect(page.getByTestId("perms")).toContainText("android.permission.CAMERA");
  await page.locator('[data-act="browse"]').click();
  await expectPlugin(page, "archive", 2);
  await expect(page.locator(".fv-arclist")).toContainText("classes.dex");
});
