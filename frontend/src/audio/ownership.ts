/** One page-local lease shared by speech and music; undefined preserves standalone player compatibility. */
let owns: boolean | undefined;
const listeners = new Set<(value: boolean) => void>();
export const ownsAudio = () => owns;
export function updateAudioOwnership(value: boolean) {
  if (value === owns) return;
  owns = value;
  for (const listener of listeners) listener(value);
}
export function onAudioOwnership(listener: (value: boolean) => void) {
  listeners.add(listener);
  if (owns !== undefined) listener(owns);
  return () => { listeners.delete(listener); };
}
