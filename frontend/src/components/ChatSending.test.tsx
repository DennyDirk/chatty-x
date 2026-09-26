import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError } from "../lib/api";
import type { Conversation, RuntimeStatus } from "../lib/types";
import { Chat } from "./Conversations";

vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api: vi.fn(),
}));
const request = vi.mocked(api);
let cache: QueryClient;
afterEach(() => {
  cleanup();
  cache?.clear();
  vi.resetAllMocks();
});
const chat: Conversation = {
  id: "chat",
  connectionId: "account",
  connectionStatus: "READY",
  connectionEnabled: false,
  externalId: "1001",
  title: "Тестовый чат",
  selected: true,
  imported: true,
  importCount: 0,
  mode: "PAUSED",
  status: "PAUSED",
  version: 0,
  needsAttention: null,
  summary: "",
  accountName: "Тестовый аккаунт",
  adapter: "telegram",
  preview: null,
};
const runtime: RuntimeStatus = {
  workersEnabled: true,
  automationEnabled: false,
  modelConfigured: false,
  demo: false,
};
function mount(state = runtime, queued = false) {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  request.mockImplementation(async (path) =>
    path === "/runtime"
      ? state
      : path.endsWith("/outbound") && queued
        ? [
            {
              id: "pending",
              body: "Старый ответ",
              source: "WEB_OWNER",
              status: "READY",
              reason: "",
              contextVersion: 0,
            },
          ]
        : [],
  );
  render(
    <QueryClientProvider client={cache}>
      <Chat chat={chat} onBack={vi.fn()} />
    </QueryClientProvider>,
  );
}
it("explains stopped workers, blocks sending, and allows cancelling the existing queue", async () => {
  mount({ ...runtime, workersEnabled: false }, true);
  expect(await screen.findByText("Отправка отключена на сервере")).toBeVisible();
  expect(await screen.findByText("В очереди на отправку")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Ваш ответ"), { target: { value: "Новый ответ" } });
  const send = screen.getByRole("button", { name: "Отправить сообщение" });
  expect(send).toBeDisabled();
  fireEvent.click(send);
  expect(request.mock.calls.some((call) => call[1] === "POST")).toBe(false);
  fireEvent.click(screen.getByRole("button", { name: "Отменить" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/conversations/chat/drafts/pending", "DELETE", undefined),
  );
  expect(screen.getByLabelText("Ваш ответ")).toHaveValue("Новый ответ");
});
it("allows a manual reply despite global and account pauses and a missing model key", async () => {
  mount();
  expect(await screen.findByText("Автоответы выключены в общих настройках.")).toBeVisible();
  expect(screen.getByText("Автоматизация аккаунта выключена.")).toBeVisible();
  expect(screen.getByText("Не настроен ключ модели для автоответов.")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Ваш ответ"), { target: { value: "Отвечу сам" } });
  const send = screen.getByRole("button", { name: "Отправить сообщение" });
  expect(send).toBeEnabled();
  fireEvent.click(send);
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/conversations/chat/messages", "POST", {
      text: "Отвечу сам",
      requestKey: expect.any(String),
    }),
  );
});
it("preserves the typed reply if processing stops after the screen was loaded", async () => {
  mount();
  await screen.findByText("Ручной ответ доступен независимо от этих настроек.");
  request.mockImplementation(async (path, method) => {
    if (method === "POST") throw new ApiError("PROCESSING_DISABLED", 409);
    return path === "/runtime" ? { ...runtime, workersEnabled: false } : [];
  });
  fireEvent.change(screen.getByLabelText("Ваш ответ"), { target: { value: "Не потерять" } });
  fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Сообщение не добавлено в очередь");
  expect(screen.getByLabelText("Ваш ответ")).toHaveValue("Не потерять");
  await waitFor(() => expect(screen.getByRole("button", { name: "Отправить сообщение" })).toBeDisabled());
});
it("keeps sending unavailable when runtime status cannot be loaded", async () => {
  mount();
  await screen.findByText("Ручной ответ доступен независимо от этих настроек.");
  request.mockRejectedValue(new ApiError("NETWORK_ERROR", 503));
  await cache.invalidateQueries({ queryKey: ["data", "runtime"] });
  expect(await screen.findByText("Не удалось проверить доступность отправки.")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Ваш ответ"), { target: { value: "Черновик" } });
  expect(screen.getByRole("button", { name: "Отправить сообщение" })).toBeDisabled();
});
