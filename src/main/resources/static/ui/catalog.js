import { api, effects, days } from "./common.js";
const model = {
  subjects: ["Fächer", { id: "Kürzel", name: "Bezeichnung" }],
  groups: [
    "Klassen & Gruppen",
    {
      id: "Kürzel",
      name: "Bezeichnung",
      size: "Schülerzahl",
      home: "Stammraum",
      cohorts: "Teilgruppen",
    },
  ],
  teachers: [
    "Lehrkräfte",
    {
      id: "Kürzel",
      name: "Name",
      employment: "Anstellungsart",
      employedFrom: "Anstellung ab",
      employedUntil: "Anstellung bis",
      subjects: "Qualifikationen",
      load: "Soll-Wochenstunden",
      daily: "Max. Präsenz pro Tag",
      blocked: "Sperrzeiten",
      wishes: "Wunschzeiten",
    },
  ],
  rooms: [
    "Räume",
    {
      id: "Kürzel",
      name: "Bezeichnung",
      capacity: "Plätze",
      equipment: "Ausstattung",
      blocked: "Sperrzeiten",
    },
  ],
  students: [
    "Schüler",
    { id: "Kürzel", name: "Name", groups: "Zugehörige Gruppen" },
  ],
  courses: [
    "Unterricht",
    {
      id: "Kürzel",
      subject: "Fach",
      groups: "Gruppen (gemeinsam)",
      teachers: "Lehrkräfte (gemeinsam)",
      hours: "Stunden je aktiver Woche",
      block: "Blocklänge",
      week: "Wochenrhythmus",
      equipment: "Ausstattung",
      resources: "Geräte",
    },
  ],
  bookings: [
    "Reservierungen",
    {
      id: "Kürzel",
      resource: "Raum oder Gerät",
      from: "Von",
      until: "Bis",
      first: "Erste Stunde",
      last: "Letzte Stunde",
    },
  ],
  holidays: ["Ferien", { name: "Bezeichnung", from: "Von", until: "Bis" }],
  periods: [
    "Prüfperioden",
    { id: "Kürzel", name: "Bezeichnung", from: "Von", until: "Bis" },
  ],
  exams: [
    "Prüfungen",
    {
      id: "Kürzel",
      name: "Prüfung",
      period: "Prüfperiode",
      date: "Termin",
      start: "Beginn",
      end: "Ende",
      room: "Prüfungsraum",
      teachers: "Aufsicht",
      candidates: "Kandidaten",
    },
  ],
  slots: ["Zeitraster", { start: "Beginn", end: "Ende" }],
};
const relations = {
  home: "rooms",
  subject: "subjects",
  period: "periods",
  room: "rooms",
  groups: "groups",
  subjects: "subjects",
  teachers: "teachers",
  candidates: "students",
};
const multi = [
  "groups",
  "teachers",
  "candidates",
  "subjects",
  "resources",
  "cohorts",
  "equipment",
];
const numeric = [
  "size",
  "load",
  "daily",
  "capacity",
  "hours",
  "block",
  "first",
  "last",
];
const windowFields = ["blocked", "wishes"];
const special = ["settings", "templates"];
const formatWin = (ws) =>
  ws.map((w) => w.day + "/" + (w.slot + 1) + "/" + w.week).join(", ");
function parseWin(s) {
  if (!s.trim()) return [];
  return s.split(",").map((item) => {
    let [day, slot, week] = item.trim().split("/");
    return { day: Number(day), slot: Number(slot) - 1, week: week || "AB" };
  });
}
const clone = (x) => JSON.parse(JSON.stringify(x));
export default {
  props: ["snap", "base", "school"],
  emits: ["changed", "error"],
  data: () => ({
    model,
    effects,
    tab: "subjects",
    row: null,
    idx: -1,
    settings: null,
    importText: "",
    report: null,
    templates: [],
    tplName: "",
    tplGroup: "",
    tplId: "",
    prefix: "",
    tplTargets: [],
    search: "",
  }),
  computed: {
    s() {
      return this.snap.scenario;
    },
    c() {
      return this.s.plan.catalog;
    },
    path() {
      return this.base + "/scenarios/" + this.s.id;
    },
    editable() {
      return (
        ["PLAN", "OFFICE"].includes(this.school.role) &&
        this.s.status === "DRAFT"
      );
    },
    fields() {
      return this.model[this.tab]?.[1] || {};
    },
    rows() {
      return (this.c[this.tab] || [])
        .map((r, i) => ({ r, i }))
        .filter((x) =>
          Object.values(x.r)
            .join(" ")
            .toLowerCase()
            .includes(this.search.toLowerCase()),
        );
    },
  },
  methods: {
    async act(fn) {
      try {
        await fn();
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    select(k) {
      this.tab = k;
      this.row = null;
      this.report = null;
      this.importText = "";
      this.search = "";
      if (k === "settings") this.initSettings();
      if (k === "templates") this.getTemplates();
    },
    initSettings() {
      this.settings = clone(this.c);
      this.settings.resources = this.c.resources.join(", ");
      this.settings.blocked = formatWin(this.c.blocked);
      this.settings.coverOrder = this.c.coverOrder.join(", ");
    },
    async getTemplates() {
      await this.act(
        async () => (this.templates = await api(this.base + "/templates")),
      );
    },
    async makeTpl() {
      await this.act(async () => {
        let rules = this.c.courses
          .filter((c) => c.groups.includes(this.tplGroup))
          .map(
            ({
              subject,
              hours,
              block,
              week,
              equipment,
              resources,
              teachers,
            }) => ({
              subject,
              hours,
              block,
              week,
              equipment,
              resources,
              teachers,
            }),
          );
        await api(this.base + "/templates", "POST", {
          name: this.tplName,
          schoolType: this.c.schoolType,
          rules,
        });
        await this.getTemplates();
        this.$emit("changed", "Stundentafel gespeichert.");
      });
    },
    async useTpl() {
      await this.act(async () => {
        await api(this.path + "/curriculum", "POST", {
          revision: this.s.revision,
          template: this.tplId,
          prefix: this.prefix,
          groups: this.tplTargets,
        });
        this.$emit("changed", "Unterrichtsveranstaltungen erzeugt.");
      });
    },
    type(k) {
      if (relations[k]) return "ref";
      if (k === "week" || k === "resource") return "pick";
      if (windowFields.includes(k)) return "window";
      if (numeric.includes(k)) return "number";
      if (
        ["from", "until", "date", "employedFrom", "employedUntil"].includes(k)
      )
        return "date";
      if (["start", "end"].includes(k)) return "time";
      return "text";
    },
    many: (k) => multi.includes(k),
    options(k) {
      return relations[k]
        ? this.c[relations[k]].map((x) => ({ id: x.id, name: x.name || x.id }))
        : (k === "week"
            ? ["A", "B", "AB"]
            : [...this.c.rooms.map((r) => r.id), ...this.c.resources]
          ).map((id) => ({ id, name: id }));
    },
    display(v, k) {
      if (windowFields.includes(k)) return formatWin(v);
      if (Array.isArray(v)) return v.join(", ");
      if (k === "first" || k === "last") return v + 1;
      return v;
    },
    edit(i) {
      this.idx = i;
      let r = {};
      for (let k in this.fields) {
        const v = i >= 0 ? this.c[this.tab][i][k] : undefined;
        if (windowFields.includes(k)) r[k] = formatWin(v || []);
        else if (multi.includes(k))
          r[k] = relations[k] ? [...(v || [])] : (v || []).join(", ");
        else if (["first", "last"].includes(k)) r[k] = (v ?? 0) + 1;
        else r[k] = v ?? (numeric.includes(k) ? 1 : k === "week" ? "AB" : "");
      }
      this.row = r;
    },
    async persist(c, msg) {
      await api(this.path + "/catalog", "PUT", {
        revision: this.s.revision,
        catalog: c,
      });
      this.row = null;
      this.$emit("changed", msg);
    },
    async saveRow() {
      await this.act(async () => {
        let c = clone(this.c),
          r = {};
        for (let [k, v] of Object.entries(this.row)) {
          r[k] =
            ["employedFrom", "employedUntil"].includes(k) && !v
              ? null
              : windowFields.includes(k)
                ? parseWin(v)
                : multi.includes(k) && !relations[k]
                  ? v
                      .split(",")
                      .map((x) => x.trim())
                      .filter(Boolean)
                  : numeric.includes(k)
                    ? Number(v) - (["first", "last"].includes(k) ? 1 : 0)
                    : v;
        }
        if (this.idx < 0) c[this.tab].push(r);
        else c[this.tab][this.idx] = r;
        await this.persist(c, "Stammdaten gespeichert.");
      });
    },
    async remove(i) {
      if (!confirm("Diesen Eintrag entfernen?")) return;
      await this.act(async () => {
        let c = clone(this.c);
        c[this.tab].splice(i, 1);
        await this.persist(c, "Eintrag entfernt.");
      });
    },
    preset() {
      let catalogue = {
        VS: [4, 4, 4, 4, 3, 2],
        MS: [3, 3, 3, 2, 2, 2],
        AHS: [3, 2, 2, 1, 1, 1],
        "BHS/BMS": [2, 2, 1, 1, 3, 1],
        BS: [1, 1, 1, 1, 4, 0],
      };
      let vals = catalogue[this.settings.schoolType];
      Object.keys(effects).forEach(
        (k, i) => (this.settings.weights[k] = vals[i]),
      );
    },
    async saveSettings() {
      await this.act(async () => {
        let c = clone(this.settings);
        c.resources = c.resources
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
        c.blocked = parseWin(c.blocked);
        c.coverOrder = c.coverOrder.split(",").map((s) => s.trim());
        await this.persist(c, "Planungsregeln gespeichert.");
      });
    },
    async importCsv(apply) {
      await this.act(async () => {
        this.report = await api(
          this.path + "/master/" + this.tab + "/import",
          "POST",
          { revision: this.s.revision, csv: this.importText, apply },
        );
        if (this.report.applied) this.$emit("changed", "Import abgeschlossen.");
      });
    },
  },
  template: `<section class="panel"><div class="tabs"><button v-for="(v,k) in model" :class="{on:k===tab}" @click="select(k)">{{v[0]}}</button><button @click="select('settings')" :class="{on:tab==='settings'}">Planungsregeln</button><button @click="select('templates')" :class="{on:tab==='templates'}">Stundentafeln</button></div>
 <template v-if="model[tab]"><div class="workspace-head"><h2>{{model[tab][0]}}</h2><button v-if="editable" class="primary" @click="edit(-1)">＋ Hinzufügen</button></div><input v-model="search" aria-label="Einträge durchsuchen" placeholder="Einträge durchsuchen"><div class="grid-scroll"><table class="data"><thead><tr><th v-for="(label,k) in fields">{{label}}</th><th v-if="editable">Aktion</th></tr></thead><tbody><tr v-for="x in rows"><td v-for="(label,k) in fields">{{display(x.r[k],k)||'–'}}</td><td v-if="editable"><button @click="edit(x.i)">Ändern</button> <button @click="remove(x.i)">Entfernen</button></td></tr></tbody></table></div><p v-if="!rows.length">Keine Einträge.</p>
 <details v-if="fields.id&&editable"><summary>CSV-Abgleich und Export</summary><p><a :href="path+'/master/'+tab+'.csv'">CSV mit Spaltenvorlage herunterladen</a></p><label>CSV-Datei<input type="file" accept=".csv,text/csv" @change="async e=>{if(e.target.files[0]){importText=await e.target.files[0].text();report=null}}"></label><button :disabled="!importText" @click="importCsv(false)">Abgleich prüfen</button> <button v-if="report&&!report.applied&&!report.errors.length" @click="importCsv(true)">Import übernehmen</button><p v-if="report">{{report.added}} neu · {{report.updated}} geändert · {{report.unchanged}} identisch</p><p class="alert" v-for="e in report?.errors">{{e}}</p></details>
 <div class="overlay" v-if="row" @keydown.esc="row=null"><form class="dialog" @submit.prevent="saveRow"><button type="button" class="right" @click="row=null">Schließen</button><h2>{{model[tab][0]}} bearbeiten</h2><div class="fields"><label v-for="(label,k) in fields">{{label}}
 <select v-if="['ref','pick'].includes(type(k))" v-model="row[k]" :multiple="many(k)"><option v-if="!many(k)" value="">Auswählen</option><option v-for="o in options(k)" :value="o.id">{{o.name}}</option></select><input v-else :type="type(k)==='window'?'text':type(k)" v-model="row[k]" :required="['id','name','from','until','date','start','end'].includes(k)" :placeholder="type(k)==='window'?'Tag/Stunde/Woche, z. B. 1/2/AB':many(k)?'Mit Komma trennen':''"></label></div><p v-if="tab==='groups'">Gemeinsame Teilgruppenkürzel sperren gleichzeitigen Unterricht. Getrennte Teilgruppen können parallel stattfinden.</p><button class="primary">Speichern</button></form></div></template>
 <form v-if="tab==='settings'&&settings" @submit.prevent="saveSettings"><h2>Planungsregeln</h2><fieldset :disabled="!editable"><div class="fields"><label>Schulart<select v-model="settings.schoolType"><option v-for="x in ['VS','MS','AHS','BHS/BMS','BS']">{{x}}</option></select></label><label>Schuljahr<input v-model="settings.year"></label><label>Semester<input v-model="settings.semester"></label><label>Beginn<input type="date" v-model="settings.from"></label><label>Ende<input type="date" v-model="settings.until"></label><label>Gerätepool<input v-model="settings.resources" placeholder="Kürzel, mit Komma getrennt"></label><label>Schulweite Sperren<input v-model="settings.blocked" placeholder="Tag/Stunde/Woche"></label><label>Max. Stunden am Stück<input type="number" min="1" v-model.number="settings.maxConsecutive"></label><label>Mindestpause (Minuten)<input type="number" min="0" v-model.number="settings.breakMinutes"></label></div><h3>Gewichte für Strafpunkte</h3><button type="button" @click="preset">Planungsvorlage für {{settings.schoolType}} einsetzen</button><div class="fields"><label v-for="(v,k) in effects">{{v}}<input type="number" min="0" max="1000" v-model.number="settings.weights[k]"></label></div><label>Vertretungskaskade<input v-model="settings.coverOrder"></label><label><input type="checkbox" v-model="settings.balanceCover"> Vertretungskonto bei Vorschlägen berücksichtigen</label><label><input type="checkbox" v-model="settings.preferRemainingLoad"> Restverpflichtung der Planwoche bevorzugen</label><button v-if="editable" class="primary">Regeln speichern</button></fieldset></form>
 <section v-if="tab==='templates'"><h2>Stundentafeln</h2><form v-if="editable" @submit.prevent="makeTpl"><h3>Aus bestehendem Unterricht ableiten</h3><div class="fields"><label>Bezeichnung<input required v-model="tplName"></label><label>Quellgruppe<select required v-model="tplGroup"><option v-for="g in c.groups" :value="g.id">{{g.name}}</option></select></label></div><button>Vorlage speichern</button></form><form v-if="editable&&templates.length" @submit.prevent="useTpl"><h3>Unterricht erzeugen</h3><div class="fields"><label>Stundentafel<select required v-model="tplId"><option v-for="t in templates.filter(t=>t.school_type===c.schoolType)" :value="t.id">{{t.name}}</option></select></label><label>Kurspräfix<input required v-model="prefix"></label><label>Zielgruppen (gekoppelt)<select required multiple v-model="tplTargets"><option v-for="g in c.groups" :value="g.id">{{g.name}}</option></select></label></div><button>Veranstaltungen erzeugen</button></form><p v-if="!templates.length">Noch keine Stundentafel hinterlegt.</p></section></section>`,
};
