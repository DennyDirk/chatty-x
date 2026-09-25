import type { Conversation } from "../lib/types";
import { Button } from "./ui/button";

export function HistoryImportNotice({
  chat,
  busy,
  onStart,
  onCancel,
}: {
  chat: Pick<
    Conversation,
    "selected" | "imported" | "importCount" | "importRunId" | "importCancelled" | "status" | "needsAttention"
  >;
  busy: boolean;
  onStart: () => void;
  onCancel: () => void;
}) {
  if (!chat.selected) return null;
  if (chat.importRunId || chat.status === "IMPORTING")
    return (
      <div className="notice" role="status">
        <span>Загружаем историю: {chat.importCount} из максимум 1000. Автоответы на паузе.</span>
        <Button size="small" variant="ghost" disabled={busy} onClick={onCancel}>
          Отменить загрузку
        </Button>
      </div>
    );
  const explanation = chat.imported
    ? `История загружена: ${chat.importCount} сообщений. Повторная загрузка поставит автоответы на паузу.`
    : chat.needsAttention === "IMPORT_INTERRUPTED"
      ? "Загрузка прервалась при перезапуске. Сохранённые сообщения доступны — запустите её снова."
      : chat.needsAttention === "IMPORT_FAILED"
        ? "Не удалось загрузить всю историю. Проверьте подключение аккаунта и повторите загрузку."
        : chat.importCancelled
          ? "Загрузка отменена. Сохранённые сообщения доступны; для автоответов сначала завершите импорт."
          : "История ещё не загружена. Автоответы будут доступны после завершения импорта.";
  return (
    <div className="notice" role="status">
      <span>{explanation}</span>
      <Button size="small" variant="ghost" disabled={busy} onClick={onStart}>
        {chat.imported ? "Загрузить заново" : "Повторить загрузку"}
      </Button>
    </div>
  );
}
