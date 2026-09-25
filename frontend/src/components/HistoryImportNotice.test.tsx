import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { HistoryImportNotice } from "./HistoryImportNotice";

afterEach(cleanup);
const base = { selected: true, imported: false, importCount: 12, status: "PAUSED", needsAttention: null };

it("keeps cancellation available after a live message changes the chat status", () => {
  const cancel = vi.fn();
  render(
    <HistoryImportNotice
      chat={{ ...base, importRunId: "running" }}
      busy={false}
      onStart={vi.fn()}
      onCancel={cancel}
    />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("Загружаем историю: 12");
  fireEvent.click(screen.getByRole("button", { name: "Отменить загрузку" }));
  expect(cancel).toHaveBeenCalledOnce();
  expect(screen.queryByRole("button", { name: "Повторить загрузку" })).not.toBeInTheDocument();
});

it.each(["IMPORT_FAILED", "IMPORT_INTERRUPTED"])("offers retry for %s", (reason) => {
  const start = vi.fn();
  render(
    <HistoryImportNotice
      chat={{ ...base, needsAttention: reason }}
      busy={false}
      onStart={start}
      onCancel={vi.fn()}
    />,
  );
  expect(screen.getByRole("status")).not.toHaveTextContent(reason);
  fireEvent.click(screen.getByRole("button", { name: "Повторить загрузку" }));
  expect(start).toHaveBeenCalledOnce();
});

it("explains the pause before importing completed history again", () => {
  render(
    <HistoryImportNotice
      chat={{ ...base, imported: true }}
      busy={true}
      onStart={vi.fn()}
      onCancel={vi.fn()}
    />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("поставит автоответы на паузу");
  expect(screen.getByRole("button", { name: "Загрузить заново" })).toBeDisabled();
});
