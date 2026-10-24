import type { ConfigContext, ExpoConfig } from 'expo/config';

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: 'skytrace',
  slug: 'skytrace',
  scheme: 'skytrace',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  backgroundColor: '#07090c',
  android: {
    ...config.android,
    package: 'com.zot24.skytrace',
    backgroundColor: '#07090c',
    adaptiveIcon: {
      backgroundColor: '#07090c',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
  },
  plugins: ['@maplibre/maplibre-react-native', './plugins/withCleartext'],
  extra: {
    ...config.extra,
    apiBase: 'http://10.0.2.2:8787',
  },
});
