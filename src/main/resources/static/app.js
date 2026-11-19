import { api, session, login, roles, stages } from "./ui/common.js";
import Planner from "./ui/planner.js";
import Catalog from "./ui/catalog.js";
import Daily from "./ui/daily.js";
import Admin from "./ui/admin.js";
const { createApp } = Vue;
createApp({
  components: { Planner, Catalog, Daily, Admin },
  data: () => ({
    user: null,
    schoolId: "",
    section: "plan",
    scenarios: [],
    selected: "",
    snap: null,
    error: "",
    notice: "",
    requestVersion: 0,
    role: "PLAN",
    roles,
    stages,
    createOpen: false,
    createName: "",
    copyId: "",
    changeDates: false,
    dateFrom: "",
    dateTo: "",
    compare: "",
    diff: null,
  }),
  computed: {
    school() {
      return this.user?.schools.find((s) => s.id === this.schoolId);
    },
    base() {
      return "/api/v1/schools/" + encodeURIComponent(this.schoolId);
    },
    sections() {
      const r = this.school?.role;
      return [
        ["plan", "Wochenplan", "▦", ["PLAN", "DIRECTION", "OFFICE"]],
        ["catalog", "Stammdaten", "▤", ["PLAN", "OFFICE", "DIRECTION"]],
        ["daily", "Vertretungen", "⇄", ["PLAN", "DIRECTION", "TEACHER"]],
        [
          "published",
          "Veröffentlichungen",
          "◷",
          [
            "PLAN",
            "DIRECTION",
            "OFFICE",
            "TEACHER",
            "CURATOR",
            "STUDENT",
            "GUARDIAN",
          ],
        ],
        ["admin", "Administration", "⚙", ["ADMIN", "DIRECTION"]],
      ].filter((v) => v[3].includes(r));
    },
    active() {
      return this.sections.find((x) => x[0] === this.section);
    },
    isWork() {
      return ["plan", "catalog"].includes(this.section);
    },
  },
  async mounted() {
    await this.guard(async () => {
      this.user = await session();
      if (this.user.authenticated && this.user.schools.length) {
        this.schoolId = this.user.schools[0].id;
        await this.init();
      }
    });
  },
  methods: {
    async guard(fn) {
      this.error = "";
      try {
        await fn();
      } catch (e) {
        this.error = e.message;
      }
    },
    async init() {
      this.section = this.sections[0]?.[0];
      this.snap = null;
      this.selected = "";
      if (["PLAN", "DIRECTION", "OFFICE"].includes(this.school?.role))
        await this.list();
    },
    async list(prefer) {
      const base = this.base;
      const rows = await api(base + "/scenarios");
      if (this.base !== base) return;
      this.scenarios = rows;
      this.selected = prefer || this.selected || this.scenarios[0]?.id || "";
      await this.load();
    },
    async load() {
      const id = this.selected,
        base = this.base,
        version = ++this.requestVersion;
      if (this.snap?.scenario.id !== id) this.snap = null;
      const result = id ? await api(base + "/scenarios/" + id) : null;
      if (
        this.selected !== id ||
        this.base !== base ||
        this.requestVersion !== version
      )
        return;
      this.snap = result;
      if (this.snap) {
        let x = this.scenarios.find((x) => x.id === this.selected);
        if (x) x.status = this.snap.scenario.status;
      }
    },
    async enter() {
      await this.guard(async () => {
        await login(this.role);
        location.reload();
      });
    },
    async logout() {
      await this.guard(async () => {
        await api("/logout", "POST");
        location.reload();
      });
    },
    async changed(msg) {
      await this.guard(async () => {
        await this.load();
        this.notice = msg || "Gespeichert.";
      });
    },
    select(section) {
      this.section = section;
      this.error = "";
      this.notice = "";
    },
    openCreate() {
      this.createName = "";
      this.copyId = this.selected;
      this.changeDates = false;
      this.createOpen = true;
    },
    async create() {
      await this.guard(async () => {
        const r = await api(this.base + "/scenarios", "POST", {
          name: this.createName,
          source: this.copyId || null,
          from: this.changeDates ? this.dateFrom : null,
          until: this.changeDates ? this.dateTo : null,
        });
        await this.list(r.id);
        this.createOpen = false;
        this.section = "plan";
      });
    },
    async comparePlans() {
      await this.guard(async () => {
        this.diff = await api(
          this.base +
            "/scenarios/" +
            this.selected +
            "/diff?other=" +
            this.compare,
        );
      });
    },
  },
  template: `
 <div v-if="!user" class="login"><h1>SchoolPlanning</h1><p role="status">{{error||'Arbeitsplatz wird geladen …'}}</p></div>
 <div v-else-if="!user.authenticated" class="login"><section class="login-card"><div class="brand-mark">S</div><p class="eyebrow">SchoolPlanning</p><h1>Deine Schulplanung.</h1><p>Stundenpläne erstellen, gemeinsam prüfen und für die Schule freigeben.</p><form v-if="user.demo" @submit.prevent="enter"><label>Demozugang<select v-model="role"><option v-for="(name,id) in roles" :value="id">{{name}}</option></select></label><button class="primary">Arbeitsplatz öffnen →</button></form><a v-else class="button primary" href="/oauth2/authorization/school">Mit Schulkonto anmelden →</a><p class="alert" v-if="error">{{error}}</p></section></div>
 <div v-else-if="!school" class="login"><h1>Schulzuordnung fehlt</h1><p>Die Administration kann deinem Schulkonto eine Rolle zuweisen.</p><button @click="logout">Abmelden</button></div>
 <div v-else class="shell"><aside><div class="identity"><span class="brand-mark">S</span><strong>SchoolPlanning</strong></div><div class="school"><span class="eyebrow">Schule</span><select v-if="user.schools.length>1" v-model="schoolId" @change="guard(init)"><option v-for="s in user.schools" :value="s.id">{{s.name}}</option></select><strong v-else>{{school.name}}</strong></div>
 <nav aria-label="Hauptnavigation"><button v-for="x in sections" :class="{active:section===x[0]}" @click="select(x[0])"><span aria-hidden="true">{{x[2]}}</span>{{x[1]}}</button></nav><footer><span class="avatar">{{roles[school.role].slice(0,1)}}</span><div><strong>{{roles[school.role]}}</strong><button class="logout" @click="logout">Abmelden</button></div></footer></aside>
 <main><header class="top"><div><span class="eyebrow">{{school.name}}</span><h1>{{active?.[1]}}</h1></div><span class="pill" v-if="snap&&isWork"><i></i>{{stages[snap.scenario.status]}}</span></header>
 <div v-if="error" role="alert" class="alert">{{error}} <button @click="error=''">Schließen</button></div><div v-if="notice" role="status" class="success">{{notice}} <button @click="notice=''">×</button></div>
 <section v-if="isWork" class="scenario-bar"><label>Szenario<select aria-label="Szenario" v-model="selected" @change="guard(load)"><option v-for="s in scenarios" :value="s.id">{{s.name}} · {{stages[s.status]}}</option></select></label><button v-if="school.role==='PLAN'" @click="openCreate">＋ Szenario</button><details v-if="scenarios.length>1"><summary>Vergleichen</summary><select aria-label="Vergleichsszenario" v-model="compare"><option v-for="s in scenarios.filter(x=>x.id!==selected)" :value="s.id">{{s.name}}</option></select><button :disabled="!compare" @click="comparePlans">Differenz anzeigen</button></details></section>
 <section v-if="diff" class="panel"><button class="right" @click="diff=null">Schließen</button><h2>Szenarienvergleich</h2><p>{{diff.placements.length}} unterschiedliche Platzierungen · Stammdaten {{diff.catalogChanged?'unterschiedlich':'gleich'}}</p><p v-for="p in diff.placements">{{p.lesson}} · {{p.current?.room||'offen'}} / {{p.other?.room||'offen'}} · Tag {{p.current?.day||'–'}} / {{p.other?.day||'–'}} · Stunde {{(p.current?.slot??-1)+1}} / {{(p.other?.slot??-1)+1}}</p></section>
 <div v-if="createOpen" class="panel"><form @submit.prevent="create"><h2>Szenario anlegen</h2><div class="fields"><label>Name<input required maxlength="120" v-model="createName"></label><label>Grundlage<select v-model="copyId"><option value="">Leerer Plan</option><option v-for="s in scenarios" :value="s.id">{{s.name}}</option></select></label><label><input type="checkbox" v-model="changeDates"> Neuer Semesterzeitraum</label><template v-if="changeDates"><label>Von<input type="date" v-model="dateFrom" required></label><label>Bis<input type="date" v-model="dateTo" required></label></template></div><button class="primary">Anlegen</button> <button type="button" @click="createOpen=false">Abbrechen</button></form></div>
 <Planner v-if="snap&&section==='plan'" :key="selected" :snap="snap" :base="base" :school="school" @changed="changed" @error="error=$event"/>
 <Catalog v-if="snap&&section==='catalog'" :key="selected" :snap="snap" :base="base" :school="school" @changed="changed" @error="error=$event"/>
 <p v-if="isWork&&!snap">Für diese Schule ist noch kein Szenario angelegt.</p>
 <Daily v-if="section==='daily'||section==='published'" :key="section+schoolId" :section="section" :base="base" :school="school" :snap="snap" @error="error=$event" @notice="notice=$event"/>
 <Admin v-if="section==='admin'" :base="base" :school="school" @error="error=$event" @notice="notice=$event"/>
 </main></div>`,
}).mount("#app");
