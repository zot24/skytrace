export type ViewBox = {
  west: number;
  south: number;
  east: number;
  north: number;
  zoom: number;
};

export const viewBox: ViewBox = {
  west: -180,
  south: -85,
  east: 180,
  north: 85,
  zoom: 1.8,
};

export function setViewBox(next: ViewBox) {
  viewBox.west = next.west;
  viewBox.south = next.south;
  viewBox.east = next.east;
  viewBox.north = next.north;
  viewBox.zoom = next.zoom;
}
