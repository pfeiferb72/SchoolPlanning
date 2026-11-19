CREATE TABLE spring_session (
 primary_id char(36) PRIMARY KEY, session_id char(36) NOT NULL UNIQUE,
 creation_time bigint NOT NULL, last_access_time bigint NOT NULL, max_inactive_interval integer NOT NULL,
 expiry_time bigint NOT NULL, principal_name varchar(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id char(36) NOT NULL REFERENCES spring_session ON DELETE CASCADE,
 attribute_name varchar(200) NOT NULL, attribute_bytes bytea NOT NULL,
 PRIMARY KEY(session_primary_id,attribute_name)
);
CREATE TABLE solver_job (
 id uuid PRIMARY KEY, school_id varchar(80) NOT NULL REFERENCES school ON DELETE CASCADE,
 scenario_id uuid NOT NULL REFERENCES scenario ON DELETE CASCADE,
 status text NOT NULL, progress integer NOT NULL DEFAULT 0, placed integer NOT NULL DEFAULT 0,
 score integer NOT NULL DEFAULT 0, message text NOT NULL DEFAULT '', cancelled boolean NOT NULL DEFAULT false,
 heartbeat timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX solver_job_active ON solver_job(scenario_id) WHERE status IN ('WARTET','LÄUFT');
ALTER TABLE membership ADD COLUMN subscribe_plan boolean NOT NULL DEFAULT true,
 ADD COLUMN subscribe_cover boolean NOT NULL DEFAULT true;
CREATE TABLE inbox (notice_id bigint NOT NULL REFERENCES notice ON DELETE CASCADE,
 school_id varchar(80) NOT NULL, subject text NOT NULL,
 PRIMARY KEY(notice_id,school_id,subject),FOREIGN KEY(school_id,subject) REFERENCES membership ON DELETE CASCADE);
INSERT INTO inbox SELECT n.id,n.school_id,m.subject FROM notice n JOIN membership m ON m.school_id=n.school_id;
