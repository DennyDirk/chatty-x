import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError } from "../lib/api";
import type { Conversation } from "../lib/types";
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
const base: Conversation = {
  id: "chat",
  connectionId: "account",
  connectionStatus: "AUTHORIZING",
  connectionEnabled: false,
  externalId: "1001",
  title: "Тестовый чат",
  selected: false,
  imported: false,
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
function open(chat = base, step = "PASSWORD") {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  request.mockImplementation(async (path) => (path.endsWith("/authorization") ? { step, fields: {} } : []));
  const view = (value: Conversation) => (
    <QueryClientProvider client={cache}>
      <Chat chat={value} onBack={vi.fn()} />
    </QueryClientProvider>
  );
  const result = render(view(chat));
  return { ...result, view };
}

it("continues the existing login from a chat without selecting or creating another account", async () => {
  open();
  expect(screen.getByRole("button", { name: "Продолжить вход" })).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Выбрать чат" }));
  expect(await screen.findByLabelText("Пароль двухэтапной проверки")).toBeVisible();
  expect(request).toHaveBeenCalledWith("/connections/account/authorization");
  expect(request.mock.calls.every((call) => !call[1] || call[1] === "GET")).toBe(true);
});

it("opens reconnection when retrying import on a disconnected account", async () => {
  open({ ...base, selected: true, connectionStatus: "DISCONNECTED" }, "DISCONNECTED");
  fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
  expect(await screen.findByRole("dialog", { name: "Подключение Telegram" })).toBeVisible();
  expect(request.mock.calls.some((call) => call[0].endsWith("/import"))).toBe(false);
});

it("offers login when the connection drops after rendering a ready chat", async () => {
  open({ ...base, connectionStatus: "READY" }, "PHONE");
  request.mockImplementation(async (path, method) => {
    if (method === "PUT") throw new ApiError("TELEGRAM_NOT_CONNECTED", 409);
    return path.endsWith("/authorization") ? { step: "PHONE", fields: {} } : [];
  });
  fireEvent.click(screen.getByRole("button", { name: "Выбрать чат" }));
  expect(await screen.findByLabelText("Номер телефона с кодом страны")).toBeVisible();
  expect(screen.getByRole("alert")).toHaveTextContent("Telegram не подключён");
  expect(request.mock.calls.filter((call) => call[1] === "PUT")).toHaveLength(1);
});

it("lets the owner explicitly retry selection after login without enabling automation", async () => {
  const { rerender, view } = open(base, "READY");
  fireEvent.click(screen.getByRole("button", { name: "Продолжить вход" }));
  fireEvent.click(await screen.findByRole("button", { name: "Готово" }));
  expect(request.mock.calls.every((call) => !call[1])).toBe(true);
  rerender(view({ ...base, connectionStatus: "READY" }));
  fireEvent.click(screen.getByRole("button", { name: "Выбрать чат" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/conversations/chat/selection", "PUT", { selected: true }),
  );
  expect(
    request.mock.calls.some((call) => call[0].includes("/control/resume") || call[0].endsWith("/enabled")),
  ).toBe(false);
});
