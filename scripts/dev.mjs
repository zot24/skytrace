import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';

function localBin(name) {
  const bin = `node_modules/.bin/${name}`;
  if (!existsSync(bin)) throw new Error(`missing ${bin}. Run npm i in the repo root.`);
  return bin;
}

const env = {
  ...process.env,
  CI: process.env.CI ?? '1',
  WRANGLER_SEND_METRICS: 'false',
};

const wranglerArgs = ['dev', '--port', '8787', '--ip', '127.0.0.1', '--show-interactive-dev-session=false'];
if (process.env.SKYTRACE_SIMULATE_429 === '1') wranglerArgs.push('--var', 'SKYTRACE_SIMULATE_429:1');

const stdio = ['ignore', 'inherit', 'inherit'];
const children = [
  spawn(localBin('wrangler'), wranglerArgs, { stdio, env }),
  spawn(localBin('vite'), ['--host', '127.0.0.1', '--port', '5173'], { stdio, env }),
];

let stopping = false;

function shutdown(code) {
  if (stopping) return;
  stopping = true;
  for (const child of children) child.kill('SIGTERM');
  setTimeout(() => process.exit(code), 150);
}

process.on('SIGINT', () => shutdown(0));
process.on('SIGTERM', () => shutdown(0));
for (const child of children) {
  child.on('exit', (code) => shutdown(code ?? 0));
}
