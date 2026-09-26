import { useEffect, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRight } from "lucide-react";
import { api, ApiError, errorText } from "../lib/api";
import { Button } from "./ui/button";

export function Authorization({ id, onClose }: { id: string; onClose: () => void }) {
  const cache = useQueryClient();
  const state = useQuery({
    queryKey: ["data", "auth", id],
    queryFn: () => api<{ step: string; fields: Record<string, string> }>(`/connections/${id}/authorization`),
    refetchInterval: 1500,
    gcTime: 0,
  });
  const [value, setValue] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const step = state.data?.step ?? "INITIALIZING";
  useEffect(() => {
    setValue("");
    setError("");
  }, [id, step]);
  async function submit(body: unknown) {
    setBusy(true);
    setError("");
    try {
      await api(`/connections/${id}/authorization`, "POST", body);
      setValue("");
      await cache.invalidateQueries({ queryKey: ["data"] });
    } catch (e) {
      setError(errorText(e));
      await state.refetch();
    } finally {
      setValue("");
      setBusy(false);
    }
  }
  const methodBusy = busy || state.isPending || ["INITIALIZING", "CLOSING", "READY"].includes(step);
  const qrLink =
    step === "QR" && state.data?.fields.link?.startsWith("tg://login?token=")
      ? state.data.fields.link
      : undefined;
  const stateError = state.data?.fields.code;
  const visibleError =
    error ||
    (state.isError ? errorText(state.error) : "") ||
    (step === "ERROR" ? errorText(new ApiError(stateError ?? "TELEGRAM_AUTH_REQUEST_FAILED", 502)) : "");
  const labels: Record<string, string> = {
    DISCONNECTED: "Выберите способ входа",
    INITIALIZING: "Подключаемся к Telegram…",
    CLOSING: "Завершаем предыдущий вход…",
    QR_PENDING: "Получаем QR-код…",
    QR: "Откройте Telegram → Настройки → Устройства → Подключить устройство",
    PHONE: "Введите номер телефона",
    CODE: "Введите код подтверждения",
    PASSWORD: "Нужен пароль двухэтапной проверки",
    EMAIL: "Подтвердите email",
    EMAIL_CODE: "Введите код из письма",
    READY: "Аккаунт подключён",
    ERROR: "Не удалось завершить вход",
    UNSUPPORTED_STEP: "Нужно дополнительное действие",
  };
  return (
    <div className="modal-backdrop">
      <section className="modal" role="dialog" aria-modal="true" aria-label="Подключение Telegram">
        <header>
          <h2>Подключить Telegram</h2>
          <Button variant="ghost" onClick={onClose}>
            Закрыть
          </Button>
        </header>
        <p className="muted">
          Подтвердите вход в своём Telegram. Коды и пароль используются только для авторизации.
        </p>
        <div className="button-row">
          <Button variant="secondary" disabled={methodBusy} onClick={() => void submit({ method: "qr" })}>
            Вход по QR
          </Button>
          <Button variant="secondary" disabled={methodBusy} onClick={() => void submit({ method: "phone" })}>
            По номеру
          </Button>
        </div>
        <p className="badge" role="status">
          {labels[step] ?? "Ожидаем Telegram…"}
        </p>
        {qrLink && (
          <div className="qr-code">
            <QRCodeSVG value={qrLink} size={224} title="QR для подключения Telegram" />
          </div>
        )}
        {qrLink && (
          <a className="auth-link" href={qrLink}>
            Открыть подтверждение в Telegram <ArrowUpRight size={15} />
          </a>
        )}
        {["PHONE", "CODE", "PASSWORD", "EMAIL", "EMAIL_CODE"].includes(step) && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void submit({ step, value });
            }}
          >
            <label>
              {
                (
                  {
                    PHONE: "Номер телефона с кодом страны",
                    CODE: "Код из Telegram",
                    PASSWORD: "Пароль двухэтапной проверки",
                    EMAIL: "Email",
                    EMAIL_CODE: "Код из письма",
                  } as Record<string, string>
                )[step]
              }
              <input
                type={step === "PASSWORD" ? "password" : step === "EMAIL" ? "email" : "text"}
                autoComplete="off"
                disabled={busy}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                required
              />
            </label>
            <Button disabled={busy || !value.trim()}>Продолжить</Button>
          </form>
        )}
        {step === "READY" && <Button onClick={onClose}>Готово</Button>}
        {step === "UNSUPPORTED_STEP" && (
          <p className="error">Этот шаг авторизации пока не поддержан интерфейсом.</p>
        )}
        {visibleError && (
          <p className="error" role="alert">
            {visibleError}
          </p>
        )}
      </section>
    </div>
  );
}
