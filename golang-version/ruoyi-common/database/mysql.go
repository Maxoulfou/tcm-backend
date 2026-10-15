package database

import (
	"context"
	"database/sql"
	"fmt"
	"regexp"
	"sort"
	"strings"

	_ "github.com/go-sql-driver/mysql"
)

type Store struct{ DB *sql.DB }

var ident = regexp.MustCompile(`^[A-Za-z0-9_]+$`)

func Open(dsn string) (*Store, error) {
	db, e := sql.Open("mysql", dsn)
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(30)
	db.SetMaxIdleConns(8)
	if e = db.Ping(); e != nil {
		_ = db.Close()
		return nil, e
	}
	return &Store{DB: db}, nil
}
func Quote(name string) (string, error) {
	if !ident.MatchString(name) {
		return "", fmt.Errorf("invalid identifier")
	}
	return "`" + name + "`", nil
}
func (s *Store) Columns(ctx context.Context, table string) (map[string]bool, error) {
	rows, e := s.DB.QueryContext(ctx, "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?", table)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var c string
		if e = rows.Scan(&c); e != nil {
			return nil, e
		}
		out[c] = true
	}
	return out, rows.Err()
}
func (s *Store) Rows(ctx context.Context, query string, args ...any) ([]map[string]any, error) {
	rows, e := s.DB.QueryContext(ctx, query, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	return scanRows(rows)
}
func (s *Store) One(ctx context.Context, table, pk, id string) (map[string]any, error) {
	t, e := Quote(table)
	if e != nil {
		return nil, e
	}
	p, e := Quote(pk)
	if e != nil {
		return nil, e
	}
	rows, e := s.Rows(ctx, "SELECT * FROM "+t+" WHERE "+p+"=? LIMIT 1", id)
	if e != nil {
		return nil, e
	}
	if len(rows) == 0 {
		return nil, sql.ErrNoRows
	}
	return rows[0], nil
}
func (s *Store) Insert(ctx context.Context, table string, fields map[string]any) error {
	query, args, e := insertSQL(table, fields)
	if e != nil {
		return e
	}
	_, e = s.DB.ExecContext(ctx, query, args...)
	return e
}
func (s *Store) InsertTx(ctx context.Context, tx *sql.Tx, table string, fields map[string]any) error {
	query, args, e := insertSQL(table, fields)
	if e != nil {
		return e
	}
	_, e = tx.ExecContext(ctx, query, args...)
	return e
}
func (s *Store) Transaction(ctx context.Context, run func(*sql.Tx) error) error {
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if e = run(tx); e != nil {
		return e
	}
	return tx.Commit()
}
func insertSQL(table string, fields map[string]any) (string, []any, error) {
	t, e := Quote(table)
	if e != nil {
		return "", nil, e
	}
	keys := fieldKeys(fields)
	if len(keys) == 0 {
		return "", nil, fmt.Errorf("no fields")
	}
	cols, marks, args := make([]string, len(keys)), make([]string, len(keys)), make([]any, len(keys))
	for i, key := range keys {
		cols[i], e = Quote(key)
		if e != nil {
			return "", nil, e
		}
		marks[i] = "?"
		args[i] = fields[key]
	}
	return "INSERT INTO " + t + " (" + strings.Join(cols, ",") + ") VALUES (" + strings.Join(marks, ",") + ")", args, nil
}
func (s *Store) Update(ctx context.Context, table, pk, id string, fields map[string]any) error {
	query, args, e := updateSQL(table, pk, id, fields)
	if e != nil {
		return e
	}
	_, e = s.DB.ExecContext(ctx, query, args...)
	return e
}
func updateSQL(table, pk, id string, fields map[string]any) (string, []any, error) {
	t, e := Quote(table)
	if e != nil {
		return "", nil, e
	}
	p, e := Quote(pk)
	if e != nil {
		return "", nil, e
	}
	keys := fieldKeys(fields)
	if len(keys) == 0 {
		return "", nil, fmt.Errorf("no fields")
	}
	sets, args, e := updateClauses(keys, fields)
	if e != nil {
		return "", nil, e
	}
	args = append(args, id)
	return "UPDATE " + t + " SET " + strings.Join(sets, ",") + " WHERE " + p + "=?", args, nil
}
func updateClauses(keys []string, fields map[string]any) ([]string, []any, error) {
	sets, args := make([]string, len(keys)), make([]any, len(keys))
	for i, key := range keys {
		q, x := Quote(key)
		if x != nil {
			return nil, nil, x
		}
		sets[i] = q + "=?"
		args[i] = fields[key]
	}
	return sets, args, nil
}
func (s *Store) Delete(ctx context.Context, table, pk, id string) error {
	t, e := Quote(table)
	if e != nil {
		return e
	}
	p, e := Quote(pk)
	if e != nil {
		return e
	}
	_, e = s.DB.ExecContext(ctx, "DELETE FROM "+t+" WHERE "+p+"=?", id)
	return e
}
func scanRows(rows *sql.Rows) ([]map[string]any, error) {
	cols, e := rows.Columns()
	if e != nil {
		return nil, e
	}
	out := []map[string]any{}
	for rows.Next() {
		row, e := scanRow(rows, cols)
		if e != nil {
			return nil, e
		}
		out = append(out, row)
	}
	return out, rows.Err()
}
func scanRow(rows *sql.Rows, cols []string) (map[string]any, error) {
	values := make([]any, len(cols))
	ptrs := make([]any, len(cols))
	for i := range values {
		ptrs[i] = &values[i]
	}
	if e := rows.Scan(ptrs...); e != nil {
		return nil, e
	}
	row := map[string]any{}
	for i, key := range cols {
		row[key] = normalize(values[i])
	}
	return row, nil
}
func normalize(value any) any {
	if raw, ok := value.([]byte); ok {
		return string(raw)
	}
	return value
}
func fieldKeys(fields map[string]any) []string {
	keys := make([]string, 0, len(fields))
	for key := range fields {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
