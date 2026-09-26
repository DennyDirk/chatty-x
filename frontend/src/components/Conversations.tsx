import { useState, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Search,
  Send,
  SlidersHorizontal,
  Pause,
  Play,
  ArrowLeft,
  Brain,
  Pin,
  Trash2,
  MessageCircle,
  Sparkles,
  AlertCircle,
} from "lucide-react";
import { api, ApiError, errorText } from "../lib/api";
import { type Conversation, type Message, type Outbound, type Fact, statuses } from "../lib/types";
import { Button } from "./ui/button";
import { HistoryImportNotice } from "./HistoryImportNotice";
import { Authorization } from "./Authorization";
import { SettingsEditor } from "./SettingsEditor";

export function Conversations() {
  const [active, setActive] = useState<string | null>(null),
    [search, setSearch] = useState(""),
    [filter, setFilter] = useState("all");
  const query = useQuery({
    queryKey: ["data", "conversations"],
    queryFn: () => api<Conversation[]>("/conversations"),
    refetchInterval: 5000,
  });
  const list = query.data ?? [];
  const chat = list.find((c) => c.id === active);
  const filtered = list.filter(
    (c) =>
      c.title.toLocaleLowerCase().includes(search.toLocaleLowerCase()) &&
      (filter === "all" ||
        (filter === "auto" && c.mode === "AUTO") ||
        (filter === "attention" && c.status === "ATTENTION")),
  );
  return (
    <div className={`inbox ${chat ? "has-chat" : ""}`}>
      <aside className="chat-list">
        <div className="list-heading">
          <h2>
            Диалоги <span>{list.length}</span>
          </h2>
          <span className="eyebrow">НА СВЯЗИ</span>
        </div>
        <label className="search">
          <Search size={17} />
          <input
            aria-label="Поиск чатов"
            placeholder="Найти собеседника"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <div className="tabs">
          {[
            ["all", "Все"],
            ["auto", "Авто"],
            ["attention", "Нужен ответ"],
          ].map(([key, title]) => (
            <button key={key} className={filter === key ? "active" : ""} onClick={() => setFilter(key)}>
              {title}
            </button>
          ))}
        </div>
        <div className="chat-items">
          {filtered.map((c, index) => (
            <button
              key={c.id}
              className={`chat-item ${active === c.id ? "active" : ""}`}
              onClick={() => setActive(c.id)}
            >
              <span className={`avatar color-${index % 4}`}>{c.title.slice(0, 1)}</span>
              <span className="chat-item-copy">
                <span className="chat-name">
                  {c.title}
                  <span className={`status-dot ${c.mode === "AUTO" ? "online" : ""}`} />
                </span>
                <span className="preview">{c.preview || "Начните с выбора этого чата"}</span>
                <small>
                  {c.accountName} · {c.adapter === "fake" ? "тестовый" : (statuses[c.status] ?? c.status)}
                </small>
              </span>
            </button>
          ))}
          {filtered.length === 0 && (
            <div className="empty small">
              <MessageCircle />
              <h3>{search ? "Не нашли совпадений" : "Здесь появятся ваши диалоги"}</h3>
              <p>Подключите аккаунт и загрузите список чатов.</p>
            </div>
          )}
        </div>
        <div className="list-footer">
          <span className="status-dot online" />
          {list.filter((c) => c.mode === "AUTO").length} диалогов в автоматическом режиме
        </div>
      </aside>
      {chat ? (
        <Chat key={chat.id} chat={chat} onBack={() => setActive(null)} />
      ) : (
        <section className="empty inbox-empty">
          <div className="empty-icon">
            <MessageCircle size={36} />
          </div>
          <span className="eyebrow">МЕСТО ДЛЯ РАЗГОВОРА</span>
          <h2>
            Хорошее общение
            <br />
            начинается с внимания.
          </h2>
          <p>
            Выберите собеседника слева.
            <br />
            Контекст, память и настройки будут под рукой.
          </p>
          <div className="empty-features">
            <span>
              <Brain size={17} />
              Помнит детали
            </span>
            <span>
              <SlidersHorizontal size={17} />
              Говорит в вашем стиле
            </span>
          </div>
        </section>
      )}
    </div>
  );
}
export function Chat({ chat, onBack }: { chat: Conversation; onBack: () => void }) {
  const cache = useQueryClient();
  const [text, setText] = useState(""),
    [authorizationOpen, setAuthorizationOpen] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [panel, setPanel] = useState<"memory" | "settings" | null>(null),
    [simulation, setSimulation] = useState(""),
    [cursors, setCursors] = useState<string[]>([""]),
    [messageSearch, setMessageSearch] = useState("");
  const cursor = cursors[cursors.length-1];
  const needsConnection = chat.adapter === "telegram" && chat.connectionStatus !== "READY";
  const pending = useRef<{ text: string; key: string } | null>(null);
  const messages = useQuery({
    queryKey: ["data", "messages", chat.id, cursor, messageSearch],
    queryFn: () => api<Message[]>(`/conversations/${chat.id}/messages?cursor=${encodeURIComponent(cursor)}&search=${encodeURIComponent(messageSearch)}`),
  });
  const outbound = useQuery({
    queryKey: ["data", "outbound", chat.id],
    queryFn: () => api<Outbound[]>(`/conversations/${chat.id}/outbound`),
  });
  const facts = useQuery({
    queryKey: ["data", "memory", chat.id],
    queryFn: () => api<Fact[]>(`/conversations/${chat.id}/memory`),
    enabled: panel === "memory",
  });
  async function act(path: string, method = "POST", body?: unknown) {
    setError("");
    if (needsConnection && method !== "DELETE" &&
        (path.endsWith("/selection") || path.endsWith("/import"))) {
      setAuthorizationOpen(true);
      return;
    }
    setBusy(true);
    try {
      await api(path, method, body);
      await cache.invalidateQueries({ queryKey: ["data"] });
    } catch (e) {
      setError(errorText(e));
      if (chat.adapter === "telegram" && e instanceof ApiError && e.code === "TELEGRAM_NOT_CONNECTED") {
        setAuthorizationOpen(true);
        await cache.invalidateQueries({ queryKey: ["data"] });
      }
    } finally {
      setBusy(false);
    }
  }
  async function send(e: React.FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    if (!pending.current || pending.current.text !== text)
      pending.current = { text, key: crypto.randomUUID() };
    setBusy(true);
    setError("");
    try {
      await api(`/conversations/${chat.id}/messages`, "POST", { text, requestKey: pending.current.key });
      pending.current = null;
      setText("");
      await cache.invalidateQueries({ queryKey: ["data"] });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const drafts = (outbound.data ?? []).filter((o) =>
    ["DRAFT", "STALE", "UNKNOWN", "FAILED", "READY", "SUBMITTED", "SENDING"].includes(o.status),
  );
  return (
    <>
      <section className="chat-thread">
        <header className="thread-header">
          <Button
            variant="ghost"
            size="icon"
            className="mobile-back"
            aria-label="Назад к чатам"
            onClick={onBack}
          >
            <ArrowLeft size={19} />
          </Button>
          <span className="avatar color-0">{chat.title.slice(0, 1)}</span>
          <div className="thread-title">
            <h2>{chat.title}</h2>
            <small>
              <span className={`status-dot ${chat.mode === "AUTO" ? "online" : ""}`} />
              {statuses[chat.status] ?? chat.status}
            </small>
          </div>
          <div className="thread-actions">
            {chat.selected && chat.imported && (
              <Button
                size="small"
                variant="secondary"
                disabled={busy}
                onClick={() =>
                  act(`/conversations/${chat.id}/control/${chat.mode === "AUTO" ? "pause" : "resume"}`)
                }
              >
                {chat.mode === "AUTO" ? <Pause size={15} /> : <Play size={15} />}
                <span>{chat.mode === "AUTO" ? "Взять управление" : "Возобновить"}</span>
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon"
              aria-label="Память собеседника"
              onClick={() => setPanel(panel === "memory" ? null : "memory")}
            >
              <Brain size={19} />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Настройки чата"
              onClick={() => setPanel(panel === "settings" ? null : "settings")}
            >
              <SlidersHorizontal size={19} />
            </Button>
          </div>
        </header>
        {needsConnection && (
          <div className="notice warning" role="status">
            <span>Telegram не подключён. Завершите подключение аккаунта, чтобы загрузить историю.</span>
            <Button size="small" variant="secondary" onClick={() => setAuthorizationOpen(true)}>
              {chat.connectionStatus === "AUTHORIZING" ? "Продолжить вход" : "Подключить Telegram"}
            </Button>
          </div>
        )}
        {!chat.selected && (
          <div className="selection-banner">
            <Sparkles size={20} />
            <div>
              <strong>Вы решаете, где включать общение</strong>
              <p>Загрузим до 1000 сообщений за последние 30 дней. Старые сообщения не запускают ответы.</p>
            </div>
            <Button
              onClick={() => act(`/conversations/${chat.id}/selection`, "PUT", { selected: true })}
              disabled={busy}
            >
              Выбрать чат
            </Button>
          </div>
        )}
        <HistoryImportNotice chat={chat} busy={busy}
          onStart={() => void act(`/conversations/${chat.id}/import`)}
          onCancel={() => void act(`/conversations/${chat.id}/import`, "DELETE")} />
        {chat.needsAttention && chat.status === "ATTENTION" && !["IMPORT_FAILED", "IMPORT_INTERRUPTED"].includes(chat.needsAttention) && (
          <div className="notice warning">
            <AlertCircle size={18} />
            <span>
              Нужно ваше участие:{" "}
              {chat.needsAttention === "DRAFT_READY"
                ? "проверьте черновик"
                : chat.needsAttention === "OLD_INCOMING"
                  ? "накопились давние сообщения"
                  : chat.needsAttention}
            </span>
          </div>
        )}
        <div className="messages">
          <div className="history-controls">
            {(messages.data?.length ?? 0) === 50 && (
              <button onClick={() => {const last=messages.data?.at(-1);if(last)setCursors(values=>[...values,btoa(`${last.sentAt}|${last.id}`).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'')]);}}>Ранее</button>
            )}
            {cursors.length > 1 && <button onClick={() => setCursors(values=>values.slice(0,-1))}>Ближе к настоящему</button>}
            <input aria-label="Поиск в переписке" placeholder="Найти сообщение" value={messageSearch} onChange={event=>{setMessageSearch(event.target.value);setCursors([""]);}}/>
          </div>
          {[...(messages.data ?? [])].reverse().map((m) => (
            <article key={m.id} className={`message ${m.direction === "OUT" ? "mine" : ""}`}>
              <div className="bubble">
                {m.body && <p>{m.body}</p>}
                {m.attachments?.map((a) => (
                  <div key={a.id}>
                    {a.status === "READY" ? (
                      a.kind === "PHOTO" ? (
                        <img
                          className="chat-photo"
                          src={`/api/v1/attachments/${a.id}`}
                          alt="Фотография из переписки"
                          loading="lazy"
                        />
                      ) : (
                        <>
                          <audio controls src={`/api/v1/attachments/${a.id}`} />
                          {a.transcript && (
                            <details>
                              <summary>Расшифровка</summary>
                              <p>{a.transcript}</p>
                            </details>
                          )}
                        </>
                      )
                    ) : (
                      <p className="muted">
                        {a.kind === "VOICE" ? "Голосовое" : a.kind === "PHOTO" ? "Фото" : "Вложение"} ·{" "}
                        {a.status}
                      </p>
                    )}
                  </div>
                ))}
                <footer>
                  {m.source === "AUTOMATION" && (
                    <span>
                      <Sparkles size={11} />
                      Автоответ
                    </span>
                  )}
                  {m.source === "WEB_OWNER" && <span>Вы</span>}
                  <time>
                    {new Date(m.sentAt).toLocaleTimeString("ru", { hour: "2-digit", minute: "2-digit" })}
                  </time>
                </footer>
              </div>
            </article>
          ))}
          {messages.data?.length === 0 && (
            <div className="empty small">
              <MessageCircle size={28} />
              <h3>Пока тихо</h3>
              <p>
                {chat.selected ? "Новые сообщения появятся здесь." : "Выберите чат, чтобы увидеть переписку."}
              </p>
            </div>
          )}
          {drafts.map((d) => (
            <div className="draft" key={d.id}>
              <strong>{statuses[d.status] ?? d.status}</strong>
              <p>{d.body}</p>
              {["DRAFT", "STALE"].includes(d.status) && (
                <Button size="small" variant="secondary" onClick={() => setText(d.body)}>
                  Изменить и ответить
                </Button>
              )}
              {d.status === "UNKNOWN" && (
                <Button size="small" variant="secondary" onClick={() => act(`/outbound/${d.id}/reconcile`)}>
                  Проверить отправку
                </Button>
              )}
              {["DRAFT", "STALE", "READY"].includes(d.status) && (
                <Button
                  size="small"
                  variant="ghost"
                  onClick={() => act(`/conversations/${chat.id}/drafts/${d.id}`, "DELETE")}
                >
                  Отменить
                </Button>
              )}
            </div>
          ))}
        </div>
        {error && (
          <p role="alert" className="error inline-error">
            {error}
          </p>
        )}
        <form className="composer" onSubmit={send}>
          <textarea
            aria-label="Ваш ответ"
            placeholder="Ответьте сами — автоматизация встанет на паузу"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={4000}
            rows={2}
          />
          <Button aria-label="Отправить сообщение" size="icon" disabled={busy || !text.trim()}>
            <Send size={18} />
          </Button>
        </form>
        <div className="composer-note">Ручной ответ передаст этот разговор вам.</div>
        {chat.adapter === "fake" && (
          <details className="simulation">
            <summary>Тестовый входящий · без Telegram</summary>
            <textarea
              aria-label="Тестовое входящее"
              value={simulation}
              onChange={(e) => setSimulation(e.target.value)}
              placeholder="Отправьте несколько реплик подряд"
            />
            <Button
              size="small"
              disabled={!simulation.trim() || busy}
              onClick={() => {
                void act(`/conversations/${chat.id}/simulate`, "POST", { text: simulation, outgoing: false });
                setSimulation("");
              }}
            >
              Добавить входящее
            </Button>
          </details>
        )}
      </section>
      {authorizationOpen && (
        <Authorization id={chat.connectionId} onClose={() => {
          setAuthorizationOpen(false);
          setError("");
          void cache.invalidateQueries({ queryKey: ["data"] });
        }} />
      )}
      {panel && (
        <aside className="detail-panel">
          <header>
            <h3>{panel === "memory" ? "Что мы помним" : "Настройки чата"}</h3>
            <Button variant="ghost" size="small" onClick={() => setPanel(null)}>
              Закрыть
            </Button>
          </header>
          {panel === "settings" ? (
            <SettingsEditor scope={`conversation:${chat.id}`} compact />
          ) : (
            <>
              <p className="muted">Только этот разговор. Каждый факт можно исправить или забыть.</p>
              {chat.summary && (
                <div className="summary-card">
                  <span className="section-caption">КОНТЕКСТ</span>
                  <p>{chat.summary}</p>
                </div>
              )}
              {facts.data?.map((f) => (
                <div className="fact" key={f.id}>
                  <small>
                    {f.subject === "contact" ? "Собеседник" : "О вас"}
                    {f.pinned ? " · закреплено" : ""}
                  </small>
                  <textarea
                    aria-label="Содержание факта"
                    defaultValue={f.content}
                    onBlur={(e) => {
                      if (e.target.value !== f.content)
                        void act(`/conversations/${chat.id}/memory/${f.id}`, "PUT", {
                          content: e.target.value,
                          pinned: f.pinned,
                          version: chat.version,
                        });
                    }}
                  />
                  {f.conflict && <p className="warning">Есть противоречие: {f.conflict}</p>}
                  <small>{f.sources.length} источников</small>
                  <div>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Закрепить факт"
                      onClick={() =>
                        act(`/conversations/${chat.id}/memory/${f.id}`, "PUT", {
                          content: f.content,
                          pinned: !f.pinned,
                          version: chat.version,
                        })
                      }
                    >
                      <Pin size={15} />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Забыть факт"
                      onClick={() =>
                        act(`/conversations/${chat.id}/memory/${f.id}?version=${chat.version}`, "DELETE")
                      }
                    >
                      <Trash2 size={15} />
                    </Button>
                  </div>
                </div>
              ))}
              {facts.data?.length === 0 && (
                <div className="empty small">
                  <Brain size={25} />
                  <p>Память появится по мере разговора.</p>
                </div>
              )}
              <Button
                variant="secondary"
                size="small"
                disabled={busy}
                onClick={() => act(`/conversations/${chat.id}/memory/refresh`)}
              >
                Обновить память
              </Button>
            </>
          )}
        </aside>
      )}
    </>
  );
}
