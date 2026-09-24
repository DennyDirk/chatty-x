import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, errorText } from "./api";

afterEach(() => vi.unstubAllGlobals());
describe("API transport", () => {
  it("sends CSRF on mutations and accepts empty successful responses", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    expect(await api("/stop", "POST")).toBeUndefined();
    expect(fetch.mock.calls[1][1].headers["X-CSRF-TOKEN"]).toBe("synthetic-csrf");
    expect(fetch.mock.calls[1][1].credentials).toBe("same-origin");
  });
  it("keeps context conflicts actionable instead of silently retrying", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ code: "CONTEXT_CHANGED" }), { status: 409 }));
    vi.stubGlobal("fetch", fetch);
    await expect(api("/conversations/test")).rejects.toMatchObject({ code: "CONTEXT_CHANGED", status: 409 });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(errorText(new ApiError("CONTEXT_CHANGED", 409))).toContain("Переписка изменилась");
  });
});
