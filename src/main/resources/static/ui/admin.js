import { api, roles } from "./common.js";
export default {
  props: ["base", "school"],
  emits: ["error", "notice"],
  data: () => ({
    roles,
    members: [],
    audit: [],
    form: { subject: "", role: "TEACHER", entity: "", email: "" },
  }),
  async mounted() {
    await this.refresh();
  },
  methods: {
    async refresh() {
      try {
        this.audit = await api(this.base + "/audit");
        if (this.school.role === "ADMIN")
          this.members = await api(this.base + "/members");
      } catch (e) {
        this.$emit("error", e.message);
      }
    },
    async save() {
      try {
        await api(this.base + "/members", "POST", this.form);
        await this.refresh();
        this.$emit("notice", "Zugang gespeichert.");
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
  template: `<div><section v-if="school.role==='ADMIN'" class="panel"><h2>Benutzer und Rollen</h2><div class="grid-scroll"><table class="data"><thead><tr><th>Identität</th><th>Rolle</th><th>Zuordnung</th><th>Aktion</th></tr></thead><tbody><tr v-for="u in members"><td>{{u.subject}}</td><td>{{roles[u.role]}}</td><td>{{u.entity}}</td><td><button @click="form={...u}">Bearbeiten</button> <button @click="remove(u.subject)">Entziehen</button></td></tr></tbody></table></div><form @submit.prevent="save"><h3>Zugang bearbeiten</h3><div class="fields"><label>OIDC-Subject / Demokonto<input required v-model="form.subject"></label><label>Rolle<select v-model="form.role"><option v-for="(name,k) in roles" :value="k">{{name}}</option></select></label><label>Klassen- oder Personenkürzel<input v-model="form.entity"></label><label>E-Mail für Mitteilungen<input type="email" v-model="form.email"></label></div><button class="primary">Zugang speichern</button></form><p><a :href="base+'/offboarding'">Gesamten Mandanten als JSON exportieren</a></p></section><section class="panel"><h2>Änderungsprotokoll</h2><div class="grid-scroll"><table class="data"><thead><tr><th>Zeitpunkt</th><th>Benutzer</th><th>Aktion</th></tr></thead><tbody><tr v-for="r in audit"><td>{{new Date(r.created).toLocaleString('de-AT')}}</td><td>{{r.actor}}</td><td>{{r.event}}</td></tr></tbody></table></div></section></div>`,
};
