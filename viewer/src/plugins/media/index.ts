import type { FormatPlugin } from "../../core/types";

const BEST_EFFORT = new Set(["video/x-matroska", "video/quicktime", "video/x-msvideo", "audio/aiff", "audio/x-ms-wma", "audio/midi", "audio/aac"]);

export const mediaPlugin: FormatPlugin = {
  id: "media",
  name: "Media",
  maxLevel: 3,
  sizeLimits: { view: 512 * 1024 * 1024 },
  formats: ["mp3", "m4a", "ogg", "opus", "flac", "wav", "mp4", "webm", "mkv*", "mov*"],
  match(info) {
    const d = info.detected ?? "";
    if (!d.startsWith("audio/") && !d.startsWith("video/")) return null;
    if (d === "video/mpeg" || d === "video/x-flv") return { confidence: 0.4, level: 3, label: "Media player (best effort)" };
    return { confidence: BEST_EFFORT.has(d) ? 0.7 : 0.9, level: 3, label: BEST_EFFORT.has(d) ? "Media player (best effort)" : "Media player" };
  },
  load: () => import("./view").then((m) => m.default),
};
