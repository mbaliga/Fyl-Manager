import type { FileSource } from "./types";

/** FileSource over a Blob/File (drag-drop, file picker, FS Access, nested entries). */
export class BlobSource implements FileSource {
  name: string;
  size: number;
  lastModified?: number;
  mimeHint?: string;
  constructor(private readonly b: Blob, name?: string, opts: { lastModified?: number; mimeHint?: string } = {}) {
    this.name = name ?? (b as File).name ?? "untitled";
    this.size = b.size;
    this.lastModified = opts.lastModified ?? (b as File).lastModified;
    this.mimeHint = opts.mimeHint ?? (b.type || undefined);
  }
  async read(offset: number, length: number): Promise<Uint8Array> {
    const start = Math.max(0, Math.min(offset, this.size));
    const end = Math.max(start, Math.min(offset + length, this.size));
    return new Uint8Array(await this.b.slice(start, end).arrayBuffer());
  }
  async blob(): Promise<Blob> {
    return this.b;
  }
  stream(): ReadableStream<Uint8Array> {
    return this.b.stream() as ReadableStream<Uint8Array>;
  }
}

/** FileSource over in-memory bytes (nested archive entries etc.). */
export function bytesSource(bytes: Uint8Array, name: string, mimeHint?: string): FileSource {
  return new BlobSource(new Blob([bytes as BlobPart]), name, { mimeHint });
}

/** Generic range-reader source, used by the Fylz host bridge. */
export class RangeSource implements FileSource {
  name: string;
  size: number;
  lastModified?: number;
  mimeHint?: string;
  constructor(
    meta: { name: string; size: number; lastModified?: number; mimeHint?: string },
    private readonly reader: (offset: number, length: number) => Promise<Uint8Array>,
    private readonly chunk = 1 << 20,
  ) {
    this.name = meta.name;
    this.size = meta.size;
    this.lastModified = meta.lastModified;
    this.mimeHint = meta.mimeHint;
  }
  read(offset: number, length: number): Promise<Uint8Array> {
    const end = Math.min(this.size, offset + length);
    if (offset >= end) return Promise.resolve(new Uint8Array(0));
    return this.reader(offset, end - offset);
  }
  async blob(): Promise<Blob> {
    const parts: BlobPart[] = [];
    for (let o = 0; o < this.size; o += this.chunk) parts.push((await this.read(o, this.chunk)) as BlobPart);
    return new Blob(parts, { type: this.mimeHint ?? "" });
  }
  stream(): ReadableStream<Uint8Array> {
    let o = 0;
    return new ReadableStream<Uint8Array>({
      pull: async (ctl) => {
        if (o >= this.size) return ctl.close();
        const b = await this.read(o, this.chunk);
        o += b.length;
        ctl.enqueue(b);
        if (b.length === 0) ctl.close();
      },
    });
  }
}
