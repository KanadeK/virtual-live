export type Action =
  | "idle"
  | "walk"
  | "walk_left"
  | "walk_right"
  | "talk"
  | "blink"
  | "wave"
  | "happy"
  | "surprised"
  | "celebrate"
  | "sad"
  | "think";
export type Emotion = "neutral" | "happy" | "surprised" | "sad" | "think";
export type EventType = "USER_ENTER" | "COMMENT" | "GIFT" | "LIKE" | "SYSTEM";
export interface SpeechTask {
  taskId: string;
  text: string;
  emotion: Emotion;
  action: Action;
  priority: number;
  ownerId: string;
  ownerEpoch?: string;
  startedAt: number;
  ttsProvider?: "doubao"|"browser";
}
export interface AudioSettings {
  enabled: boolean;
  rate: number;
  pitch: number;
  /** 豆包音色 ID；空串表示使用 .env 配置的默认音色。 */
  voice: string;
}
export interface CaptureAudience {
  available: boolean; uniqueChatters: number | null; scope: 'capture_run'; date: string; timeZone: 'Asia/Shanghai';
  platform: string; roomId: string; state: string; startedAt: number | null; throughAt: number | null;
  hadGaps: boolean; observedEvents: number; reason: string;
}
export interface RoomStatus {
  audience?: CaptureAudience;
  online: number;
  likes: number;
  gifts: number;
  queueSize: number;
  queue: { taskId: string; text: string; priority: number }[];
  current: Partial<SpeechTask>;
  ownerConnected: boolean;
  ownerId: string;
  ownerEpoch?: string;
  dropped: number;
  audio: AudioSettings;
  logs: LogEntry[];
  autoRunning?: boolean;
  aiProvider: string;
  phase?: 'IDLE'|'PREPARING_AI'|'PLAYING';
  aiStatus?: ProviderStatus;
  ttsStatus?: ProviderStatus;
  usage?: {date:string;counters:Record<string,number>;aiDailyRequestLimit:number;ttsDailyCharLimit:number;aiTotalTokens?:number};
  paidAutoEnabled?: boolean;
}
export interface LogEntry {
  id: string;
  timestamp: number;
  type: string;
  text: string;
}
export interface Gift {
  nickname: string;
  giftName: string;
  giftCount: number;
}
export interface WireMessage {
  type: string;
  data: unknown;
}

export type CapturePlatform = "douyin" | "bilibili";
export interface CaptureEvent {
  /** Older capture messages without a platform belong to Douyin. */
  platform?: CapturePlatform;
  id: string;
  roomId: string;
  type: "chat" | "gift" | "member";
  timestamp: number;
  userId: string;
  nickname: string;
  content: string;
  giftName: string;
  giftCount: number;
  comboTotal: number;
}
export interface CaptureStatus {
  platform?: CapturePlatform;
  mode: string;
  roomId: string;
  inputRoomId?: string;
  state: "stopped" | "connecting" | "live" | "reconnecting" | "closed" | "error";
  message: string;
  hasLogin: boolean;
  comments: number;
  gifts: number;
  members: number;
  frames: number;
  attempts: number;
  events?: CaptureEvent[];
  loginState?: "idle" | "opening" | "waiting" | "success" | "expired" | "cancelled" | "error";
  loginMessage?: string;
  errorCode?: string;
  requiresLogin?: boolean;
  liveStatus?: number;
}

export interface ProviderStatus {provider:string;requestedProvider?:string;configured?:boolean;model?:string;voice?:string;lastSuccessAt?:number;lastLatencyMs?:number;lastError?:string;lastFallback?:string;circuitOpen?:boolean;configurationIssue?:string}

export type CorpusType = "welcome" | "idle" | "gift";
export interface CorpusItem {
  id: string;
  type: CorpusType;
  text: string;
  enabled: boolean;
}
export interface IdleSettings {
  enabled: boolean;
  intervalSeconds: number;
  requireRecentActivitySeconds: number;
}
export interface CorpusState {
  items: CorpusItem[];
  idle: IdleSettings;
  revision: number;
}
export interface IntegrationConfig {
  ai: {
    provider: "mock" | "deepseek";
    model: string;
    apiKeyConfigured: boolean;
    maxOutputTokens: number;
    maxReplyChars: number;
    dailyRequestLimit: number;
  };
  tts: {
    provider: "browser" | "doubao";
    authMode: "apiKey" | "appToken";
    apiKeyConfigured: boolean;
    appId: string;
    accessTokenConfigured: boolean;
    resourceId: string;
    speaker: string;
    dailyCharLimit: number;
  };
  paidAutoEnabled: boolean;
  timeoutSeconds: number;
  revision: number;
}
export type SecretName = "deepseekApiKey" | "doubaoApiKey" | "doubaoAccessToken";
export interface IntegrationUpdate {
  ai?: Partial<Omit<IntegrationConfig["ai"], "apiKeyConfigured">> & { apiKey?: string };
  tts?: Partial<Omit<IntegrationConfig["tts"], "apiKeyConfigured" | "accessTokenConfigured">> & {
    apiKey?: string;
    accessToken?: string;
  };
  paidAutoEnabled?: boolean;
  timeoutSeconds?: number;
  clearSecrets?: SecretName[];
}
