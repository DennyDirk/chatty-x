import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Save, RotateCcw } from "lucide-react";
import { api, errorText } from "../lib/api";
import type { Settings, SettingValue } from "../lib/types";
import { Button } from "./ui/button";

export function SettingsEditor({ scope = "global", compact = false }: { scope?: string; compact?: boolean }) {
  const query = useQuery({
    queryKey: ["data", "settings", scope],
    queryFn: () => api<Settings>(`/settings?scope=${encodeURIComponent(scope)}`),
  });
  return query.data ? (
    <Form key={`${scope}:${query.data.version}`} scope={scope} data={query.data} compact={compact} />
  ) : (
    <p className="muted">Загружаем настройки…</p>
  );
}
function Form({ scope, data, compact }: { scope: string; data: Settings; compact: boolean }) {
  const [body, setBody] = useState(data.body),
    [error, setError] = useState(""),
    [saved, setSaved] = useState(false),
    [busy, setBusy] = useState(false),
    [tab, setTab] = useState("style");
  const cache = useQueryClient();
  const inherited = scope !== "global";
  const value = (name: string) => body[name] ?? data.effective?.[name];
  const source = (name: string) =>
    inherited ? (
      <small>
        {Object.hasOwn(body, name)
          ? "Своя настройка"
          : `Из ${data.sources?.[name] === "connection" ? "аккаунта" : "общих настроек"}`}
        {Object.hasOwn(body, name) && (
          <button
            type="button"
            className="inherit-reset"
            onClick={() =>
              setBody((previous) => {
                const next = { ...previous };
                delete next[name];
                return next;
              })
            }
          >
            Наследовать
          </button>
        )}
      </small>
    ) : null;
  const set = (name: string, value: SettingValue) => {
    setBody((v) => ({ ...v, [name]: value }));
    setSaved(false);
  };
  const text = (name: string, label: string, multiline = false) => (
    <label key={name}>
      {label}
      {source(name)}
      {multiline ? (
        <textarea
          rows={3}
          value={String(value(name) ?? "")}
          onChange={(e) => set(name, e.target.value)}
          placeholder={inherited ? "Использовать общие настройки" : ""}
        />
      ) : (
        <input value={String(value(name) ?? "")} onChange={(e) => set(name, e.target.value)} />
      )}
    </label>
  );
  const number = (name: string, label: string, min: number, max: number) => (
    <label key={name}>
      {label}
      {source(name)}
      <input
        type="number"
        min={min}
        max={max}
        value={typeof value(name) === "number" ? Number(value(name)) : ""}
        placeholder={inherited ? "Наследуется" : ""}
        onChange={(e) => set(name, Number(e.target.value))}
      />
    </label>
  );
  const list = (name: string, label: string) => (
    <label key={name}>
      {label}
      {source(name)}
      <textarea
        rows={3}
        value={Array.isArray(value(name)) ? (value(name) as string[]).join("\n") : ""}
        onChange={(e) => set(name, e.target.value.split("\n").filter(Boolean))}
        placeholder="Каждый пример или эмодзи — с новой строки"
      />
    </label>
  );
  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api(`/settings?scope=${encodeURIComponent(scope)}`, "PUT", { version: data.version, body });
      setSaved(true);
      await cache.invalidateQueries({ queryKey: ["data"] });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={save} className={`settings-form ${compact ? "compact" : ""}`}>
      <div className="tabs">
        {[
          ["style", "Общение"],
          ["rules", "Договорённости"],
          ["automation", "Автоматизация"],
        ].map(([id, title]) => (
          <button key={id} type="button" className={tab === id ? "active" : ""} onClick={() => setTab(id)}>
            {title}
          </button>
        ))}
      </div>
      {inherited && (
        <div className="notice">
          Заполненные поля переопределяют общие настройки.
          <Button type="button" variant="ghost" size="small" onClick={() => setBody({})}>
            <RotateCcw size={14} />
            Вернуть наследование
          </Button>
        </div>
      )}
      {tab === "style" && (
        <>
          <div className="section-caption">ВАШ ГОЛОС</div>
          {text("name", "Имя")}
          {text("biography", "Сведения о себе", true)}
          {text("tone", "Манера общения", true)}
          {text("slang", "Сленг")}
          {text("addressing", "Обращения и прозвища")}
          {text("warmth", "Теплота")}
          {text("humor", "Юмор")}
          {text("boldness", "Дерзость")}
          {text("letterCase", "Регистр букв")}
          {text("punctuation", "Пунктуация")}
          {text("followUpQuestions", "Встречные вопросы")}
          {text("boundaries", "Темы и границы", true)}
          {number("maxReplyChars", "Максимальная длина ответа", 20, 4000)}
          {list("emoji", "Эмодзи и смайлы")}
          {list("examples", "Как хочется отвечать")}
          {list("avoidExamples", "Как отвечать не нужно")}
          {text("identityReaction", "Если спрашивают про бота", true)}
          <label className="check">
            <input
              type="checkbox"
              checked={value("profanity") === true}
              onChange={(e) => set("profanity", e.target.checked)}
            />
            Допускать ненормативную лексику
          </label>
        </>
      )}
      {tab === "rules" && (
        <>
          <p className="muted">
            Отдельные границы для решений от вашего имени. По умолчанию окончательное согласие остаётся за
            вами.
          </p>
          {[
            ["meetings", "Встречи"],
            ["promises", "Обещания"],
            ["money", "Деньги"],
            ["personal", "Личные решения"],
          ].map(([key, label]) => {
            const rules = {
              ...((data.effective?.commitments ?? {}) as Record<string, { mode: string; rule: string }>),
              ...((body.commitments ?? {}) as Record<string, { mode: string; rule: string }>),
            };
            const rule = rules[key] ?? { mode: "DRAFT", rule: "" };
            return (
              <section className="rule-card" key={key}>
                <strong>{label}</strong>
                <select
                  aria-label={label}
                  value={rule.mode}
                  onChange={(e) => set("commitments", { ...rules, [key]: { ...rule, mode: e.target.value } })}
                >
                  <option value="DRAFT">Только черновик</option>
                  <option value="DISCUSS">Обсуждать, не обещать</option>
                  <option value="RULED">По моим правилам</option>
                </select>
                {rule.mode === "RULED" && (
                  <textarea
                    aria-label={`Правила: ${label}`}
                    placeholder="Какие именно решения разрешены?"
                    value={rule.rule}
                    onChange={(e) =>
                      set("commitments", { ...rules, [key]: { ...rule, rule: e.target.value } })
                    }
                  />
                )}
              </section>
            );
          })}
        </>
      )}
      {tab === "automation" && (
        <>
          <div className="section-caption">ТЕМП РАЗГОВОРА</div>
          {number("quietSeconds", "Пауза после последней реплики, сек.", 1, 300)}
          {number("maxBurstSeconds", "Накопление серии, сек.", 1, 300)}
          {number("delayMinSeconds", "Минимальная задержка ответа, сек.", 0, 300)}
          {number("delayMaxSeconds", "Максимальная задержка ответа, сек.", 0, 300)}
          {text("timezone", "Часовой пояс")}
          {text("quietHoursStart", "Тихие часы: начало (23:00)")}
          {text("quietHoursEnd", "Тихие часы: конец (08:00)")}
          {!inherited && (
            <>
              <div className="section-caption">МОДЕЛИ И ХРАНЕНИЕ</div>
              <label>
                Провайдер ответов и памяти
                <select
                  value={String(body.modelProvider ?? "openai")}
                  onChange={(e) => set("modelProvider", e.target.value)}
                >
                  <option value="openai">OpenAI — облачный API</option>
                  <option value="ollama">Ollama — на моём компьютере</option>
                </select>
              </label>
              {body.modelProvider === "ollama" && (
                <p className="notice">
                  Qwen3 8B отвечает и обновляет память локально, без API-ключа и оплаты запросов. Ollama
                  должна работать на компьютере сервера. Запросы выполняются по одному. Фото и голосовые
                  потребуют вашего ответа. После сохранения проверьте модель в «Площадке».
                </p>
              )}
              {number("monthlyBudgetUsd", "Месячный бюджет облачного API, USD", 0, 10000)}
              {number("messageRetentionDays", "Хранить сообщения, дней", 1, 3650)}
              {number("fileRetentionDays", "Хранить вложения, дней", 1, 3650)}
              {body.modelProvider !== "ollama" && (
                <label>
                  Модель ответов
                  <select
                    value={String(body.replyModel ?? "gpt-5.4")}
                    onChange={(e) => set("replyModel", e.target.value)}
                  >
                    <option value="gpt-5.4">GPT-5.4</option>
                    <option value="gpt-5.4-mini">GPT-5.4 mini</option>
                  </select>
                </label>
              )}
              <label className="check">
                <input
                  type="checkbox"
                  checked={body.enabled === true}
                  onChange={(e) => set("enabled", e.target.checked)}
                />
                Разрешить автоответы в выбранных чатах
              </label>
            </>
          )}
        </>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {saved && <p role="status">Сохранено</p>}
      <Button type="submit" disabled={busy}>
        <Save size={16} />
        {busy ? "Сохраняем…" : "Сохранить настройки"}
      </Button>
    </form>
  );
}
