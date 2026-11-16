// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { parseGeoJson, parseGpx, parseKml, project, lengthKm } from "../src/plugins/geo/parse";
import { fixtureBytes } from "./util";
const text = (n: string) => new TextDecoder().decode(fixtureBytes(n));

describe("geo", () => {
  it("parses geojson, gpx, kml", () => {
    expect(parseGeoJson(text("shapes.geojson")).map((s) => s.kind)).toEqual(["point", "line", "poly"]);
    const gpx = parseGpx(text("track.gpx"));
    expect(gpx.map((s) => s.group)).toEqual(["waypoints", "tracks"]);
    expect(lengthKm(gpx[1]!.pts)).toBeGreaterThan(5);
    expect(parseKml(text("places.kml")).map((s) => s.kind)).toEqual(["point", "line"]);
  });
  it("projects into the viewport", () => {
    const { fn, bbox } = project(parseGeoJson(text("shapes.geojson")), 800, 600);
    expect(bbox!.minX).toBeCloseTo(77.59);
    const [x, y] = fn([77.59, 12.97]);
    expect(x).toBeGreaterThanOrEqual(0); expect(x).toBeLessThanOrEqual(800); expect(y).toBeGreaterThanOrEqual(0); expect(y).toBeLessThanOrEqual(600);
  });
});

