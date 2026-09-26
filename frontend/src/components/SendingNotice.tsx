import type { Conversation, RuntimeStatus } from "../lib/types";

export function SendingNotice({ chat, runtime }: { chat: Conversation; runtime: RuntimeStatus }) {
  if (!runtime.workersEnabled)
    return (
      <div className="notice warning" role="status">
        <div>
          <strong>Отправка отключена на сервере</strong>
          <p>История доступна, но ручные сообщения и автоответы сейчас не отправляются.</p>
          <p>Перед включением проверьте сообщения «В очереди на отправку»: ненужные можно отменить.</p>
          <p>Для запуска измените настройку обработки на сервере по инструкции запуска.</p>
        </div>
      </div>
    );
  const reasons: { text: string; href?: string; link?: string }[] = [];
  if (!runtime.automationEnabled)
    reasons.push({
      text: "Автоответы выключены в общих настройках.",
      href: "/profile",
      link: "Характер и настройки",
    });
  if (!chat.connectionEnabled)
    reasons.push({ text: "Автоматизация аккаунта выключена.", href: "/accounts", link: "Аккаунты" });
  if (!runtime.demo && !runtime.modelConfigured)
    reasons.push({
      text: "Не настроен ключ модели для автоответов.",
      href: "/profile",
      link: "Настроить модель",
    });
  if (!chat.selected || !chat.imported)
    reasons.push({ text: "Для автоответов выберите чат и дождитесь загрузки истории." });
  else if (chat.mode !== "AUTO")
    reasons.push({ text: "Автоответы в этом чате на паузе. Для включения нажмите «Возобновить»." });
  if (reasons.length === 0) return null;
  return (
    <div className="notice warning" role="status">
      <div>
        {reasons.map((reason) => (
          <p key={reason.text}>
            {reason.text} {reason.href && <a href={reason.href}>{reason.link}</a>}
          </p>
        ))}
        {chat.connectionStatus === "READY" && <p>Ручной ответ доступен независимо от этих настроек.</p>}
      </div>
    </div>
  );
}
