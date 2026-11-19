export const roles = {
  PLAN: "Stundenplanung",
  DIRECTION: "Schulleitung",
  TEACHER: "Lehrkraft",
  CURATOR: "Klassenvorstand",
  STUDENT: "Schüler",
  GUARDIAN: "Erziehungsberechtigte",
  OFFICE: "Verwaltung",
  ADMIN: "Administration",
};
export const stages = {
  DRAFT: "Entwurf",
  REVIEW: "In Prüfung",
  APPROVED: "Freigegeben",
  PUBLISHED: "Veröffentlicht",
};
export const days = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag"];
export const today = () =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Europe/Vienna" }).format(
    new Date(),
  );
export function addDays(date, n) {
  const d = new Date(date + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
let token = "";
export async function session() {
  const r = await fetch("/session");
  const data = await r.json();
  token = data.csrf;
  return data;
}
export async function api(path, method = "GET", body) {
  const r = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await r.text();
  let data = null;
  try {
    data = raw ? JSON.parse(raw) : null;
  } catch {}
  if (!r.ok)
    throw Error(
      data?.message ||
        {
          401: "Bitte erneut anmelden.",
          403: "Berechtigung fehlt.",
          404: "Für diesen Zeitraum liegt kein veröffentlichter Plan vor.",
          409: "Daten geändert. Bitte Ansicht neu laden.",
        }[r.status] ||
        "Anfrage fehlgeschlagen (" + r.status + ")",
    );
  return data;
}
export async function login(role) {
  const r = await fetch("/login", {
    method: "POST",
    headers: { "X-CSRF-TOKEN": token },
    body: new URLSearchParams({
      username: role.toLowerCase(),
      password: "schulplan-demo",
    }),
  });
  if (!r.ok) throw Error("Anmeldung fehlgeschlagen.");
}
export const effects = {
  wishes: "Wunschzeiten",
  spread: "Fächerstreuung",
  gaps: "Lücken",
  late: "Randstunden",
  home: "Stammraum",
  morning: "Vormittag",
};
export function describe(p) {
  return p
    ? days[p.day - 1] +
        " · " +
        (p.slot + 1) +
        ". Std. · " +
        p.room +
        " · " +
        p.teachers.join(", ")
    : "Offen";
}
