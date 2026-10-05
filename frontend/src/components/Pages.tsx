import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Send, ShieldCheck, Sparkles, KeyRound } from "lucide-react";
import { api, errorText } from "../lib/api";
import { type Connection, type Usage, statuses } from "../lib/types";
import { SettingsEditor } from "./SettingsEditor";
import { Button } from "./ui/button";
import { Authorization } from "./Authorization";

export function Accounts() {
  const cache = useQueryClient();
  const query = useQuery({
    queryKey: ["data", "connections"],
    queryFn: () => api<Connection[]>("/connections"),
  });
  const adapters = useQuery({
    queryKey: ["data", "adapters"],
    queryFn: () => api<{ name: string }[]>("/adapters"),
  });
  const [name, setName] = useState("Мой Telegram"),
    [adapter, setAdapter] = useState("telegram"),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [auth, setAuth] = useState<string | null>(null),
    [override, setOverride] = useState<string | null>(null);
  async function action(path: string, method = "POST", body?: unknown) {
    setBusy(true);
    setError("");
    try {
      const result = await api<{ id: string }>(path, method, body);
      await cache.invalidateQueries({ queryKey: ["data"] });
      return result;
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="page">
      <header className="page-heading">
        <span className="eyebrow">ПОДКЛЮЧЕНИЯ</span>
        <h1>Ваши аккаунты</h1>
        <p>Общение от вашего имени. Вы выбираете, кому и когда отвечать.</p>
      </header>
      <div className="cards">
        {query.data?.map((c) => (
          <article className="account-card" key={c.id}>
            <div className="account-icon">
              <Send size={25} />
            </div>
            <span className={`badge ${c.status === "READY" ? "green" : ""}`}>
              {statuses[c.status] ?? c.status}
            </span>
            <h2>{c.name}</h2>
            <p className="muted">
              {c.adapter === "fake" ? "Тестовый адаптер · без внешних сообщений" : "Личный аккаунт Telegram"}
            </p>
            <label className="check">
              <input
                type="checkbox"
                checked={c.enabled}
                onChange={(e) =>
                  void action(`/connections/${c.id}/enabled`, "PUT", {
                    enabled: e.target.checked,
                  })
                }
              />
              Разрешить автоматизацию аккаунта
            </label>
            <div className="button-row">
              <Button variant="secondary" size="small" onClick={() => setAuth(c.id)}>
                Подключение
              </Button>
              <Button
                variant="ghost"
                size="small"
                onClick={() => void action(`/connections/${c.id}/refresh`)}
              >
                Обновить чаты
              </Button>
              <Button
                variant="ghost"
                size="small"
                onClick={() => setOverride(override === c.id ? null : c.id)}
              >
                Настройки
              </Button>
            </div>
            <Button
              variant="ghost"
              size="small"
              onClick={() => void action(`/connections/${c.id}/disconnect?revoke=true`)}
            >
              Отозвать сессию
            </Button>
          </article>
        ))}
        {(query.data?.length ?? 0) < 3 && (
          <form
            className="account-card new-account"
            onSubmit={(e) => {
              e.preventDefault();
              void action("/connections", "POST", { name, adapter }).then((r) => {
                if (r) setAuth(r.id);
              });
            }}
          >
            <Plus size={25} />
            <h2>Добавить аккаунт</h2>
            <label>
              Название
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} required />
            </label>
            <label>
              Подключение
              <select value={adapter} onChange={(e) => setAdapter(e.target.value)}>
                {adapters.data?.map((a) => (
                  <option key={a.name} value={a.name}>
                    {a.name === "fake" ? "Тестовый адаптер" : "Telegram · TDLib"}
                  </option>
                ))}
              </select>
            </label>
            <Button disabled={busy}>Добавить</Button>
          </form>
        )}
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {auth && <Authorization id={auth} onClose={() => setAuth(null)} />}{" "}
      {override && (
        <section className="surface">
          <h2>Настройки аккаунта</h2>
          <SettingsEditor scope={`connection:${override}`} />
        </section>
      )}
      <div className="notice">
        <ShieldCheck size={20} />
        <p>
          Подключение не включает автоответы. Сначала выберите чаты, дождитесь загрузки истории и проверьте
          характер общения.
        </p>
      </div>
    </section>
  );
}
export function Profile() {
  const [key, setKey] = useState(""),
    [notice, setNotice] = useState("");
  const credentials = useQuery({
    queryKey: ["data", "credentials"],
    queryFn: () => api<{ openaiConfigured: boolean }>("/credentials"),
  });
  const cache = useQueryClient();
  return (
    <section className="page narrow">
      <header className="page-heading">
        <span className="eyebrow">НАСТРОЙКИ</span>
        <h1>Характер имеет значение.</h1>
        <p>Задайте свой голос. Частные настройки аккаунтов и чатов могут его уточнять.</p>
      </header>
      <section className="surface">
        <SettingsEditor />
      </section>
      <section className="surface">
        <h2>
          <KeyRound size={21} />
          Подключение модели
        </h2>
        <p className="muted">
          {credentials.data?.openaiConfigured
            ? "Ключ сохранён в зашифрованном виде."
            : "Для OpenAI нужен API-ключ. При выборе Ollama ключ не требуется."}
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void api("/credentials/openai", "PUT", { value: key })
              .then(() => {
                setKey("");
                setNotice("Ключ сохранён");
                return cache.invalidateQueries({
                  queryKey: ["data", "credentials"],
                });
              })
              .catch((e) => setNotice(errorText(e)));
          }}
        >
          <label>
            API-ключ
            <input
              type="password"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              autoComplete="off"
              required
            />
          </label>
          <Button>Сохранить ключ</Button>
          {notice && <p role="status">{notice}</p>}
        </form>
      </section>
    </section>
  );
}
export function Playground() {
  const [text, setText] = useState("Привет!\nЯ сегодня наконец закрыл тот проект"),
    [result, setResult] = useState<{
      action: string;
      text: string;
      reason: string;
    } | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <section className="page narrow">
      <header className="page-heading">
        <span className="eyebrow">БЕЗ РЕАЛЬНЫХ ОТПРАВОК</span>
        <h1>Попробуйте разговор.</h1>
        <p>Проверьте тон и характер до включения автоматизации. Настоящая память не меняется.</p>
      </header>
      <div className="playground-grid">
        <form
          className="surface"
          onSubmit={(e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            void api<{ action: string; text: string; reason: string }>("/playground", "POST", { text })
              .then(setResult)
              .catch((e) => setError(errorText(e)))
              .finally(() => setBusy(false));
          }}
        >
          <label>
            Реплики собеседника
            <textarea rows={9} value={text} onChange={(e) => setText(e.target.value)} maxLength={12000} />
          </label>
          <Button disabled={busy || !text.trim()}>
            <Sparkles size={16} />
            {busy ? "Готовим ответ…" : "Попробовать ответ"}
          </Button>
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
        </form>
        <div className="surface playground-answer">
          <span className="eyebrow">КАК ЭТО ПРОЗВУЧИТ</span>
          {result ? (
            <>
              <span className="badge">
                {result.action === "handoff"
                  ? "Черновик для владельца"
                  : result.action === "skip"
                    ? "Ответ не нужен"
                    : result.reason === "DEMO_MODEL"
                      ? "Тестовый ответ · без AI"
                      : "Ответ"}
              </span>
              <p className="reply-preview">{result.text || "Без сообщения"}</p>
            </>
          ) : (
            <div className="empty small">
              <Sparkles size={28} />
              <p>
                Здесь появится ответ
                <br />с вашим характером.
              </p>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
export function Overview() {
  const usage = useQuery({
    queryKey: ["data", "usage"],
    queryFn: () => api<Usage>("/usage"),
  });
  const diagnostics = useQuery({
    queryKey: ["data", "diagnostics"],
    queryFn: () =>
      api<{
        uncertainDelivery: { id: string; status: string; reason: string }[];
        jobs: unknown[];
      }>("/diagnostics"),
  });
  return (
    <section className="page">
      <header className="page-heading">
        <span className="eyebrow">ВСЁ ПОД КОНТРОЛЕМ</span>
        <h1>Состояние пространства</h1>
        <p>Расходы, обработка и вопросы, требующие внимания.</p>
      </header>
      <div className="metrics">
        <article className="surface">
          <small>Расходы за месяц</small>
          <h2>
            ${usage.data?.usedUsd.toFixed(3) ?? "0.000"}{" "}
            <span className="muted">/ ${usage.data?.limitUsd ?? 30}</span>
          </h2>
          <progress max={100} value={usage.data?.percent ?? 0} />
          <p className="muted">Включая зарезервированные и неподтверждённые расходы.</p>
        </article>
        <article className="surface">
          <small>В очереди</small>
          <h2>{diagnostics.data?.jobs.length ?? 0}</h2>
          <p className="muted">Серии сообщений, ожидающие обработки.</p>
        </article>
        <article className="surface">
          <small>Проверка доставки</small>
          <h2>{diagnostics.data?.uncertainDelivery.length ?? 0}</h2>
          <p className="muted">Неоднозначные отправки не повторяются автоматически.</p>
        </article>
      </div>
      <section className="surface">
        <h2>Последние обращения к моделям</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Время</th>
                <th>Модель</th>
                <th>Задача</th>
                <th>Статус</th>
                <th>USD</th>
              </tr>
            </thead>
            <tbody>
              {usage.data?.entries.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.createdAt).toLocaleString("ru")}</td>
                  <td>{e.model}</td>
                  <td>{e.kind}</td>
                  <td>{e.state}</td>
                  <td>{Number(e.actualUsd ?? e.reservedUsd).toFixed(4)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {usage.data?.entries.length === 0 && <p className="muted">Пока без расходов.</p>}
        </div>
      </section>
      <a className="button button-secondary" href="/api/v1/export" download>
        Экспортировать данные
      </a>
    </section>
  );
}
