CREATE TABLE school (id varchar(80) PRIMARY KEY, name text NOT NULL);
CREATE TABLE membership (
 school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE, subject text NOT NULL,
 role text NOT NULL CHECK(role IN ('PLAN','DIRECTION','TEACHER','CURATOR','STUDENT','GUARDIAN','OFFICE','ADMIN')),
 entity text NOT NULL DEFAULT '', email text NOT NULL DEFAULT '', PRIMARY KEY(school_id,subject)
);
CREATE TABLE scenario (
 id uuid PRIMARY KEY, school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 name text NOT NULL, status text NOT NULL DEFAULT 'DRAFT', revision bigint NOT NULL DEFAULT 0,
 cursor integer NOT NULL DEFAULT 0, document jsonb NOT NULL
);
CREATE INDEX scenario_school ON scenario(school_id);
CREATE TABLE revision (scenario_id uuid REFERENCES scenario ON DELETE CASCADE, step integer NOT NULL,
 document jsonb NOT NULL, author text NOT NULL, created timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(scenario_id,step));
CREATE TABLE publication (id bigserial PRIMARY KEY, school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 scenario_id uuid NOT NULL REFERENCES scenario ON DELETE CASCADE, document jsonb NOT NULL, created timestamptz NOT NULL DEFAULT now());
CREATE INDEX publication_school ON publication(school_id,id DESC);
CREATE TABLE absence (id bigserial PRIMARY KEY, school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 teacher text NOT NULL, day date NOT NULL, reason text NOT NULL, UNIQUE(school_id,teacher,day));
CREATE TABLE cover (publication_id bigint REFERENCES publication ON DELETE CASCADE, day date NOT NULL,
 lesson text NOT NULL, missing text NOT NULL, replacement text NOT NULL, kind text NOT NULL,
 PRIMARY KEY(publication_id,day,lesson,missing));
CREATE TABLE audit (id bigserial PRIMARY KEY,school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 actor text NOT NULL, event text NOT NULL, created timestamptz NOT NULL DEFAULT now());
CREATE TABLE notice (id bigserial PRIMARY KEY, school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 message text NOT NULL, created timestamptz NOT NULL DEFAULT now());
CREATE TABLE mail_queue (id bigserial PRIMARY KEY,school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 recipient text NOT NULL, message text NOT NULL, attempts integer NOT NULL DEFAULT 0, retry_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE cover ADD COLUMN target text NOT NULL DEFAULT '', ADD COLUMN hours integer NOT NULL DEFAULT 1;
CREATE TABLE feed (token_hash text PRIMARY KEY,school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 scope text NOT NULL CHECK(scope IN('group','teacher')), entity text NOT NULL, created timestamptz NOT NULL DEFAULT now());
CREATE TABLE wish (id bigserial PRIMARY KEY,school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 teacher text NOT NULL, message text NOT NULL, status text NOT NULL DEFAULT 'OFFEN', created timestamptz NOT NULL DEFAULT now());
CREATE TABLE curriculum (id uuid PRIMARY KEY,school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 name text NOT NULL, school_type text NOT NULL, rules jsonb NOT NULL);
