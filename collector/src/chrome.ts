import { existsSync } from 'node:fs';
import path from 'node:path';

// Prefer the installed machine-wide Chrome on Windows; channel discovery can select a stale user copy.
export function chromeExecutable(): string | undefined {
  if (process.platform !== 'win32') return undefined;
  for (const directory of [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.LOCALAPPDATA]) {
    if (!directory) continue;
    const executable = path.join(directory, 'Google', 'Chrome', 'Application', 'chrome.exe');
    if (existsSync(executable)) return executable;
  }
  return undefined;
}
