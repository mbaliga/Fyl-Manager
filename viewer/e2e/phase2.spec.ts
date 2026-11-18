import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { unzipSync } from "fflate";
import { PDFDocument } from "pdf-lib";
import { PNG } from "pngjs";
import * as XLSX from "xlsx";
import { expectPlugin, fx, openFile, watchErrors } from "./helpers";

test.beforeEach(async ({ page }) => { await page.goto("/"); });

async function enterEdit(page: Page) {
  await page.getByTestId("edit-toggle").click();
  await expect(page.getByTestId("stage")).toHaveAttribute("data-mode", "edit");
}
let lastDownloadPath = "";
async function saveDownload(page: Page): Promise<Buffer> {
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByTestId("save").click()]);
  lastDownloadPath = (await dl.path())!;
  return readFileSync(lastDownloadPath);
}
async function runCmd(page: Page, id: string) {
  await page.getByTestId("more").click();
  await page.locator(`[data-cmd="${id}"]`).click();
}
async function typeInEditor(page: Page, text: string) {
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type(text);
}

test("markdown: edit → preview updates → save", async ({ page }) => {
  const errs = watchErrors(page);
  await openFile(page, fx("notes.md"));
  await enterEdit(page);
  await typeInEditor(page, "\n\n## Added heading\n");
  await expect(page.frameLocator("iframe.fv-md-preview").locator("h2")).toHaveText("Added heading");
  const out = (await saveDownload(page)).toString();
  expect(out).toContain("## Added heading");
  expect(out.startsWith("# Title")).toBe(true);
  await expect(page.getByTestId("dirty")).toHaveCount(0);
  expect(errs).toEqual([]);
});

test("json: inline validation, format, save", async ({ page }) => {
  await openFile(page, fx("broken.json"));
  await enterEdit(page);
  await expect(page.locator(".cm-lintRange-error").first()).toBeVisible({ timeout: 5000 });
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+a");
  await page.keyboard.type('{"a":1,"b":[1,2]}');
  await expect(page.locator(".cm-lintRange-error")).toHaveCount(0, { timeout: 5000 });
  await runCmd(page, "format");
  const out = (await saveDownload(page)).toString();
  expect(JSON.parse(out)).toEqual({ a: 1, b: [1, 2] });
  expect(out).toContain('\n  "a": 1');
});

test("yaml: invalid yaml shows an inline error", async ({ page }) => {
  await openFile(page, fx("config.yaml"));
  await enterEdit(page);
  await typeInEditor(page, "\nfoo: bar: baz\n");
  await expect(page.locator(".cm-lintRange-error").first()).toBeVisible({ timeout: 5000 });
});

test("csv: edit a cell, insert row, change delimiter, save", async ({ page }) => {
  await openFile(page, fx("quoted.csv"));
  await enterEdit(page);
  await page.locator('.fv-cell[data-r="1"][data-c="0"]').dblclick();
  const inp = page.getByLabel("Edit cell");
  await inp.fill("changed, value");
  await inp.press("Enter");
  await expect(page.getByTestId("dirty")).toBeVisible();
  await page.locator('.fv-cell[data-r="1"][data-c="1"]').click();
  await runCmd(page, "row-below");
  await runCmd(page, "delim"); // , → ;
  const out = (await saveDownload(page)).toString();
  expect(out).toBe('a;b\nchanged, value;"multi\nline"\n;\n'); // comma no longer needs quoting under ';'
});

test("image: rotate + annotate + save as PNG", async ({ page }) => {
  await openFile(page, fx("image.png"));
  await enterEdit(page);
  await expect(page.getByTestId("exif-warning")).toBeVisible();
  await page.locator('[data-act="rotate-right"]').click();
  await expect(page.locator('[data-k="dims"]')).toHaveText("48 × 64");
  await page.locator('[data-tool="rect"]').click();
  const ov = page.locator(".fv-ov");
  const box = (await ov.boundingBox())!;
  await page.mouse.move(box.x + 5, box.y + 5);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 5, box.y + box.height - 5, { steps: 4 });
  await page.mouse.up();
  await expect(page.getByTestId("dirty")).toBeVisible();
  const png = PNG.sync.read(await saveDownload(page));
  expect([png.width, png.height]).toEqual([48, 64]);
  // the red-ish rectangle outline (#e53935) should now exist on the top-left edge
  const px = (x: number, y: number) => [...png.data.subarray((y * png.width + x) * 4, (y * png.width + x) * 4 + 3)];
  const found = [px(2, 2), px(3, 3), px(4, 4), px(5, 5)].some(([r, g, b]) => r! > 200 && g! < 90 && b! < 90);
  expect(found).toBe(true);
});

test("image: crop and resize, JPEG shows quality slider", async ({ page }) => {
  await openFile(page, fx("photo.jpg"));
  await enterEdit(page);
  await expect(page.getByLabel("Quality")).toBeVisible();
  await page.locator('[data-tool="crop"]').click();
  const box = (await page.locator(".fv-ov").boundingBox())!;
  await page.mouse.move(box.x + box.width * 0.25, box.y + box.height * 0.25);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.75, box.y + box.height * 0.75, { steps: 4 });
  await page.mouse.up();
  await page.locator('[data-act="apply-crop"]').click();
  await expect(page.locator('[data-k="dims"]')).toHaveText("48 × 32");
  await page.getByLabel("Width", { exact: true }).fill("24");
  await page.locator('[data-act="resize"]').click();
  await expect(page.locator('[data-k="dims"]')).toHaveText("24 × 16");
  const jpg = await saveDownload(page);
  expect([jpg[0], jpg[1]]).toEqual([0xff, 0xd8]);
  // undo restores
  await page.locator('[data-act="undo"]').click();
  await expect(page.locator('[data-k="dims"]')).toHaveText("48 × 32");
});

test("pdf: rotate, delete, reorder, merge, annotate, save", async ({ page }) => {
  const errs = watchErrors(page);
  await openFile(page, fx("sample.pdf"));
  await enterEdit(page);
  await expect(page.getByTestId("thumb")).toHaveCount(3);
  await page.getByLabel("rotate page 1").click();
  await page.getByLabel("delete page 2").click();
  await expect(page.getByTestId("thumb")).toHaveCount(2);
  await page.getByLabel("down page 1").click(); // swap → [orig3, orig1(rot)]
  await page.getByTestId("merge-input").setInputFiles(fx("other.pdf"));
  await expect(page.getByTestId("thumb")).toHaveCount(3);
  // text annotation on the first page
  await page.locator('[data-tool="text"]').click();
  const pg = page.getByTestId("pdf-page").first();
  await expect(pg.locator("canvas.fv-pdf-ann")).toBeVisible();
  const b = (await pg.boundingBox())!;
  await page.mouse.click(b.x + 40, b.y + 60);
  const ti = page.getByTestId("pdf-text-input");
  await ti.fill("SIGNED-BY-FYLZ");
  await ti.press("Enter");
  const out = await saveDownload(page);
  const doc = await PDFDocument.load(out);
  expect(doc.getPageCount()).toBe(3);
  expect(doc.getPage(1).getRotation().angle).toBe(90);
  // re-open the saved file in the viewer (round trip): the flattened annotation must be real page text
  await openFile(page, lastDownloadPath);
  await expect(page.getByTestId("pdf-page")).toHaveCount(3);
  await expect(page.locator(".textLayer", { hasText: "SIGNED-BY-FYLZ" })).toHaveCount(1);
  expect(errs).toEqual([]);
});

test("pdf: extract selected pages via Save as (download)", async ({ page }) => {
  await openFile(page, fx("sample.pdf"));
  await enterEdit(page);
  await page.getByLabel("Select page 2").check();
  await page.getByLabel("Select page 3").check();
  const [dl] = await Promise.all([page.waitForEvent("download"), runCmd(page, "pdf-extract")]);
  expect(dl.suggestedFilename()).toBe("sample-extract.pdf");
  expect((await PDFDocument.load(readFileSync((await dl.path())!))).getPageCount()).toBe(2);
});

test("sheet: edit a cell value, banner warns, save as xlsx", async ({ page }) => {
  await openFile(page, fx("book.xlsx"));
  await enterEdit(page);
  await expect(page.getByTestId("sheet-banner")).toContainText("formatting");
  await page.locator('.fv-cell[data-r="1"][data-c="1"]').dblclick();
  const inp = page.getByLabel("Edit cell");
  await inp.fill("edited name");
  await inp.press("Enter");
  await page.locator('.fv-cell[data-r="1"][data-c="2"]').dblclick();
  await page.getByLabel("Edit cell").fill("99.5");
  await page.getByLabel("Edit cell").press("Enter");
  const out = await saveDownload(page);
  const wb = XLSX.read(out, { type: "buffer" });
  expect(wb.SheetNames).toEqual(["Data", "Second"]);
  const rows = XLSX.utils.sheet_to_json<unknown[]>(wb.Sheets.Data!, { header: 1 });
  expect(rows[1]).toEqual([1, "edited name", 99.5]); // untouched id stays numeric, edited score becomes number
  expect(rows[2]).toEqual([2, "row 2", 3]);
});

test("zip: add, rename, remove entries and save", async ({ page }) => {
  await openFile(page, fx("sample.zip"));
  await enterEdit(page);
  await page.getByTestId("archive-add").setInputFiles(fx("hello.txt"));
  await expect(page.locator(".fv-arclist")).toContainText("hello.txt");
  await page.locator(".fv-arclist .fv-vl-row", { hasText: "dir/b.txt" }).click();
  await runCmd(page, "zip-rename");
  const r = page.getByTestId("rename-input");
  await r.fill("dir/renamed.txt");
  await r.press("Enter");
  await page.locator(".fv-arclist .fv-vl-row", { hasText: "a.txt" }).first().click();
  await runCmd(page, "zip-remove");
  const out = unzipSync(new Uint8Array(await saveDownload(page)));
  expect(Object.keys(out).sort()).toEqual(["dir/renamed.txt", "dir/sub/c.json", "hello.txt"]);
  expect(new TextDecoder().decode(out["dir/renamed.txt"])).toBe("bravo\n");
});

test("archives other than zip stay read-only", async ({ page }) => {
  await openFile(page, fx("sample.tar"));
  await expectPlugin(page, "archive", 2);
  await expect(page.getByTestId("edit-toggle")).toHaveCount(0);
});

test("docx/pptx offer no edit toggle", async ({ page }) => {
  await openFile(page, fx("doc.docx"));
  await expect(page.getByTestId("edit-toggle")).toHaveCount(0);
});

test("save refuses output whose type changed", async ({ page }) => {
  await openFile(page, fx("hello.txt"));
  await enterEdit(page);
  await page.evaluate(() => {
    // simulate a buggy plugin: make serialize return PNG bytes for a text file
    const s = (window as unknown as { __fv: { session: { handle: { serialize(): Promise<Blob> } } } }).__fv.session;
    s.handle.serialize = async () => new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])]);
  });
  await typeInEditor(page, "x");
  await page.getByTestId("save").click();
  await expect(page.getByTestId("error")).toContainText("output type changed");
});
