import { baseUrl, currentProject, toBase } from "./project";

/** Whether the page signs in: "off" when the server has no sign-in, else whether this browser is signed in. */
export type SignIn = { state: "off" } | { state: "out" } | { state: "in"; name: string };

/** When the page last sent the browser to sign in, so that a sign-in that does not help is not tried again at once. */
const ATTEMPT_KEY = "atlas.signInAttempt";
const ATTEMPT_WINDOW_MS = 5 * 60 * 1000;

let signIn: Promise<SignIn> | null = null;

export function signInState(): Promise<SignIn> {
  signIn ??= fetch(baseUrl("auth/me"), { cache: "no-store" }).then(async response => {
    if (response.status === 404) return { state: "off" } as const;
    if (!response.ok) return { state: "out" } as const;
    const body = await response.json();
    return { state: "in", name: String(body.name) } as const;
  }).catch(() => ({ state: "off" }) as const);
  return signIn;
}

/** Whether a sign-in started at [stored] (epoch ms as text) is recent enough that a new 401 means it did not help. */
export function attemptPending(stored: string | null, now: number): boolean {
  const started = Number(stored);
  return stored !== null && Number.isFinite(started) && now - started >= 0 && now - started < ATTEMPT_WINDOW_MS;
}

/**
 * Where an answer of [status] sends the page: the sign-in, returning to [location]; null to show the error. [base] leads
 * from the page's folder to the base path, where the sign-in is.
 */
export function loginRedirect(status: number, state: SignIn["state"], attempted: boolean, location: { pathname: string; search: string }, base: string): string | null {
  if (status !== 401 || state === "off" || attempted) return null;
  return base + "auth/login?return=" + encodeURIComponent(location.pathname + location.search);
}

export interface SignInContext {
  storage: Pick<Storage, "getItem" | "setItem">;
  location: { pathname: string; search: string; assign(url: string): void };
  state(): Promise<SignIn>;
  now(): number;
  /** From the page's folder to the base path. */
  base(): string;
}

const browserContext: SignInContext = {
  storage: { getItem: key => sessionStorage.getItem(key), setItem: (key, value) => sessionStorage.setItem(key, value) },
  location: { get pathname() { return window.location.pathname; }, get search() { return window.location.search; }, assign: url => window.location.assign(url) },
  state: signInState,
  now: () => Date.now(),
  base: () => toBase(currentProject())
};

/**
 * On an answer of [status] from the API: true when the browser is being sent to sign in. A sign-in sent in the last
 * minutes is not sent again until an answer is accepted, so every 401 after it shows its error instead of looping.
 */
export async function signInFor(status: number, context: SignInContext = browserContext): Promise<boolean> {
  const attempted = attemptPending(context.storage.getItem(ATTEMPT_KEY), context.now());
  const target = loginRedirect(status, (await context.state()).state, attempted, context.location, context.base());
  if (target === null) return false;
  context.storage.setItem(ATTEMPT_KEY, String(context.now()));
  context.location.assign(target);
  return true;
}

export function signInWorked() {
  sessionStorage.removeItem(ATTEMPT_KEY);
}
