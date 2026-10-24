import {
  Camera,
  GeoJSONSource,
  Images,
  Layer,
  Map,
  type CameraRef,
  type CircleLayerSpecification,
  type LineLayerSpecification,
  type PressEvent,
  type PressEventWithFeatures,
  type SymbolLayerSpecification,
  type ViewStateChangeEvent,
} from '@maplibre/maplibre-react-native';
import { forwardRef, memo, useCallback, useEffect, useImperativeHandle, useRef, useState, type RefObject } from 'react';
import { StyleSheet, type NativeSyntheticEvent } from 'react-native';
import { airportData } from './airports';
import { buildPlaneData } from './planes';
import { craftByHex, craftList, positionAt } from './traffic';
import { emptyTrail, extendTrail, trailData } from './trail';
import { setViewBox } from './view';

const STYLE = 'https://tiles.openfreemap.org/styles/dark';
const EMPTY = '{"type":"FeatureCollection","features":[]}';
const INITIAL_VIEW = { center: [12, 24] as [number, number], zoom: 1.8 };
const COMPASS_POSITION = { bottom: 78, right: 12 };
const PLANE_HITBOX = { top: 16, right: 16, bottom: 16, left: 16 };

const IMAGES = {
  plane: require('../assets/plane.png'),
  'plane-selected': require('../assets/plane-selected.png'),
};

const planePaint: SymbolLayerSpecification['paint'] = {
  'icon-opacity': ['get', 'op'],
};

const planeLayout: SymbolLayerSpecification['layout'] = {
  'icon-image': ['case', ['==', ['get', 'sel'], 1], 'plane-selected', 'plane'],
  'icon-rotate': ['get', 'hdg'],
  'icon-rotation-alignment': 'map',
  'icon-pitch-alignment': 'map',
  'icon-allow-overlap': true,
  'icon-ignore-placement': true,
  'icon-size': ['interpolate', ['linear'], ['zoom'], 2, 0.42, 6, 0.58, 11, 0.78, 14, 1],
};

const largeFilter: CircleLayerSpecification['filter'] = ['==', ['get', 'k'], 'L'];
const mediumFilter: CircleLayerSpecification['filter'] = ['==', ['get', 'k'], 'M'];
const largePaint: CircleLayerSpecification['paint'] = {
  'circle-radius': ['interpolate', ['linear'], ['zoom'], 1.5, 1.7, 4, 3.2, 8, 5.5],
  'circle-color': '#e4b15a',
  'circle-stroke-width': 1,
  'circle-stroke-color': 'rgba(0,0,0,0.5)',
  'circle-opacity': 0.92,
};
const mediumPaint: CircleLayerSpecification['paint'] = {
  'circle-radius': ['interpolate', ['linear'], ['zoom'], 4, 1.6, 7, 3, 11, 4.5],
  'circle-color': '#8ea0b5',
  'circle-opacity': 0.8,
  'circle-stroke-width': 0.6,
  'circle-stroke-color': 'rgba(0,0,0,0.45)',
};
const labelLayout: SymbolLayerSpecification['layout'] = {
  'text-field': ['case', ['!=', ['get', 'iata'], ''], ['get', 'iata'], ['get', 'icao']],
  'text-font': ['Noto Sans Regular'],
  'text-size': 11,
  'text-offset': [0, 0.7],
  'text-anchor': 'top',
};
const labelPaint: SymbolLayerSpecification['paint'] = {
  'text-color': '#e7eef6',
  'text-halo-color': '#0c1016',
  'text-halo-width': 1.2,
};
const trailLayout: LineLayerSpecification['layout'] = {
  'line-cap': 'round',
  'line-join': 'round',
};
const trailGlow: LineLayerSpecification['paint'] = {
  'line-color': '#ffb020',
  'line-width': ['interpolate', ['linear'], ['zoom'], 1, 10, 6, 14, 10, 18, 14, 24],
  'line-opacity': 0.35,
  'line-blur': 1.5,
};
const trailCasing: LineLayerSpecification['paint'] = {
  'line-color': '#1a1004',
  'line-width': ['interpolate', ['linear'], ['zoom'], 1, 6, 6, 8, 10, 11, 14, 15],
  'line-opacity': 0.95,
};
const trailCore: LineLayerSpecification['paint'] = {
  'line-color': '#ffc14a',
  'line-width': ['interpolate', ['linear'], ['zoom'], 1, 3, 6, 4, 10, 5.5, 14, 7.5],
  'line-opacity': 1,
};

export type SkyMapRef = {
  flyTo: (lon: number, lat: number) => void;
};

type Props = {
  selected: string | null;
  onSelect: (hex: string | null) => void;
};

const Airports = memo(function Airports() {
  return (
    <GeoJSONSource id="airports" data={airportData}>
      <Layer id="airports-large" type="circle" filter={largeFilter} paint={largePaint} />
      <Layer id="airports-medium" type="circle" minzoom={4} filter={mediumFilter} paint={mediumPaint} />
      <Layer id="airport-label" type="symbol" minzoom={6} filter={largeFilter} layout={labelLayout} paint={labelPaint} />
    </GeoJSONSource>
  );
});

const LiveTraffic = memo(function LiveTraffic({
  selectedRef,
  onSelectRef,
}: {
  selectedRef: RefObject<string | null>;
  onSelectRef: RefObject<(hex: string | null) => void>;
}) {
  const [planes, setPlanes] = useState(EMPTY);
  const [trail, setTrail] = useState(emptyTrail);

  useEffect(() => {
    let raf = 0;
    let lastPush = 0;
    let lastTrailNote = 0;
    let pushEvery = 33;
    let positions = new Float64Array(0);
    let trailVisible = false;

    const tick = () => {
      raf = requestAnimationFrame(tick);
      const now = performance.now();
      const crafts = craftList();
      if (positions.length < crafts.length * 2) {
        positions = new Float64Array(Math.max(crafts.length * 2, Math.ceil(positions.length * 1.5) || 256));
      }
      for (let i = 0; i < crafts.length; i++) {
        const p = positionAt(crafts[i]!, now);
        positions[i * 2] = p.lon;
        positions[i * 2 + 1] = p.lat;
      }
      const hex = selectedRef.current;
      const craft = hex ? craftByHex(hex) : undefined;
      if (hex && craft) lastTrailNote = extendTrail(hex, craft, now, lastTrailNote);
      if (now - lastPush < pushEvery) return;
      lastPush = now;
      // Keep dead-reckoning every frame; push the shape source at 15–30Hz.
      const built = buildPlaneData(crafts, positions, hex, now);
      pushEvery = built.count > 2500 ? 66 : built.count > 800 ? 50 : 33;
      setPlanes((prev) => (prev === built.json ? prev : built.json));
      if (hex && craft) {
        trailVisible = true;
        const next = trailData(craft, now);
        setTrail((prev) => (prev === next ? prev : next));
      } else if (trailVisible) {
        trailVisible = false;
        setTrail(emptyTrail());
      }
    };

    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [selectedRef]);

  const onPlanePress = useCallback((event: NativeSyntheticEvent<PressEventWithFeatures>) => {
    event.stopPropagation();
    const hex = event.nativeEvent.features[0]?.properties?.hex;
    if (typeof hex === 'string') onSelectRef.current(hex);
  }, [onSelectRef]);

  return (
    <>
      <GeoJSONSource id="trail" data={trail} tolerance={0}>
        <Layer id="trail-glow" type="line" layout={trailLayout} paint={trailGlow} />
        <Layer id="trail-casing" type="line" layout={trailLayout} paint={trailCasing} />
        <Layer id="trail" type="line" layout={trailLayout} paint={trailCore} />
      </GeoJSONSource>
      <GeoJSONSource id="planes" data={planes} tolerance={0} onPress={onPlanePress} hitbox={PLANE_HITBOX}>
        {/* v11 GeoJSONSource + symbol layer: the current ShapeSource / SymbolLayer. */}
        <Layer id="plane-icons" type="symbol" layout={planeLayout} paint={planePaint} />
      </GeoJSONSource>
    </>
  );
});

export const SkyMap = memo(
  forwardRef<SkyMapRef, Props>(function SkyMap({ selected, onSelect }, ref) {
    const cameraRef = useRef<CameraRef>(null);
    const selectedRef = useRef(selected);
    const onSelectRef = useRef(onSelect);
    selectedRef.current = selected;
    onSelectRef.current = onSelect;

    useImperativeHandle(ref, () => ({
      flyTo(lon: number, lat: number) {
        cameraRef.current?.flyTo({ center: [lon, lat], zoom: 11, duration: 1400 });
      },
    }), []);

    const onRegion = useCallback((event: NativeSyntheticEvent<ViewStateChangeEvent>) => {
      const { bounds, zoom } = event.nativeEvent;
      setViewBox({ west: bounds[0], south: bounds[1], east: bounds[2], north: bounds[3], zoom });
    }, []);

    const onMapPress = useCallback((event: NativeSyntheticEvent<PressEvent> | NativeSyntheticEvent<PressEventWithFeatures>) => {
      const native = event.nativeEvent;
      if ('features' in native && native.features.length > 0) return;
      onSelectRef.current(null);
    }, []);

    return (
      <Map
        style={styles.map}
        mapStyle={STYLE}
        androidView="texture"
        attribution={false}
        logo={false}
        compass
        compassPosition={COMPASS_POSITION}
        onPress={onMapPress}
        onRegionIsChanging={onRegion}
        onRegionDidChange={onRegion}
      >
        <Camera ref={cameraRef} initialViewState={INITIAL_VIEW} />
        <Images images={IMAGES} />
        <Airports />
        <LiveTraffic selectedRef={selectedRef} onSelectRef={onSelectRef} />
      </Map>
    );
  }),
);

const styles = StyleSheet.create({
  map: { flex: 1 },
});
