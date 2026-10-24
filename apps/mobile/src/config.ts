import Constants from 'expo-constants';

function configuredBase(): string | undefined {
  const extra = Constants.expoConfig?.extra;
  if (!extra || typeof extra !== 'object' || !('apiBase' in extra)) return undefined;
  const value = extra.apiBase;
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

const raw = process.env.EXPO_PUBLIC_SKYTRACE_API?.trim() || configuredBase() || 'http://10.0.2.2:8787';

export const API_BASE = raw.replace(/\/$/, '');
