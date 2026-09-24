import { useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { MessageCircle, ArrowRight, ShieldCheck } from "lucide-react";
import { api, errorText } from "../lib/api";
import { Button } from "./ui/button";
export function Auth({ setup, onDone }: { setup: boolean; onDone: () => void }) {
  const [bootstrap, setBootstrap] = useState(""),
    [secret, setSecret] = useState(""),
    [username, setUsername] = useState("owner"),
    [password, setPassword] = useState(""),
    [otp, setOtp] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (setup && !secret) {
        const response = await api<{ secret: string }>("/identity/enroll", "POST", {
          bootstrapToken: bootstrap,
        });
        setSecret(response.secret);
      } else {
        await api(setup ? "/identity/setup" : "/identity/login", "POST", {
          bootstrapToken: bootstrap,
          username,
          password,
          otp,
        });
        setPassword("");
        setSecret("");
        onDone();
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-layout">
      <section className="auth-story">
        <div className="brand">
          <span className="brand-mark">
            <MessageCircle size={24} />
          </span>
          chatty<span className="brand-x">x</span>
        </div>
        <div>
          <span className="eyebrow">БЛИЖЕ К ЖИВОМУ ОБЩЕНИЮ</span>
          <h1>
            Разговор продолжается.
            <br />
            <em>Вы управляете.</em>
          </h1>
          <p>
            Ваш характер, общий контекст и внимание к деталям. Одно спокойное пространство для всех диалогов.
          </p>
          <div className="sample-bubble">Я сегодня наконец закрыл тот проект</div>
          <div className="sample-bubble outgoing">О, наконец-то 😄 Как ощущения?</div>
        </div>
        <small>
          <ShieldCheck size={16} /> Личное пространство · только выбранные чаты
        </small>
      </section>
      <section className="auth-form">
        <form onSubmit={submit}>
          <span className="eyebrow">CHATTY-X</span>
          <h2>{setup ? "Добро пожаловать" : "С возвращением"}</h2>
          <p className="muted">
            {setup
              ? "Создадим защищённый доступ к вашему пространству."
              : "Войдите, чтобы увидеть, как идут разговоры."}
          </p>
          {setup && (
            <label>
              Ключ первоначальной настройки
              <input
                type="password"
                autoComplete="off"
                value={bootstrap}
                onChange={(e) => setBootstrap(e.target.value)}
                required
              />
            </label>
          )}
          {(!setup || secret) && (
            <>
              <label>
                Имя владельца
                <input
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                />
              </label>
              <label>
                Пароль{setup && <small>Не менее 12 символов</small>}
                <input
                  type="password"
                  minLength={setup ? 12 : undefined}
                  autoComplete={setup ? "new-password" : "current-password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </label>
              {secret && (
                <div className="notice">
                  <strong>Добавьте ключ в приложение-аутентификатор</strong>
                  <QRCodeSVG
                    value={`otpauth://totp/Chatty-X:owner?secret=${secret}&issuer=Chatty-X`}
                    size={176}
                    title="Добавить Chatty-X в аутентификатор"
                  />
                  <code className="totp-secret">{secret}</code>
                  <small>
                    Google Authenticator, 1Password или другое приложение TOTP. Ключ показывается только во
                    время настройки.
                  </small>
                </div>
              )}
              <label>
                Код из приложения
                <input
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  pattern="[0-9]{6}"
                  value={otp}
                  onChange={(e) => setOtp(e.target.value)}
                  required
                />
              </label>
            </>
          )}
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
          <Button disabled={busy} type="submit">
            {busy
              ? "Подождите…"
              : setup && !secret
                ? "Настроить защиту"
                : setup
                  ? "Создать пространство"
                  : "Войти"}
            <ArrowRight size={17} />
          </Button>
          <p className="fine-print">Доступ к Chatty-X и подключение Telegram — отдельные шаги.</p>
        </form>
      </section>
    </main>
  );
}
