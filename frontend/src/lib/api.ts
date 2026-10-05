export class ApiError extends Error {
  constructor(
    public code: string,
    public status: number,
  ) {
    super(code);
  }
}
let csrf: { token: string; headerName: string } | undefined;
export async function api<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    if (!csrf) {
      const response = await fetch("/api/v1/identity/csrf", {
        credentials: "same-origin",
      });
      if (!response.ok) throw new ApiError("CONNECTION_FAILED", response.status);
      csrf = await response.json();
    }
    headers[csrf!.headerName] = csrf!.token;
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch("/api/v1" + path, {
    method,
    credentials: "same-origin",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ code: "REQUEST_FAILED" }));
    if (response.status === 401 || response.status === 403) csrf = undefined;
    throw new ApiError(error.code ?? "REQUEST_FAILED", response.status);
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
const errors: Record<string, string> = {
  INVALID_CREDENTIALS: "Проверьте имя, пароль и новый код из приложения.",
  INVALID_OTP: "Код не подошёл. Введите текущий шестизначный код.",
  INVALID_BOOTSTRAP_TOKEN: "Неверный ключ первоначальной настройки.",
  BUDGET_EXHAUSTED: "Лимит расходов достигнут. Ручные ответы доступны.",
  MODEL_KEY_REQUIRED: "Добавьте API-ключ в общих настройках.",
  MODEL_BUSY: "Модель занята другим запросом. Попробуйте через несколько секунд.",
  LOCAL_MODEL_UNAVAILABLE: "Нет связи с Ollama. Запустите её на компьютере сервера и проверьте подключение.",
  LOCAL_MODEL_NOT_FOUND:
    "В Ollama не найдена Qwen3 8B. Выполните ollama pull qwen3:8b на компьютере сервера.",
  LOCAL_MODEL_TIMEOUT: "Ollama не успела ответить. Проверьте нагрузку компьютера и повторите в площадке.",
  LOCAL_MEDIA_UNSUPPORTED:
    "Локальный режим пока обрабатывает только текст. Ответьте на фото или голосовое вручную.",
  INVALID_MODEL_REPLY: "Модель вернула неподходящий ответ. Проверьте её в площадке; ручной ответ доступен.",
  MODEL_INCOMPLETE_RESPONSE: "Модель не завершила ответ. Проверьте запрос в площадке.",
  CONTEXT_LIMIT_REQUIRES_OWNER:
    "Разговор или профиль не помещается в контекст модели. Ответьте вручную или сократите настройки профиля.",
  PROCESSING_DISABLED:
    "Отправка отключена на сервере. Сообщение не добавлено в очередь; текст сохранён в поле ответа.",
  TELEGRAM_APP_CREDENTIALS_REQUIRED: "Укажите TELEGRAM_API_ID и TELEGRAM_API_HASH на сервере.",
  TELEGRAM_NOT_CONNECTED:
    "Telegram не подключён. Откройте подключение и завершите вход, затем повторите действие.",
  TELEGRAM_AUTH_IN_PROGRESS: "Предыдущий вход ещё завершается. Дождитесь следующего шага.",
  TELEGRAM_AUTH_STEP_CHANGED: "Этап входа изменился. Введите данные для текущего шага.",
  TELEGRAM_AUTH_REQUEST_FAILED:
    "Telegram не подтвердил запрос. Проверьте соединение и выберите способ входа снова.",
  TELEGRAM_INITIALIZATION_FAILED: "Не удалось подготовить сессию Telegram. Попробуйте подключиться снова.",
  TELEGRAM_REQUEST_REJECTED:
    "Telegram отклонил данные. Проверьте номер, актуальный код или пароль и повторите ввод.",
  INVALID_AUTH_VALUE: "Введите данные для текущего шага входа.",
  TDLIB_NATIVE_UNAVAILABLE: "Нужна production-сборка с TDLib. Тестовый режим не подключает Telegram.",
  CONTEXT_CHANGED: "Переписка изменилась. Обновите данные и проверьте действие.",
  SETTINGS_CHANGED: "Настройки изменились в другой вкладке. Обновите страницу.",
  IMPORT_REQUIRED: "Сначала выберите чат и дождитесь импорта.",
  TRY_LATER: "Слишком много попыток. Попробуйте через пять минут.",
  DELIVERY_STILL_UNKNOWN: "Подтвердить отправку пока не удалось. Автоматического повтора не будет.",
};
export function errorText(error: unknown) {
  return error instanceof ApiError
    ? (errors[error.code] ?? `Не удалось выполнить действие (${error.code}).`)
    : "Нет связи с сервером. Попробуйте ещё раз.";
}
