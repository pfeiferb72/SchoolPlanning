import { api, days, effects, describe } from "./common.js";
export default {
  props: ["snap", "base", "school"],
  emits: ["changed", "error"],
  data: () => ({
    days,
    effects,
    describe,
    week: "A",
    group: "",
    teacher: "",
    room: "",
    subject: "",
    editor: "",
    pos: {},
    choices: [],
    validation: "",
    previewScore: null,
    previewSeq: 0,
    drag: "",
    over: "",
    solverOpen: false,
    seconds: 5,
    empty: false,
    partialGroup: "",
    partialTeacher: "",
    partialDay: "",
    job: null,
    timer: null,
  }),
  computed: {
    s() {
      return this.snap.scenario;
    },
    c() {
      return this.s.plan.catalog;
    },
    places() {
      return this.s.plan.places;
    },
    path() {
      return this.base + "/scenarios/" + this.s.id;
    },
    editable() {
      return this.school.role === "PLAN" && this.s.status === "DRAFT";
    },
    metrics() {
      return this.snap.metrics;
    },
    units() {
      return this.c.courses.flatMap((c) =>
        Array.from({ length: c.hours / c.block }, (_, i) => ({
          ...c,
          key: c.id + "#" + (i + 1),
        })),
      );
    },
    pdfUrl() {
      const { week, group, teacher, room, subject } = this;
      return this.path + "/wochenplan.pdf?" + new URLSearchParams({ week, group, teacher, room, subject });
    },
    relatedGroups() {
      const selected = this.c.groups.find(g => g.id === this.group);
      return new Set(this.c.groups.filter(g => !selected ||
        g.cohorts.some(key => selected.cohorts.includes(key))).map(g => g.id));
    },
    visible() {
      return this.units.filter(
        (u) =>
          [this.week, "AB"].includes(u.week) &&
          (!this.group || u.groups.some(g => this.relatedGroups.has(g))) &&
          (!this.subject || u.subject === this.subject) &&
          (!this.room || this.places[u.key]?.room === this.room) &&
          (!this.teacher ||
            (this.places[u.key]?.teachers || u.teachers).includes(
              this.teacher,
            )),
      );
    },
    cells() {
      const result = {};
      for (const u of this.visible) {
        const p = this.places[u.key];
        if (!p) continue;
        for (let i = p.slot; i < p.slot + u.block; i++)
          (result[p.day + "/" + i] ??= []).push(u);
      }
      return result;
    },
    open() {
      return this.visible.filter((u) => !this.places[u.key]);
    },
    lesson() {
      return this.units.find((x) => x.key === this.editor);
    },
    running() {
      return this.job && ["WARTET", "LÄUFT"].includes(this.job.status);
    },
  },
  watch: {
    "s.revision"() {
      this.editor = "";
      this.validation = "";
    },
  },
  beforeUnmount() {
    clearTimeout(this.timer);
  },
  methods: {
    async act(fn) {
      try {
        await fn();
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    name(id) {
      return this.c.subjects.find((x) => x.id === id)?.name || id;
    },
    cell(day, slot) {
      return this.cells[day + "/" + slot] || [];
    },
    hue(u) {
      return ["ocean", "moss", "clay", "plum"][
        Math.max(
          0,
          this.c.subjects.findIndex((x) => x.id === u.subject),
        ) % 4
      ];
    },
    async edit(u, d, s) {
      this.editor = u.key;
      const p = this.places[u.key];
      this.pos = {
        day: d ?? p?.day ?? 1,
        slot: s ?? p?.slot ?? 0,
        room:
          p?.room ||
          this.c.groups.find((g) => g.id === u.groups[0])?.home ||
          this.c.rooms[0]?.id ||
          "",
        teachers: [...(p?.teachers || u.teachers)],
        fixed: p?.fixed || false,
      };
      if (!this.pos.teachers.length)
        this.pos.teachers = this.c.teachers
          .filter((t) => t.subjects.includes(u.subject))
          .slice(0, 1)
          .map((t) => t.id);
      this.choices = [];
      await this.preview();
      await this.act(async () => {
        let opts = await api(
          this.path + "/suggestions?lesson=" + encodeURIComponent(u.key),
        );
        if (this.editor === u.key) this.choices = opts;
      });
    },
    async preview() {
      let seq = ++this.previewSeq;
      this.validation = "Wird geprüft …";
      this.previewScore = null;
      try {
        let res = await api(this.path + "/placement?preview=true", "POST", {
          revision: this.s.revision,
          lesson: this.editor,
          place: this.pos,
        });
        if (seq === this.previewSeq) {
          this.validation = "";
          this.previewScore = res.score;
        }
      } catch (e) {
        if (seq === this.previewSeq) this.validation = e.message;
      }
    },
    async save(place) {
      await this.act(async () => {
        await api(this.path + "/placement", "POST", {
          revision: this.s.revision,
          lesson: this.editor,
          place,
        });
        this.editor = "";
        this.$emit("changed", "Platzierung gespeichert.");
      });
    },
    drop(d, s, e) {
      const k = e.dataTransfer.getData("text/plain");
      this.over = "";
      let u = this.units.find((x) => x.key === k);
      if (this.editable && u) this.edit(u, d, s);
    },
    dragStart(k, e) {
      e.dataTransfer.setData("text/plain", k);
      e.dataTransfer.effectAllowed = "move";
    },
    async step(path, action) {
      await this.act(async () => {
        await api(this.path + "/" + path, "POST", {
          revision: this.s.revision,
          action,
        });
        this.$emit("changed", "Planstand aktualisiert.");
      });
    },
    async solve() {
      await this.act(async () => {
        this.job = await api(this.path + "/solver", "POST", {
          revision: this.s.revision,
          seconds: this.seconds,
          empty: this.empty,
          group: this.partialGroup || null,
          teacher: this.partialTeacher || null,
          day: this.partialDay || null,
        });
        this.poll();
      });
    },
    async poll() {
      await this.act(async () => {
        this.job = await api(this.base + "/jobs/" + this.job.id);
        if (this.running) this.timer = setTimeout(() => this.poll(), 550);
        else this.$emit("changed", this.job.status + " · " + this.job.message);
      });
    },
    cancel() {
      this.act(() => api(this.base + "/jobs/" + this.job.id, "DELETE"));
    },
  },
  template: `<section><div class="metrics"><div><span>Geplante Unterrichtsblöcke</span><strong>{{metrics.placed}} <small>/ {{metrics.total}}</small></strong></div><div><span>Offene Blöcke</span><strong>{{metrics.total-metrics.placed}}</strong></div><div><span>Strafpunkte</span><strong>{{metrics.score}}</strong></div></div>
 <div class="workspace-head"><div><p class="eyebrow">{{c.semester}} · {{c.year}}</p><h2>Planungsraster</h2></div><div class="actions" v-if="editable"><button @click="step('history','undo')" :disabled="!s.cursor" title="Rückgängig">↶ Zurück</button><button @click="step('history','redo')" :disabled="!snap.redo" title="Wiederholen">↷</button><button class="primary" @click="solverOpen=!solverOpen">✦ Solver</button></div></div>
 <form v-if="solverOpen&&editable" class="panel" @submit.prevent="solve"><h3>Automatische Planung</h3><div class="fields"><label>Zeitbudget (s)<input type="number" min="1" max="900" v-model.number="seconds" required></label><label>Klasse<select v-model="partialGroup"><option value="">Alle</option><option v-for="g in c.groups" :value="g.id">{{g.name}}</option></select></label><label>Lehrkraft<select v-model="partialTeacher"><option value="">Alle</option><option v-for="t in c.teachers" :value="t.id">{{t.name}}</option></select></label><label>Tag<select v-model="partialDay"><option value="">Alle</option><option v-for="(d,i) in days" :value="i+1">{{d}}</option></select></label><label><input type="checkbox" v-model="empty"> Neu anordnen</label></div><button class="primary" :disabled="running">Plan berechnen</button> <button v-if="running" type="button" @click="cancel">Abbrechen</button><p role="status" v-if="job">{{job.status}} · {{job.progress}} % · {{job.placed}} Blöcke</p></form>
 <div class="filters"><div class="seg" aria-label="Planwoche"><button v-for="w in ['A','B']" :class="{on:week===w}" @click="week=w">Woche {{w}}</button></div><select aria-label="Klassenfilter" v-model="group"><option value="">Alle Klassen</option><option v-for="g in c.groups" :value="g.id">{{g.name}}</option></select><select aria-label="Fachfilter" v-model="subject"><option value="">Alle Fächer</option><option v-for="s in c.subjects" :value="s.id">{{s.name}}</option></select><select aria-label="Lehrkraftfilter" v-model="teacher"><option value="">Alle Lehrkräfte</option><option v-for="t in c.teachers" :value="t.id">{{t.name}}</option></select><select aria-label="Raumfilter" v-model="room"><option value="">Alle Räume</option><option v-for="r in c.rooms" :value="r.id">{{r.name}}</option></select><a :href="pdfUrl">Wochenplan als PDF</a></div>
 <div class="grid-scroll"><table class="timetable"><thead><tr><th>Zeit</th><th v-for="d in days">{{d}}</th></tr></thead><tbody><tr v-for="(t,i) in c.slots"><th>{{i+1}}. Std.<small>{{t.start.slice(0,5)}}</small></th><td v-for="(d,j) in days" :class="{over:over===j+'-'+i}" @dragover.prevent="over=j+'-'+i" @dragleave="over=''" @drop.prevent="drop(j+1,i,$event)"><button v-for="u in cell(j+1,i)" class="tile" :class="hue(u)" :draggable="editable&&!places[u.key].fixed" @dragstart="dragStart(u.key,$event)" @click="editable&&edit(u)" :aria-label="name(u.subject)+' '+u.groups.join(', ')"><strong>{{places[u.key].fixed?'◆ ':''}}{{name(u.subject)}}</strong><small>{{u.groups.join(' + ')}} · {{places[u.key].room}} · {{places[u.key].teachers.join(', ')}}</small></button></td></tr></tbody></table></div>
 <section class="panel"><h3>Offene Unterrichtsblöcke <span class="count">{{open.length}}</span></h3><div class="chips"><button v-for="u in open" :disabled="!editable" :draggable="editable" @dragstart="dragStart(u.key,$event)" @click="edit(u)">{{u.subject}} · {{u.groups.join('+')}} <small>{{u.block}} Std.</small></button><p v-if="!open.length">Alle Blöcke dieser Auswahl sind platziert.</p></div></section>
 <details class="panel"><summary>Planqualität und offene Verpflichtungen</summary><p v-for="(v,k) in metrics.penalties">{{effects[k]}} · {{v}}</p><p v-for="msg in metrics.outstanding">{{msg}}</p></details>
 <section class="panel"><h3>Prüfung und Veröffentlichung</h3><p>Entwurf → interne Prüfung → Freigabe durch die Schulleitung → Veröffentlichung</p><button v-if="editable" @click="step('workflow','REVIEW')">Zur Prüfung einreichen</button> <button v-if="s.status==='REVIEW'&&school.role==='DIRECTION'" @click="step('workflow','APPROVED')">Plan freigeben</button> <button v-if="s.status==='APPROVED'&&['DIRECTION','PLAN'].includes(school.role)" class="primary" @click="step('workflow','PUBLISHED')">Jetzt veröffentlichen</button> <button v-if="['REVIEW','APPROVED'].includes(s.status)&&['DIRECTION','PLAN'].includes(school.role)" @click="step('workflow','DRAFT')">Zur Überarbeitung</button></section>
 <div v-if="editor&&lesson" class="overlay" @keydown.esc="editor=''"><section role="dialog" aria-modal="true" aria-label="Unterricht platzieren" class="dialog"><button class="right" @click="editor=''">Schließen</button><p class="eyebrow">{{lesson.groups.join(' + ')}} · {{lesson.block}} Std.<span v-if="lesson.parallelBand"> · Parallelband {{lesson.parallelBand}}</span></p><h2>{{name(lesson.subject)}} platzieren</h2><p v-if="lesson.parallelBand&&places[editor]">Beim Verschieben wechseln die bereits platzierten Partner dieses Blocks gemeinsam den Termin.</p><div class="fields"><label>Tag<select v-model="pos.day" @change="preview"><option v-for="(d,i) in days" :value="i+1">{{d}}</option></select></label><label>Stunde<select v-model="pos.slot" @change="preview"><option v-for="(t,i) in c.slots" :value="i">{{i+1}} · {{t.start.slice(0,5)}}</option></select></label><label>Raum<select v-model="pos.room" @change="preview"><option v-for="r in c.rooms" :value="r.id">{{r.name}}</option></select></label><label>Lehrkräfte<select multiple v-model="pos.teachers" @change="preview"><option v-for="t in c.teachers" :value="t.id">{{t.name}}</option></select></label><label><input type="checkbox" v-model="pos.fixed" @change="preview"> Fixieren</label></div><p class="alert" v-if="validation" role="status">{{validation}}</p><p class="success" v-else>Platzierung zulässig · {{previewScore}} Strafpunkte im Gesamtplan</p><button class="primary" :disabled="!!validation" @click="save(pos)">Platzieren</button> <button v-if="places[editor]&&!places[editor].fixed" @click="save(null)">Aus Raster nehmen</button><h3>Alternative Plätze</h3><div class="chips"><button v-for="p in choices" @click="pos={...p};preview()">{{describe(p)}}</button></div></section></div>
 </section>`,
};
