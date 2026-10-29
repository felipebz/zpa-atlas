import { describe, expect, it } from "vitest";
import { fileTitleParts, langQuery, otherProjectHref, projectTitle, projectsHtml, stateMark, toBase } from "../src/project";
import type { ProjectInfo, ProjectTexts } from "../src/project";

// The texts show their key and parameters, so that the HTML tells which message went where.
const texts: ProjectTexts = {
  t: (key, params) => params ? `${key}${JSON.stringify(params)}` : key,
  formatDate: iso => `date(${iso})`
};

const hr: ProjectInfo = { name: "hr", path: "p/hr/", state: "READY", finishedAt: "2026-10-22T14:47:16.372Z", files: 101, error: null };
const web: ProjectInfo = { name: "web", path: "p/web/", state: "NOT_INDEXED", finishedAt: null, files: null, error: null };
const billing: ProjectInfo = { name: "billing", path: "p/billing/", state: "UNAVAILABLE", finishedAt: null, files: null, error: "cannot open <db>" };

describe("toBase", () => {
  it("leads from a project page two folders up to the base path, and stays put at the base path", () => {
    expect(toBase("hr")).toBe("../../");
    expect(toBase(null)).toBe("");
    expect(toBase("hr") + "auth/me").toBe("../../auth/me");
    expect(toBase(null) + "auth/logout").toBe("auth/logout");
  });
});

describe("projectTitle", () => {
  it("puts the project before the page title, and leaves the title alone without one", () => {
    expect(projectTitle("hr", "Atlas · entity")).toBe("hr · Atlas · entity");
    expect(projectTitle(null, "Atlas · entity")).toBe("Atlas · entity");
  });
});

describe("otherProjectHref", () => {
  it("opens the other project's home page, keeping only the language", () => {
    expect(otherProjectHref("web", "")).toBe("../web/");
    expect(otherProjectHref("web", "?key=db:HR.COMPANY&lang=pt")).toBe("../web/?lang=pt");
    expect(otherProjectHref("a b", "")).toBe("../a%20b/");
  });
});

describe("langQuery", () => {
  it("keeps the language alone", () => {
    expect(langQuery("?q=x&lang=pt")).toBe("?lang=pt");
    expect(langQuery("?q=x")).toBe("");
  });
});

describe("stateMark", () => {
  it("marks a project without an index to answer from", () => {
    expect(stateMark(hr, texts)).toBeNull();
    expect(stateMark(web, texts)).toBe("project.notIndexed");
    expect(stateMark(billing, texts)).toBe("project.unavailable");
    expect(stateMark({ state: "OTHER" }, texts)).toBe("OTHER");
  });
});

describe("projectsHtml", () => {
  it("links each project to its home page, with its index date and files when ready", () => {
    expect(projectsHtml([hr], texts, "")).toBe(
      `<ul class="projects"><li><span class="project-head"><a class="project-name" href="p/hr/">hr</a></span>` +
      `<span class="project-date">projects.indexed{&quot;date&quot;:&quot;date(2026-10-22T14:47:16.372Z)&quot;}</span>` +
      `<span class="project-files">home.files{&quot;count&quot;:101}</span></li></ul>`);
  });

  it("shows a project without an index by its state, without a date, and escapes the error", () => {
    const html = projectsHtml([web, billing], texts, "");
    expect(html).toContain(`<a class="project-name" href="p/web/">web</a></span><span class="project-state">projects.notIndexed</span></li>`);
    expect(html).toContain(`<span class="project-state">projects.unavailable{&quot;error&quot;:&quot;cannot open &lt;db&gt;&quot;}</span>`);
    expect(html).not.toContain("project-date");
  });

  it("shows a title as the link, with the name beside it, the default marked and the description below, escaped", () => {
    const core: ProjectInfo = { ...hr, name: "core", path: "p/core/", title: "Core <platform>", description: "The main codebase & its jobs.", default: true };
    const html = projectsHtml([core], texts, "");
    expect(html).toContain(`<span class="project-head"><a class="project-name" href="p/core/">Core &lt;platform&gt;</a>` +
      `<span class="project-id">core</span><span class="project-default">projects.default</span></span>`);
    expect(html).toContain(`<p class="project-description">The main codebase &amp; its jobs.</p></li>`);
    expect(projectsHtml([hr], texts, "")).not.toContain("project-id");
  });

  it("keeps the language in the links", () => {
    expect(projectsHtml([web], texts, "?lang=pt")).toContain(`href="p/web/?lang=pt"`);
  });

  it("says when there is no project", () => {
    expect(projectsHtml([], texts, "")).toBe(`<p class="muted">projects.empty</p>`);
  });
});

describe("fileTitleParts", () => {
  it("puts the file name before its folder", () => {
    expect(fileTitleParts("hr/pack_process.pkb")).toEqual({ name: "pack_process.pkb", folder: "hr" });
    expect(fileTitleParts("src/hr/sub/pack_process.pkb")).toEqual({ name: "pack_process.pkb", folder: "src/hr/sub" });
  });

  it("a file at the root has no folder", () => {
    expect(fileTitleParts("install.sql")).toEqual({ name: "install.sql", folder: "" });
  });
});
