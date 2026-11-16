/** Page model + serialization for PDF editing (pdf-lib). Annotations use normalised display coordinates (0–1, y down). */
export type Ann =
  | { kind: "text"; u: number; v: number; text: string; size: number; color: string }
  | { kind: "ink"; pts: [number, number][]; w: number; color: string }
  | { kind: "img"; u: number; v: number; w: number; aspect: number; bytes: Uint8Array; mime: string };

export interface PageRef { src: number; index: number; rot: number; annots: Ann[]; w: number; h: number; intrinsic: number }

/** Normalised display point → unrotated pdf-lib page coordinates for total rotation r (clockwise degrees). */
export function toPdf(u: number, v: number, r: number, W: number, H: number): { x: number; y: number } {
  switch (((r % 360) + 360) % 360) {
    case 90: return { x: v * W, y: u * H };
    case 180: return { x: (1 - u) * W, y: v * H };
    case 270: return { x: (1 - v) * W, y: (1 - u) * H };
    default: return { x: u * W, y: (1 - v) * H };
  }
}

const hexToRgb = (h: string): [number, number, number] => [parseInt(h.slice(1, 3), 16) / 255, parseInt(h.slice(3, 5), 16) / 255, parseInt(h.slice(5, 7), 16) / 255];

export async function buildPdf(sources: Uint8Array[], pages: PageRef[]): Promise<Uint8Array> {
  const { PDFDocument, StandardFonts, degrees, rgb } = await import("pdf-lib");
  const docs = await Promise.all(sources.map((b) => PDFDocument.load(b)));
  const out = await PDFDocument.create();
  const font = await out.embedFont(StandardFonts.Helvetica);
  for (const ref of pages) {
    const [p] = await out.copyPages(docs[ref.src]!, [ref.index]);
    const total = (((p.getRotation().angle + ref.rot) % 360) + 360) % 360;
    p.setRotation(degrees(total));
    out.addPage(p);
    const { width: W, height: H } = p.getSize();
    const dw = total % 180 === 0 ? W : H, dh = total % 180 === 0 ? H : W;
    for (const a of ref.annots) {
      if (a.kind === "text") {
        const { x, y } = toPdf(a.u, a.v, total, W, H);
        const [r, g, b] = hexToRgb(a.color);
        for (const [i, line] of a.text.split("\n").entries()) {
          const off = i * a.size * dw * 1.2;
          const o = toPdf(a.u, a.v + off / dh, total, W, H);
          p.drawText(line, { x: i === 0 ? x : o.x, y: i === 0 ? y : o.y, size: a.size * dw, font, color: rgb(r, g, b), rotate: degrees(total) });
        }
      } else if (a.kind === "ink") {
        const [r, g, b] = hexToRgb(a.color);
        for (let i = 1; i < a.pts.length; i++) {
          const s = toPdf(a.pts[i - 1]![0], a.pts[i - 1]![1], total, W, H), e = toPdf(a.pts[i]![0], a.pts[i]![1], total, W, H);
          p.drawLine({ start: s, end: e, thickness: Math.max(0.5, a.w * dw), color: rgb(r, g, b), lineCap: 1 });
        }
      } else {
        const img = a.mime === "image/png" ? await out.embedPng(a.bytes) : await out.embedJpg(a.bytes);
        const wPts = a.w * dw, hPts = wPts / a.aspect;
        const bl = toPdf(a.u, a.v + hPts / dh, total, W, H);
        p.drawImage(img, { x: bl.x, y: bl.y, width: wPts, height: hPts, rotate: degrees(total) });
      }
    }
  }
  return out.save();
}

export async function extractPages(sources: Uint8Array[], pages: PageRef[]): Promise<Uint8Array> {
  return buildPdf(sources, pages);
}
