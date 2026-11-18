import type { FormatPlugin } from "../../core/types";

export const calPlugin: FormatPlugin = {
  id: "cal",
  name: "Calendar / contacts",
  maxLevel: 2,
  formats: ["ics", "vcf"],
  match(info) {
    return info.isText && ["ics", "vcf", "vcard", "ical"].includes(info.ext) ? { confidence: 0.93, level: 2, label: "Readable cards" } : null;
  },
  load: () => import("./view").then((m) => m.default),
};
