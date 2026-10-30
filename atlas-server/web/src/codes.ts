import type {Code, Codes} from "./api";
import {api} from "./api";

// The codes the pages offer and accept come from the server (`/api/codes`), in the order the index writer declares them.

let loading: Promise<Codes> | null = null;

/** The code lists, fetched on the first call; every later call gets the same answer. */
export function loadCodes(): Promise<Codes> {
  loading ??= api<Codes>("api/codes");
  return loading;
}

/** The lists a page shows when the codes could not be read: every picker stays empty. */
export const NO_CODES: Codes = {
  roles: [], statuses: [], entityKinds: [], unitKinds: [], origins: [], statementKinds: [], valueKinds: [], groupDimensions: [],
  reasons: [], parseStatuses: []
};

/** The codes of [list], reserved ones included, in its order. */
export const codesOf = (list: Code[]) => list.map(c => c.code);

/** The entity kinds the search page offers: those the index produces, not the reserved ones. */
export const searchKinds = (codes: Codes) => codes.entityKinds.filter(c => !c.reserved).map(c => c.code);

/** The codes a picker offers: those the index produces, not the reserved ones. */
export const offeredCodes = (list: Code[]) => list.filter(c => !c.reserved).map(c => c.code);

/**
 * The options of a picker: the [offered] codes, then every [chosen] one it does not offer (a reserved or unknown code
 * from the URL), so the form still shows and runs the whole query.
 */
export const pickerCodes = (offered: string[], chosen: string[]) => [...offered, ...chosen.filter(c => !offered.includes(c))];

/** The roles "who uses" lists by default: every role but where the entity is defined or declared. */
export const usageRoles = (codes: Codes) => codesOf(codes.roles).filter(r => r !== "DEFINITION" && r !== "DECLARATION");
