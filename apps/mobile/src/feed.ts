import { useEffect, useState } from 'react';
import { API_BASE } from './config';
import { applyFeedBody, craftList, type FeedRow } from './traffic';
import { viewBox } from './view';

type FeedBody = {
  src?: string;
  ac?: FeedRow[];
  stale?: boolean;
};

const SUCCESS_MS = 5_000;
const FAIL_BASE_MS = 2_000;
const FAIL_CAP_MS = 60_000;

export type FeedStat = { count: number; source: string };

function failureDelay(attempt: number): number {
  const exp = Math.min(FAIL_CAP_MS, FAIL_BASE_MS * 2 ** Math.min(attempt - 1, 5));
  return Math.round(Math.min(FAIL_CAP_MS, exp * (0.5 + Math.random() * 0.5)));
}

function feedUrl(): string {
  const latSpan = viewBox.north - viewBox.south;
  let lonSpan = viewBox.east - viewBox.west;
  if (lonSpan < 0) lonSpan += 360;
  if (latSpan > 12 || lonSpan > 18) return `${API_BASE}/api/planes`;
  const qn = (n: number) => (Math.round(n * 2) / 2).toFixed(1);
  return `${API_BASE}/api/planes?lamin=${qn(viewBox.south)}&lamax=${qn(viewBox.north)}&lomin=${qn(viewBox.west)}&lomax=${qn(viewBox.east)}`;
}

export function useFeed(): FeedStat {
  const [stat, setStat] = useState<FeedStat>({ count: 0, source: 'connecting' });

  useEffect(() => {
    let stopped = false;
    let timer = 0;
    let attempt = 0;
    let sourceName = 'connecting';

    const publish = () => {
      if (!stopped) setStat({ count: craftList().length, source: sourceName });
    };

    const arm = (delay: number, reason: string | null) => {
      if (stopped) return;
      if (reason) console.info(`skytrace backoff ${delay}ms (${reason})`);
      timer = setTimeout(() => void poll(), delay) as unknown as number;
    };

    const markStale = () => {
      const base = sourceName.replace(/ · stale$/, '');
      sourceName = base && base !== 'connecting' && base !== 'feed unavailable' ? `${base} · stale` : 'feed unavailable';
    };

    async function poll() {
      try {
        const res = await fetch(feedUrl(), { cache: 'no-store' });
        let body: FeedBody | null = null;
        try {
          body = (await res.json()) as FeedBody;
        } catch {
          body = null;
        }
        const outcome = applyFeedBody(body, res.ok, performance.now());
        if (outcome.src) sourceName = outcome.src;
        if (!outcome.fresh) {
          attempt += 1;
          if (craftList().length) markStale();
          else sourceName = 'feed unavailable';
          const why = !res.ok ? `http ${res.status}` : body?.stale ? 'stale' : 'empty';
          arm(failureDelay(attempt), `attempt ${attempt}, ${why}`);
          publish();
          return;
        }
        attempt = 0;
        arm(SUCCESS_MS + Math.round(Math.random() * 800), null);
      } catch {
        applyFeedBody(null, false, performance.now());
        attempt += 1;
        if (craftList().length) markStale();
        else sourceName = 'feed unavailable';
        arm(failureDelay(attempt), `attempt ${attempt}, network`);
      }
      publish();
    }

    void poll();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, []);

  return stat;
}
