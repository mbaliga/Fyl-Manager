declare module "mammoth/mammoth.browser" {
  const mammoth: {
    convertToHtml(input: { arrayBuffer: ArrayBuffer }, options?: unknown): Promise<{ value: string; messages: { type: string; message: string }[] }>;
  };
  export default mammoth;
}
declare module "*.mjs?url" { const url: string; export default url; }
