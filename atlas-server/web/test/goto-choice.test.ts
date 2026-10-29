import {describe, expect, it} from "vitest";
import {enterAction, firstOrObjects} from "../src/goto-choice";

const recent = { text: "", hrefs: ["entity.html?key=db%3AHR.PACK_ORDERS.SHIP", "entity.html?key=db%3AHR.PACK_ORDERS.BILL"], active: 0 };

describe("enterAction", () => {
  it("never opens an entry listed for another text: a paste or a key typed before the list came in waits for its own", () => {
    expect(enterAction("pack_bills.tot", recent)).toEqual({ wait: "pack_bills.tot" });
    const earlier = { text: "pack_orders", hrefs: ["entity.html?key=db%3AHR.PACK_ORDERS", "./?q=pack_orders"], active: 0 };
    expect(enterAction("pack_orders.sh", earlier)).toEqual({ wait: "pack_orders.sh" });
  });

  it("opens the chosen entry of the list made for the field's text, the objects opened last for an empty field", () => {
    expect(enterAction("", { ...recent, active: 1 })).toEqual({ open: recent.hrefs[1] });
    const listed = { text: "pack_orders.sh", hrefs: ["entity.html?key=db%3AHR.PACK_ORDERS.SHIP", "./?q=pack_orders.sh"], active: 1 };
    expect(enterAction("pack_orders.sh", listed)).toEqual({ open: "./?q=pack_orders.sh" });
  });

  it("does nothing with an empty field and no list, and waits when no list was shown yet", () => {
    expect(enterAction("", { text: "", hrefs: [], active: 0 })).toBeNull();
    expect(enterAction("pack", { text: null, hrefs: [], active: 0 })).toEqual({ wait: "pack" });
  });
});

describe("firstOrObjects", () => {
  it("opens the first suggestion, or the Objects page when there is none", () => {
    expect(firstOrObjects(["entity.html?key=a", "entity.html?key=b"], "./?q=x")).toBe("entity.html?key=a");
    expect(firstOrObjects([], "./?q=x")).toBe("./?q=x");
  });
});
