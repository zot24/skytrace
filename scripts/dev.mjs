import { spawn } from 'node:child_process';

const children = [
  spawn('npx', ['wrangler', 'dev', '--port', '8787', '--ip', '127.0.0.1'], { stdio: 'inherit' }),
  spawn('npx', ['vite', '--host', '127.0.0.1', '--port', '5173'], { stdio: 'inherit' }),
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
