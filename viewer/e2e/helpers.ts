import { expect, type Page } from "@playwright/test";
import { join } from "node:path";

export const fx = (n: string) => join(import.meta.dirname, "..", "test", "fixtures", n);

/** Collect console errors / page errors for the whole test. */
export function watchErrors(page: Page): string[] {
  const errs: string[] = [];
  page.on("pageerror", (e) => errs.push(`pageerror: ${e.message}`));
  page.on("console", (m) => {
    if (m.type() === "error") errs.push(`console: ${m.text()}`);
  });
  return errs;
}

export async function openFile(page: Page, path: string, opts: { waitReady?: boolean } = {}) {
  await page.getByTestId("file-input").setInputFiles(path);
  if (opts.waitReady !== false) await expect(page.getByTestId("stage")).toHaveAttribute("data-status", "ready", { timeout: 20_000 });
}

export const stage = (page: Page) => page.getByTestId("stage");
export async function expectPlugin(page: Page, id: string, level?: number) {
  await expect(stage(page)).toHaveAttribute("data-plugin", id);
  if (level !== undefined) await expect(stage(page)).toHaveAttribute("data-level", String(level));
}
