import { api, roles } from "./common.js";
export default {
  props: ["base", "school"],
  emits: ["error", "notice"],
  data: () => ({
    roles,
    members: [],
    audit: [],
    retentionDays: "",
    preview: null,
    form: {
      subject: "",
      role: "TEACHER",
      entity: "",
      email: "",
      purposeUntil: null,
    },
  }),
  async mounted() {
    await this.refresh();
  },
  methods: {
    async refresh() {
      try {
        this.audit = await api(this.base + "/audit");
        if (this.school.role === "ADMIN") {
          this.members = await api(this.base + "/members");
          this.retentionDays = (await api(this.base + "/retention")).days ?? "";
        }
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async save() {
      try {
        await api(this.base + "/members", "POST", {
          ...this.form,
          purposeUntil: this.form.purposeUntil || null,
        });
        await this.refresh();
        this.$emit("notice", "Zugang gespeichert.");
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async previewRetention() {
      try {
        this.preview = await api(
          this.base +
            "/retention/preview?days=" +
            encodeURIComponent(this.retentionDays),
        );
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async saveRetention() {
      const days =
        this.retentionDays === "" ? null : Number(this.retentionDays);
      if (
        days !== null &&
        (!this.preview ||
          !confirm("Die tägliche Löschung nach dieser Frist aktivieren?"))
      )
        return;
      try {
        await api(this.base + "/retention", "PUT", { days });
        this.preview = null;
        this.$emit("notice", "Aufbewahrungsfrist gespeichert.");
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async remove(s) {
      if (!confirm("Zugang für diese Schule entziehen?")) return;
      try {
        await api(this.base + "/members/" + encodeURIComponent(s), "DELETE");
        await this.refresh();
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
  },
  template: `<div><section v-if="school.role==='ADMIN'" class="panel"><h2>Benutzer und Rollen</h2><div class="grid-scroll"><table class="data"><thead><tr><th>Identität</th><th>Rolle</th><th>Zuordnung</th><th>Aktion</th></tr></thead><tbody><tr v-for="u in members"><td>{{u.subject}}</td><td>{{roles[u.role]}}</td><td>{{u.entity}}</td><td><button @click="form={...u}">Bearbeiten</button> <button @click="remove(u.subject)">Entziehen</button></td></tr></tbody></table></div><form @submit.prevent="save"><h3>Zugang bearbeiten</h3><div class="fields"><label>OIDC-Subject / Demokonto<input required v-model="form.subject"></label><label>Rolle<select v-model="form.role"><option v-for="(name,k) in roles" :value="k">{{name}}</option></select></label><label>Klassen- oder Personenkürzel<input v-model="form.entity"></label><label>Zweckende des Zugangs<input type="date" v-model="form.purposeUntil"></label><label>E-Mail für Mitteilungen<input type="email" v-model="form.email"></label></div><button class="primary">Zugang speichern</button></form><p><a :href="base+'/offboarding'">Gesamten Mandanten als JSON exportieren</a></p></section><section class="panel" v-if="school.role==='ADMIN'"><h2>Aufbewahrung</h2><p>Die Frist beginnt bei Plänen mit Semesterende und bei personenbezogenen Zugängen mit dem hinterlegten Zweckende. Ohne Frist ist die tägliche Löschung ausgeschaltet.</p><label>Aufbewahrungsdauer in Tagen<input type="number" min="1" max="36500" v-model="retentionDays" @input="preview=null"></label><button :disabled="retentionDays===''" @click="previewRetention">Betroffene Daten prüfen</button><p v-if="preview">Stichtag {{preview.cutoff}} · {{preview.scenarios}} Szenarien samt Historie und Veröffentlichungen · {{preview.identities}} Konten · {{preview.absences}} Abwesenheiten · {{preview.notices}} Mitteilungen · {{preview.wishes}} Wünsche · {{preview.audit}} Protokolleinträge · {{preview.pendingMail}} ausstehende E-Mails</p> <button :disabled="retentionDays!==''&&!preview" @click="saveRetention">Frist speichern</button></section><section class="panel"><h2>Änderungsprotokoll</h2><div class="grid-scroll"><table class="data"><thead><tr><th>Zeitpunkt</th><th>Benutzer</th><th>Aktion</th></tr></thead><tbody><tr v-for="r in audit"><td>{{new Date(r.created).toLocaleString('de-AT')}}</td><td>{{r.actor}}</td><td>{{r.event}}</td></tr></tbody></table></div></section></div>`,
};
