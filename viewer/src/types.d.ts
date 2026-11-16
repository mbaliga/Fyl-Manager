declare module "mammoth/mammoth.browser" {
  const mammoth: {
    convertToHtml(input: { arrayBuffer: ArrayBuffer }, options?: unknown): Promise<{ value: string; messages: { type: string; message: string }[] }>;
  };
  export default mammoth;
}
declare module "*.mjs?url" { const url: string; export default url; }
declare module "libheif-js/wasm-bundle" {
  interface HeifImage { get_width(): number; get_height(): number; display(target: { data: Uint8ClampedArray; width: number; height: number }, cb: (r: unknown) => void): void }
  const libheif: { HeifDecoder: new () => { decode(data: Uint8Array): HeifImage[] } };
  export default libheif;
}
