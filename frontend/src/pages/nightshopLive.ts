import { CharacterController, type CharacterPose } from '../character/CharacterController';
import { PngCharacterRenderer } from '../character/PngCharacterRenderer';
import { CHARACTER_PROFILE } from '../character/profile';
import { MusicPlayer, type MusicState } from '../audio/MusicPlayer';
import {NativeMusicClient,type NativeMusicState} from '../audio/NativeMusicClient';
import { SpeechPlayer } from '../audio/SpeechPlayer';
import { RoomStream } from '../events/stream';
import { api } from '../events/api';
import type { Action, AudioSettings, CaptureStatus, Emotion, RoomStatus, SpeechTask, WireMessage } from '../events/protocol';
import { fitNightshop, NightshopProjection, type NightView, type VoiceObservation } from './nightshop';

/** Presentation adapter for the existing room/owner/renderer/audio chain. */
export async function bootNightshop(view: NightView) {
  fitNightshop();
  // These two layers are static art. Avoid consuming browser HTTP/1 SSE slots.
  if (view.layer === 'background' || view.layer === 'foreground') return;
  const projection = new NightshopProjection();
  let character: CharacterController | undefined;
  if (view.avatar) {
    const actor = document.querySelector<HTMLElement>('.nightshop .actor')!;
    const renderer = await PngCharacterRenderer.create(actor, CHARACTER_PROFILE.manifestUrl);
    actor.dataset.character = CHARACTER_PROFILE.id; actor.setAttribute('aria-label', CHARACTER_PROFILE.label);
    character = new CharacterController(actor, document.querySelector('.speech-bubble')!, document.querySelector('.actor-shadow')!, renderer);
  }
  let stream: RoomStream;
  let owns = false, connected = false, refreshGeneration = 0;
  let ownerId = '', ownerEpoch = '', voice: VoiceObservation | undefined;
  let native:NativeMusicClient|undefined;
  const channel = 'BroadcastChannel' in window ? new BroadcastChannel('ds-nightshop-render-v3') : null;
  const observeVoice = (value: VoiceObservation) => {
    if(owns){if(value.state==='started')native?.actualAudioStarted(value.taskId,ownerEpoch);else native?.actualAudioEnded(value.taskId);}
    voice = value.state === 'started' ? value : undefined;
    projection.voice(value);
    if (owns) channel?.postMessage({ ownerId, ownerEpoch, voice: value });
  };
  // Observer-only layers and preview never construct a speech/music player or audio context.
  const music = view.canOwn ? new MusicPlayer(true, () => stream.clientId) : undefined;
  native=music?new NativeMusicClient(music,()=>stream.clientId):undefined;
  const player = view.canOwn && character ? new SpeechPlayer(character, document.querySelector('.speech-bubble')!, document.querySelector('.bubble-text')!, true,
    () => stream.clientId,
    notice => { const el = document.getElementById('night-audio-notice'); if (el) el.textContent = notice; }, observeVoice) : undefined;
  const ownership = (id: string, epoch: string, ownerConnected: boolean) => {
    const next = view.canOwn && id === stream.clientId;
    if (id !== ownerId || epoch !== ownerEpoch) { character?.becomeOwner(); character?.endSpeech(); }
    if (next && (!owns || epoch !== ownerEpoch)) character?.becomeOwner();
    if (owns && (!next || epoch !== ownerEpoch)) { player?.cancel(); voice = undefined; }
    ownerId = id; ownerEpoch = epoch; owns = next;
    music?.ownership(owns, ownerConnected, epoch);
    native?.ownership(owns,epoch);
  };
  if (channel) channel.onmessage = event => {
    const value = event.data as { ownerId: string; ownerEpoch: string; pose?: CharacterPose; voice?: VoiceObservation };
    if (!connected || owns || !ownerEpoch || value.ownerId !== ownerId || value.ownerEpoch !== ownerEpoch) return;
    if (value.pose) character?.follow(value.pose);
    if (value.voice) projection.voice(value.voice);
  };
  const timer = window.setInterval(() => {
    if (owns && connected) {
      // Only a real engine start creates voice; terminal/cancel/lease paths clear it.
      if (voice) projection.voice(voice);
      channel?.postMessage({ ownerId, ownerEpoch, pose: character?.pose(), voice });
    }
  }, 100);
  const refresh = () => {
    const generation = ++refreshGeneration;
    void api<MusicState>('/music/status').then(state => {
      if (connected && generation === refreshGeneration) { projection.musicState(state); music?.receive(state);if(state.native)native?.receive(state.native); }
    }).catch(() => {});
    for (const platform of ['douyin', 'bilibili']) void api<CaptureStatus>(`/${platform}/status`).then(state => {
      if (connected && generation === refreshGeneration) projection.captureStatus({ ...state, platform: platform as 'douyin' | 'bilibili' });
    }).catch(() => {});
  };
  const receive = (message: WireMessage) => {
    const { type, data } = message;
    projection.receive(message);
    if (type === 'SNAPSHOT' || type === 'QUEUE_UPDATE') {
      const status = data as RoomStatus;
      ownership(status.ownerId, status.ownerEpoch || '', status.ownerConnected);
      player?.applySettings(status.audio);
    }
    if (type === 'MUSIC_UPDATE') music?.receive(data as MusicState);
    if(type==='NATIVE_MUSIC_STATE')native?.receive(data as NativeMusicState);
    if (type === 'SPEAK') {
      const task = data as SpeechTask;
      ownership(task.ownerId, task.ownerEpoch || '', true);
      if (owns) { void player?.play(task); }
    }
    if (type === 'SPEECH_END' || type === 'SPEECH_CANCEL') { const taskId=(data as {taskId:string}).taskId;player?.ended(taskId);native?.actualAudioEnded(taskId); }
    if (type === 'CHARACTER_ACTION') {
      const action = data as { action?: Action; emotion?: Emotion };
      if (action.emotion) character?.setEmotion(action.emotion);
      if (action.action) character?.request(action.action, action.action === 'celebrate' ? 40 : 20);
    }
    if (type === 'AUDIO_SETTINGS') player?.applySettings(data as AudioSettings);
  };
  stream = new RoomStream(view.canOwn, receive, value => {
    connected = value; refreshGeneration++;
    projection.connection(value);
    if (!value) { owns = false; voice = undefined; player?.cancel();native?.disconnected(); music?.disconnected(); character?.becomeOwner(); character?.endSpeech(); }
    else refresh();
  });
  const gate = document.getElementById('enable-voice');
  if (gate) gate.onclick = () => { player?.unlock(); music?.unlock(); gate.remove(); };
  const debug = document.querySelector<HTMLElement>('.debug-panel')!;
  if (new URLSearchParams(location.search).get('debug') === '1') {
    debug.hidden = false;
    window.setInterval(() => debug.textContent = `layer=${view.layer}\nrole=${view.canOwn ? 'live' : 'preview'} owns=${owns}\nrenderer=${CHARACTER_PROFILE.renderer}\nstate=${character?.state || 'no actor'}`, 500);
  }
  window.addEventListener('pagehide', () => {native?.destroy(); clearInterval(timer); channel?.close(); }, { once: true });
}
