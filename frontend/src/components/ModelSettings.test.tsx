import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../lib/api";
import { SettingsEditor } from "./SettingsEditor";

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
function mount(scope = "global") {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  request.mockResolvedValue({
    version: 3,
    body: { modelProvider: "openai", replyModel: "gpt-5.4", enabled: false },
  });
  render(
    <QueryClientProvider client={cache}>
      <SettingsEditor scope={scope} />
    </QueryClientProvider>,
  );
}
it("saves the local provider without enabling automation or changing the cloud model", async () => {
  mount();
  fireEvent.click(await screen.findByRole("button", { name: "Автоматизация" }));
  fireEvent.change(screen.getByLabelText("Провайдер ответов и памяти"), { target: { value: "ollama" } });
  expect(screen.getByText(/Фото и голосовые потребуют/)).toBeVisible();
  expect(screen.queryByLabelText("Модель ответов")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Сохранить настройки" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/settings?scope=global", "PUT", {
      version: 3,
      body: { modelProvider: "ollama", replyModel: "gpt-5.4", enabled: false },
    }),
  );
});
it("keeps provider selection global", async () => {
  mount("conversation:test");
  fireEvent.click(await screen.findByRole("button", { name: "Автоматизация" }));
  expect(screen.queryByLabelText("Провайдер ответов и памяти")).not.toBeInTheDocument();
});
