import "./styles/base.css";
import "./styles/room-lighting.css";
import "./styles/character-png.css";
import "./styles/stage-scene.css";
import "./styles/control.css";
import "./styles/console-layout.css";
import "./styles/capture.css";
import { receiveCapture, refreshCapture } from './pages/capture';
import { bindMusic, updateMusicControls, type MusicState } from './audio/MusicPlayer';
import type {NativeMusicState} from './audio/NativeMusicClient';
import { nightView, nightshopMarkup } from "./pages/nightshop";
import { bootNightshop } from "./pages/nightshopLive";
import "./styles/nightshop.css";
import { icon } from "./pages/icons";
import { controlMarkup, bindControl, updateControl, updateCharacterState } from "./pages/control";

import { RoomStream } from "./events/stream";

import type {
  RoomStatus,
  SpeechTask,
  WireMessage,
  Action,
  Emotion,
} from "./events/protocol";
import { api, toast } from "./events/api";

const isLive = location.pathname === "/live";
const view = nightView();
document.body.classList.add(isLive ? "live-page" : "control-page");
if (isLive) document.body.classList.add("nightshop-page");
const app = document.querySelector<HTMLElement>("#app")!;
app.innerHTML = isLive
  ? `<main class="live-viewport">${nightshopMarkup(view)}</main>${view.canOwn ? `<button class="voice-gate" id="enable-voice" aria-label="启用语音与音乐" title="启用语音与音乐">${icon("volume")}</button>` : ''}`
  : controlMarkup();

function connectionChanged(connected: boolean) {
  const indicator = document.getElementById("connection");
  if (indicator) {
    indicator.innerHTML = `<i></i>${connected ? "服务已连接" : "正在重新连接"}`;
    indicator.classList.toggle("connected", connected);
  }
}

/** The workbench only observes the room and binds controls; it never constructs a stage or audio player. */
function bootControl() {
  bindControl();
  bindMusic();
  let ownerConnected = false;
  let musicState: MusicState | undefined;
  const refreshMusic = () => void api<MusicState>("/music/status").then(state => {
    musicState = state; updateMusicControls(state, ownerConnected);
  }).catch(() => {});
  new RoomStream(false, ({ type, data }) => {
    receiveCapture(type, data);
    if (type === "SNAPSHOT" || type === "QUEUE_UPDATE") {
      const status = data as RoomStatus;
      ownerConnected = status.ownerConnected;
      updateControl(status);
      if (musicState) updateMusicControls(musicState, ownerConnected);
      if (type === "SNAPSHOT") { void refreshCapture(); refreshMusic(); }
    }
    if (type === "MUSIC_UPDATE") { musicState = data as MusicState; updateMusicControls(musicState, ownerConnected); }
    if(type==='NATIVE_MUSIC_STATE'&&musicState){musicState={...musicState,native:data as NativeMusicState};updateMusicControls(musicState,ownerConnected);}
    if (type === "CHARACTER_ACTION") { const action = data as { action?: Action; emotion?: Emotion }; updateCharacterState(action.action, action.emotion); }
    if (type === "SPEAK") updateCharacterState((data as SpeechTask).action || "talk");
    if (type === "SPEECH_END" || type === "SPEECH_CANCEL") updateCharacterState("idle");
  }, connectionChanged);
}

async function boot() {
  if (!isLive) { bootControl(); return; }
  await bootNightshop(view);
}
boot().catch((error) => {
  console.error(error);
  toast(error.message || "页面启动失败，请刷新重试", true);
});
