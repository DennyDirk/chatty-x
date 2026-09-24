import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError } from "../lib/api";
import { Authorization } from "./Pages";

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

function open(step: string, fields: Record<string, string> = {}) {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  request.mockResolvedValue({ step, fields });
  render(
    <QueryClientProvider client={cache}>
      <Authorization id="test" onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

it("clears the previous code when Telegram requests a password and submits only that password", async () => {
  open("CODE");
  fireEvent.change(await screen.findByLabelText("Код из Telegram"), {
    target: { value: "12345" },
  });
  act(() =>
    cache.setQueryData(["data", "auth", "test"], {
      step: "PASSWORD",
      fields: {},
    }),
  );
  const password = await screen.findByLabelText("Пароль двухэтапной проверки");
  expect(password).toHaveValue("");
  fireEvent.change(password, { target: { value: "synthetic-password" } });
  request.mockResolvedValue({ step: "PASSWORD", fields: {} });
  fireEvent.click(screen.getByRole("button", { name: "Продолжить" }));
  await waitFor(() =>
    expect(request).toHaveBeenCalledWith("/connections/test/authorization", "POST", {
      step: "PASSWORD",
      value: "synthetic-password",
    }),
  );
  await waitFor(() => expect(password).toHaveValue(""));
});

it("shows asynchronous initialization failures and allows restarting authorization", async () => {
  open("ERROR", { code: "TELEGRAM_AUTH_REQUEST_FAILED" });
  expect(await screen.findByRole("alert")).toHaveTextContent("Telegram не подтвердил запрос");
  expect(screen.getByRole("button", { name: "По номеру" })).toBeEnabled();
});

it("refreshes a stale challenge without resending the code", async () => {
  open("CODE");
  fireEvent.change(await screen.findByLabelText("Код из Telegram"), {
    target: { value: "12345" },
  });
  request.mockImplementation(async (_path, method) => {
    if (method === "POST") throw new ApiError("TELEGRAM_AUTH_STEP_CHANGED", 409);
    return { step: "PASSWORD", fields: {} };
  });
  fireEvent.click(screen.getByRole("button", { name: "Продолжить" }));
  expect(await screen.findByLabelText("Пароль двухэтапной проверки")).toHaveValue("");
  expect(request.mock.calls.filter((call) => call[1] === "POST")).toHaveLength(1);
});

it("replaces expired QR links and disables method changes while closing", async () => {
  open("QR", { link: "tg://login?token=synthetic-one" });
  const link = await screen.findByRole("link", {
    name: /Открыть подтверждение/,
  });
  act(() =>
    cache.setQueryData(["data", "auth", "test"], {
      step: "QR",
      fields: { link: "tg://login?token=synthetic-two" },
    }),
  );
  await waitFor(() => expect(link).toHaveAttribute("href", "tg://login?token=synthetic-two"));
  act(() =>
    cache.setQueryData(["data", "auth", "test"], {
      step: "CLOSING",
      fields: {},
    }),
  );
  await waitFor(() => expect(screen.queryByRole("link")).not.toBeInTheDocument());
  expect(screen.getByRole("button", { name: "По номеру" })).toBeDisabled();
});
