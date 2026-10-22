import { describe, expect, it } from "vitest";
import { attemptPending, loginRedirect, signInFor } from "../src/session";
import type { SignIn, SignInContext } from "../src/session";

const here = { pathname: "/entity.html", search: "?key=db:HR.COMPANY&lang=pt" };

describe("loginRedirect", () => {
  it("sends a 401 to the sign-in, returning to the page and its query", () => {
    expect(loginRedirect(401, "out", false, here, "")).toBe("auth/login?return=%2Fentity.html%3Fkey%3Ddb%3AHR.COMPANY%26lang%3Dpt");
    expect(loginRedirect(401, "in", false, { pathname: "/", search: "" }, "")).toBe("auth/login?return=%2F");
  });

  it("from a project page, sends to the sign-in at the base path, returning to the project page", () => {
    const page = { pathname: "/atlas/p/hr/entity.html", search: "?key=x" };
    expect(loginRedirect(401, "out", false, page, "../../")).toBe("../../auth/login?return=%2Fatlas%2Fp%2Fhr%2Fentity.html%3Fkey%3Dx");
  });

  it("does not redirect again right after a sign-in, so a refused sign-in shows the error instead of looping", () => {
    expect(loginRedirect(401, "in", true, here, "")).toBeNull();
    expect(loginRedirect(401, "out", true, here, "")).toBeNull();
  });

  it("does not redirect without the sign-in, or for another status", () => {
    expect(loginRedirect(401, "off", false, here, "")).toBeNull();
    expect(loginRedirect(403, "out", false, here, "")).toBeNull();
    expect(loginRedirect(503, "in", false, here, "../../")).toBeNull();
  });
});

describe("attemptPending", () => {
  const now = 1_000_000_000;

  it("is true for a sign-in started in the last minutes", () => {
    expect(attemptPending(String(now - 1000), now)).toBe(true);
  });

  it("is false without one, for an old one, or for a value that is not a time", () => {
    expect(attemptPending(null, now)).toBe(false);
    expect(attemptPending(String(now - 10 * 60 * 1000), now)).toBe(false);
    expect(attemptPending(String(now + 60_000), now)).toBe(false);
    expect(attemptPending("x", now)).toBe(false);
  });
});

describe("signInFor", () => {
  function page(state: SignIn["state"], base = "") {
    const stored = new Map<string, string>();
    const sent: string[] = [];
    let now = 1_000_000_000;
    const context: SignInContext = {
      storage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => void stored.set(key, value) },
      location: { pathname: "/entity.html", search: "?key=x", assign: url => void sent.push(url) },
      state: async () => (state === "in" ? { state, name: "ana" } : { state }) as SignIn,
      now: () => now,
      base: () => base
    };
    return { context, sent, later: (ms: number) => { now += ms; } };
  }

  it("sends the first 401 to sign in, and every 401 after the sign-in comes back shows its error", async () => {
    const { context, sent, later } = page("out");
    expect(await signInFor(401, context)).toBe(true);
    expect(sent).toEqual(["auth/login?return=%2Fentity.html%3Fkey%3Dx"]);
    later(5_000);
    // The page loads again after the sign-in, and its requests answer 401 together.
    expect(await signInFor(401, context)).toBe(false);
    expect(await signInFor(401, context)).toBe(false);
    expect(sent).toHaveLength(1);
  });

  it("signs in again once the last attempt is old", async () => {
    const { context, sent, later } = page("in");
    await signInFor(401, context);
    later(6 * 60 * 1000);
    expect(await signInFor(401, context)).toBe(true);
    expect(sent).toHaveLength(2);
  });

  it("sends a project page to the sign-in at the base path", async () => {
    const { context, sent } = page("out", "../../");
    expect(await signInFor(401, context)).toBe(true);
    expect(sent).toEqual(["../../auth/login?return=%2Fentity.html%3Fkey%3Dx"]);
  });

  it("never sends to sign in without the sign-in", async () => {
    const { context, sent } = page("off");
    expect(await signInFor(401, context)).toBe(false);
    expect(sent).toEqual([]);
  });
});
