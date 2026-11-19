import { api, today, addDays } from "./common.js";
export default {
  props: ["section", "school", "base", "snap"],
  emits: ["notice", "error"],
  data: () => ({
    from: today(),
    until: addDays(today(), 6),
    day: today(),
    absUntil: today(),
    person: "",
    reason: "Krankheit",
    review: null,
    events: [],
    notices: [],
    scope: "group",
    entity: "",
    subs: { plans: true, covers: true },
    wish: "",
    wishes: [],
    feeds: [],
    link: null,
    accounts: [],
    loaded: false,
  }),
  computed: {
    can() {
      return ["PLAN", "DIRECTION"].includes(this.school.role);
    },
    q() {
      return new URLSearchParams({
        from: this.from,
        until: this.until,
        scope: this.scope,
        entity: this.entity,
      }).toString();
    },
  },
  async mounted() {
    await this.act(async () => {
      if (this.section === "published") {
        await this.show();
        this.subs = await api(this.base + "/subscriptions");
        this.notices = await api(this.base + "/notices");
        if (this.can) this.feeds = await api(this.base + "/feeds");
      } else {
        if (["PLAN", "TEACHER"].includes(this.school.role))
          this.wishes = await api(this.base + "/wishes");
        this.person =
          this.school.entity ||
          this.snap?.scenario.plan.catalog.teachers[0]?.id ||
          "";
        await this.stats();
      }
    });
  },
  methods: {
    async act(fn) {
      try {
        await fn();
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async subscribe() {
      await this.act(async () => {
        await api(this.base + "/subscriptions", "PUT", this.subs);
        this.$emit("notice", "Abonnements gespeichert.");
      });
    },
    async show() {
      this.loaded = false;
      this.events = await api(this.base + "/events?" + this.q);
      this.loaded = true;
    },
    async stats() {
      this.accounts = await api(
        this.base + "/accounts?from=" + this.from + "&until=" + this.until,
      );
    },
    async absence() {
      await this.act(async () => {
        await api(this.base + "/absences", "POST", {
          teacher: this.person,
          from: this.day,
          until: this.absUntil,
          reason: this.reason,
        });
        this.review = null;
        this.$emit(
          "notice",
          "Abwesenheit erfasst. Der Vertretungsvorschlag kann jetzt berechnet werden.",
        );
      });
    },
    async propose() {
      await this.act(async () => {
        this.review = await api(this.base + "/covers?day=" + this.day);
      });
    },
    async publish() {
      await this.act(async () => {
        await api(this.base + "/covers", "POST", this.review.proposal);
        this.review = null;
        await this.stats();
        this.$emit("notice", "Vertretungsplan veröffentlicht.");
      });
    },
    async sendWish() {
      await this.act(async () => {
        await api(this.base + "/wishes", "POST", { message: this.wish });
        this.wish = "";
        this.wishes = await api(this.base + "/wishes");
      });
    },
    async closeWish(id) {
      await this.act(async () => {
        await api(this.base + "/wishes/" + id + "/close", "POST");
        this.wishes = await api(this.base + "/wishes");
      });
    },
    async feed() {
      await this.act(async () => {
        this.link = await api(this.base + "/feeds", "POST", {
          scope: this.scope,
          entity: this.entity,
        });
        this.feeds = await api(this.base + "/feeds");
      });
    },
    async revoke(id) {
      await this.act(async () => {
        await api(this.base + "/feeds/" + id, "DELETE");
        this.link = null;
        this.feeds = await api(this.base + "/feeds");
      });
    },
    url(path) {
      return location.origin + path;
    },
  },
  template: `<div v-if="section==='published'"><section class="panel"><h2>Freigegebener Plan</h2><form @submit.prevent="act(show)"><div class="fields"><label>Von<input required type="date" v-model="from"></label><label>Bis<input required type="date" v-model="until"></label><label>Ansicht<select v-model="scope"><option value="group">Klasse</option><option value="teacher">Lehrkraft</option><option value="room">Raum</option><option value="subject">Fach</option></select></label><label>Kürzel<input v-model="entity" placeholder="Alle sichtbaren Termine"></label></div><button class="primary">Plan anzeigen</button></form><div class="grid-scroll" v-if="events.length"><table class="data"><thead><tr><th>Datum</th><th>Zeit</th><th>Fach / Prüfung</th><th>Gruppe</th><th>Lehrkraft</th><th>Raum</th><th>Status</th></tr></thead><tbody><tr v-for="e in events"><td>{{e.date}}</td><td>{{e.start.slice(0,5)}}–{{e.end.slice(0,5)}}</td><td>{{e.subject}}</td><td>{{e.groups.join(', ')}}</td><td>{{e.teachers.join(', ')}}</td><td>{{e.room}}</td><td>{{e.status}}</td></tr></tbody></table></div><p v-else-if="loaded">Für diese Auswahl sind keine freigegebenen Termine vorhanden.</p><div class="chips"><a v-for="f in ['pdf','ics','csv','html']" :href="base+'/exports/'+f+'?'+q">{{f.toUpperCase()}}</a></div></section>
 <section v-if="can" class="panel"><h3>Kalender-Abo und Schultafel</h3><p>Ein Freigabelink zeigt die veröffentlichten Termine der gewählten Klasse oder Lehrkraft. Jeder mit dem Link kann diesen Ausschnitt lesen.</p><button :disabled="!entity||!['group','teacher'].includes(scope)" @click="feed">Link für gewählte Ansicht erstellen</button><div v-if="link"><label>Kalenderadresse<input readonly :value="url(link.calendar)" @focus="$event.target.select()"></label><label>Adresse für das HTML-Widget<input readonly :value="url(link.widget)" @focus="$event.target.select()"></label></div><p v-for="f in feeds">{{f.scope}} · {{f.entity}} <button @click="revoke(f.token_hash)">Widerrufen</button></p></section>
 <section class="panel"><h3>Mitteilungen</h3><form @submit.prevent="subscribe"><label><input type="checkbox" v-model="subs.plans"> Planveröffentlichungen abonnieren</label><label><input type="checkbox" v-model="subs.covers"> Eigene Vertretungsänderungen abonnieren</label><button>Abonnements speichern</button></form><p v-if="!notices.length">Noch keine Veröffentlichungen.</p><p v-for="n in notices">{{new Date(n.created).toLocaleDateString('de-AT')}} · {{n.message}}</p></section></div>
 <div v-else><section class="panel"><h2>Tagesplanung</h2><div class="fields"><label>Unterrichtstag<input type="date" v-model="day" @change="review=null;absUntil=day"></label></div><form v-if="['PLAN','TEACHER'].includes(school.role)" @submit.prevent="absence"><h3>Abwesenheit melden</h3><div class="fields"><label>Lehrkraft (Kürzel)<input required v-model="person" :readonly="school.role==='TEACHER'" list="staff"><datalist id="staff"><option v-for="t in snap?.scenario.plan.catalog.teachers" :value="t.id">{{t.name}}</option></datalist></label><label>Bis einschließlich<input type="date" required v-model="absUntil" :min="day"></label><label>Grund<select v-model="reason"><option v-for="v in ['Krankheit','Dienstweg','Fortbildung','Exkursion','Freistellung']">{{v}}</option></select></label></div><button>Abwesenheit speichern</button></form><div v-if="can"><h3>Vertretungsvorschlag</h3><button class="primary" @click="propose">Vorschlag berechnen</button><div v-if="review"><p>{{review.proposal.rows.length}} betroffene Unterrichtsblöcke</p><label v-for="(r,i) in review.proposal.rows">{{r.lesson}} · {{r.missing}}<select v-model="review.proposal.rows[i]" :disabled="school.role!=='PLAN'"><option v-for="o in review.options[r.lesson+'/'+r.missing]" :value="o">{{o.kind}} {{o.replacement}} {{o.target}}</option></select></label><button v-if="school.role==='PLAN'&&review.proposal.rows.length" @click="publish">Vertretungsplan veröffentlichen</button></div></div></section>
 <section class="panel"><h3>Vertretungskonten</h3><form class="fields" @submit.prevent="act(stats)"><label>Von<input required type="date" v-model="from"></label><label>Bis<input required type="date" v-model="until"></label><button>Anzeigen</button></form><p v-if="!accounts.length">Keine gebuchten Vertretungsstunden im Zeitraum.</p><p v-for="x in accounts">{{x.teacher}} · {{x.hours}} Std.</p></section>
 <section v-if="['PLAN','TEACHER'].includes(school.role)" class="panel"><h3>Planungswünsche</h3><form v-if="school.role==='TEACHER'" @submit.prevent="sendWish"><label>Wunsch an die Planung<textarea required maxlength="1000" v-model="wish"></textarea></label><button>Senden</button></form><p v-for="w in wishes">{{w.teacher}} · {{w.message}} · {{w.status}} <button v-if="school.role==='PLAN'&&w.status==='OFFEN'" @click="closeWish(w.id)">Erledigen</button></p><p v-if="!wishes.length">Keine Wünsche eingegangen.</p></section></div>`,
};
