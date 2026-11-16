import { el, formatBytes } from "../../lib/format";
import type { PluginModule, ViewContext, ViewHandle } from "../../core/types";

const dur = (s?: number) => (s === undefined ? "—" : `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`);

const mod: PluginModule = {
  async mount(ctx: ViewContext): Promise<ViewHandle> {
    const { info } = ctx;
    const isVideo = (info.detected ?? "").startsWith("video/");
    const blob = await ctx.file.blob();
    const typed = info.detected && info.detected !== "video/x-matroska" ? new Blob([blob], { type: info.detected === "audio/ogg" && info.ext === "opus" ? "audio/ogg; codecs=opus" : info.detected }) : new Blob([blob], { type: "video/webm" });
    const url = URL.createObjectURL(typed);
    const root = el("div", { class: "fv-media" });
    const player = el(isVideo ? "video" : "audio", { controls: true, preload: "metadata", class: isVideo ? "fv-video" : "fv-audio", "data-testid": "player" }) as HTMLMediaElement;
    const err = el("div", { class: "fv-banner fv-banner-error", role: "alert", hidden: true, "data-testid": "media-error" });
    const tags = el("dl", { class: "fv-kv fv-media-tags", "data-testid": "tags" });
    const cover = el("img", { class: "fv-cover", alt: "Cover art", hidden: true });
    root.append(player, err, el("div", { class: "fv-media-info" }, cover, tags));
    ctx.container.append(root);
    let codec = "";

    player.addEventListener("error", () => {
      err.hidden = false;
      err.textContent = `This device cannot play this file${codec ? ` (codec: ${codec})` : ""}. ${["video/x-matroska", "video/quicktime"].includes(info.detected ?? "") ? "MKV/MOV support is best effort. " : ""}You can still inspect it with Hex, or open it in another app.`;
    });
    player.src = url;

    // Tags in the background — failures are non-fatal.
    void (async () => {
      try {
        const { parseBlob } = await import("music-metadata");
        const md = await parseBlob(blob, { duration: true, skipCovers: false });
        codec = md.format.codec ?? md.format.container ?? "";
        const rows: [string, string | undefined][] = [
          ["Title", md.common.title], ["Artist", md.common.artist], ["Album", md.common.album], ["Year", md.common.year?.toString()],
          ["Track", md.common.track.no ? String(md.common.track.no) : undefined], ["Genre", md.common.genre?.join(", ")],
          ["Duration", dur(md.format.duration)], ["Container", md.format.container], ["Codec", md.format.codec],
          ["Bitrate", md.format.bitrate ? `${Math.round(md.format.bitrate / 1000)} kbps` : undefined],
          ["Sample rate", md.format.sampleRate ? `${md.format.sampleRate} Hz` : undefined], ["Channels", md.format.numberOfChannels?.toString()],
          ["Size", formatBytes(info.size)],
        ];
        for (const [k, v] of rows) if (v) tags.append(el("dt", { text: k }), el("dd", { text: v }));
        const pic = md.common.picture?.[0];
        if (pic) { cover.src = URL.createObjectURL(new Blob([pic.data as BlobPart], { type: pic.format })); cover.hidden = false; }
      } catch (e) {
        ctx.host.log("info", "no tags", String(e));
        tags.append(el("dt", { text: "Size" }), el("dd", { text: formatBytes(info.size) }));
      }
    })();

    return {
      isDirty: () => false,
      onDirtyChange: () => {},
      unmount() { player.pause(); player.removeAttribute("src"); player.load(); URL.revokeObjectURL(url); root.remove(); },
    };
  },
};
export default mod;
