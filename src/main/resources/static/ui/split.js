export default {
  props: ["catalog", "group"],
  emits: ["save", "close"],
  data() {
    return {
      subjects: ["", ""],
      firstSize: Math.ceil(this.group.size / 2),
      hours: 1,
      week: "AB",
      members: {},
    };
  },
  computed: {
    ids() {
      return this.subjects.map(subject => this.group.id + "-" + subject);
    },
    sizes() {
      return [this.firstSize, this.group.size - this.firstSize];
    },
    pupils() {
      return this.catalog.students.filter(s => s.groups.includes(this.group.id));
    },
    band() {
      return this.ids[0] + "-" + this.subjects[1];
    },
    error() {
      if (this.subjects.some(s => !s)) return "";
      if (this.subjects[0] === this.subjects[1]) return "Wähle zwei verschiedene Fächer.";
      if (this.catalog.courses.some(c => this.ids.includes(c.id) || c.parallelBand === this.band) ||
          this.catalog.groups.some(g => this.ids.includes(g.id) || g.cohorts.some(k => this.ids.includes(k))))
        return "Diese Kürzel sind bereits vergeben. Bestehende Gruppen können im Bereich Unterricht gekoppelt werden.";
      for (let i = 0; i < 2; i++)
        if (Object.values(this.members).filter(v => v === i).length > this.sizes[i])
          return "Mehr Schüler zugeordnet als Plätze in der Teilgruppe.";
      return "";
    },
  },
  methods: {
    save() {
      if (this.error || this.subjects.some(s => !s)) return;
      const c = JSON.parse(JSON.stringify(this.catalog));
      c.groups.find(g => g.id === this.group.id).cohorts = this.ids;
      this.ids.forEach((id, i) => {
        const subject = c.subjects.find(s => s.id === this.subjects[i]);
        c.groups.push({ id, name: this.group.name + " · " + subject.name,
          size: this.sizes[i], home: this.group.home, cohorts: [id] });
        c.courses.push({ id, subject: subject.id, groups: [id], teachers: [],
          hours: this.hours, block: 1, week: this.week, equipment: [], resources: [], parallelBand: this.band });
      });
      for (const s of c.students) {
        const side = this.members[s.id];
        if (side === 0 || side === 1) s.groups.push(this.ids[side]);
      }
      this.$emit("save", c);
    },
  },
  template: `<div class="overlay" @keydown.esc="$emit('close')"><form class="dialog" role="dialog" aria-label="Klasse teilen" @submit.prevent="save">
    <button type="button" class="right" @click="$emit('close')">Schließen</button><h2>{{group.name}} teilen</h2>
    <div class="fields"><label v-for="(_,i) in subjects">Fach der {{i+1}}. Teilgruppe<select required v-model="subjects[i]"><option value="">Fach wählen</option><option v-for="s in catalog.subjects" :value="s.id">{{s.name}}</option></select></label>
    <label>Größe der ersten Teilgruppe<input required type="number" min="1" :max="group.size-1" v-model.number="firstSize"></label><label>Größe der zweiten Teilgruppe<output>{{sizes[1]}}</output></label>
    <label>Gemeinsame Wochenstunden<input required type="number" min="1" max="100" v-model.number="hours"></label>
    <label>Wochenrhythmus<select v-model="week"><option>AB</option><option>A</option><option>B</option></select></label></div>
    <details v-if="pupils.length"><summary>Schüler den Fächern zuordnen</summary><div class="fields"><label v-for="s in pupils">{{s.name}}<select v-model="members[s.id]"><option :value="undefined">Später zuordnen</option><option v-for="(subject,i) in subjects" :value="i">{{subject || ('Teilgruppe '+(i+1))}}</option></select></label></div></details>
    <p>Der Solver plant beide Fächer gleichzeitig und wählt je Teilgruppe einen passenden Raum und eine qualifizierte Lehrkraft.</p>
    <p v-if="error" class="alert">{{error}}</p><button class="primary" :disabled="!!error">Teilgruppen und Unterricht anlegen</button></form></div>`,
};
