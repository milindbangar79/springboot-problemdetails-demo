CREATE TABLE IF NOT EXISTS employees (
    id         INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    department TEXT    NOT NULL,
    email      TEXT    NOT NULL,
    salary     REAL    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_employees_email ON employees (LOWER(email));
