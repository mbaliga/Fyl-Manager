import type { FormatPlugin } from "../../core/types";

const M = {
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  xls: "application/vnd.ms-excel",
  ods: "application/vnd.oasis.opendocument.spreadsheet",
};

export const sheetPlugin: FormatPlugin = {
  id: "sheet",
  name: "Spreadsheet",
  maxLevel: 4,
  sizeLimits: { view: 64 * 1024 * 1024, edit: 16 * 1024 * 1024 },
  formats: ["xlsx (edit values)", "xls", "ods (edit values)"],
  match(info) {
    const d = info.detected;
    if (d === M.xlsx || d === M.ods) return { confidence: 0.9, level: 4, label: "Spreadsheet" };
    if (d === M.xls) return { confidence: 0.9, level: 3, label: "Spreadsheet (read-only)" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
