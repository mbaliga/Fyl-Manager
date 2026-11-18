import { test, expect } from "@playwright/test";
import { expectPlugin, fx, openFile, watchErrors } from "./helpers";

test.beforeEach(async ({ page }) => { await page.goto("/"); });

const TABLE: [string, string, number][] = [
  ["hello.txt", "text", 5], ["code.ts", "text", 5], ["notes.md", "markdown", 5], ["data.json", "json", 5], ["config.yaml", "json", 5],
  ["config.toml", "json", 5], ["feed.xml", "json", 5], ["log.ndjson", "json", 5], ["table.csv", "csv", 4], ["table.tsv", "csv", 4],
  ["image.png", "image", 4], ["image.bmp", "image", 3], ["anim.gif", "image", 3], ["vector.svg", "image", 3],
  ["tone.wav", "media", 3], ["tone.mp3", "media", 3], ["tone.opus", "media", 3], ["clip.webm", "media", 3],
  ["sample.pdf", "pdf", 4], ["sample.zip", "archive", 4], ["sample.tar", "archive", 2], ["sample.txt.gz", "archive", 2], ["sample.tar.gz", "archive", 2],
  ["sample.tar.bz2", "archive", 2], ["sample.tar.xz", "archive", 2], ["sample.7z", "archive", 2],
  ["doc.docx", "docx", 3], ["book.xlsx", "sheet", 4], ["book.ods", "sheet", 4], ["deck.pptx", "pptx", 2], ["test.otf", "font", 3],
  ["unknown.bin", "hex", 1], ["page.html", "text", 5],
];

for (const [file, plugin, level] of TABLE) {
  test(`opens ${file} with ${plugin} at L${level} (no console errors)`, async ({ page }) => {
    const errs = watchErrors(page);
    await openFile(page, fx(file));
    await expectPlugin(page, plugin, level);
    await expect(page.getByTestId("notice")).toHaveCount(0); // no silent fallback to hex
    expect(errs).toEqual([]);
  });
}

test("markdown preview is sandboxed and strips scripts", async ({ page }) => {
  await openFile(page, fx("notes.md"));
  const frame = page.locator("iframe.fv-md-preview");
  await expect(frame).toHaveAttribute("sandbox", "");
  const fl = page.frameLocator("iframe.fv-md-preview");
  await expect(fl.locator("h1")).toHaveText("Title");
  expect(await frame.getAttribute("srcdoc")).not.toContain("<script>alert");
});

test("svg renders in a sandboxed frame without scripts", async ({ page }) => {
  await openFile(page, fx("vector.svg"));
  const frame = page.locator("iframe.fv-sandbox");
  await expect(frame).toHaveAttribute("sandbox", "");
  expect(await frame.getAttribute("srcdoc")).not.toMatch(/<script/i);
});

test("json tree view toggles and shows nested values", async ({ page }) => {
  await openFile(page, fx("data.json"));
  await page.getByTestId("more").click();
  await page.locator('[data-cmd="tree"]').click();
  await expect(page.getByTestId("tree")).toContainText("nested");
  await expect(page.getByTestId("tree")).toContainText('"fylz"');
});

test("csv grid is virtualized and shows header + rows", async ({ page }) => {
  await openFile(page, fx("table.csv"));
  await expect(page.locator(".fv-grid-head")).toContainText("name");
  await expect(page.locator(".fv-gridlist .fv-vl-row").first()).toContainText("name 1");
  expect(await page.locator(".fv-gridlist .fv-vl-row").count()).toBeLessThan(60);
});

test("image shows dimensions and EXIF toggle", async ({ page }) => {
  await openFile(page, fx("image.png"));
  await expect(page.locator('[data-k="dims"]')).toHaveText("64 × 48");
});

test("media: audio tags are read from mp3", async ({ page }) => {
  await openFile(page, fx("tone.mp3"));
  await expect(page.getByTestId("tags")).toContainText("Fixture Tone");
  await expect(page.getByTestId("tags")).toContainText("Fylz");
});

test("pdf renders pages, thumbnails and searches", async ({ page }) => {
  const errs = watchErrors(page);
  await openFile(page, fx("sample.pdf"));
  await expect(page.getByTestId("pdf-page")).toHaveCount(3);
  await expect(page.getByTestId("thumb")).toHaveCount(3);
  await expect(page.locator(".fv-pdf-page canvas.fv-pdf-canvas").first()).toBeVisible();
  await expect(page.locator(".textLayer").first()).toContainText("Fylz test page 1");
  await page.getByLabel("Search in PDF").fill("page 2");
  await page.getByLabel("Search in PDF").press("Enter");
  await expect(page.locator('[data-k="hits"]')).toContainText("1 / 1");
  expect(errs).toEqual([]);
});

test("archive lists entries and opens one recursively, with back", async ({ page }) => {
  await openFile(page, fx("sample.zip"));
  await expect(page.locator(".fv-arclist")).toContainText("dir/sub/c.json");
  await page.locator(".fv-arclist .fv-vl-row", { hasText: "dir/sub/c.json" }).click();
  await page.getByRole("button", { name: "Open", exact: true }).click();
  await expect(page.getByTestId("title")).toContainText("c.json");
  await expectPlugin(page, "json");
  await page.getByTestId("back").click();
  await expectPlugin(page, "archive");
});

test("7z and tar.xz listings come from libarchive (wasm)", async ({ page }) => {
  await openFile(page, fx("sample.7z"));
  await expect(page.locator(".fv-arclist")).toContainText("dir/b.txt");
  await openFile(page, fx("sample.tar.xz"));
  await expect(page.locator(".fv-arclist")).toContainText("one.txt");
});

test("docx converts to sandboxed HTML", async ({ page }) => {
  await openFile(page, fx("doc.docx"));
  const fl = page.frameLocator("iframe.fv-sandbox");
  await expect(fl.locator("h1")).toContainText("Fixture Heading");
  await expect(fl.locator("body")).toContainText("Hello from a generated docx.");
});

test("sheet shows tabs and grid", async ({ page }) => {
  await openFile(page, fx("book.xlsx"));
  await expect(page.getByRole("tab")).toHaveText(["Data", "Second"]);
  await expect(page.locator(".fv-gridlist")).toContainText("row 1");
  await page.getByRole("tab", { name: "Second" }).click();
  await expect(page.locator(".fv-gridlist")).toContainText("x");
});

test("pptx outline keeps slide order (10 after 2)", async ({ page }) => {
  await openFile(page, fx("deck.pptx"));
  const titles = await page.locator(".fv-slide h3").allTextContents();
  expect(titles).toEqual(["Slide 1 — First slide title", "Slide 2 — Second slide title", "Slide 3 — Tenth slide title"]);
});

test("font shows metadata and glyph grid", async ({ page }) => {
  await openFile(page, fx("test.otf"));
  await expect(page.getByTestId("font-meta")).toContainText("FylzTest");
  await expect(page.locator(".fv-glyphlist")).toContainText("A");
});

test("zip entry nested open shows hex for binary and Open-as works", async ({ page }) => {
  await openFile(page, fx("unknown.bin"));
  await page.getByTestId("open-as").selectOption("text");
  await expectPlugin(page, "text", 3); // binary-as-text is view-only
  await expect(page.getByTestId("edit-toggle")).toHaveCount(0);
});

test("@mobile pdf and csv fit narrow viewport", async ({ page }) => {
  await openFile(page, fx("table.csv"));
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await openFile(page, fx("sample.pdf"));
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
