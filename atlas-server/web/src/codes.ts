import { api } from "./api";
import type { Code, Codes } from "./api";

// The codes the pages offer and accept come from the server (`/api/codes`), in the order the index writer declares them.

let loading: Promise<Codes> | null = null;

/** The code lists, fetched on the first call; every later call gets the same answer. */
export function loadCodes(): Promise<Codes> {
  loading ??= api<Codes>("api/codes");
  return loading;
}

/** The lists a page shows when the codes could not be read: every picker stays empty. */
export const NO_CODES: Codes = {
  roles: [], statuses: [], entityKinds: [], unitKinds: [], origins: [], statementKinds: [], valueKinds: [], groupDimensions: []
};

/** The codes of [list], reserved ones included, in its order. */
export const codesOf = (list: Code[]) => list.map(c => c.code);

/** The entity kinds the search page offers: those the index produces, not the reserved ones. */
export const searchKinds = (codes: Codes) => codes.entityKinds.filter(c => !c.reserved).map(c => c.code);

/** The roles "who uses" lists by default: every role but where the entity is defined or declared. */
export const usageRoles = (codes: Codes) => codesOf(codes.roles).filter(r => r !== "DEFINITION" && r !== "DECLARATION");
