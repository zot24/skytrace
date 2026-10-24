import type { CustomLayerInterface, CustomRenderMethodInput, Map as MapLibreMap } from 'maplibre-gl';
import { opacityAt, positionAt, type Craft } from './traffic';

const MESH = new Float32Array([
  0, -13, -3.5, -2, 3.5, -2,
  -3.5, -2, -11, 1, -3, 3,
  3.5, -2, 3, 3, 11, 1,
  -3.5, -2, -3, 3, 3, 3,
  -3.5, -2, 3, 3, 3.5, -2,
  -3, 3, -5, 10, 0, 6,
  3, 3, 0, 6, 5, 10,
  -3, 3, 0, 6, 3, 3,
]);

const VERT = `#version 300 es
uniform mat4 u_matrix;
uniform float u_mpp;
uniform float u_scale;
layout(location = 0) in vec2 a_local;
layout(location = 1) in vec2 a_merc;
layout(location = 2) in float a_heading;
layout(location = 3) in float a_sel;
layout(location = 4) in float a_opacity;
out float v_sel;
out float v_opacity;
void main() {
  float c = cos(a_heading);
  float s = sin(a_heading);
  vec2 p = a_local * u_scale;
  vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
  gl_Position = u_matrix * vec4(a_merc + r * u_mpp, 0.0, 1.0);
  v_sel = a_sel;
  v_opacity = a_opacity;
}`;

const FRAG = `#version 300 es
precision highp float;
in float v_sel;
in float v_opacity;
uniform vec4 u_color;
uniform float u_tint;
out vec4 fragColor;
void main() {
  vec3 base = mix(vec3(0.93, 0.96, 1.0), vec3(1.0, 0.67, 0.22), v_sel);
  vec3 rgb = mix(u_color.rgb, base, u_tint);
  fragColor = vec4(rgb * v_opacity, v_opacity);
}`;

const DEG = Math.PI / 180;

export class PlaneLayer implements CustomLayerInterface {
  id = 'planes';
  type = 'custom' as const;
  renderingMode = '2d' as const;
  crafts: Craft[] = [];
  selected: string | null = null;
  drawn = 0;
  onFrame: () => void = () => {};

  private map!: MapLibreMap;
  private program: WebGLProgram | null = null;
  private mesh: WebGLBuffer | null = null;
  private instances: WebGLBuffer | null = null;
  private data = new Float32Array(0);
  private capacity = 0;
  private matrix = new Float32Array(16);
  private locs = {
    matrix: null as WebGLUniformLocation | null,
    mpp: null as WebGLUniformLocation | null,
    scale: null as WebGLUniformLocation | null,
    color: null as WebGLUniformLocation | null,
    tint: null as WebGLUniformLocation | null,
  };

  onAdd(map: MapLibreMap, gl: WebGL2RenderingContext) {
    this.map = map;
    const program = link(gl, VERT, FRAG);
    this.program = program;
    this.locs = {
      matrix: gl.getUniformLocation(program, 'u_matrix'),
      mpp: gl.getUniformLocation(program, 'u_mpp'),
      scale: gl.getUniformLocation(program, 'u_scale'),
      color: gl.getUniformLocation(program, 'u_color'),
      tint: gl.getUniformLocation(program, 'u_tint'),
    };
    this.mesh = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.mesh);
    gl.bufferData(gl.ARRAY_BUFFER, MESH, gl.STATIC_DRAW);
    this.instances = gl.createBuffer();
    this.ensure(gl, 1024);
  }

  onRemove(_map: MapLibreMap, gl: WebGL2RenderingContext) {
    if (this.mesh) gl.deleteBuffer(this.mesh);
    if (this.instances) gl.deleteBuffer(this.instances);
    if (this.program) gl.deleteProgram(this.program);
  }

  render(gl: WebGL2RenderingContext, options: CustomRenderMethodInput) {
    const count = this.program && this.mesh && this.instances ? this.upload(gl) : 0;
    this.drawn = count;
    if (this.program && this.mesh && this.instances && count > 0) {
      // mainMatrix takes 0–1 mercator into clip space. modelViewProjectionMatrix does not.
      const src = (options.defaultProjectionData?.mainMatrix ?? options.modelViewProjectionMatrix) as ArrayLike<number>;
      for (let i = 0; i < 16; i++) this.matrix[i] = src[i] ?? 0;
      const zoom = this.map.getZoom();
      const px = Math.max(13, Math.min(28, 10 + zoom * 1.7));
      const scale = px / 26;
      const vao = gl.getParameter(gl.VERTEX_ARRAY_BINDING) as WebGLVertexArrayObject | null;
      const depthTest = gl.isEnabled(gl.DEPTH_TEST);
      const depthMask = gl.getParameter(gl.DEPTH_WRITEMASK) as boolean;
      const blend = gl.isEnabled(gl.BLEND);
      const cull = gl.isEnabled(gl.CULL_FACE);
      const blendSrcRgb = gl.getParameter(gl.BLEND_SRC_RGB) as number;
      const blendDstRgb = gl.getParameter(gl.BLEND_DST_RGB) as number;
      const blendSrcA = gl.getParameter(gl.BLEND_SRC_ALPHA) as number;
      const blendDstA = gl.getParameter(gl.BLEND_DST_ALPHA) as number;

      gl.bindVertexArray(null);
      gl.useProgram(this.program);
      gl.uniformMatrix4fv(this.locs.matrix, false, this.matrix);
      gl.uniform1f(this.locs.mpp, mercPerCssPixel(this.map));
      gl.bindBuffer(gl.ARRAY_BUFFER, this.mesh);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.vertexAttribDivisor(0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.instances);
      gl.enableVertexAttribArray(1);
      gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 20, 0);
      gl.vertexAttribDivisor(1, 1);
      gl.enableVertexAttribArray(2);
      gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 20, 8);
      gl.vertexAttribDivisor(2, 1);
      gl.enableVertexAttribArray(3);
      gl.vertexAttribPointer(3, 1, gl.FLOAT, false, 20, 12);
      gl.vertexAttribDivisor(3, 1);
      gl.enableVertexAttribArray(4);
      gl.vertexAttribPointer(4, 1, gl.FLOAT, false, 20, 16);
      gl.vertexAttribDivisor(4, 1);
      gl.disable(gl.DEPTH_TEST);
      gl.depthMask(false);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

      const verts = MESH.length / 2;
      gl.uniform1f(this.locs.scale, scale * 1.28);
      gl.uniform4f(this.locs.color, 0.02, 0.03, 0.05, 1);
      gl.uniform1f(this.locs.tint, 0);
      gl.drawArraysInstanced(gl.TRIANGLES, 0, verts, count);

      gl.uniform1f(this.locs.scale, scale);
      gl.uniform1f(this.locs.tint, 1);
      gl.drawArraysInstanced(gl.TRIANGLES, 0, verts, count);

      gl.vertexAttribDivisor(1, 0);
      gl.vertexAttribDivisor(2, 0);
      gl.vertexAttribDivisor(3, 0);
      gl.vertexAttribDivisor(4, 0);
      gl.disableVertexAttribArray(1);
      gl.disableVertexAttribArray(2);
      gl.disableVertexAttribArray(3);
      gl.disableVertexAttribArray(4);
      gl.depthMask(depthMask);
      if (depthTest) gl.enable(gl.DEPTH_TEST);
      if (cull) gl.enable(gl.CULL_FACE);
      if (blend) gl.enable(gl.BLEND);
      else gl.disable(gl.BLEND);
      gl.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcA, blendDstA);
      gl.bindVertexArray(vao);
    }
    this.map?.triggerRepaint();
    this.onFrame();
  }

  private ensure(gl: WebGL2RenderingContext, count: number) {
    if (count <= this.capacity) return;
    this.capacity = Math.max(count, Math.ceil(this.capacity * 1.5));
    this.data = new Float32Array(this.capacity * 5);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instances);
    gl.bufferData(gl.ARRAY_BUFFER, this.capacity * 20, gl.DYNAMIC_DRAW);
  }

  private upload(gl: WebGL2RenderingContext): number {
    const crafts = this.crafts;
    const total = crafts.length;
    if (!total || !this.instances) return 0;
    this.ensure(gl, total);
    const bounds = this.map.getBounds();
    const pad = (56 * 360) / (512 * 2 ** this.map.getZoom());
    const south = bounds.getSouth() - pad;
    const north = bounds.getNorth() + pad;
    const west = bounds.getWest() - pad;
    const east = bounds.getEast() + pad;
    const wraps = bounds.getEast() < bounds.getWest();
    const selected = this.selected;
    const now = performance.now();
    const data = this.data;
    let j = 0;
    let count = 0;
    for (let i = 0; i < total; i++) {
      const craft = crafts[i]!;
      const opacity = opacityAt(craft, now);
      if (opacity <= 0) continue;
      const p = positionAt(craft, now);
      if (craft.hex !== selected && !inView(p.lat, p.lon, south, north, west, east, wraps)) continue;
      data[j++] = (p.lon + 180) / 360;
      data[j++] = mercY(p.lat);
      data[j++] = craft.track * DEG;
      data[j++] = craft.hex === selected ? 1 : 0;
      data[j++] = opacity;
      count++;
    }
    if (count > 0) {
      gl.bindBuffer(gl.ARRAY_BUFFER, this.instances);
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, data.subarray(0, count * 5));
    }
    return count;
  }
}

function inView(
  lat: number,
  lon: number,
  south: number,
  north: number,
  west: number,
  east: number,
  wraps: boolean,
): boolean {
  if (lat < south || lat > north) return false;
  let span = east - west;
  if (span < 0) span += 360;
  if (span >= 360) return true;
  if (!wraps && west <= east) {
    if (west < -180) return lon >= west + 360 || lon <= east;
    if (east > 180) return lon >= west || lon <= east - 360;
    return lon >= west && lon <= east;
  }
  return lon >= west || lon <= east;
}

function mercY(lat: number): number {
  const s = Math.sin(Math.max(-85, Math.min(85, lat)) * DEG);
  return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
}

function mercPerCssPixel(map: MapLibreMap): number {
  const center = map.getCenter();
  const origin = map.project(center);
  const east = map.project([center.lng + 0.05, center.lat]);
  const dx = east.x - origin.x;
  if (Math.abs(dx) < 0.01) return 1 / (512 * 2 ** map.getZoom());
  return Math.abs(0.05 / 360 / dx);
}

function link(gl: WebGL2RenderingContext, vert: string, frag: string): WebGLProgram {
  const program = gl.createProgram();
  if (!program) throw new Error('plane program');
  const vs = shader(gl, gl.VERTEX_SHADER, vert);
  const fs = shader(gl, gl.FRAGMENT_SHADER, frag);
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.bindAttribLocation(program, 0, 'a_local');
  gl.bindAttribLocation(program, 1, 'a_merc');
  gl.bindAttribLocation(program, 2, 'a_heading');
  gl.bindAttribLocation(program, 3, 'a_sel');
  gl.linkProgram(program);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error(gl.getProgramInfoLog(program) || 'plane shader link failed');
  }
  return program;
}

function shader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const sh = gl.createShader(type);
  if (!sh) throw new Error('plane shader');
  gl.shaderSource(sh, source);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(sh) || 'plane shader compile failed';
    gl.deleteShader(sh);
    throw new Error(log);
  }
  return sh;
}
