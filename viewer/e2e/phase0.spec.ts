import { test, expect } from "@playwright/test";
import { execSync } from "node:child_process";
import { existsSync, rmSync, writeFileSync, openSync, writeSync, closeSync, ftruncateSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expectPlugin, fx, openFile, stage, watchErrors } from "./helpers";

test.beforeEach(async ({ page }) => { await page.goto("/"); });

test("empty state shows the drop zone", async ({ page }) => {
  const errs = watchErrors(page);
  await expect(page.getByTestId("dropzone")).toBeVisible();
  expect(errs).toEqual([]);
});

test("text file opens in the text plugin (read-only → level 5 offered)", async ({ page }) => {
  const errs = watchErrors(page);
  await openFile(page, fx("hello.txt"));
  await expectPlugin(page, "text", 5);
  await expect(page.locator(".cm-content")).toContainText("Hello, Fylz Viewer.");
  expect(errs).toEqual([]);
});

test("unknown binary falls back to Hex with strings", async ({ page }) => {
  const errs = watchErrors(page);
  await openFile(page, fx("unknown.bin"));
  await expectPlugin(page, "hex", 1);
  await expect(page.locator(".fv-hexlist .fv-vl-row").first()).toContainText("00000000");
  await page.getByRole("button", { name: "Strings", exact: true }).click();
  await expect(page.locator('[data-k="strstatus"]')).toContainText("done");
  await expect(page.locator(".fv-strlist")).toContainText("FYLZ-MARKER-STRING");
  expect(errs).toEqual([]);
});

test("open-as menu offers Hex and Text for any file and switches", async ({ page }) => {
  await openFile(page, fx("image.png"));
  const sel = page.getByTestId("open-as");
  await expect(sel.locator("option")).toContainText(["Hex", "Text"]);
  await sel.selectOption("hex");
  await expectPlugin(page, "hex", 1);
  await expect(page.locator(".fv-hexlist")).toContainText("89 50 4E 47");
});

test("info panel shows detection and SHA-256", async ({ page }) => {
  await openFile(page, fx("hello.txt"));
  await page.getByTestId("info-toggle").click();
  const panel = page.getByTestId("info-panel");
  await expect(panel).toContainText("text/plain");
  const { createHash } = await import("node:crypto");
  const { readFileSync } = await import("node:fs");
  const expected = createHash("sha256").update(readFileSync(fx("hello.txt"))).digest("hex");
  await expect(panel.locator('[data-k="sha256"]')).toContainText(expected);
});

test("text edit → save downloads → dirty tracking and discard prompt", async ({ page }) => {
  await openFile(page, fx("crlf.txt"));
  await page.getByTestId("edit-toggle").click();
  await expect(stage(page)).toHaveAttribute("data-mode", "edit");
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type("added");
  await expect(page.getByTestId("dirty")).toBeVisible();
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByTestId("save").click()]);
  const path = await dl.path();
  const { readFileSync } = await import("node:fs");
  expect(readFileSync(path!).toString()).toBe("line one\r\nline two\r\nline three\r\nadded"); // CRLF preserved
  await expect(page.getByTestId("dirty")).toHaveCount(0);
  // dirty again → closing prompts
  await page.locator(".cm-content").click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type("!");
  await page.getByTestId("close").click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByTestId("keep").click();
  await expect(page.getByTestId("dirty")).toBeVisible();
  await page.getByTestId("close").click();
  await page.getByTestId("discard").click();
  await expect(page.getByTestId("dropzone")).toBeVisible();
});

test("1 GB sparse file: hex scrolls to the end", async ({ page }) => {
  test.setTimeout(120_000);
  const path = join(tmpdir(), "fv-sparse-1g.bin");
  if (existsSync(path)) rmSync(path);
  const fd = openSync(path, "w");
  ftruncateSync(fd, 1024 ** 3);
  writeSync(fd, Buffer.from("END-OF-GIGABYTE!"), 0, 16, 1024 ** 3 - 16);
  closeSync(fd);
  try {
    await openFile(page, path);
    await expectPlugin(page, "hex", 1);
    await page.locator(".fv-hexlist").evaluate((e) => { e.scrollTop = e.scrollHeight; });
    await expect(page.locator(".fv-hexlist")).toContainText("45 4E 44 2D 4F 46", { timeout: 15_000 });
    await expect(page.locator(".fv-hexlist")).toContainText("3FFFFFF0");
    // goto offset
    await page.getByLabel("Go to offset").fill("0x200000");
    await page.getByLabel("Go to offset").press("Enter");
    await expect(page.locator(".fv-hexlist")).toContainText("00200000");
  } finally { rmSync(path, { force: true }); }
});

test.describe("mobile", () => {
  test("@mobile hex and text fit the viewport", async ({ page }) => {
    await openFile(page, fx("hello.txt"));
    const sw = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
    expect(sw).toBe(true);
  });
});
void execSync; void writeFileSync;
