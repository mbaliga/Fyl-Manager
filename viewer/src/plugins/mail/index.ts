import type { FormatPlugin } from "../../core/types";

export const mailPlugin: FormatPlugin = {
  id: "mail",
  name: "Email",
  maxLevel: 2,
  sizeLimits: { view: 64 * 1024 * 1024 },
  formats: ["eml", "mbox"],
  match(info, head) {
    if (!info.isText) return null;
    if (info.ext === "eml" || info.ext === "mbox") return { confidence: 0.93, level: 2, label: "Headers, body, attachments" };
    const t = new TextDecoder().decode(head.subarray(0, 600));
    if (/^(From |Received:|Return-Path:|MIME-Version:|Message-ID:|Date:.*\n(From|To|Subject):)/im.test(t) && /^(From|To|Subject):/im.test(t) && /^MIME-Version:|^Message-ID:/im.test(t)) return { confidence: 0.6, level: 2, label: "Email message" };
    return null;
  },
  load: () => import("./view").then((m) => m.default),
};
