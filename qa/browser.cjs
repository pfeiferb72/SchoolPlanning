// Run against a dedicated local demo database. See docs/TESTS.md.
const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
(async () => {
  let page;
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_BIN || undefined,
    headless: true,
    args: ["--no-sandbox"],
  });
  try {
    const base = process.env.APP_URL || "http://127.0.0.1:8080";
    page = await browser.newPage({ viewport: { width: 1360, height: 900 } });
    page.setDefaultTimeout(10000);
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    const root = "/api/v1/schools/demo";
    async function request(path, method = "GET", data, expected = 200) {
      const res = await page.evaluate(
        async ({ path, method, data }) => {
          const s = await (await fetch("/session")).json();
          const r = await fetch(path, {
            method,
            headers: {
              "Content-Type": "application/json",
              "X-CSRF-TOKEN": s.csrf,
            },
            body: data === undefined ? undefined : JSON.stringify(data),
          });
          let raw = await r.text();
          try {
            raw = JSON.parse(raw);
          } catch {}
          return { code: r.status, body: raw };
        },
        { path, method, data },
      );
      assert.equal(
        res.code,
        expected,
        path + " " + JSON.stringify(res.body).slice(0, 300),
      );
      return res.body;
    }
    async function sign(role) {
      await page.goto(base);
      const logout = page.getByRole("button", { name: "Abmelden" });
      await page.locator(".login-card, aside footer").first().waitFor();
      if (await logout.count()) {
        await logout.click();
      }
      await page.getByLabel("Demozugang").selectOption(role);
      await page.getByRole("button", { name: /Arbeitsplatz öffnen/ }).click();
      await page.getByRole("button", { name: "Abmelden" }).waitFor();
    }
    await sign("PLAN");
    await page.getByRole("heading", { name: "Planungsraster" }).waitFor();
    const list = await request(root + "/scenarios");
    const seed = list.find((s) => s.name === "Wintersemester · Arbeitsplan");
    await page.getByLabel("Szenario", { exact: true }).selectOption(seed.id);
    await page.getByRole("button", { name: "Stammdaten" }).click();
    await page.getByRole("button", { name: "Lehrkräfte", exact: true }).click();
    await page.getByRole("cell", { name: "Anna Weber" }).first().waitFor();
    await page.getByRole("button", { name: "Wochenplan" }).click();
    const { id } = await request(root + "/scenarios", "POST", {
      name: "QA " + Date.now(),
      source: seed.id,
    });
    await page.reload();
    await page.getByLabel("Szenario", { exact: true }).selectOption(id);
    const path = root + "/scenarios/" + id;
    const manual = page.getByRole("button", { name: /M · 1A/ }).first();
    await manual.click();
    await page.getByRole("dialog").waitFor();
    await page
      .getByRole("button", { name: "Schließen", exact: true })
      .last()
      .click();
    const imp = await request(path + "/master/subjects/import", "POST", {
      revision: 0,
      csv: "id;name\nM;Mathematik\nX;Informatik",
      apply: false,
    });
    assert.equal(imp.added, 1);
    assert.equal(imp.errors.length, 0);
    let state = await request(path);
    assert.equal(state.metrics.placed, 5);
    await request(
      path + "/workflow",
      "POST",
      { revision: 0, action: "REVIEW" },
      422,
    );
    await request(
      path + "/placement",
      "POST",
      {
        revision: 0,
        lesson: "M-1A#2",
        place: {
          day: 1,
          slot: 0,
          room: "101",
          teachers: ["WEB"],
          fixed: false,
        },
      },
      422,
    );
    const place = {
      day: 5,
      slot: 1,
      room: "101",
      teachers: ["WEB"],
      fixed: true,
    };
    await request(path + "/placement", "POST", {
      revision: 0,
      lesson: "M-1A#2",
      place,
    });
    await request(
      path + "/placement",
      "POST",
      { revision: 0, lesson: "M-1A#2", place },
      409,
    );
    await request(path + "/history", "POST", { revision: 1, action: "undo" });
    await request(path + "/history", "POST", { revision: 2, action: "redo" });
    const job = await request(path + "/solver", "POST", {
      revision: 3,
      seconds: 2,
      empty: false,
    });
    let status = job;
    while (["LÄUFT", "WARTET"].includes(status.status)) {
      await new Promise((r) => setTimeout(r, 100));
      status = await request(root + "/jobs/" + job.id);
    }
    assert.equal(status.status, "FERTIG");
    state = await request(path);
    assert.deepEqual(state.metrics.outstanding, []);
    assert.deepEqual(state.scenario.plan.places["M-1A#2"], place);
    const cancelJob = await request(path + "/solver", "POST", {
      revision: state.scenario.revision,
      seconds: 30,
      empty: true,
    });
    await request(root + "/jobs/" + cancelJob.id, "DELETE");
    for (let i = 0; i < 120; i++) {
      const detail = await request(root + "/jobs/" + cancelJob.id);
      if (!["LÄUFT", "WARTET"].includes(detail.status)) {
        assert.ok(["FERTIG", "ABGEBROCHEN"].includes(detail.status));
        break;
      }
      await new Promise((r) => setTimeout(r, 100));
    }
    state = await request(path);
    await request(path + "/workflow", "POST", {
      revision: state.scenario.revision,
      action: "REVIEW",
    });
    await request(
      path + "/workflow",
      "POST",
      { revision: state.scenario.revision + 1, action: "APPROVED" },
      403,
    );
    await sign("DIRECTION");
    await request(path + "/workflow", "POST", {
      revision: state.scenario.revision + 1,
      action: "APPROVED",
    });
    await request(path + "/workflow", "POST", {
      revision: state.scenario.revision + 2,
      action: "PUBLISHED",
    });
    await sign("PLAN");
    const query = "?from=2026-09-14&until=2026-09-18";
    const events = await request(root + "/events" + query);
    assert.ok(events.length);
    const event = events.find((e) => e.teachers.includes("WEB"));
    await request(root + "/absences", "POST", {
      teacher: "WEB",
      from: event.date,
      until: event.date,
      reason: "Fortbildung",
    });
    const daily = await request(root + "/covers?day=" + event.date);
    assert.ok(daily.proposal.rows.length);
    await request(root + "/covers", "POST", daily.proposal);
    assert.ok(
      (await request(root + "/events" + query)).some(
        (e) => e.status !== "Unterricht",
      ),
    );
    for (const f of ["csv", "ics", "html", "pdf"]) {
      const r = await page.request.get(base + root + "/exports/" + f + query);
      assert.equal(r.status(), 200);
      assert.ok((await r.body()).length > 75);
    }
    const feed = await request(root + "/feeds", "POST", {
      scope: "group",
      entity: "1A",
    });
    assert.match(feed.calendar, /\.ics$/);
    const spec = await request("/v3/api-docs");
    assert.ok(Object.keys(spec.paths).length > 25);
    if (process.env.OPENAPI_OUTPUT)
      await fs.writeFile(
        process.env.OPENAPI_OUTPUT,
        JSON.stringify(spec, null, 2) + "\n",
      );
    await sign("ADMIN");
    const archive = await request(root + "/offboarding");
    assert.ok(archive.revision.length);
    assert.ok(archive.publication.length);
    await sign("STUDENT");
    await request(path, "GET", undefined, 403);
    await request(
      "/api/v1/schools/fremd/events" + query,
      "GET",
      undefined,
      403,
    );
    const own = await request(root + "/events" + query);
    assert.ok(own.length);
    assert.ok(own.every((e) => e.groups.includes("1A")));
    await page.setViewportSize({ width: 360, height: 780 });
    await page.getByRole("heading", { name: "Freigegebener Plan" }).waitFor();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth,
    );
    assert.equal(overflow, false);
    assert.deepEqual(errors, []);
    console.log(
      "PASS · Browser login, planning, catalog, revisions, solver, review, role separation, publication, covers, 4 exports, OpenAPI, mobile reader",
    );
  } catch (e) {
    if (page)
      console.error((await page.locator("body").innerText()).slice(0, 2500));
    throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
