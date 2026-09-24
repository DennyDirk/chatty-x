export interface Connection {
  id: string;
  name: string;
  adapter: string;
  status: string;
  enabled: boolean;
}
export interface Conversation {
  id: string;
  connectionId: string;
  externalId: string;
  title: string;
  selected: boolean;
  mode: string;
  status: string;
  version: number;
  imported: boolean;
  importCount: number;
  needsAttention: string | null;
  summary: string;
  accountName: string;
  adapter: string;
  preview: string | null;
}
export interface Message {
  id: string;
  externalId: string;
  direction: string;
  source: string;
  body: string;
  sentAt: string;
  kind: string;
  deliveryStatus: string;
  attachments: { id: string; kind: string; status: string; transcript: string | null }[];
}
export interface Outbound {
  id: string;
  body: string;
  status: string;
  reason: string;
  contextVersion: number;
  source: string;
}
export interface Fact {
  id: string;
  subject: string;
  content: string;
  pinned: boolean;
  conflict: string | null;
  sources: string[];
}
export type SettingValue =
  | string
  | number
  | boolean
  | string[]
  | Record<string, { mode: string; rule: string }>;
export interface Settings {
  version: number;
  body: Record<string, SettingValue>;
  effective?: Record<string, SettingValue>;
  sources?: Record<string,string>;
}
export interface Usage {
  limitUsd: number;
  usedUsd: number;
  percent: number;
  entries: {
    id: string;
    model: string;
    kind: string;
    state: string;
    actualUsd: number | null;
    reservedUsd: number;
    createdAt: string;
  }[];
}
export const statuses: Record<string, string> = {
  READY: "Готово",
  IDLE: "Автоответы включены",
  WAITING: "Ждём продолжения",
  GENERATING: "Готовим ответ",
  IMPORTING: "Загружаем историю",
  PAUSED: "На паузе",
  ATTENTION: "Нужно ваше участие",
  AUTHORIZING: "Подключение",
  DISCONNECTED: "Не подключён",
  ERROR: "Ошибка подключения",
  SENT: "Отправлено",
  SUBMITTED: "Ожидаем подтверждения",
  SENDING: "Отправляем",
  UNKNOWN: "Отправка не подтверждена",
  FAILED: "Ошибка отправки",
  DRAFT: "Черновик",
  STALE: "Устаревший черновик",
};
