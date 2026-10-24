import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Keyboard,
  Platform,
  Pressable,
  StatusBar as RNStatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { searchAirports, type Airport } from './src/airports';
import { useFeed } from './src/feed';
import { SkyMap, type SkyMapRef } from './src/SkyMap';
import { craftByHex, type Craft } from './src/traffic';

type Panel = {
  callsign: string;
  type: string;
  reg: string;
  alt: string;
  gs: string;
  hdg: string;
};

export default function App() {
  const mapRef = useRef<SkyMapRef>(null);
  const feed = useFeed();
  const [selected, setSelected] = useState<string | null>(null);
  const [panel, setPanel] = useState<Panel | null>(null);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<Airport[]>([]);
  const onSelect = useCallback((hex: string | null) => {
    setSelected(hex);
    setHits([]);
    Keyboard.dismiss();
  }, []);

  useEffect(() => {
    if (!selected) {
      setPanel(null);
      return;
    }
    const paint = () => {
      const craft = craftByHex(selected);
      if (!craft) {
        setSelected(null);
        return;
      }
      setPanel(toPanel(craft));
    };
    paint();
    const id = setInterval(paint, 250);
    return () => clearInterval(id);
  }, [selected]);

  const choose = (airport: Airport) => {
    setQuery(airport.iata || airport.icao || airport.name);
    setHits([]);
    Keyboard.dismiss();
    mapRef.current?.flyTo(airport.lon, airport.lat);
  };

  const top = Platform.OS === 'android' ? (RNStatusBar.currentHeight ?? 0) : 0;

  return (
    <View style={styles.root}>
      <SkyMap ref={mapRef} selected={selected} onSelect={onSelect} />
      <View style={[styles.top, { paddingTop: top + 10 }]} pointerEvents="box-none">
        <View style={styles.brand}>
          <Text style={styles.brandName}>skytrace</Text>
          <Text style={styles.stat}>
            {feed.count.toLocaleString('en-US')} aircraft · {feed.source}
          </Text>
        </View>
        <View style={styles.searchWrap}>
          <TextInput
            value={query}
            onChangeText={(text) => {
              setQuery(text);
              setHits(searchAirports(text));
            }}
            placeholder="Search airport — name, IATA, ICAO"
            placeholderTextColor="#93a1b3"
            autoCorrect={false}
            autoCapitalize="none"
            style={styles.search}
          />
          {hits.length > 0 ? (
            <View style={styles.hits}>
              {hits.map((airport) => (
                <Pressable key={`${airport.icao}:${airport.iata}:${airport.lat}`} onPress={() => choose(airport)} style={styles.hit}>
                  <Text style={styles.hitCode}>{[airport.iata, airport.icao].filter(Boolean).join(' · ')}</Text>
                  <Text style={styles.hitName} numberOfLines={1}>
                    {airport.name}
                    {airport.city ? ` · ${airport.city}` : ''}
                  </Text>
                </Pressable>
              ))}
            </View>
          ) : null}
        </View>
      </View>
      <View style={styles.bottom} pointerEvents="box-none">
        {panel ? (
          <View style={styles.panel}>
            <View style={styles.panelHead}>
              <Text style={styles.callsign}>{panel.callsign}</Text>
              <Pressable onPress={() => onSelect(null)} hitSlop={8} accessibilityLabel="Close">
                <Text style={styles.close}>×</Text>
              </Pressable>
            </View>
            <Row label="Type" value={panel.type} />
            <Row label="Registration" value={panel.reg} />
            <Row label="Altitude" value={panel.alt} />
            <Row label="Ground speed" value={panel.gs} />
            <Row label="Heading" value={panel.hdg} />
          </View>
        ) : null}
        <Text style={styles.credits} pointerEvents="none">
          ADS-B © adsb.lol contributors (ODbL) · OpenSky (non-commercial fallback) · OurAirports (public domain) · OpenFreeMap · © OpenStreetMap contributors
        </Text>
      </View>
      <StatusBar style="light" />
    </View>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

function toPanel(craft: Craft): Panel {
  const heading = Math.round(((craft.track % 360) + 360) % 360);
  return {
    callsign: craft.callsign || craft.hex.toUpperCase(),
    type: craft.type || '—',
    reg: craft.reg || '—',
    alt: `${Math.round(craft.alt).toLocaleString('en-US')} ft`,
    gs: `${Math.round(craft.gs).toLocaleString('en-US')} kt`,
    hdg: `${String(heading).padStart(3, '0')}°`,
  };
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#07090c' },
  top: { position: 'absolute', top: 0, left: 12, right: 12, gap: 8 },
  brand: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(8,11,16,0.9)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderWidth: 1,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  brandName: { color: '#e8eef6', fontSize: 14, fontWeight: '700', letterSpacing: 1.6 },
  stat: { color: '#93a1b3', fontSize: 12, marginTop: 2, fontVariant: ['tabular-nums'] },
  searchWrap: { alignSelf: 'stretch' },
  search: {
    backgroundColor: 'rgba(8,11,16,0.9)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderWidth: 1,
    color: '#e8eef6',
    borderRadius: 999,
    paddingHorizontal: 16,
    paddingVertical: 10,
    fontSize: 14,
  },
  hits: {
    marginTop: 8,
    backgroundColor: 'rgba(8,11,16,0.94)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderWidth: 1,
    borderRadius: 14,
    overflow: 'hidden',
  },
  hit: { paddingHorizontal: 12, paddingVertical: 8 },
  hitCode: { color: '#e6b15a', fontSize: 12, fontWeight: '700' },
  hitName: { color: '#e8eef6', fontSize: 13 },
  bottom: { position: 'absolute', left: 12, right: 12, bottom: 10, gap: 8 },
  panel: {
    backgroundColor: 'rgba(8,11,16,0.92)',
    borderColor: 'rgba(255,255,255,0.12)',
    borderWidth: 1,
    borderRadius: 16,
    paddingHorizontal: 14,
    paddingTop: 10,
    paddingBottom: 8,
  },
  panelHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 },
  callsign: { color: '#e8eef6', fontSize: 20, fontWeight: '700' },
  close: { color: '#93a1b3', fontSize: 26, lineHeight: 28, paddingHorizontal: 4 },
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  rowLabel: { color: '#93a1b3', fontSize: 13 },
  rowValue: { color: '#e8eef6', fontSize: 13, fontVariant: ['tabular-nums'] },
  credits: {
    color: 'rgba(232,238,246,0.78)',
    fontSize: 11,
    lineHeight: 15,
    textAlign: 'center',
    textShadowColor: '#000',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 2,
  },
});
